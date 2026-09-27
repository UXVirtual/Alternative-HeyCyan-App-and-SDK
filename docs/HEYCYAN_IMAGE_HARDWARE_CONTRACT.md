# HeyCyan image hardware contract — pending device observation

**Status: unverified.** No callback index, first/last packet index, wrap behavior,
framing length or checksum has been established. Do not implement strict production
assembly using guessed values. In particular, `isComplete` is a callback signal,
**not** proof of a contiguous packet range. This path is vendor **BLE SDK** for
thumbnails and Wi-Fi Direct **HTTP** for originals; no Bluetooth Classic image
endpoint is documented.

The first physical probe attempt on 2026-09-27 failed: the Samsung shell
instrumentation command without `--user 0` returned `Invalid userId -2`. A
direct retry with user 0 entered the test, passed its initial preflight, but
`syncDeviceInfo` gave no response and the lease was quarantined. No captures,
raw packets, firmware values or transfer transition were collected. The runner
now supplies a numeric Android user (`CYANBRIDGE_HIL_USER`, default `0`), and
the probe requires `isReady` as well as `isConnected` before issuing commands.
**These changes still need a successful physical rerun; do not interpret the
earlier failed probe as a hardware contract.**

On the subsequent user-0 probe with the fixed runner, BLE readiness and fresh
device-info response succeeded: BLE hardware `AM02_V1.2`, firmware
`AM02_1.20.00_260702`, Wi-Fi hardware `WIFIAM02_V1.0`, firmware
`WIFIAM02_1.00.29_2603141830`. The first capture command returned
`errorCode=-1` before a photo-ready notification or thumbnail callback. The
evidence/review is under the ignored local `build/hil/image-contract/` directory.
Investigate this non-persisting capture result and glasses camera state; do **not**
auto-retry until a favorable JPEG appears. The rejection cannot validate packet
or transfer behavior. The probe now records command and ACK metadata and
quarantines unresolved capture requests.

### One-shot control diagnostic (2026-09-27)

With `HEYCYAN_CAMERA_DIAGNOSTIC=1`, the runner sent exactly one **known CyanBridge
UI photo command** (`02 01 01`), rather than guessing a read-only status opcode
(none is established). On the physical phone it returned a vendor BLE frame
`bc4105000c4c020101ffff`: parsed `dataType=1`, `glassWorkType=1`,
`errorCode=-1`, `workTypeIng=0`. There was no `0x02` photo-ready notification
within eight seconds, although the BLE connection remained active. This shows
the nonzero ACK is **not unique to AI photo quality or `02 01 06 02 02`**.
`workTypeIng=0` on an error is a parser default, **not a measured idle/camera
mode**. Neither the SDK nor CyanBridge currently offers a verified read-only
work-state query; `02 01 01` is a capture action, not a status request.

The captured `GLASSES_LOG` also shows a second `onServiceDiscovered isReady=true`
**after** the photo command had been sent and refused. Service-discovery/setup
or a duplicate GATT connection may still have been in progress when the test
saw `isReady=true`; the evidence does not establish this as the cause. Before
another capture test, investigate duplicate connection/setup events and gate
on a demonstrably stable, single BLE session. Do not repeatedly capture,
change camera modes, or infer a firmware error definition from `-1` alone.
The diagnostic run is under `build/hil/image-contract/contract_1790469094/`.

### Stable-session control diagnostic (2026-09-27)

The next run exposed two simultaneous `AutoPairManager.connectDirectly()` calls
on startup (startup request and first loop iteration). This produced two GATT
connections and a disconnect while `syncDeviceInfo` was pending. The startup
loop is now delayed and its reconnect attempt is debounced; the probe logs
connection/discovery events and waits for four quiet seconds after the latest
service discovery **before** reading device info or sending a capture command.

On the physical retest, `contract_1790469593`, only one connection event was
recorded, followed by two discovery callbacks, a four-second quiet period and
a successful fresh device-info response. The known photo command `02 01 01`
still received `bc4105000c4c020101ffff` (`errorCode=-1`). No `0x02` photo-ready
event appeared within eight seconds; no connection/setup event occurred during
the capture request. **A startup GATT collision was real and has been removed,
but it does not explain this non-persisting run on its own.** The semantics of
`-1` remain unknown. Do not use the ACK alone to claim Step 1 from this
diagnostic; next examine physical camera availability and device-side work state
using independently verified procedures.

### Camera availability and work-state investigation (2026-09-27)

