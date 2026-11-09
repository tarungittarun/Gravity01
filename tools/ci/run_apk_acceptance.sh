#!/usr/bin/env bash
# Test the EXACT downloadable APK as a traveller would: no separate app rebuild.
set -euo pipefail
mkdir -p acceptance-evidence /tmp/acceptance-apk /tmp/acceptance-build /tmp/acceptance-test
finish() {
  rc=$?
  echo "acceptance profile=${PROFILE:-unknown} group=${GROUP:-unknown} exit=$rc" | tee -a acceptance-evidence/result.txt
  adb logcat -d -t 1200 > acceptance-evidence/logcat.txt 2>&1 || true
  adb exec-out screencap -p > acceptance-evidence/final.png 2>/dev/null || true
  adb shell uiautomator dump /sdcard/acceptance-window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/acceptance-window.xml acceptance-evidence/window.xml >/dev/null 2>&1 || true
  adb pull /sdcard/Android/data/in.opentrack.india.test/files/evidence acceptance-evidence/screens >/dev/null 2>&1 || true
}
trap finish EXIT

: "${GH_TOKEN:?GitHub token required for exact artifact download}"
: "${GITHUB_SHA:?expected source commit required}"
: "${GITHUB_RUN_ID:?same workflow run required}"
: "${PROFILE:?online or offline profile required}"
: "${GROUP:?journey, trust or refresh partition required}"
selector=$(python3 tools/ci/acceptance_plan.py "$GROUP" selector)
expected=$(python3 tools/ci/acceptance_plan.py "$GROUP" count)

# Both copies are independent downloads. The rolling release must name this SAME commit;
# a publishing failure, older APK or racing replacement is a hard FAIL, not a fallback.
gh run download "$GITHUB_RUN_ID" -n opentrack-debug-apk -D /tmp/acceptance-build
gh run download "$GITHUB_RUN_ID" -n acceptance-androidTest -D /tmp/acceptance-test
gh release download latest-apk -p app-debug.apk -D /tmp/acceptance-apk
notes=$(gh release view latest-apk --json body --jq .body)
[[ "$notes" == *"Built from ${GITHUB_SHA:0:7} "* ]] || {
  echo "::error::published release provenance does not identify $GITHUB_SHA: $notes"; exit 1;
}
python3 tools/ci/verify_acceptance_apk.py /tmp/acceptance-apk/app-debug.apk \
  /tmp/acceptance-build/app-debug.apk "$GITHUB_SHA" | tee acceptance-evidence/apk-preflight.txt

export API=34 IMG=google_apis ABI=x86_64 DEVICE=pixel_6 BOOT_TIMEOUT=360
bash tools/ci/emulator_boot.sh 2>&1 | tee acceptance-evidence/boot.txt
export PATH="${ANDROID_HOME:-/usr/local/lib/android/sdk}/platform-tools:$PATH"
if [ "$PROFILE" = offline ]; then
  adb shell wm size 1080x2160
  adb shell wm density 440
  adb shell settings put system font_scale 1.30
  adb shell svc wifi disable
  adb shell svc data disable
  # A CLI command returning zero is not proof the network state changed.
  wifi=$(adb shell settings get global wifi_on | tr -d '\r')
  [ "$wifi" = 0 ] || { echo "::error::offline profile still has Wi-Fi on: $wifi"; exit 1; }
elif [ "$PROFILE" != online ]; then
  echo "::error::unknown profile $PROFILE"; exit 1
fi

apk=/tmp/acceptance-apk/app-debug.apk
test_apk=/tmp/acceptance-test/app-debug-androidTest.apk
[ -s "$test_apk" ] || { echo "::error::test APK absent"; exit 1; }
adb install -r -g "$apk"
adb install -r "$test_apk"
adb shell pm list instrumentation | grep -F 'in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner'
# Prove the OS installed the downloaded bytes; a digest of the build job alone is not enough.
installed=$(adb shell pm path in.opentrack.india | tr -d '\r' | sed -n 's/^package://p' | head -1)
[ -n "$installed" ] || { echo "::error::PackageManager cannot find installed APK"; exit 1; }
adb pull "$installed" /tmp/acceptance-apk/installed.apk
printf 'published %s\ninstalled %s\n' "$(sha256sum "$apk")" "$(sha256sum /tmp/acceptance-apk/installed.apk)" | tee acceptance-evidence/install-hashes.txt
cmp "$apk" /tmp/acceptance-apk/installed.apk || {
  echo "::error::installed base.apk differs from published APK"; exit 1;
}
adb shell pm clear in.opentrack.india

set +e
timeout 240 adb shell am instrument -w -e debug false -e offline "$([ "$PROFILE" = offline ] && echo true || echo false)" \
  -e class "$selector" \
  in.opentrack.india.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tee acceptance-evidence/instrumentation.txt
rc=${PIPESTATUS[0]}
set -e
test_ok=false
if [ "$rc" -eq 0 ] && grep -Eq "OK \\($expected tests?\\)|Tests run: $expected, *Failures: 0" acceptance-evidence/instrumentation.txt &&
   ! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|shortMsg=Process crashed' acceptance-evidence/instrumentation.txt; then
  test_ok=true
else
  echo "::error::$PROFILE/$GROUP acceptance failed or did not execute exactly $expected methods (rc=$rc)"
fi
# A red UI journey must not suppress the independently measured startup result.
adb shell am force-stop in.opentrack.india
warm_ok=false
if launch=$(timeout 15 adb shell am start -W -n in.opentrack.india/.ui.MainActivity 2>&1); then
  echo "$launch" | tee acceptance-evidence/warm-start.txt
  ms=$(echo "$launch" | sed -n 's/.*TotalTime: \([0-9][0-9]*\).*/\1/p' | head -1)
  if [ -n "$ms" ] && [ "$ms" -le 10000 ]; then warm_ok=true; fi
else
  echo "$launch" | tee acceptance-evidence/warm-start.txt
  ms=unavailable
fi
[ "$warm_ok" = true ] || echo "::error::warm launch exceeded 10s or TotalTime unavailable: ${ms:-unavailable} ms"
{
  echo "### Exact published APK · traveller acceptance ($PROFILE/$GROUP)"
  echo "- $(sha256sum "$apk" | cut -d' ' -f1) — downloaded = built = installed"
  echo "- $expected method(s) executed: $test_ok; warm launch ${ms:-unavailable} ms: $warm_ok (≤10,000 ms)"
} >> "$GITHUB_STEP_SUMMARY"
[ "$test_ok" = true ] && [ "$warm_ok" = true ] || exit 1
echo "PASS: $PROFILE/$GROUP — $expected traveller method(s), exact release bytes, $ms ms warm launch" | tee acceptance-evidence/result.txt
