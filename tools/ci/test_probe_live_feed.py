#!/usr/bin/env python3
"""Tests for the live-feed probe itself.

Why a diagnostic needs tests: the probe's whole job is to report what is reachable, and its first version
reported a *clean* result while silently skipping the alternate source - because the erail call sat below the
primary's `if doc is None: continue`. A fallback that only fires when the primary works is not a fallback, and
nothing in the pipeline could see it. That is the same defect the app kept hitting on the device, so it gets a
regression test rather than a comment.

Run: python3 tools/ci/test_probe_live_feed.py  (also run by the `checks` job)
"""
from __future__ import annotations

import datetime
import importlib.util
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("probe_live_feed", os.path.join(HERE, "probe_live_feed.py"))
plf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(plf)

TODAY = datetime.datetime(2026, 10, 12, 0, 30)
DEAD = ("URLError: timed out", None)


class ProbeFallback(unittest.TestCase):
    def test_alternate_is_asked_when_the_primary_dies(self):
        asked = []

        def alt(a, b, c):
            asked.append((a, b, c))
            return "HTTP 200 900B", ("16228~TALGUPPA MYSURU EXP~SMET~22:10~MYS~07:00~2026-10-11-7-56-55~\n"
                                     "16226~SOME OTHER~SMET~11:25~MYS~17:05~x~\n")

        lines, entry, e_body = plf.probe_pair("SMET", "MYS", ["16228"], "12-Oct-2026", TODAY,
                                              fetch=lambda plain: DEAD, alt=alt)
        self.assertEqual([(  "SMET", "MYS", "12-Oct-2026")], asked, "alternate must be asked even when primary failed")
        self.assertTrue(any("erail.in (alternate)" in ln for ln in lines), lines)
        self.assertTrue(any("primary gave nothing usable" in ln for ln in lines),
                        "the report must say the pair rests on one witness, not that it succeeded")
        self.assertIsNotNone(entry, "an alternate-only answer still produces a proposed fact")
        self.assertIn("16228", entry["must_include"])
        self.assertIn("16226", entry["must_include"])
        self.assertIn("16228~TALGUPPA MYSURU EXP", e_body,
                      "the raw body comes home: a parser may only be written against a capture (4.5)")

    def test_no_entry_and_no_lie_when_both_sources_are_dead(self):
        lines, entry, e_body = plf.probe_pair("SMET", "MYS", ["16228"], "12-Oct-2026", TODAY,
                                              fetch=lambda plain: DEAD,
                                              alt=lambda a, b, c: ("URLError: timed out", ""))
        self.assertIsNone(entry, "unreachable must never become an empty-but-confident fact list")
        self.assertTrue(any("erail.in (alternate)" in ln for ln in lines), "the block must still be reported")
        self.assertEqual("", e_body, "no body means no capture, and the capture comment must not exist")

    def test_a_fixture_claim_contradicted_by_both_sources_is_called_out(self):
        alt_body = "16226~X~SMET~11:25~MYS~17:05~2026-10-11-7-56-55~\n"
        lines, entry, e_body = plf.probe_pair("SMET", "MYS", ["16228"], "12-Oct-2026", TODAY,
                                              fetch=lambda plain: ("ok", {"TRAINNO": ["16226"], "STN": ["MYS"]}),
                                              alt=lambda a, b, c: ("HTTP 200 60B", alt_body))
        self.assertTrue(any("erail does not list 16228 either" in ln for ln in lines), lines)
        self.assertEqual(alt_body, e_body)

    def test_the_capture_comment_carries_the_verbatim_body_for_the_parser(self):
        body = "16228~TLGP MYS EXP~TLGP~22:10~MYS~07:00~2026-10-11-7-56-55~\n" * 3
        md = plf.erail_capture_comment([("SMET", "MYS", "HTTP 200 150B", body)], "2026-10-12T00:43:30+05:30")
        self.assertIsNotNone(md)
        self.assertIn("### live-feed capture (erail, raw bodies)", md)
        self.assertIn("2026-10-11-7-56-55", md, "the feed stamp travels with the capture")
        self.assertIn("16228~TLGP MYS EXP~TLGP~22:10~MYS~07:00", md, "verbatim, not a re-serialization")
        self.assertIsNone(plf.erail_capture_comment([], "x"), "no bodies, no comment")
        self.assertIsNone(plf.erail_capture_comment([("SMET", "MYS", "URLError: timed out", "")], "x"),
                          "a dead fetch produces no capture either")

    def test_the_proposal_skeleton_carries_the_merge_rule(self):
        p = plf.new_proposal(TODAY, "CI probe test")
        self.assertEqual(plf.PROPOSAL_RULE, p["_proposal_rule"])
        self.assertIn("never delete an owner-verified fact", p["_proposal_rule"],
                      "the ac06f62 proposal said trains:[] - the rule now travels inside the proposal")