Review of the Android SDK and live traces establishes the following boundaries:

  query. `02 01 06 02 02` is the existing AI-photo action; both returned a
  **real** wire-level `FF FF` nonzero response in separate runs with no
  persisted-media evidence. The SDK parser exposes `workTypeIng` only after a
  zero response;
  its value `0` after `-1` cannot establish idle mode or camera availability.
  query. `02 04` is an existing read-only *media count* operation, but a media
  count cannot show whether the camera can currently shoot. Do not invent
  an opcode, issue `02 01 01` as a supposed status query, or reset/exit an
  unverified device mode to make the test pass.
  camera-state interpretation. Phone-side `CAMERA` permission and Android
  camera-service state describe the phone, not the glasses camera.
  20 seconds while connected; these BLE commands are **not** reserved under
  `GlassesSessionCoordinator` and may overlap a long diagnostic. Check their
  wire timestamps in every run. Do not interpret an idle process-level lease
  as proof of an idle vendor BLE command slot.
  Bluetooth service history shows it contacting a *different* device suffix
  (`B7:4B`, versus HeyCyan `34:28`). It is not evidence of an official
  HeyCyan competing connection; do not force-stop it to explain `-1`.

The next discriminating check requires an **operator-observed physical photo
control** on the same glasses after setup, outside the unattended run: note
whether a shutter/LED indication occurs and whether a new photo is visible
through an independently verified media listing. If the hardware itself
cannot capture, examine its power/battery, lens or camera fault indication,
and current mode with vendor-supported diagnostics. If the physical control
works but one app-triggered capture still returns `-1` on a stable session,
collect the device's raw response and escalate firmware/command semantics
to the hardware vendor. Neither outcome alone validates an unattended app
capture or thumbnail integrity. No hardware-button press or verified work-state
observation was performed as part of this investigation.

### Operator-assisted physical-photo check — prepared, not yet run

`HEYCYAN_OPERATOR_PHOTO=1 bash tools/hil/run_heycyan_image_contract.sh`
with `CYANBRIDGE_HIL_SERIAL` set to the physical phone runs a separate,
bounded diagnostic. After stable BLE discovery and device-info preflight, it
reads the existing read-only `02 04` media counters, opens a **30-second**
window, and logs `OPERATOR_WINDOW_OPEN` in `logcat.txt`. An operator beside
the glasses should press the **physical photo control once, only after the
window opens**, without touching the phone app. The probe records all raw
notify frames, whether `0x02` was seen, and another media count after the
window closes. It sends **no app photo command**, asks for no thumbnail,
and never infers camera readiness from an absent notification alone.

The probe does not know whether the operator actually pressed the button;
the operator must report that independently along with any shutter/LED
indication. An increased photo count supports a physical photo having been
recorded, but without a capture identifier or a separately observed new file
it is not definitive proof of identity. If the baseline or follow-up count
times out, BLE changes, or the operator is unavailable, mark the check
**inconclusive**, not passed. This check cannot satisfy the unattended
app-triggered capture contract. It has been built but was deliberately not
run without an available operator.

### Operator-assisted result (2026-09-27)

Run `contract_1790470280` recorded a physical press during the 30-second
window **while wearing the glasses**. The operator independently reported
hearing a **shutter sound**.
The glasses-reported photo count increased from **2 to 3** with no BLE setup
change during the window. This is strong evidence that the physical control
can take a photo, although the new file has not been independently identified.
The listener received a raw `0x03` notification but **no `0x02` photo-ready**
notification. Do not interpret the `0x03` frame as a photo-ready signal without
verifying its meaning. This result does **not** resolve why app-initiated photo
commands return `-1`, or validate thumbnail assembly or unattended capture.
The earlier non-persisting app-initiated captures were performed **off-face**.
Wear state is therefore a plausible difference, not an established explanation:
the physical-button and app-command paths also differ, and startup/device state
may differ across runs. Next perform one isolated app-initiated capture **while
wearing** the glasses with a stable BLE session; record its raw ACK, photo-ready
event and media-count delta. Do not repeat on failure or treat a decodable
thumbnail as proof of capture without an unambiguous new-photo observation.

### On-face app AI-photo result (2026-09-27)

Run `contract_1790471211` sent exactly one AI-photo command (`02 01 06 02 02`)
while the operator was wearing the glasses. BLE was stable at the gate: one
connection event, two service-discovery callbacks, and 4,133 ms since the final
discovery; no BLE setup event occurred during the command. The glasses returned
the same nonzero response frame, `bc410500bd8d020106ffff`, parsed as
`dataType=1`, `glassWorkType=6`, `errorCode=-1`. The read-only media count
remained **3 to 3**, so no new photo was observed.

