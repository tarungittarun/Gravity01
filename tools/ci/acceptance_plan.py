#!/usr/bin/env python3
"""Parallel APK acceptance partitions. Every device method runs once per profile.

The same plan drives the runner's selector, exact test count and the CI self-check.
Adding a @Test without assigning a partition fails the workflow instead of silently
reducing end-user coverage to make CI faster.
"""
from pathlib import Path
import re
import sys

GROUPS = {
    "journey": ("ticketToTimetableHasRealRowsAndClearlyQualifiedPosition",
                "settingsRoundTripReturnsToUsableSearch"),
    "trust": ("invalidOrImpossibleTripCannotMasqueradeAsAResult",
              "primaryControlsHaveLegibleTouchTargets"),
    "refresh": ("manualRefreshKeepsTicketAndShowsAnAnswer",),
}
CLASS = "in.opentrack.india.e2e.TravellerAcceptanceTest"
SOURCE = Path(__file__).resolve().parents[2] / "app/src/androidTest/java/in/opentrack/india/e2e/TravellerAcceptanceTest.kt"


def check():
    methods = re.findall(r"@Test\s+fun\s+(\w+)\s*\(", SOURCE.read_text())
    assigned = [name for names in GROUPS.values() for name in names]
    if len(set(assigned)) != len(assigned) or set(methods) != set(assigned):
        raise ValueError(f"Acceptance partitions do not cover each method exactly once: "
                         f"source={sorted(methods)}, groups={sorted(assigned)}")
    if len(methods) < 5 or any(not names or len(names) > 2 for names in GROUPS.values()):
        raise ValueError("Acceptance coverage or per-job work budget changed unexpectedly")
    return len(methods)


def main():
    if len(sys.argv) == 2 and sys.argv[1] == "--check":
        print(f"PASS: {check()} end-user methods assigned exactly once across {len(GROUPS)} jobs/profile")
        return
    if len(sys.argv) != 3 or sys.argv[1] not in GROUPS or sys.argv[2] not in ("selector", "count"):
        raise SystemExit("usage: acceptance_plan.py {journey|trust|refresh} {selector|count} | --check")
    check()
    names = GROUPS[sys.argv[1]]
    print(",".join(f"{CLASS}#{method}" for method in names)
          if sys.argv[2] == "selector" else len(names))


if __name__ == "__main__":
    main()