class ErailIdentityParser(unittest.TestCase):
    """One parser of the getTrains.aspx record shape, shared by probe and refresher (4.4).

    The well-formed cases are the REAL first lines captured by the ac06f62 probe comment (2026-10-12),
    not invented ones - the previous field-offset parser never worked on a single real payload, which is
    why four CI identity answers all ended in 'not recorded as a truth'.
    """

    def test_the_real_16221_record_parses(self):
        rec = plf.parse_identity_record(
            "2026-10-4-14-57-35~^16221~TLGP-MYS KUVEMPU EXPRESS~Talguppa~TLGP~Mysore Jn~MYS~Talguppa")
        self.assertEqual({"name": "TLGP-MYS KUVEMPU EXPRESS", "from": "TLGP", "to": "MYS"}, rec)

    def test_the_real_12627_record_parses(self):
        rec = plf.parse_identity_record(
            "2026-10-9-20-38-40~^12627~KARNATAKA EXP~Ksr Bengaluru~SBC~New Delhi~NDLS~Ksr Bengaluru")
        self.assertEqual({"name": "KARNATAKA EXP", "from": "SBC", "to": "NDLS"}, rec)

    def test_the_identity_body_travels_to_the_capture_comment(self):
        # The TrainNo reply may be the full SCHEDULE; the parser (17.1: schedule from erail when NTES
        # is runner-blocked) may only be written against the captured text, so the comment carries it.
        body = "2026-10-4-14-57-35~^16221~TLGP-MYS KUVEMPU EXPRESS~Talguppa~TLGP~Mysore Jn~MYS~x\nrow2~stuff"
        md = plf.erail_capture_comment([], "2026-10-12T01:31:44+05:30", identities=[("16221", body)])
        self.assertIsNotNone(md)
        self.assertIn("### live-feed capture (erail, raw bodies)", md)
        self.assertIn("train 16221 identity/schedule body", md)
        self.assertIn("row2~stuff", md, "the whole body, not just the first record")
        self.assertIsNone(plf.erail_capture_comment([], "x", identities=[("16221", "")]),
                          "an empty body is no capture")

    def test_identity_lines_return_the_body_for_capture(self):
        live = {"trains": []}
        _lines, body = plf.erail_identity_lines(
            "16221", live,
            get=lambda q: ("HTTP 200 639B", "2026-10-4-14-57-35~^16221~TLGP-MYS KUVEMPU EXPRESS~Talguppa~TLGP~Mysore Jn~MYS~Talguppa"))
        self.assertIn("^16221", body, "the verbatim body comes home with the parsed lines")

    def test_a_number_never_parses_as_a_station(self):
        rec = plf.parse_identity_record("2026-10-4-15-36-31~^16228~TLGP MYS EXP~Talguppa~TLGP~Mysore Jn~MYS~Talguppa")
        self.assertEqual("TLGP", rec["from"])
        self.assertEqual("MYS", rec["to"])
        self.assertIsNone(plf.parse_identity_record("16228~NAME~TALGUPPA TOWN~16228"),
                          "the trailing 16228 is a number, not a station; with no codes this is None")
        self.assertIsNone(plf.parse_identity_record(""))
        self.assertIsNone(plf.parse_identity_record("no anchor~in~this~line~at~all"))

    def test_feed_stamp_is_read_from_the_payload(self):
        # The stamp is how a keyless text answer carries "how current am I"; without it we are back to
        # trusting a git commit date for the data's freshness, which is the mistake that started this.
        self.assertEqual(
            "2026-10-11-7-56-55",
            __import__("re").search(r"(\d{4}-\d{2}-\d{2}-\d{1,2}-\d{1,2}-\d{1,2})",
                                   "abc~2026-10-11-7-56-55~def").group(1))


