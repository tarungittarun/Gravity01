import datetime as dt
import unittest
import probe_history as history


class HistoricalClaimTest(unittest.TestCase):
    def test_grid_is_twenty_requests_covering_every_day(self):
        self.assertEqual(20, len(history.TRAINS) * len(history.DAY_OFFSETS))
        self.assertEqual(list(range(-1, -11, -1)), list(history.DAY_OFFSETS))

    def test_static_or_wrong_run_never_promoted_to_actual(self):
        day = dt.date(2026, 10, 16)
        static = {'TrainNo': '16227', 'StartDate': '16-Oct-2026',
                  'STNS': [{'SC': 'SBC', 'STA': '16:00 16-Oct-2026', 'STD': '16:04 16-Oct-2026',
                            'ETA': '16:15 16-Oct-2026', 'ATA': '--'}]}
        self.assertEqual(0, history.evidence(static, '16227', day)[0])
        static['STNS'][0]['ATA'] = '16:12 16-Oct-2026'
        self.assertEqual(1, history.evidence(static, '16227', day)[0])
        self.assertEqual(0, history.evidence(static, '12627', day)[0])
        self.assertEqual(0, history.evidence(static, '16227', day - dt.timedelta(days=5))[0])


if __name__ == '__main__':
    unittest.main()
