#!/usr/bin/env python3
"""Synthetic-only tests: never bundle an owner's raw diary or CDN identifier."""
from pathlib import Path
import os
import stat
import tempfile
import unittest

from triage_hcdn_diary import ROOT, classify, draft, save_private

URL = "https://whereismytrain.org.in/?trainNo=16228"
ID = "sample-request-id-not-from-the-website"
UTC = "2026-11-07T19:24:35.176Z"


def receipt(value: str = ID, url: str = URL) -> str:
    return (f"11-08 00:54:35.176 [SITE_REFUSAL_PRIVATE] HTTP 403; "
            f"x-hcdn-request-id={value}; atUtc={UTC}; url={url}\n")


class TriageTest(unittest.TestCase):
    def test_valid_private_operator_draft_and_exact_fields(self):
        finding = classify(receipt())
        self.assertEqual("VALID", finding.state)
        body = draft(finding)
        self.assertIn(f"x-hcdn-request-id: {ID}", body)
        self.assertIn(f"UTC callback time: {UTC}", body)
        self.assertIn(f"Exact requested URL: {URL}", body)
        self.assertIn("2026-11-08 00:54:35.176 IST", body)
        self.assertIn("claim that we meet your security rules", body)
        self.assertNotIn("Set-Cookie", body)

    def test_absent_never_proves_origin_block(self):
        f = classify(receipt("<absent>"))
        self.assertEqual("ABSENT", f.state)
        text = draft(f)
        self.assertIn("does **not** establish that the origin", text)
        self.assertIn("both CDN and origin logs", text)
        self.assertNotIn(ID, text)

    def test_invalid_or_missing_is_not_a_valid_id(self):
        self.assertEqual("INVALID", classify(receipt("<invalid-or-oversized>")).state)
        self.assertEqual("NO_RECEIPT", classify("11-08 [HTTP] GET site -> HTTP 403").state)
        self.assertEqual("MALFORMED_RECEIPT", classify(receipt(ID, URL + "&cookie=bad")).state)
        self.assertEqual("MALFORMED_RECEIPT", classify(receipt("bad\rword")).state)
        self.assertEqual("VALID", classify(receipt(ID) + receipt("fresh-id")).state)
        self.assertEqual("fresh-id", classify(receipt(ID) + receipt("fresh-id")).request_id)

    def test_private_file_mode_and_non_private_path_rejected(self):
        private = ROOT / "private"
        private.mkdir(mode=0o700, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=private) as directory:
            path = Path(directory) / "draft.md"
            save_private(path, draft(classify(receipt())))
            self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
            self.assertIn(ID, path.read_text())
            with self.assertRaises(FileExistsError):
                save_private(path, "overwrite")
        with tempfile.TemporaryDirectory() as outside:
            with self.assertRaises(ValueError):
                save_private(Path(outside) / "bad.md", "never write")
        self.assertFalse(os.path.exists(private / "bad.md"))


if __name__ == "__main__":
    unittest.main()
