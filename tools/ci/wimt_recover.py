#!/usr/bin/env python3
"""Bounded device-assertion diagnosis from an existing artifact; no provider request."""
from pathlib import Path
import json
import re
import sys


def brief(log: str) -> list[str]:
    lines = []
    patterns = ("FAILURES!!!", "java.lang.AssertionError", "java.lang.IllegalStateException",
                "no known unambiguously active", "INSTRUMENTATION_FAILED",
                "INSTRUMENTATION_RESULT:", "Tests run:", "Process crashed", "OK (", "AssertionError:")
    for line in log.splitlines():
        if any(k in line for k in patterns):
            # This test's assertions include public UI labels only; still do not
            # print arbitrary stack frames, URLs, session headers or CDN IDs.
            clean = re.sub(r"(?i)(x-hcdn-request-id|cdnRequestId|cookie|token)[=: ]+\S+",
                           r"\1=<redacted>", line)[:280]
            lines.append(clean)
    return lines[-18:]


if __name__ == "__main__":
    folder = Path(sys.argv[1])
    original = json.loads((folder / "result.json").read_text())
    inst = (folder / "instrumentation.txt").read_text(errors="replace") if (folder / "instrumentation.txt").exists() else ""
    boot = (folder / "boot.txt").read_text(errors="replace") if (folder / "boot.txt").exists() else ""
    from wimt_device_report import verdict
    # Reinterpret only the existing visible test failure if the diary was absent.
    # No added GET, no invented wire count or source success.
    result = (verdict(inst, "", 1) if not original.get("accepted") and
              (not original.get("wireReceiptAvailable", False) or
               original.get("websiteGetCount") == 0) and inst else original)
    report = ["### Existing WIMT emulator run · failure diagnosis (no new site GET)", "",
              "```json", json.dumps(result, sort_keys=True), "```", "",
              "Instrumented test assertions/results (bounded, no raw diary):", "```"]
    report += brief(inst) or ["No instrumented result captured; boot tail follows."]
    if not inst:
        report += [re.sub(r"https?://\S+", "<url>", s)[:220] for s in boot.splitlines()[-12:]]
    verified = (folder / "screen-verification.json").exists()
    report += ["```", "", "Verified in-app screenshots remain in the originating run's artifact."
               if verified else "In-app screenshots were NOT verified; do not present the artifact as app evidence."]
    (folder / "recovered-report.md").write_text("\n".join(report) + "\n")
    print("\n".join(report))
