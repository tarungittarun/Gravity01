#!/usr/bin/env bash
# Turn the coverage file an instrumented emulator run left on the device into numbers a gate can use.
#
# The Android Gradle plugin will happily render its own coverage HTML, but it wants to re-run the tests to
# do it, and on a runner without acceleration "re-run the tests" is another 20 minutes of booting. This
# script therefore takes the `.ec` that `am instrument -e coverage true` wrote, and asks the JaCoCo CLI -
# straight from Maven Central, no wrapper, no plugin - for an XML report. From that XML the flow gate can
# print per-package line coverage and enforce a floor.
#
# Usage: jacoco_report.sh <coverage.ec> <classes-dir> <source-dir> <out-dir>
set -uo pipefail

EC=${1:-}; CLASSES=${2:-}; SRC=${3:-}; OUT=${4:-reports/coverage}
if [ -z "$EC" ] || [ -z "$CLASSES" ]; then
  echo "usage: $0 <coverage.ec> <classes-dir> [source-dir] [out-dir]"; exit 2
fi
if [ ! -s "$EC" ]; then
  echo "::error::no coverage data in $EC — the app was not built with testCoverageEnabled, or the run died before the instrumentation reported"
  exit 3
fi

JAR_DIR=${JAR_DIR:-/tmp/jacoco}
JAR="$JAR_DIR/org.jacoco.cli-0.8.12-nodeps.jar"
URL="https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/0.8.12/org.jacoco.cli-0.8.12-nodeps.jar"
if [ ! -f "$JAR" ]; then
  mkdir -p "$JAR_DIR"
  curl -sSfL --retry 3 -o "$JAR" "$URL" || { echo "::error::could not fetch the JaCoCo CLI from Maven Central"; exit 3; }
fi

mkdir -p "$OUT"
ARGS=(report "$EC" --classfiles "$CLASSES" --name "OpenTrack India (device runs)" --xml "$OUT/cov.xml" --html "$OUT/html")
[ -n "$SRC" ] && ARGS+=(--sourcefiles "$SRC")
if ! java -jar "$JAR" "${ARGS[@]}" > /tmp/jacoco.log 2>&1; then
  echo "::error::jacoco could not build a report"; tail -20 /tmp/jacoco.log; exit 3
fi
echo "wrote $OUT/cov.xml and $OUT/html"
# A quick human-readable total, so a runner log explains itself without the gate.
python3 - "$OUT/cov.xml" << 'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
c = root.find("counter[@type='LINE']")
if c is None:
    print("no LINE counter in the report"); raise SystemExit(0)
cov, mis = int(c.get("covered")), int(c.get("missed"))
print(f"device-run line coverage: {100.0*cov/max(1,cov+mis):.1f}% ({cov}/{cov+mis} lines)")
for pkg in root.findall("package"):
    pc = pkg.find("counter[@type='LINE']")
    if pc is None: continue
    a, b = int(pc.get("covered")), int(pc.get("missed"))
    print(f"  {100.0*a/max(1,a+b):5.1f}%  {pkg.get('name')}")
PY
