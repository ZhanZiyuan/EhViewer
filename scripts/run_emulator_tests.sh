#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:?Android SDK required}"
: "${TEST_IMAGE:?SDK package required}"
: "${TEST_LABEL:?Test label required}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
if adb -s emulator-5554 get-state >/dev/null 2>&1; then
  echo "Emulator port 5554 is already in use" >&2
  exit 1
fi
mkdir -p device-reports
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
avdmanager="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
"$sdkmanager" "$TEST_IMAGE"
avd_path="${RUNNER_TEMP:-/tmp}/ehviewer-ci-avd"
echo no | "$avdmanager" create avd --name ehviewer-ci --package "$TEST_IMAGE" --device pixel_2 --path "$avd_path"
python3 - "$avd_path/config.ini" <<'PYAVD'
import sys
from pathlib import Path
p = Path(sys.argv[1])
lines = [line for line in p.read_text().splitlines() if not line.startswith("disk.dataPartition.size=")]
p.write_text("\n".join(lines) + "\ndisk.dataPartition.size=2G\n")
PYAVD
emulator -avd ehviewer-ci -port 5554 -no-window -no-audio -no-snapshot -gpu swiftshader -no-boot-anim > device-reports/emulator.log 2>&1 &
emulator_pid=$!
finish() {
  adb -s emulator-5554 logcat -d > device-reports/logcat.txt 2>&1 || true
  if kill -0 "$emulator_pid" 2>/dev/null; then
    adb -s emulator-5554 emu kill || true
  fi
  wait "$emulator_pid" || true
}
trap finish EXIT
booted=false
for _ in {1..180}; do
  if [[ "$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    booted=true
    break
  fi
  kill -0 "$emulator_pid"
  sleep 2
done
[[ "$booted" == true ]] || { echo 'Emulator failed to boot'; exit 1; }
adb -s emulator-5554 shell getprop ro.build.version.sdk > device-reports/api.txt
if ! adb -s emulator-5554 shell getconf PAGE_SIZE > device-reports/page-size.txt 2> device-reports/page-size-error.txt; then
  # Older Android images do not ship getconf. Read this shell process's actual mappings.
  adb -s emulator-5554 shell cat /proc/self/smaps > device-reports/smaps.txt
  awk '/^KernelPageSize:/ { print $2 * 1024; exit }' device-reports/smaps.txt > device-reports/page-size.txt
fi
page_size=$(tr -d '\r\n' < device-reports/page-size.txt)
[[ "$page_size" =~ ^[0-9]+$ && "$page_size" -ge 4096 ]]
expected_api="${TEST_LABEL#api}"
expected_api="${expected_api%%-*}"
[[ "$(tr -d '\r\n' < device-reports/api.txt)" == "$expected_api" ]]
if [[ "$TEST_LABEL" == *16k ]]; then
  [[ "$(tr -d '\r\n' < device-reports/page-size.txt)" == 16384 ]]
fi
app=$(find device-inputs -name app-universal-debug.apk -print -quit)
test_apk=$(find device-inputs -name app-debug-androidTest.apk -print -quit)
[[ -f "$app" && -f "$test_apk" ]]
adb -s emulator-5554 install -r "$app"
adb -s emulator-5554 install -r "$test_apk"
adb -s emulator-5554 shell am instrument -w -r moe.tarsin.ehviewer.debug.test/androidx.test.runner.AndroidJUnitRunner | tee device-reports/instrumentation.txt
grep -Eq '^OK \([1-9][0-9]* tests?\)' device-reports/instrumentation.txt
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' device-reports/instrumentation.txt
