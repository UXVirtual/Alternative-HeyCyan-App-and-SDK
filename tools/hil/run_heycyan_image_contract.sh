#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
output_root="${HEYCYAN_CONTRACT_OUTPUT_DIR:-$repo_root/build/hil/image-contract}"
adb_bin="${ADB_BIN:-adb}"
app_package=com.fersaiyan.cyanbridge
test_package=com.fersaiyan.cyanbridge.test
runner=androidx.test.runner.AndroidJUnitRunner
android_user="${CYANBRIDGE_HIL_USER:-0}"
[[ "$android_user" =~ ^[0-9]+$ ]] || { echo "CYANBRIDGE_HIL_USER must be a numeric Android user ID" >&2; exit 3; }
adb_for() {
  local serial="$1"
  shift
  "$adb_bin" -s "$serial" "$@"
}
package_installed() { adb_for "$1" shell pm path "$2" | grep -q '^package:'; }

serial="${CYANBRIDGE_HIL_SERIAL:-${1:-}}"
if [[ -z "$serial" || "$serial" == emulator-* || "$(adb_for "$serial" get-state 2>/dev/null || true)" != device ]]; then
  echo "Specify an online physical phone via CYANBRIDGE_HIL_SERIAL or the first argument" >&2
  exit 3
fi

companion="${HEYCYAN_OFFICIAL_PACKAGE:-}"
if [[ -n "$companion" ]] && { [[ "$companion" == "$app_package" ]] || ! package_installed "$serial" "$companion"; }; then
  echo "Invalid or absent HEYCYAN_OFFICIAL_PACKAGE; specify the installed vendor companion package" >&2
  exit 4
fi

if ! package_installed "$serial" "$app_package"; then
  echo "CyanBridge must be installed and paired before the probe" >&2
  exit 6
fi
app_apk="$repo_root/android/CyanBridge/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$repo_root/android/CyanBridge/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ ! -s "$app_apk" || ! -s "$test_apk" ]]; then
  echo "Build the debug and androidTest APKs before running the contract probe" >&2
  exit 6
fi

