#!/usr/bin/env python3
"""Fail closed when an acceptance @Test is omitted/duplicated by partitioning."""
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import acceptance_plan as plan


class PlanTest(unittest.TestCase):
    def test_every_device_method_has_exactly_one_partition(self):
        self.assertEqual(5, plan.check())
        self.assertEqual({"journey", "trust", "refresh"}, set(plan.GROUPS))

    def test_new_unassigned_method_rejects_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "Test.kt"
            source.write_text(plan.SOURCE.read_text() + "\n@Test fun newJourney() {}\n")
            with patch.object(plan, "SOURCE", source):
                with self.assertRaisesRegex(ValueError, "do not cover"):
                    plan.check()

    def test_duplicate_method_rejects_plan(self):
        with patch.dict(plan.GROUPS, {"refresh": plan.GROUPS["refresh"] + plan.GROUPS["trust"][:1]}):
            with self.assertRaisesRegex(ValueError, "do not cover"):
                plan.check()


if __name__ == "__main__":
    unittest.main()
