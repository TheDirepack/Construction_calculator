#!/usr/bin/env bash
set -euo pipefail

mkdir -p ci-logs
# The emulator action shuts down the device as soon as this script returns.
if ./gradlew --console=plain --stacktrace :app:connectedDebugAndroidTest 2>&1 | tee ci-logs/instrumentation.log; then
    exit 0
else
    result=$?
    adb devices -l > ci-logs/devices.txt 2>&1 || true
    timeout 20s adb logcat -d -v threadtime > ci-logs/logcat.txt 2>&1 || true
    exit "$result"
fi
