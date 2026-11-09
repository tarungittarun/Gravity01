#!/usr/bin/env python3
"""Tests for the Actions-store hygiene tool.

The tool has two dangerous powers - deleting artifacts and cancelling runs - so its classification
rules are tested against synthetic listings with zero network (4.4): fresh things survive, stale
things go, completed runs are never cancelled, and the report says what it did.

Run: python3 tools/ci/test_artifact_hygiene.py  (runs in the `checks` job)
"""
from __future__ import annotations

import datetime
import importlib.util
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("artifact_hygiene", os.path.join(HERE, "artifact_hygiene.py"))
hyg = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hyg)

NOW = datetime.datetime(2026, 10, 12, 3, 0, tzinfo=datetime.timezone.utc)


def art(aid: int, hours_old: float, mb: float = 9.9, expired: bool = False) -> dict:
    created = NOW - datetime.timedelta(hours=hours_old)
    return {"id": aid, "created_at": created.isoformat().replace("+00:00", "Z"),
            "size_in_bytes": int(mb * 1_048_576), "expired": expired}


def run(rid: int, hours_old: float, status: str = "in_progress") -> dict:
    created = NOW - datetime.timedelta(hours=hours_old)
    return {"id": rid, "created_at": created.isoformat().replace("+00:00", "Z"), "status": status}


class Classification(unittest.TestCase):
    def test_fresh_artifacts_survive_stale_are_deleted_and_expired_ignored(self):
        arts = [art(1, 0.5), art(2, 5.9), art(3, 6.1), art(4, 90.0), art(5, 90.0, expired=True)]
        self.assertEqual([3, 4], hyg.stale_artifacts(arts, NOW),
                         "under 6h stays; over 6h goes; already-expired is not our business")

    def test_only_old_in_progress_runs_are_wedged(self):
        runs = [run(10, 0.5), run(11, 4.1), run(12, 9.0, "completed"), run(13, 9.0, "completed/success"),
                run(14, 1.0, "queued")]
        self.assertEqual([11], hyg.wedged_runs(runs, NOW),
                         "young runs keep flying; concluded runs are never touched; queued is not wedged")


class Orchestration(unittest.TestCase):
    def test_it_deletes_and_cancels_exactly_what_it_classified_and_says_so(self):
        arts = [art(1, 0.5, 9.9), art(2, 10.0, 6.3)]
        runs = [run(20, 5.0), run(21, 0.2)]
        deleted, cancelled = [], []

        def get(path: str) -> str:
            if "artifacts" in path:
                return json.dumps({"artifacts": arts})
            return json.dumps({"workflow_runs": runs})

        report = hyg.run(get_fn=get, delete_fn=lambda p: deleted.append(p),
                         cancel_fn=lambda p: cancelled.append(p), repo="o/r", pages=1, now=NOW)
        self.assertEqual(["repos/o/r/actions/artifacts/2"], deleted)
        self.assertEqual(["repos/o/r/actions/runs/20/cancel"], cancelled)
        self.assertIn("deleted 1 artifact(s) (6.3 MB) older than 6h", report)
        self.assertIn("cancelled 1 wedged run(s)", report)
        self.assertIn("releases are never artifacts", report, "the owner-facing promise is in the line")

    def test_pagination_stops_when_a_page_is_short(self):
        pages = []

        def get(path: str) -> str:
            pages.append(path)
            # endswith, not `in`: "per_page=100" contains "page=1" as a substring - the first version
            # of this fake returned a full page for every page number and the pagination loop never
            # stopped. The test fake had the substring bug; the loop is fine.
            if path.endswith("page=1"):
                return json.dumps({"artifacts": [art(1, 1.0)] * 100})
            return json.dumps({"artifacts": []})

        deleted = []
        report = hyg.run(get_fn=get, delete_fn=lambda p: deleted.append(p),
                         cancel_fn=lambda p: None, repo="o/r", now=NOW)
        self.assertEqual(3, len(pages),
                         "2 artifact pages (page 2 is short -> stop) + 1 runs listing")
        self.assertEqual("hygiene: deleted 0 artifact(s) (0.0 MB) older than 6h; "
                         "cancelled 0 wedged run(s) in_progress over 4h; kept the rest "
                         "(releases are never artifacts - untouched)", report)


if __name__ == "__main__":
    unittest.main(verbosity=2)
