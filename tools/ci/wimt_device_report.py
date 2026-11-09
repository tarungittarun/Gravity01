#!/usr/bin/env python3
"""Bounded verdict for the strict .org.in APK device journey; no raw diary publication."""
import json
import re
import sys
from pathlib import Path

HOST = "whereismytrain.org.in"


def verdict(instrumentation: str, diary: str, process_rc: int) -> dict:
    attempted = re.findall(r"\[HTTP\] GET whereismytrain\.org\.in try#\d+ -> ([^\r\n]+)", diary)
    evidence = [line for line in diary.splitlines()
                if "[EVIDENCE] source=whereismytrain.org.in (website)" in line]
    latest = evidence[-1] if evidence else ""
    transport = re.search(r"\btransport=([A-Z_]+)", latest)
    rows = re.search(r"\bsiteRows=(\d+)", latest)
    train = re.search(r"\btrain=(\d{5})\b", latest)
    test_passed = process_rc == 0 and bool(re.search(r"OK \(1 test\)", instrumentation)) and \
        "FAILURES!!!" not in instrumentation and "INSTRUMENTATION_FAILED" not in instrumentation
    site_answered = any("answered " in x for x in attempted)
    wire_refused = any("HTTP 403" in x or "HTTP 429" in x for x in attempted)
    # The opt-in diary may not have been collected from an emulator. The test's
    # *visible UI* failure can still establish that the app displayed a website
    # refusal. Do not invent a wire request count when the wire receipt is absent.
    ui_refused = bool(re.search(r"Website refused request \(HTTP (?:403|429)\)", instrumentation))
    refused = wire_refused or ui_refused
    accepted = test_passed and site_answered and not refused and \
        transport is not None and transport.group(1) == "NETWORK_ANSWER" and \
        rows is not None and int(rows.group(1)) > 0
    reason = ("PASS_SITE_CARD_AND_DEVICE_UI" if accepted else
              "WEBSITE_REFUSED_WIRE" if wire_refused else
              "WEBSITE_REFUSED_UI_DIARY_UNAVAILABLE" if ui_refused else
              "DEVICE_TEST_NOT_EXECUTED" if not instrumentation else
              "SITE_REPLY_OR_SOURCE_QUALIFICATION_MISSING" if site_answered else
              "NO_WEBSITE_NETWORK_ANSWER")
    return {"verdict": reason, "accepted": accepted, "instrumentationPassed": test_passed,
            "websiteGetCount": len(attempted) if attempted else None, "websiteAnswered": site_answered,
            "websiteRefused": refused, "wireReceiptAvailable": bool(attempted),
            "sourceTransport": transport.group(1) if transport else "unknown",
            "siteRows": int(rows.group(1)) if rows else 0,
            "trainNumber": train.group(1) if train else "unknown",
            "googlePhoneAppFeed": False, "browserEngineRequest": True,
            "websiteGetCountScope": "main_document_navigation_only",
            "note": "Emulator WebView + independent .org.in website; not owner's Chrome or Google WIMT app"}


def self_test() -> None:
    good = ("11-05 [HTTP] GET whereismytrain.org.in try#1 -> answered 108395B\n"
            "11-05 [EVIDENCE] source=whereismytrain.org.in (website) train=20652 "
            "transport=NETWORK_ANSWER siteRows=10 cdnRequestId=PRIVATE\n")
    denied = good.replace("answered 108395B", "HTTP HTTP 403").replace(
        "transport=NETWORK_ANSWER siteRows=10", "transport=FAILED_OR_UNUSABLE siteRows=0")
    assert verdict("OK (1 test)", good, 0)["accepted"]
    assert verdict("OK (1 test)", denied, 0)["verdict"] == "WEBSITE_REFUSED_WIRE"
    no_diary = verdict("java.lang.AssertionError: warning='Website refused request (HTTP 403)'\n"
                       "FAILURES!!!\nTests run: 1,  Failures: 1", "", 0)
    assert no_diary["verdict"] == "WEBSITE_REFUSED_UI_DIARY_UNAVAILABLE"
    assert no_diary["websiteGetCount"] is None and not no_diary["accepted"]
    unrelated_diary = verdict("Website refused request (HTTP 403)", "other app log entry", 1)
    assert unrelated_diary["websiteGetCount"] is None
    assert not unrelated_diary["wireReceiptAvailable"]
    assert not verdict("FAILURES!!!", good, 0)["accepted"]
    assert not verdict("OK (1 test)", good, 1)["accepted"]
    assert not verdict("", good, 0)["accepted"]
    assert "PRIVATE" not in json.dumps(verdict("OK (1 test)", good, 0))
    print("offline strict gate and diary redaction tests passed")


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    elif len(sys.argv) == 4:
        ins = Path(sys.argv[1]).read_text(errors="replace") if Path(sys.argv[1]).exists() else ""
        diary = Path(sys.argv[2]).read_text(errors="replace") if Path(sys.argv[2]).exists() else ""
        report = verdict(ins, diary, int(sys.argv[3]))
        print(json.dumps(report, sort_keys=True))
        if not report["accepted"]:
            sys.exit(1)
    else:
        raise SystemExit("usage: wimt_device_report.py instrumentation.txt private-diary.txt process-exit-code")
