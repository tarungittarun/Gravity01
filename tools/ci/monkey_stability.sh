#!/usr/bin/env bash
# Random-event stability pass over the *installed* app, plus a crash scan of logcat and dropbox.
#
# Why this exists alongside a flow suite: a scripted test walks the paths someone thought of. Monkey walks
# the ones nobody did — a tap landing while a coroutine is mid-flight, the IME open while the panel
# re-renders, Back pressed twice in 40 ms during a live fetch, rotation between a keystroke and its
# debounce. Two bugs in this project (a suggestion list that answered with nothing, a live column that
# reprinted the timetable) were logic, not layout: monkey would not have caught either, which is why it is
# a *second* axis and not the plan.
#
# Env in: PKG COUNT THROTTLE SEED EXTRA_ARGS
set -uo pipefail

PKG=${PKG:-in.opentrack.india}
COUNT=${COUNT:-30000}
THROTTLE=${THROTTLE:-20}
SEED=${SEED:-1}

echo "== monkey round 1: $COUNT events, ${THROTTLE}ms apart, seed $SEED (a fixed seed makes a red run reproducible)"
adb shell monkey -p "$PKG" --ignore-security-exceptions --ignore-crashes --ignore-timeouts \
  -s "$SEED" --throttle "$THROTTLE" -v -v -v "$COUNT" > /tmp/monkey1.log 2>&1
M1=$?

echo "== killing the process, then relaunching: the reopen-after-kill path"
adb shell am force-stop "$PKG"
sleep 2
adb shell monkey -p "$PKG" --ignore-security-exceptions --ignore-crashes -s "$((SEED + 1))" \
  -c android.intent.category.LAUNCHER 1 > /tmp/monkey2.log 2>&1
sleep 6
# A smoke run must not silently add 4,000 extra events after its requested budget. A full
# stability sweep still includes the 4,000-event relaunch pass.
REOPEN_COUNT=$COUNT
[ "$REOPEN_COUNT" -gt 4000 ] && REOPEN_COUNT=4000
adb shell monkey -p "$PKG" --ignore-security-exceptions --ignore-crashes -s "$((SEED + 2))" \
  --throttle "$THROTTLE" "$REOPEN_COUNT" > /tmp/monkey3.log 2>&1

echo "== collecting evidence"
adb logcat -d -v brief > /tmp/logcat.txt 2>&1 || true
adb shell dumpsys dropbox --print > /tmp/dropbox.txt 2>&1 || true
adb shell dumpsys activity processes > /tmp/procstate.txt 2>&1 || true

# The three things that matter, in the three places they show up. A monkey run whose exit code is 0 can
# still have crashed the app four times: `--ignore-crashes` makes monkey continue *precisely so that the
# whole run is covered*, so the verdict has to come from the logs, not from monkey's status.
{
  echo "monkey exit codes: round1=$M1"
  grep -c "^Events injected:" /tmp/monkey1.log 2>/dev/null || true
  grep -E "Events injected|: SEED|count=" /tmp/monkey1.log | tail -5 || true
} > /tmp/monkey-summary.txt
cat /tmp/monkey-summary.txt

CRASHES=$(grep -Ec "FATAL EXCEPTION|Fatal signal|beginning of crash" /tmp/logcat.txt || true)
ANRS=$(grep -Ec "ANR in $PKG|Reason: Input dispatching timed out" /tmp/logcat.txt || true)
DROPS=$(grep -Ec "$PKG" /tmp/dropbox.txt || true)
DIED=$(grep -Ec "has stopped|keeps stopping|Process: $PKG" /tmp/monkey1.log /tmp/logcat.txt || true)
ALIVE=$(adb shell pidof "$PKG" | tr -d '\r')

echo "== verdict: crashes=$CRASHES anrs=$ANRS dropbox-entries=$DROPS stopped-dialogs=$DIED pid-after='${ALIVE:-none}'"
if [ "${CRASHES:-0}" -gt 0 ] || [ "${ANRS:-0}" -gt 0 ]; then
  echo "::error::monkey found ${CRASHES} crash and ${ANRS} ANR line(s) in logcat for $PKG"
  echo "--- first crash ---"
  grep -A 30 -m1 "FATAL EXCEPTION" /tmp/logcat.txt || head -40 /tmp/logcat.txt
  echo "--- dropbox (crash/ANR records, first 40 lines) ---"
  head -40 /tmp/dropbox.txt || true
  exit 1
fi
if [ -z "${ALIVE:-}" ]; then
  # Not fatal on its own: monkey can end with the app in the background or with the last gesture having
  # pressed Back out of it. Loud, though, because "no process" plus "no FATAL" is also what a silent kill
  # by the low-memory killer looks like — and that is worth reading about.
  echo "::warning::$PKG has no process id after the run, and no crash line was found"
fi
echo "OK  no crash or ANR attributed to $PKG across $COUNT+$REOPEN_COUNT random events"
exit 0