A raw `0x73` notification (`bc730500dc570200180100`) arrived after the nonzero
ACK, and the existing generic listener marked it as photo-ready. That label is
not validated: it did not accompany a count increase and cannot establish a
successful capture. **Wearing the glasses is therefore not sufficient to
explain or work around the app-command non-persistence.** This remains a one-shot
diagnostic only; it did not request a thumbnail and does not validate transfer,
JPEG integrity, or Step 1.

### Delayed read-only media-count check (2026-09-27)

The operator reported an audible sound during the on-face remote-trigger test,
so `contract_1790471430` performed a separate, delayed, **read-only** `02 04`
media-count query. It sent no capture, thumbnail, transfer, or mode command.
After a fresh stable BLE setup, the count was still **3 photos, 0 videos, and
1 audio**, unchanged from the on-face test baseline and immediate follow-up.
The same read-only count had increased during the prior physical-button test.
This makes a delayed stored photo from the remote command unlikely: the audible
event may indicate an attempted operation or a device notification, but it is
not evidence of a completed, persisted capture. The vendor SDK could still
mislabel an operation response, but the available device-state evidence does
not support treating this `-1` as a successful app capture.

### Official-app HCI reference (2026-09-27)

The first official-app attempt, `reference_1790472315`, is configuration-only
evidence: Samsung Developer Options still displayed **Enable Bluetooth HCI
snoop log: Disabled**, despite legacy shell preference values. Its bug report
therefore exposed only a Samsung-specific compressed `BTSNOOP_LOG_SUMMARY`, not
raw ATT traffic. Do not use that attempt to infer the official protocol.

The Samsung Developer Options selector was then explicitly set to **Enabled**
(the unfiltered mode; **Enabled Filtered** is not appropriate for this work) and
Bluetooth was toggled as the selector instructed. Bluetooth Manager then
reported `sSnoopLogSettingAtEnable = FULL`. The second operator-reported
official-app photo exported
`build/hil/official-hci/enabled_reference_1790472840/`
`dumpstate-2026-09-27-14-34-01.zip`, which contains the standard raw
`FS/data/log/bt/btsnoop_hci.log` (`BTSnoop version 1, HCI UART`). Its SHA-256 is
`2014ce5d8534dfca3e07605058b56a6340d7b7237e57ab9596f43de3eaa445ea`.

The record-aware ATT extraction confirms that `com.glasssutdio.wear` established
the BLE connection on handle `0x0002` and, at `14:33:46.464802 UTC`, wrote
`bc4103001050020101` to characteristic handle `0x008e`. This frame carries the
same logical **normal photo** action `02 01 01` as CyanBridge. At
`14:33:46.913000 UTC`, the glasses notified characteristic handle `0x0090` with
`bc4105000c4c020101ffff`: the same explicit `FF FF` bytes previously observed
through CyanBridge. A later `bc73080032070102000000010001` notification at
`14:33:48.846166 UTC` is not validated as capture success.

This proves that the nonzero response is **not CyanBridge-specific**: the
verified official companion also sends the normal photo action and receives the
same `FF FF` bytes. The operator-reported in-app photo alone did not establish
that a file persisted; that check is recorded below. The HCI trace also reveals
that the official frame uses a three-byte outer payload length, so a future
single CyanBridge normal-photo diagnostic captured with this now-enabled HCI
setting can compare the SDK's exact outer frame before any broader retry.

### Official-app photo plus delayed count (2026-09-27)

With unfiltered HCI logging confirmed active, the operator took one more photo
in `com.glasssutdio.wear`. The raw standard trace in
`build/hil/official-hci/count_reference_1790473213/`
`btsnoop_hci.log` (SHA-256
`ced40ec8795ba826562cc786fde771234b9c5fcb3e1de939cf330d285d2d0e14`) records the
official app's `bc4103001050020101` write at record 1423, the same
`bc4105000c4c020101ffff` nonzero response at record 1426, and a subsequent
`bc73080022c70103000000010001` notification at record 1432.

After force-stopping the official app, `contract_1790473303` established a new,
stable BLE session and issued only the existing read-only media-count command
`02 04`. It reported **3 photos, 0 videos, 1 audio**, unchanged from the
previous baseline of 3; no capture, thumbnail, transfer, or mode command was
sent by this follow-up. At this point the repeated `FF FF` response remained
ambiguous: no persisted media followed that specific full-media run, but the
response itself was not yet proven to be a refusal. The accompanying `0x73`
notifications alone are not evidence of persisted media.