class FlatShapes(unittest.TestCase):
    def test_parallel_arrays_are_reported_ragged_instead_of_transposed_lie(self):
        flat = plf.flat_table({"TRAINNO": ["16228", "16226"], "ATD": ["22:10"]})
        self.assertEqual({"TRAINNO": 2, "ATD": 1}, flat["lengths"])
        self.assertEqual([], flat["rows"], "ragged columns must not be silently zipped into rows")

    def test_row_objects_still_work(self):
        self.assertIsNone(plf.flat_table({"a": 1}))


class ErailWitness(unittest.TestCase):
    def test_identity_is_asked_even_when_the_primary_died(self):
        # The pair probe had this bug; this case exists so the identity probe cannot grow it back.
        asked = []
        live = {"trains": []}
        lines, _body = plf.erail_identity_lines("16228", live,
                                        get=lambda q: (asked.append(q) or
                                                       ("HTTP 200 400B", "16228~TALGUPPA MYSURU EXP~SMET~MYS~x")))
        self.assertTrue(asked and "TrainNo=16228" in asked[0])
        self.assertTrue(any("identity of 16228 via erail.in" in l for l in lines))
        self.assertEqual([{"train_no": "16228", "name": "TALGUPPA MYSURU EXP", "from": "SMET", "to": "MYS"}],
                         live["trains"], "4-letter codes must survive parsing intact")

    def test_a_miscounted_column_is_reported_not_recorded(self):
        live = {"trains": []}
        lines, _body = plf.erail_identity_lines("16228", live,
                                        get=lambda q: ("HTTP 200 60B", "16228~NAME~TALGUPPA TOWN~16228"))
        self.assertEqual([], live["trains"], "a half-parsed identity must not become a fact")
        self.assertTrue(any("not recorded as a truth" in l for l in lines))

    def test_date_forms_cover_both_styles_the_site_accepts(self):
        qs = plf.erail_candidates("SMET", "MYS", "12-Oct-2026")
        self.assertIn("Date_1=12-10-2026", qs[0], "the DD-MM-YYYY candidate must be numeric")
        self.assertIn("Date_1=12-Oct-2026", qs[1])
        self.assertEqual(4, len(qs), "the 404 control stays in the ladder so the report proves the path")


