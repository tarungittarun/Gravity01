#!/usr/bin/env python3
"""Tests for the print-only truths refresher.

The tool's one dangerous power would be writing fixtures/known_truths.json - the file the app's gate
trusts - so the first test here reads the tool's own AST and asserts it contains no write-mode open at
all. The rest assert the three verdicts behave: agreement proposes, contradiction flags without
applying, and a dead network proposes nothing (4.6: the test in which the thing it falls back from is
already dead).

Run: python3 tools/ci/test_known_truths_refresh.py  (add to the `checks` job with the other tool tests)
"""
from __future__ import annotations

import ast
import datetime
import importlib.util
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("known_truths_refresh", os.path.join(HERE, "known_truths_refresh.py"))
ktr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ktr)

TODAY = datetime.datetime(2026, 10, 12, 1, 0)

IDENT_16221 = "2026-10-4-14-57-35~^16221~TLGP-MYS KUVEMPU EXPRESS~Talguppa~TLGP~Mysore Jn~MYS~Talguppa"
IDENT_16226 = "2026-4-2-10-11-52~^16226~SMET-MYS UR EXP~Shivamogga Town~SMET~Mysore Jn~MYS~Shivamogga Town"
PAIR_BODY = ("2026-10-11-7-56-55~\n"
             "16226~SMET-MYS UR EXP~SMET~MYS~\n"
             "16228~TLGP MYS EXP~TLGP~MYS~\n")


class NoWritePower(unittest.TestCase):
    def test_the_tool_contains_no_write_mode_open(self):
        """Auto-editing the fixture the app trusts is the one failure this tool must be incapable of."""
        with open(os.path.join(HERE, "known_truths_refresh.py"), encoding="utf-8") as fh:
            src = fh.read()
        tree = ast.parse(src)
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and getattr(node.func, "id", "") == "open":
                modes = [a.value for a in node.args[1:] if isinstance(a, ast.Constant) and isinstance(a.value, str)]
                for kw in node.keywords:
                    if kw.arg == "mode" and isinstance(kw.value, ast.Constant):
                        modes.append(kw.value.value)
                for m in modes:
                    self.assertFalse(any(c in m for c in "wax+"),
                                     f"open() with mode {m!r} found - the refresher must be read-only")
        self.assertNotIn("os.remove", src)
        self.assertNotIn("shutil", src)
        self.assertNotIn("write(", src, "no fh.write either - printing is the only output")


