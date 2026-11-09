"""E2E speed changes may split methods but must not silently omit a device/profile."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class OptInMatrixTest(unittest.TestCase):
    def test_full_matrix_and_audit_agree_on_all_four_tracking_shards(self):
        workflow = (ROOT / ".github/workflows/e2e.yml").read_text()
        import apk_audit
        rows = re.findall(r"^\s+- name: ((?:api34|min24|density)-[a-z0-9-]+)$", workflow, re.M)
        self.assertEqual(30, len(rows), "10 device groups per profile")
        self.assertEqual(30, len(set(rows)), "duplicate matrix row")
        self.assertEqual(set(rows) | {"release"}, apk_audit.EXPECTED_ROWS)
        action = (ROOT / ".github/actions/e2e-row/action.yml").read_text()
        self.assertIn('numShards 4 -e shardIndex $idx', action)
        for i in range(4):
            self.assertIn(f"track{i}", action)

    def test_emulator_jobs_are_opt_in_but_requested_sweep_is_full(self):
        workflow = (ROOT / ".github/workflows/e2e.yml").read_text()
        event_block = workflow.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual(["workflow_dispatch"],
                         re.findall(r"^  ([a-z_]+):", event_block, re.M),
                         "no automatic or alternate E2E triggers")
        self.assertIn("  plan:\n    if: github.event_name == 'workflow_dispatch'", workflow)
        self.assertNotIn("github.event.head_commit.message", workflow.split("  plan:\n", 1)[1])
        self.assertNotIn("'[e2e-request]'", workflow)
        self.assertIn("if: always() && needs.plan.result == 'success'", workflow)
        self.assertIn("if: always() && needs.plan.outputs.full == 'true'", workflow)


if __name__ == "__main__":
    unittest.main()