class ErailRouteCapture(unittest.TestCase):
    """The route endpoint capture: the per-stop schedule question's first real body (T20.2's sequel).

    The function parses NOTHING on purpose - the reply's shape is unknown until a capture lands - so
    these tests pin the contract that matters: right endpoint, honest verdict, raw body carried, and
    a dead endpoint is a fact in a line, never an exception and never a half-truth.
    """

    def test_the_route_ladder_mirrors_the_proven_param_style_first(self):
        asked = []

        def fake(q, timeout=20, base=plf.ERAIL_BASE):
            asked.append((q, base))
            return ("HTTP 200 900B", "16221~TLGP~MYS~first stop row~second stop row")

        lines, caps = plf.erail_route_lines("16221", get=fake)
        self.assertEqual(3, len(asked), "each candidate is asked exactly once")
        self.assertEqual("TrainNo=16221&DataSource=1&Language=0", asked[0][0],
                         "first candidate mirrors the identity call that demonstrably works")
        self.assertEqual("https://erail.in/rail/getRoute.aspx", asked[0][1],
                         "the route question goes to getRoute.aspx, not getTrains.aspx")
        self.assertTrue(any("route of 16221" in l and "HTTP 200" in l for l in lines))
        self.assertTrue(any("first:" in l for l in lines), "a trimmed first-line hint travels")
        self.assertEqual([("16221", "https://erail.in/rail/getRoute.aspx",
                           "16221~TLGP~MYS~first stop row~second stop row")] * 3,
                         caps, "every answering candidate is captured with its URL")

    def test_a_dead_route_ladder_is_a_fact_not_a_crash(self):
        lines, caps = plf.erail_route_lines(
            "16221", get=lambda q, timeout=20, base=plf.ERAIL_BASE: ("HTTP 404", ""))
        self.assertEqual(3, len([l for l in lines if "HTTP 404" in l]))
        self.assertEqual([], caps, "no body means no capture, and the comment says so by absence")

    def test_the_capture_comment_carries_route_bodies_with_url_but_never_empty_ones(self):
        md = plf.erail_capture_comment([], "2026-10-12T04:00:00+05:30",
                                       routes=[("16221", "https://erail.in/rail/getRoute.aspx", "A~B~C"),
                                               ("16228", "https://erail.in/rail/getRoute.aspx", "")])
        self.assertIn("### live-feed capture (erail, raw bodies)", md)
        self.assertIn("train 16221 route body (https://erail.in/rail/getRoute.aspx) · 5B · lines: 1", md)
        self.assertIn("A~B~C", md)
        self.assertNotIn("train 16228 route body", md, "an empty route body is not a capture")

    def test_the_capture_comment_truncates_a_route_body_at_6000_and_says_so(self):
        md = plf.erail_capture_comment([], "2026-10-12T04:00:00+05:30",
                                       routes=[("12627", "https://erail.in/rail/getRoute.aspx", "x" * 7000)])
        self.assertIn("x" * 6000, md)
        self.assertNotIn("x" * 6001, md)
        self.assertIn("truncated at 6000 of 7000 bytes", md)


class ErailLiveStatusProbe(unittest.TestCase):
    """The elephant asked of erail, from CI: live arrival/departure without the phone.

    These tests pin the probe's honesty, not a reply shape nobody has captured yet: every candidate
    is asked exactly once, its verdict is a line, bodies are captured verbatim with their URL, and a
    dead ladder is facts-in-lines - never an exception, never a half-parsed claim (4.5).
    """

    def test_both_candidates_are_asked_and_bodies_carry_their_url(self):
        asked = []

        def fake(q, timeout=20, base=plf.ERAIL_BASE, headers=None):
            asked.append((base + q.split("?")[0], headers is not None))
            return ("HTTP 200 2500B", "16221~actual~rows~here")

        lines, caps = plf.erail_live_status_lines("16221", "12-10-2026", get=fake)
        self.assertEqual(
            [("https://api.erail.in/runningstatus/", False),
             ("https://api.erail.in/route/", False),
             ("https://erail.in/rail/getRunTrain.aspx", False),
             ("https://erail.in/rail/getRunTrain.aspx", True),
             ("https://erail.in/rail/getRunTrain.aspx", True),
             ("https://erail.in/running-status/16221/", False),
             ("https://api.railradar.in/v1/trains/16221/live", True)],
            asked, "api.erail.in keyless first (their own advertised door), then the rest",
        )
        self.assertTrue(any("cookie-session" in l for l in lines), "the cookie-session ask is reported")
        self.assertTrue(any("browser-UA+XHR" in l and "HTTP 200" in l for l in lines))
        self.assertTrue(any("railradar-no-key" in l and "HTTP 200" in l for l in lines))
        self.assertEqual(7, len(caps), "the faked transports answer 7; the session ask is exercised in its own test")
        self.assertEqual("16221", caps[0][0])
        self.assertTrue(caps[0][1].startswith("https://api.erail.in/"), "the API door is asked first")
        self.assertEqual("16221~actual~rows~here", caps[0][2])

    def test_a_dead_ladder_is_lines_and_no_bodies(self):
        lines, caps = plf.erail_live_status_lines(
            "16221", "12-10-2026",
            get=lambda q, timeout=20, base=plf.ERAIL_BASE, headers=None: ("HTTP 404", ""),
        )
        self.assertEqual(7, len([l for l in lines if "HTTP 404" in l]))
        self.assertEqual([], caps, "nothing came back, so nothing is captured")

    def test_the_cookie_session_candidate_uses_the_session_fetcher(self):
        calls = []

        def fake_session(q, timeout=20, base=plf.ERAIL_BASE, headers=None):
            calls.append((q, base))
            return ("HTTP 200 1200B cookies=3", "16221~actual~per~stop")

        lines, caps = plf.erail_live_status_lines(
            "16221", "12-10-2026", get=lambda q, timeout=20, base=plf.ERAIL_BASE, headers=None: ("HTTP 403", ""),
            session_get=fake_session)
        self.assertEqual(1, len(calls), "session fetcher used exactly once, for the session candidate")
        self.assertIn("getRunTrain.aspx", calls[0][0])
        self.assertTrue(any("cookie-session" in l and "HTTP 200" in l for l in lines))
        self.assertIn("16221~actual~per~stop", caps[-1][2])

    def test_the_capture_comment_carries_live_status_bodies_with_url_and_truncates(self):
        md = plf.erail_capture_comment([], "2026-10-12T04:00:00+05:30",
                                       live_status=[("16221", "https://erail.in/rail/getRunTrain.aspx",
                                                     "y" * 6500), ("16228", "u", "")])
        self.assertIn("train 16221 live-status body (https://erail.in/rail/getRunTrain.aspx)", md)
        self.assertIn("y" * 6000, md)
        self.assertNotIn("y" * 6001, md)
        self.assertIn("truncated at 6000 of 6500 bytes", md)
        self.assertNotIn("train 16228 live-status body", md, "empty bodies are not captures")


