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

echo "==> Uninstalling any existing app version to avoid downgrade conflicts"
adb uninstall com.fersaiyan.cyanbridge || adb shell pm uninstall --user 0 com.fersaiyan.cyanbridge || true

echo "==> Building and installing APK to connected device"
./gradlew :app:installDebug --no-daemon --console=plain
