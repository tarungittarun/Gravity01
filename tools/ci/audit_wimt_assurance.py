#!/usr/bin/env python3
"""Never promote green simulated requests to independently accepted website access.

Call only on an artifact downloaded from a completed SOURCE-STRICT device run.
The workflow verifies run provenance/conclusion before calling this offline gate.
"""
from pathlib import Path
import json
import sys

from verify_wimt_screens import verify


def audit(folder: Path) -> dict:
    result = json.loads((folder / "result.json").read_text())
    inst = (folder / "instrumentation.txt").read_text(errors="replace")
    snap = json.loads((folder / "screen-verification.json").read_text())
    if not (result.get("accepted") is True and result.get("instrumentationPassed") is True
            and result.get("websiteAnswered") is True
            and result.get("wireReceiptAvailable") is True
            and isinstance(result.get("websiteGetCount"), int)
            and result["websiteGetCount"] > 0
            and result.get("websiteRefused") is False
            and result.get("sourceTransport") == "NETWORK_ANSWER"
            and isinstance(result.get("siteRows"), int) and result["siteRows"] > 0
            and result.get("googlePhoneAppFeed") is False
            and result.get("browserEngineRequest") is True
            and result.get("websiteGetCountScope") == "main_document_navigation_only"):
        raise ValueError("artifact is not a source-strict independent website network pass")
    if "OK (1 test)" not in inst or "FAILURES!!!" in inst or "INSTRUMENTATION_FAILED" in inst:
        raise ValueError("exact installed-app instrumented test did not pass")
    if snap.get("displayedRefusalRequired") is not False or len(snap.get("verifiedAppScreens", {})) != 2:
        raise ValueError("source-qualified in-app before/after screenshots were not verified")
    verify(folder, require_http_403=False)
    return {"sourceVerdict": "SITE_CARD_AND_APP_UI_ACCEPTED",
            "websiteGetCount": result["websiteGetCount"], "siteRows": result["siteRows"],
            "googlePhoneAppFeed": False,
            "note": "Even a passing website APK run is not provider permission or owner-phone GPS"}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: audit_wimt_assurance.py <source-strict-run-evidence>")
    try:
        print(json.dumps(audit(Path(sys.argv[1])), sort_keys=True))
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        print(f"SOURCE_ASSURANCE_REJECTED: {error}", file=sys.stderr)
        sys.exit(1)