class ErailSiteScriptCapture(unittest.TestCase):
    """erail's own app scripts, asked from CI: their code names the hub (the owner's phone observed
    the URLs; nothing here is invented). The tests pin the filter's honesty - windows around real
    keywords, verbatim, and a dead asset is a fact, never a crash or a half-parse."""

    def test_homepage_in_full_and_js_through_the_session_opener(self):
        home = "<html>" + ("x" * 500) + "$.connection.hub.url='/signalr';live"
        js = "window.SignalR={hub:'/signalr/negotiate?clientProtocol=1.5'};getRunTrain.aspx"
        calls = []

        def fake_get(q, timeout=20, base=plf.ERAIL_BASE, headers=None):
            calls.append(("plain", q))
            return ("HTTP 200 27127B", home)

        def fake_session(q, timeout=20, base=plf.ERAIL_BASE, headers=None):
            calls.append(("session", q))
            return ("HTTP 200 900B cookies=2", js)

        lines, caps = plf.erail_site_script_lines(get=fake_get, session_get=fake_session)
        self.assertEqual(3, len(caps))
        self.assertEqual("erail-homepage.html", caps[0][0])
        self.assertIn("home" if False else "eRailMobileHTMLEN.js", caps[1][0])
        self.assertIn("requester_min.js", caps[2][0])
        self.assertEqual(1, len([c for c in calls if c[0] == "plain"]), "homepage once, plain")
        self.assertEqual(2, len([c for c in calls if c[0] == "session"]), "JS assets via the cookie session")
        joined = " ".join(lines)
        self.assertIn("hub.url", joined, "the homepage's inline config lands in endpoint-hits")
        self.assertIn("negotiate", joined)

    def test_a_dead_asset_is_a_fact_not_a_capture(self):
        lines, caps = plf.erail_site_script_lines(
            get=lambda q, timeout=20, base=plf.ERAIL_BASE, headers=None: ("HTTP 404", ""),
            session_get=lambda q, timeout=20, base=plf.ERAIL_BASE, headers=None: ("HTTP 404", ""))
        self.assertEqual(3, len([l for l in lines if "HTTP 404" in l]))
        self.assertEqual([], caps)

    def test_the_capture_comment_carries_script_bodies(self):
        md = plf.erail_capture_comment([], "2026-10-12T06:00:00+05:30",
                                       scripts=[("requester_min.js", "https://erail.in/SignalR/js/requester_min.js",
                                                 "hub.url='/signalr'")])
        self.assertIn("site script requester_min.js (https://erail.in/SignalR/js/requester_min.js)", md)
        self.assertIn("hub.url", md)


if __name__ == "__main__":
    unittest.main(verbosity=2)
