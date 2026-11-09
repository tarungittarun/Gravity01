#!/usr/bin/env bash
# Real APK + Android instrumentation, but all website answers are test-owned.
# Never execute this as proof that the independent website accepted a request.
set -euo pipefail
mkdir -p wimt-simulation-evidence
export API=34 IMG=google_apis ABI=x86_64 DEVICE=pixel_6 BOOT_TIMEOUT=360
bash tools/ci/emulator_boot.sh > wimt-simulation-evidence/boot.txt 2>&1 || {
  tail -25 wimt-simulation-evidence/boot.txt
  exit 3
}
export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
# Network OFF for the entire suite; do not accidentally probe the site from CI.
adb shell svc wifi disable
adb shell svc data disable
wifi=$(adb shell settings get global wifi_on | tr -d '\r')
[ "$wifi" = 0 ] || { echo "::error::could not disable emulator Wi-Fi: $wifi"; exit 3; }
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm clear in.opentrack.india
set +e
timeout 240 adb shell am instrument -w -e class \
  in.opentrack.india.e2e.WimtMobileRequestSimulationTest \
  in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner \
  > wimt-simulation-evidence/instrumentation.txt 2>&1
rc=$?
set -e
if [ "$rc" -ne 0 ] || ! grep -q 'OK (4 tests)' wimt-simulation-evidence/instrumentation.txt || \
    grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=Process crashed' wimt-simulation-evidence/instrumentation.txt; then
  tail -50 wimt-simulation-evidence/instrumentation.txt
  echo '::error::4 Android simulation methods did not all pass'
  exit 1
fi
# The in-test APK screenshot must show TrackTrain, not the post-test launcher.
for ext in png xml; do
  adb exec-out run-as in.opentrack.india cat "files/evidence/wimt-simulation-offline-track.$ext" \
    > "wimt-simulation-evidence/offline-track.$ext"
done
python3 tools/ci/verify_wimt_simulation.py wimt-simulation-evidence \
  | tee wimt-simulation-evidence/verified.json
