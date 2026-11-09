#!/usr/bin/env python3
"""Self-test for check_refs.py's import-package rule.

The rule exists because of a real miss on 2026-10-12: PairSource.kt imported
`in`.opentrack.india.v2.Provenance AFTER the type had moved to .v2.contract, and the gate - which
matched the simple name - reported zero problems; CI's compiler caught it one push later. A gate that
can miss the exact class of defect it exists for gets its own test (4.4), and the test reproduces the
miss first (8.7: failing-first, named after the symptom).

Run: python3 tools/ci/test_check_refs.py  (add to the `checks` job next to the other tool tests)
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import textwrap
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
CHECK = os.path.join(HERE, "check_refs.py")

DECL = 'package `%s`\n\nclass %s(val marker: String = "x")\n'
USE_WITH_IMPORT = ('package other.pkg\n\nimport %s\n\n'
                   'fun use(): %s = %s("y")\n')


class ImportPackageRule(unittest.TestCase):
    def run_check(self, files: dict[str, str]) -> tuple[int, str]:
        with tempfile.TemporaryDirectory() as td:
            for rel, text in files.items():
                p = os.path.join(td, rel)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as fh:
                    fh.write(textwrap.dedent(text))
            r = subprocess.run([sys.executable, CHECK, td], capture_output=True, text=True)
            return r.returncode, r.stdout + r.stderr

    def test_the_real_miss_is_now_caught(self):
        # the exact shape that reached CI: type moved to .contract, import still says the old package
        rc, out = self.run_check({
            "src/contract/Provenance.kt": DECL % ("in.opentrack.india.v2.contract", "Provenance"),
            "src/source/PairSource.kt": USE_WITH_IMPORT % ("`in`.opentrack.india.v2.Provenance", "Provenance", "Provenance"),
        })
        self.assertEqual(1, rc, out)
        self.assertIn("is declared in package", out)

    def test_a_correct_import_passes(self):
        rc, out = self.run_check({
            "src/contract/Provenance.kt": DECL % ("in.opentrack.india.v2.contract", "Provenance"),
            "src/source/PairSource.kt": USE_WITH_IMPORT % ("`in`.opentrack.india.v2.contract.Provenance", "Provenance", "Provenance"),
        })
        self.assertEqual(0, rc, out)

    def test_an_import_of_something_we_never_declare_is_caught(self):
        rc, out = self.run_check({
            "src/source/A.kt": 'package `in`.opentrack.india.v2.source\n\nimport `in`.opentrack.india.v2.Ghost\n\nval g = Ghost()\n',
        })
        self.assertEqual(1, rc, out)
        self.assertIn("no source in this tree declares", out)

    def test_generated_names_and_foreign_imports_are_not_our_business(self):
        rc, out = self.run_check({
            "src/source/A.kt": 'package `in`.opentrack.india.v2.source\n\n'
                               'import android.view.View\n'
                               'import `in`.opentrack.india.v2.BuildConfig\n'
                               'import `in`.opentrack.india.v2.databinding.FooBinding\n'
                               'import `in`.opentrack.india.v2.Real\n\n'
                               'val v = View(null)\nval b = BuildConfig.DEBUG\nval r = Real()\n',
            "src/v2/Real.kt": DECL % ("in.opentrack.india.v2", "Real"),
        })
        self.assertEqual(0, rc, out)


if __name__ == "__main__":
    unittest.main(verbosity=2)