if [[ "$(adb_for "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" -lt 33 ]]; then
  wifi_permission=android.permission.ACCESS_FINE_LOCATION
else
  wifi_permission=android.permission.NEARBY_WIFI_DEVICES
fi
for permission in android.permission.BLUETOOTH_CONNECT android.permission.BLUETOOTH_SCAN "$wifi_permission"; do
  if ! adb_for "$serial" shell dumpsys package "$app_package" | grep -F "$permission: granted=true" >/dev/null; then
    echo "Missing runtime permission $permission; perform one-time permission setup before the run" >&2
    exit 5
  fi
done

if [[ -n "$companion" ]]; then
  adb_for "$serial" shell am force-stop "$companion"
else
  echo "No official HeyCyan app specified; CyanBridge alone will run the probe. Ensure no other app holds the glasses connection."
fi

run_id="contract_$(date +%s)"
output="$output_root/$run_id"
mkdir -p "$output"
echo "Phone: $serial | report: $output | optional official app: ${companion:-not installed/specified}"
if [[ "${HEYCYAN_OPERATOR_PHOTO:-}" == "1" ]]; then
  echo "Operator check: have the glasses ready. After BLE setup and baseline media count," \
    "press the physical PHOTO control ONCE during the 30-second observation window." \
    "No press is needed until the window opens; do not use the CyanBridge UI."
fi
if [[ "${HEYCYAN_ON_FACE_CAPTURE:-}" == "1" ]]; then
  echo "One on-face app capture will be sent after BLE setup and a baseline media count." \
    "Keep wearing the glasses; do not press their photo control or use the app UI."
fi
if [[ "${HEYCYAN_DELAYED_COUNT:-}" == "1" ]]; then
  echo "A read-only delayed media count will be collected; no photo, thumbnail, transfer, or mode command will be sent." \
    "Do not press the glasses' photo control or use the app UI."
fi
adb_for "$serial" install -r "$app_apk"
adb_for "$serial" install -r "$test_apk"
adb_for "$serial" logcat -c
adb_for "$serial" logcat -v threadtime -s HeyCyanImageContract HeyCyanBleSetupTrace AutoPair DataDownload DeviceNotify WifiP2pManagerSingleton GLASSES_LOG MyBluetoothReceiver > "$output/logcat.txt" &
logcat_pid=$!
set +e
instrument_args=(-w -r \
  -e class com.fersaiyan.cyanbridge.hil.HeyCyanImageContractProbeTest \
  -e contract_serial "$serial" \
  -e contract_run_id "$run_id")
if [[ "${HEYCYAN_CAMERA_DIAGNOSTIC:-}" == "1" ]]; then
  instrument_args+=(-e contract_camera_diagnostic true)
fi
if [[ "${HEYCYAN_OPERATOR_PHOTO:-}" == "1" ]]; then
  instrument_args+=(-e contract_operator_photo true)
fi
if [[ "${HEYCYAN_ON_FACE_CAPTURE:-}" == "1" ]]; then
  instrument_args+=(-e contract_on_face_capture true)
fi
if [[ "${HEYCYAN_DELAYED_COUNT:-}" == "1" ]]; then
  instrument_args+=(-e contract_delayed_count true)
  instrument_args+=(-e contract_expected_photo_count "${HEYCYAN_EXPECTED_PHOTO_COUNT:-3}")
fi
if [[ -n "$companion" ]]; then
  instrument_args+=(-e contract_official_package "$companion")
fi
instrument_args+=(--user "$android_user" "$test_package/$runner")
adb_for "$serial" shell am instrument "${instrument_args[@]}" | tee "$output/instrumentation.txt"
instrument_status=${PIPESTATUS[0]}
set -e
kill "$logcat_pid" 2>/dev/null || true
wait "$logcat_pid" 2>/dev/null || true
# Private instrumentation evidence is copied by run-as on debuggable builds; no root required.
if ! adb_for "$serial" exec-out run-as "$app_package" tar -cf - "files/hil/image-contract/$run_id" > "$output/evidence.tar" ||
   ! tar -tf "$output/evidence.tar" | grep -Fq "files/hil/image-contract/$run_id/report.json"; then
  echo "Could not export private raw callback evidence (requires debuggable APK)" >&2
  exit 7
fi
mkdir -p "$output/extracted"
tar -xf "$output/evidence.tar" -C "$output/extracted"
report="$output/extracted/files/hil/image-contract/$run_id/report.json"
review_status=0
if [[ "${HEYCYAN_CAMERA_DIAGNOSTIC:-}" != "1" && "${HEYCYAN_OPERATOR_PHOTO:-}" != "1" && "${HEYCYAN_ON_FACE_CAPTURE:-}" != "1" && "${HEYCYAN_DELAYED_COUNT:-}" != "1" ]]; then
  review_args=("$report" --output "$output/review")
  if [[ -n "${HEYCYAN_VISION_MODEL:-}" ]]; then
    review_args+=(--vision-model "$HEYCYAN_VISION_MODEL")
  fi
  "${HEYCYAN_REVIEW_PYTHON:-python3}" "$repo_root/tools/hil/review_heycyan_image_contract.py" "${review_args[@]}" || review_status=$?
  if (( review_status != 0 )); then
    echo "Offline packet review incomplete; see $output/review/review.json (if present)" >&2
  fi
fi
if (( instrument_status != 0 )) || ! grep -Eq 'OK \(1 test\)' "$output/instrumentation.txt" ||
  grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$output/instrumentation.txt"; then
  echo "Hardware contract probe FAILED. Inspect $output; never infer a validated packet contract from this run." >&2
  exit 8
fi
if [[ "${HEYCYAN_CAMERA_DIAGNOSTIC:-}" == "1" ]]; then
  echo "One-shot camera diagnostic collected: $output (no thumbnail/transfer contract claim)"
  exit 0
fi
if [[ "${HEYCYAN_OPERATOR_PHOTO:-}" == "1" ]]; then
  echo "Operator photo diagnostic collected: $output (no unattended-capture/transfer contract claim)"
  exit 0
fi
if [[ "${HEYCYAN_ON_FACE_CAPTURE:-}" == "1" ]]; then
  echo "Single on-face capture diagnostic collected: $output (inspect ACK and media delta; contract not approved)"
  exit 0
fi
if [[ "${HEYCYAN_DELAYED_COUNT:-}" == "1" ]]; then
  echo "Delayed read-only media-count diagnostic collected: $output (no capture or transfer contract claim)"
  exit 0
fi
if (( review_status != 0 )); then
  echo "Hardware observations collected but exact-byte JPEG candidates did not pass review; see $output" >&2
  exit 9
fi
echo "Observation completed: $output (contract NOT approved until raw packet/framing review)"