### Storage-full hypothesis (unverified)

The official app's documented download workflow removed the source media: the
read-only baseline `contract_1790473701` changed from 3 photos, 0 videos, 1
audio to **0 photos, 0 videos, 0 audio**. With that empty baseline, one more
official-app photo produced **1 photo** in the read-only follow-up
`contract_1790473838`. Its HCI trace,
`build/hil/official-hci/post_download_1790473758/btsnoop_hci.log` (SHA-256
`ba3ed3d295c7bba8a989ba63a02d538445bd04ccea8327181092bf8247b36797`), records the
same `bc4103001050020101` command and the same
`bc4105000c4c020101ffff` response before the count increased.

This is strong evidence that freeing glasses media storage enabled capture, but
it does **not** define `FF FF` / `errorCode=-1` as “memory full”: the same bytes
occurred for both the earlier non-persisting runs and the later successful
`0 -> 1` run. The vendor parser exposes item counts only; it has no
capacity/free-space field or error-code mapping. Treat the ACK as asynchronous
status and establish capture success from independent device evidence such as a
new media count and raw thumbnail callbacks. Do not send an invented storage
query or assume every download removes source media without observing the count.

For the tested glasses only (`AM02_V1.2`, firmware `AM02_1.20.00_260702`), treat
the observed limit as **four total media slots**: the non-persisting condition
was observed at 3 photos plus 1 audio item, and capture resumed after the count
was cleared to zero. This is not a claim of four *image* slots, because audio
and video consume the same observed limit. Scope this empirical value by
hardware and firmware, keep the raw counts in evidence, and invalidate it if a
future run persists a fifth total item.

### CyanBridge storage-handling next steps

Implement the following product behavior for HeyCyan glasses. These are app
requirements derived from the observed `0 -> 1` capture after official-media
download; they do not redefine the vendor ACK semantics.

1. For **Capture + Preview**, read the `02 04` media count before capture, send
  the capture action once, and read the count again after the bounded capture
  window. Treat a photo-count increase as the capture-persisted signal. Record
  the ACK, but never classify success or failure from `errorCode` alone.
2. When a capture window completes without a photo-count increase after the
  app's storage check, show the user-facing error **“Storage full”** and a
  direct prompt to transfer/sync images with the glasses. Do not silently retry
  capture or request thumbnails as a recovery action. Preserve the ACK,
  notifications, and before/after counts in debug evidence.
3. In **Glasses > Glasses status**, display the latest stored-media counts as
  `Photos`, `Videos`, and `Audio`, plus a prominent **Sync images** action.
  Refresh this status after a capture attempt, transfer completion, reconnect,
  and an explicit manual refresh.
4. For the tested `AM02_V1.2` / `AM02_1.20.00_260702` profile, calculate
  `remaining media slots = max(0, 4 - Photos - Videos - Audio)`. Label this
  **Remaining media slots**, not “images remaining,” and make the
  hardware/firmware scope visible in diagnostics.
5. Disable **Capture + Preview** when the scoped remaining-media-slot value is
  zero or CyanBridge has a current storage-full result. Keep the adjacent sync
  action enabled. Re-enable Capture + Preview only after a refreshed media
  count shows that the official transfer workflow removed source media, or
  after a later verified capture persists media.
6. Do not apply the four-slot rule to another firmware or glasses model until
  the same bounded count/capture evidence establishes it. If a fifth total
  item persists on this profile, remove the rule and return to an unknown-
  capacity state rather than silently clamping the displayed count.

On 2026-09-27, the connected lab phone was `SM_A176B` (Android API 36, ADB
serial `R5CY82HAQ4T`); CyanBridge had Bluetooth Connect/Scan and Nearby Wi-Fi
runtime grants. The CyanBridge harness app is `com.fersaiyan.cyanbridge` and
its instrumentation package is `com.fersaiyan.cyanbridge.test`. No installed
official HeyCyan companion package was identified on that phone. The *old*
runner incorrectly required its package ID and stopped before issuing glasses
commands; that gate has been removed. An official companion is optional, not
the harness app. BLE pairing and firmware were observed in the later stable
diagnostic; callback framing, transfer-mode HTTP and camera readiness remain
unmeasured. This is **not** a successful step 1 hardware contract.

## Step 1 observation probe

