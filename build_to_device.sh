#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_APP_DIR="$REPO_ROOT/android/CyanBridge"

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="/Users/michael/Library/Android"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

cd "$ANDROID_APP_DIR"

echo "==> Repo root: $REPO_ROOT"
echo "==> Java: $JAVA_HOME"
echo "==> Android SDK: $ANDROID_HOME"
echo "==> Device status"
adb devices
adb -d wait-for-device

echo "==> Stopping stale Gradle daemons"
./gradlew --stop || true

echo "==> Installing updated APK without wiping app data"
# Do not uninstall here: that clears /data/data/com.fersaiyan.cyanbridge and
# resets all SharedPreferences and encrypted app state between deployments.
./gradlew :app:installDebug --no-daemon --console=plain
