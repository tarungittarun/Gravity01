#!/usr/bin/env python3
"""A green device pass must never be mistaken for an untested row in the roll-up."""
from __future__ import annotations

import contextlib
import io
import os
import pathlib
import sys
import tempfile
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import e2e_rollup


class RollupVerdictTest(unittest.TestCase):
    def run_rollup(self, one_status: str = "station0=passed", full: bool = False):
        groups = ("station0", "station1", "station2", "train", *(f"track{i}" for i in range(4)))
        names = [f"api34-{g}" for g in groups] + ["api34-settings-ui", "api34-settings-net"]
        if full:
            names += [f"{device}-{g}" for device in ("min24", "density")
                      for g in (*groups, "settings-ui", "settings-net")]
            names += ["release"]
        with tempfile.TemporaryDirectory() as tmp:
            for name in names:
                directory = pathlib.Path(tmp, f"e2e-result-{name}")
                directory.mkdir()
                status = one_status if name == "api34-station0" else "station0=passed"
                (directory / "status.txt").write_text(
                    f"row={name}\nboot=ok 18s\nvariant_row=debug\n{status}\ncoverage=coverage: 49%\n",
                    encoding="utf-8",
                )
            before_argv = sys.argv
            before_full = os.environ.get("E2E_FULL")
            try:
                sys.argv = ["e2e_rollup.py", tmp]
                os.environ["E2E_FULL"] = "true" if full else "false"
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    result = e2e_rollup.main()
                return result, output.getvalue()
            finally:
                sys.argv = before_argv
                if before_full is None:
                    os.environ.pop("E2E_FULL", None)
                else:
                    os.environ["E2E_FULL"] = before_full

    def test_all_booted_passed_rows_are_green(self):
        result, output = self.run_rollup(full=True)
        self.assertEqual(0, result, output)
        self.assertIn("1/1 passes green", output)
        self.assertIn("31/31 rows booted", output)
        self.assertNotIn("failed: no instrumented pass", output)

    def test_failed_pass_fails_the_rollup(self):
        result, output = self.run_rollup("station0=failed (assertion)")
        self.assertEqual(1, result)
        self.assertIn("api34-station0: the flow suite failed", output)

    def test_booted_row_without_instrumentation_is_not_green(self):
        result, output = self.run_rollup("tests=not-run")
        self.assertEqual(1, result)
        self.assertIn("failed: no instrumented pass reported", output)


if __name__ == "__main__":
    unittest.main()