`HeyCyanImageContractProbeTest` in `app/src/androidTest/.../hil/` is an
**observational**, debug-only test. It checks a paired BLE identity, Wi-Fi,
runtime Bluetooth and P2P permissions, idle P2P/process route, inactive
auto-capture services/settings, and obtains an exclusive media-sync lease. It
records phone/model/API and fresh BLE/Wi-Fi hardware and firmware identifiers.
It makes two fresh captures with a command ACK and a fresh `0x02` photo-ready
notification, saving every `getPictureThumbnails` callback (including completion
and duplicates) as an ordered index/length/hash/header/trailer record plus the
full raw packet bytes. It also saves the raw notify frames. Between captures it
enters transfer mode, waits for a P2P group and BLE-reported glasses IP, reads a
bounded `HTTP 200 /files/media.config` through the P2P network, exits transfer
mode and confirms the P2P group is gone and the process is unbound. A fresh
capture after **each** teardown checks capture/photo-ready/thumbnail readiness.
It never reconstructs a thumbnail or claims a decodable image is intact.

The lease stays quarantined on an unresolved SDK response slot or unconfirmed
teardown; a **real BLE disconnect/reconnect** is required before another probe.
The runner must be given the exact physical ADB serial. CyanBridge is the
required, paired debug app. If an official HeyCyan app is **also installed**,
identify its *separate* package ID and set `HEYCYAN_OFFICIAL_PACKAGE` so the
runner can force-stop it. Never set that variable to CyanBridge's package ID;
no official app on the phone means the variable is unnecessary. To inspect
installed candidate apps, use `adb -s <serial> shell pm list packages -3`,
then verify a candidate with `adb -s <serial> shell pm path <package>` and
its label/identity before setting the variable. Do not silently substitute a
different app or run against an emulator. Keep
auto-capture/Walking Aid/Visual Diary disabled for the entire run and do not
open the CyanBridge UI concurrently.

After one-time pairing/permission setup, build and invoke the runner from the
repository root (with the Android Studio JDK selected for Gradle):

1. Build `:app:assembleDebug` and `:app:assembleDebugAndroidTest` in
   `android/CyanBridge`.
2. Set `CYANBRIDGE_HIL_SERIAL` to the physical serial. Set
  `HEYCYAN_OFFICIAL_PACKAGE` **only if** an official HeyCyan app is also
  installed, using its verified package ID (not `com.fersaiyan.cyanbridge`).
3. Run `bash tools/hil/run_heycyan_image_contract.sh` (the file may not be
  executable on a fresh checkout). The runner defaults to Android user 0;
  override `CYANBRIDGE_HIL_USER` only for a verified different Android user.

The runner installs both APKs with `-r` (preserving app data/pairing), captures
filtered logcat and exports the private evidence directory with `run-as` and
`tar` into `build/hil/image-contract/<run-id>/`. Protect the raw notify dumps
and paired BLE address as device-identifying lab data. A failed probe still
exports the evidence; it is not a contract approval. The runner also invokes
`tools/hil/review_heycyan_image_contract.py` on every exported report, saving
`review/review.json` and any **exact-byte, unmodified** index-order or
arrival-order candidates which pass full JPEG decoding. The script requires
Pillow (`HEYCYAN_REVIEW_PYTHON` can select an interpreter with Pillow).
`HEYCYAN_VISION_MODEL` enables optional OpenAI image review when `OPENAI_API_KEY`
is already set locally; this uploads only locally decoded candidate JPEGs,
not the raw packet/notify dumps. Do not set it when lab image privacy forbids
upload. Vision output is advisory and cannot certify packet completeness,
correct capture identity or lack of entropy corruption. No loop that silently
retries captures until images look good is permitted: keep failures and
investigate the underlying packet contract.

## Review required before step 2

For *each* fresh capture, analyze the ordered `thumbnailCallbacks` and saved
`packet_*.bin` files. Record:

- Whether the first callback integer is a **packet index** rather than a type
  or counter, its first value, wrap width, duplicate behavior, and the index
  of any completion-only callback; test additional captures near a wrap if the
  range is too short to observe it.
- Whether a reliable **last index/total** is provided independently of received
  packets. Deliberately inspect missing-first/middle/last scenarios: if only
  `isComplete` is available without a verified last index, stop; do not certify
  a contiguous-range completeness rule by guessing the last packet.
- Byte-for-byte evidence for **prefix/trailer** length/content across multiple
  raw captures (including first and last packet). If unverified, do not strip.
- Transfer-mode `media.config` HTTP status and route, post-exit P2P group
  removal and a successful photo-ready/thumbnail request after teardown.

Only after those observations and a documented, repeatable index/framing rule
can step 2 introduce strict assembly. No hardware contract result or 10/10
transfer reliability is claimed by this document.