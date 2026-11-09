#!/usr/bin/env python3
"""Offline negative cases; synthetic evidence NEVER represents a real website response."""
from pathlib import Path
import json
import tempfile
import unittest

from audit_wimt_assurance import audit
from verify_wimt_screens import NAMES, PNG


class AssuranceAuditTest(unittest.TestCase):
    def test_cannot_promote_a_mock_or_refusal_to_live_site_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            screens = folder / "screens"
            screens.mkdir()
            for name in NAMES:
                (screens / (name + ".png")).write_bytes(PNG + b"test-fixture" * 300)
                (screens / (name + ".xml")).write_text(
                    '<hierarchy><node package="in.opentrack.india" '
                    'resource-id="in.opentrack.india:id/trainNo" text="20651" /></hierarchy>')
            sample = {"accepted": True, "instrumentationPassed": True,
                      "websiteAnswered": True, "wireReceiptAvailable": True,
                      "websiteGetCount": 1, "websiteRefused": False,
                      "sourceTransport": "NETWORK_ANSWER", "siteRows": 2,
                      "googlePhoneAppFeed": False, "browserEngineRequest": True,
                      "websiteGetCountScope": "main_document_navigation_only"}
            (folder / "instrumentation.txt").write_text("OK (1 test)")
            (folder / "screen-verification.json").write_text(json.dumps({
                "displayedRefusalRequired": False,
                "verifiedAppScreens": {name: {} for name in NAMES}}))
            result = folder / "result.json"
            result.write_text(json.dumps(sample))
            self.assertEqual(audit(folder)["sourceVerdict"], "SITE_CARD_AND_APP_UI_ACCEPTED")
            for key, value in (("accepted", False), ("websiteGetCount", None),
                               ("wireReceiptAvailable", False), ("siteRows", 0),
                               ("websiteRefused", True), ("sourceTransport", "unknown")):
                with self.subTest(key=key):
                    result.write_text(json.dumps({**sample, key: value}))
                    with self.assertRaises(ValueError):
                        audit(folder)
            result.write_text(json.dumps(sample))
            (folder / "instrumentation.txt").write_text("FAILURES!!!")
            with self.assertRaises(ValueError):
                audit(folder)
            (folder / "instrumentation.txt").write_text("OK (1 test)")
            (screens / (NAMES[1] + ".xml")).write_text(
                '<hierarchy><node package="com.google.android.apps.nexuslauncher" /></hierarchy>')
            with self.assertRaises(ValueError):
                audit(folder)


if __name__ == "__main__":
    unittest.main()
