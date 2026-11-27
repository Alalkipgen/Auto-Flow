#!/usr/bin/env bash
# Only disposable emulator/device test sandboxes. Never replace an owner's stable APK with a local debug key.
set -euo pipefail
[[ "${AUTOFLOW_DISPOSABLE_ANDROID:-}" == 1 ]] || { echo 'Refusing installation: set AUTOFLOW_DISPOSABLE_ANDROID=1 only on a disposable emulator/device'; exit 2; }
command -v adb >/dev/null
adb get-state >/dev/null
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
./gradlew :ui:qaRecapFixture -PqaFixtureDir="$fixture" --max-workers=2
mkdir -p app-android/build/generated/restore-regression
cp "$fixture/AutoFlow-5A8-synthetic-backup.zip" app-android/build/generated/restore-regression/
./gradlew :app-android:assembleDebug :app-android:assembleDebugAndroidTest --max-workers=2
adb install -r app-android/build/outputs/apk/debug/app-android-debug.apk
adb install -r app-android/build/outputs/apk/androidTest/debug/app-android-debug-androidTest.apk
adb shell am instrument -w io.github.alalkipgen.autoflow.test/io.github.alalkipgen.autoflow.android.RestoreRegressionInstrumentation | tee "$fixture/result.txt"
grep -q 'PASS: production Android restore' "$fixture/result.txt"
