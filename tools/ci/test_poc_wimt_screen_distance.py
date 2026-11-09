"""Offline POC checks; no WIMT app, provider, OCR or network is exercised."""
import datetime as dt
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from poc_wimt_screen_distance import Rejected, parse_points, project

UTC = dt.timezone.utc
CAPTURE = dt.datetime(2026, 10, 19, 14, 56, 0, tzinfo=UTC)
NOW = CAPTURE + dt.timedelta(seconds=8)
# Synthetic test route from the R137 rendering fixture; NOT a current 12089 route claim.
ROUTE = parse_points("RRB:211,SPV:215,KRNH:220,TKE:235")


class ScreenDistancePocTest(unittest.TestCase):
    def observation(self, text="12089   1 km to SPV", previous="RRB", points=ROUTE,
                    captured=CAPTURE, now=NOW, train="12089"):
        return project(text, train, previous, points, captured, now)

    def test_proportional_position_only_on_immediately_adjacent_leg(self):
        result = self.observation()
        self.assertEqual("SPV", result.next)
        self.assertEqual(4.0, result.map_leg_km)
        self.assertEqual(.75, result.schematic_fraction)
        self.assertIn("unverified", result.source)
        self.assertNotIn("actual", result.source)
        self.assertEqual(0, self.observation("12089 4 km to SPV").schematic_fraction)
        self.assertEqual(1, self.observation("12089 0 km to SPV").schematic_fraction)
        self.assertEqual(.625, self.observation("12089 1.5 km to SPV").schematic_fraction)

    def test_user_example_nyh_is_rejected_on_unmatched_route(self):
        with self.assertRaisesRegex(Rejected, "not adjacent"):
            self.observation("12089 1 km to NYH")
        self.assertEqual(.75, self.observation("12089 1 km to NYH",
                         points=parse_points("RRB:211,NYH:215")).schematic_fraction)

    def test_rejects_wrong_train_and_stale_or_future_captures(self):
        for kwargs in [dict(text="16227 1 km to SPV"),
                       dict(captured=NOW - dt.timedelta(seconds=121)),
                       dict(captured=NOW + dt.timedelta(seconds=11))]:
            with self.subTest(kwargs=kwargs), self.assertRaises(Rejected):
                self.observation(**kwargs)

    def test_rejects_ambiguous_or_bad_screen_text(self):
        for text in ["12089", "12089 1 km to SPV 1 km to KRNH",
                     "12089 -1 km to SPV", "12089 5 km to SPV",
                     "12089 1 km to TKE", "12089 1 km to SPA"]:
            with self.subTest(text=text), self.assertRaises(Rejected):
                self.observation(text)

    def test_rejects_bad_map_and_naive_timestamps(self):
        for points in ["RRB:211,SPV:210", "RRB:211,SPV:211", "RRB:211,RRB:215",
                       "RRB:211,SPV:inf", "RRB:211,SPV:nan", "RRB:211"]:
            with self.subTest(points=points), self.assertRaises(Rejected):
                parse_points(points)
        with self.assertRaisesRegex(Rejected, "timezone"):
            self.observation(captured=CAPTURE.replace(tzinfo=None))


if __name__ == "__main__":
    unittest.main()
