#!/usr/bin/env python3
"""Guard the audit itself: no missing row, skipped test, old SHA or slow job gets green."""
from __future__ import annotations

import pathlib
import tempfile
import unittest

import apk_audit as qa

SHA = "a" * 40


def run(run_id: int, conclusion: str = "success") -> dict:
    return {"id": run_id, "head_sha": SHA, "conclusion": conclusion,
            "status": "completed", "html_url": f"https://github.com/x/y/actions/runs/{run_id}",
            "created_at": "2026-10-17T00:00:00Z", "updated_at": "2026-10-17T00:02:30Z"}


def job(name: str, result: str = "success", duration: int = 40) -> dict:
    return {"name": name, "conclusion": result,
            "started_at": "2026-10-17T00:00:00Z",
            "completed_at": f"2026-10-17T00:{duration // 60:02d}:{duration % 60:02d}Z",
            "steps": []}


def inputs():
    android = [job(n) for n in ("debug APK", "field-tests", "checks", "publish", "screens")]
    android.extend(job(f"traveller / published APK / {p} / {group}")
                   for p in ("online", "offline") for group in ("journey", "trust", "refresh"))
    e2e = [job(n) for n in ("plan", "build", "instrument", "release_build", "report", "release_smoke")]
    for row in sorted(qa.EXPECTED_ROWS - {"release"}):
        group = "e2e" if row.startswith("api34") else "e2e_full"
        e2e.append(job(f"{group} ({row}, installed device partition)"))
    unit = {p: {"tests": t, "skipped": 0, "failed": 0, "classes": {p}}
            for p, t in zip(qa.PARTS, (31, 70, 120, 170))}
    apk = {"sha256": "a" * 64, "size": 10_000_000, "package": ("in.opentrack.india", "55", "1.0.54-r125"),
           "offline_asset_bytes": 1_000_000}
    return [run(1), run(2), android, e2e, unit, [], apk, [],
            {"sha256": apk["sha256"], "size": apk["size"]}, [], [], SHA, True, []]


class AuditTest(unittest.TestCase):
    def test_complete_result(self):
        report, ok = qa.summarize(*inputs())
        self.assertFalse(ok, report)
        self.assertIn("31/31 rows passed", report)
        self.assertIn("**STRICT VERDICT: FAIL**", report)
        self.assertIn("Live train data parity | **UNVERIFIED**", report)

    def test_breached_budget_is_red_without_weakening_device_evidence(self):
        args = inputs()
        args[3][7] = job(args[3][7]["name"], duration=240)
        report, ok = qa.summarize(*args)
        self.assertFalse(ok)
        self.assertIn("Three-minute workflow & job budget | **FAIL**", report)
        self.assertIn("240s", report)

    def test_wrong_sha_and_failed_device_row_are_both_explained(self):
        args = inputs()
        args[0]["head_sha"] = "b" * 40
        args[3][7]["conclusion"] = "failure"
        args[10] = [{"body": "### e2e/api34-station0 — FAIL\nError in geometry: clock misplaced"}]
        report, ok = qa.summarize(*args)
        self.assertFalse(ok)
        self.assertIn("Android CI | **FAIL**", report)
        self.assertIn("30/31 rows passed", report)
        self.assertIn("Error in geometry", report)

    def test_missing_xml_or_skipped_method_cannot_pass(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder, "unit-results-ui-render")
            path.mkdir()
            (path / "results.xml").write_text(
                '<testsuite><testcase classname="ScreenRenderTest" name="geometry"/>'
                '<testcase classname="ScreenRenderTest" name="age"><skipped/></testcase></testsuite>')
            counts, failures = qa.unit_results(pathlib.Path(folder))
            self.assertEqual(2, counts["ui-render"]["tests"])
            self.assertEqual(1, counts["ui-render"]["skipped"])
            self.assertTrue(any("SKIPPED" in x for x in failures))
            self.assertTrue(any("no JUnit XML" in x for x in failures))

    def test_binary_mismatch_is_not_certified(self):
        args = inputs()
        args[12] = False
        report, ok = qa.summarize(*args)
        self.assertFalse(ok)
        self.assertIn("Independently built E2E APK correspondence | **FAIL**", report)

    def test_missing_or_failed_traveller_row_cannot_pass(self):
        args = inputs()
        args[2] = [j for j in args[2] if j["name"] != "traveller / published APK / offline / refresh"]
        report, ok = qa.summarize(*args)
        self.assertFalse(ok)
        self.assertIn("End-user acceptance on exact published APK | **FAIL**", report)
        self.assertIn("offline/refresh: job absent", report)
        args = inputs()
        args[2][-1]["conclusion"] = "failure"
        report, ok = qa.summarize(*args)
        self.assertFalse(ok)
        self.assertIn("End-user acceptance on exact published APK | **FAIL**", report)


if __name__ == "__main__":
    unittest.main()