class Verdicts(unittest.TestCase):
    def truths(self):
        return {"trains": [{"train_no": "16221", "name": "KUVEMPU EXPRESS", "from": "TLGP", "to": "MYS"},
                           {"train_no": "16226", "name": "SMET MYS EXP", "from": "SMET", "to": "MYS"}],
                "pair_lists": [{"from": "SMET", "to": "MYS", "must_include": ["16226", "16228"]}]}

    def test_agreement_is_reported_and_proposed_as_is(self):
        def get(q):
            return ("HTTP 200 639B", IDENT_16221 if "16221" in q else IDENT_16226)

        lines, prop = ktr.refresh(self.truths(), TODAY, get_identity=get,
                                  get_pair=lambda s, d, t: ("HTTP 200 100B", PAIR_BODY))
        self.assertTrue(any("train 16221: AGREE" in ln for ln in lines), lines)
        by_no = {t["train_no"]: t for t in prop["trains"]}
        self.assertEqual("AGREE", by_no["16221"]["status"])
        self.assertEqual("TLGP-MYS KUVEMPU EXPRESS", by_no["16221"]["name"],
                         "the proposal carries the live-confirmed wording, loosely matched to the fixture")

    def test_a_contradiction_is_flagged_with_both_values_and_applies_nothing(self):
        def get(q):
            # erail claims a different identity for 16221 than the owner-verified fixture
            return ("HTTP 200 639B", "2026-10-4-14-57-35~^16221~KAVERI EXPRESS~Chennai~MAS~Mysore~MYS~x")

        lines, prop = ktr.refresh(self.truths(), TODAY, get_identity=get,
                                  get_pair=lambda s, d, t: ("dead", ""))
        self.assertTrue(any("train 16221: CONTRADICTION" in ln for ln in lines), lines)
        self.assertTrue(any("owner-verified fixture says KUVEMPU EXPRESS" in ln for ln in lines), lines)
        entry = next(t for t in prop["trains"] if t["train_no"] == "16221")
        self.assertEqual("CONTRADICTION", entry["status"])
        self.assertEqual("KUVEMPU EXPRESS", entry["current"]["name"], "the fixture value must survive untouched")
        self.assertEqual("KAVERI EXPRESS", entry["live"]["name"], "the live claim is shown, not applied")

    def test_a_dead_network_is_unverifiable_and_deletes_nothing(self):
        dead = ("URLError: timed out", "")
        lines, prop = ktr.refresh(self.truths(), TODAY, get_identity=lambda q: dead,
                                  get_pair=lambda s, d, t: dead)
        self.assertEqual(2, sum(1 for ln in lines if "UNVERIFIABLE" in ln and "train" in ln), lines)
        for t in prop["trains"]:
            self.assertEqual("UNVERIFIABLE", t["status"])
            self.assertIn("current", t, "the fixture fact is carried through, never dropped")
        self.assertEqual("UNVERIFIABLE", prop["pair_lists"][0]["status"])
        self.assertNotIn("must_include", prop["pair_lists"][0], "an uncheckable pair proposes no list")

    def test_pair_additions_are_shown_not_asserted_and_missing_is_a_hypothesis(self):
        def get(q):
            return ("HTTP 200 639B", IDENT_16221 if "16221" in q else IDENT_16226)

        bigger = PAIR_BODY + "56209~NEW ONE~SMET~MYS~\n"
        lines, prop = ktr.refresh(self.truths(), TODAY, get_identity=get,
                                  get_pair=lambda s, d, t: ("HTTP 200 150B", bigger))
        self.assertTrue(any("seen but unclaimed: 56209" in ln for ln in lines), lines)
        self.assertTrue(any("missing today: none" in ln for ln in lines), lines)
        entry = prop["pair_lists"][0]
        self.assertIn("56209", entry["must_include"], "additions are proposed for review")
        self.assertEqual("SEEN", entry["status"])

    def test_absent_today_is_not_evidence_of_absence(self):
        def get(q):
            return ("HTTP 200 639B", IDENT_16221 if "16221" in q else IDENT_16226)

        lines, prop = ktr.refresh(self.truths(), TODAY, get_identity=get,
                                  get_pair=lambda s, d, t: ("HTTP 200 40B", "16226~X~SMET~MYS~\n"))
        self.assertTrue(any("hypothesis, not an assertion" in ln for ln in lines), lines)
        self.assertTrue(any("16228" in ln and "does not list" in ln for ln in lines), lines)

    def test_the_proposal_carries_the_merge_rule_from_the_probe(self):
        _, prop = ktr.refresh(self.truths(), TODAY, get_identity=lambda q: ("dead", ""),
                              get_pair=lambda s, d, t: ("dead", ""))
        self.assertEqual(ktr.PROPOSAL_RULE, prop["_proposal_rule"])


class Main(unittest.TestCase):
    def test_main_prints_and_returns_0_without_writing(self):
        with tempfile.TemporaryDirectory() as td:
            path = os.path.join(td, "truths.json")
            with open(path, "w", encoding="utf-8") as fh:
                json.dump({"trains": [], "pair_lists": []}, fh)
            with open(path, encoding="utf-8") as fh:
                before = fh.read()
            rc = ktr.main(["--truths", path])
            self.assertEqual(0, rc)
            with open(path, encoding="utf-8") as fh:
                self.assertEqual(before, fh.read(), "print-only means print-only")

    def test_main_reports_an_unreadable_fixture_and_returns_1(self):
        rc = ktr.main(["--truths", "/nonexistent/truths.json"])
        self.assertEqual(1, rc)


if __name__ == "__main__":
    unittest.main(verbosity=2)
