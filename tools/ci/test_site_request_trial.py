#!/usr/bin/env python3
"""Offline trial/contract tests; NEVER call the website from this test file."""
import gzip
import io
import unittest
import urllib.error
from email.message import Message

import site_request_trial as trial


class Response(io.BytesIO):
    def __init__(self, body, status=200, headers=None):
        super().__init__(body)
        self.status = status
        self.headers = Message()
        self.headers["Content-Type"] = "text/html; charset=utf-8"
        for key, value in (headers or {}).items():
            self.headers[key] = value


class FakeOpener:
    def __init__(self, answer):
        self.answer = answer
        self.calls = []

    def open(self, request, timeout):
        self.calls.append((request, timeout))
        if isinstance(self.answer, BaseException):
            raise self.answer
        return self.answer


class NoReadError(urllib.error.HTTPError):
    def read(self, *args):
        raise AssertionError("must not read a refused response or challenge")


def refusal(status):
    headers = Message()
    headers["Server"] = "hcdn"
    headers["x-hcdn-request-id"] = "private-cdn-correlation-id"
    headers["Retry-After"] = "900" if status == 429 else ""
    return NoReadError("https://whereismytrain.org.in/?trainNo=20689", status,
                       "blocked", headers, io.BytesIO(b"secret challenge"))


class TrialTest(unittest.TestCase):
    def test_app_contract_and_one_honest_first_hop_get(self):
        contract = trial.app_contract()
        opener = FakeOpener(Response(
            b"<h1>20689 Running Status</h1><!-- Station Details Card -->"))
        answer = trial.classify("20689", opener, contract)
        self.assertTrue(answer["pageAccepted"])
        self.assertEqual(answer["stationCards"], 1)
        self.assertEqual(answer["verdict"], "MATCHED_PAGE_STRUCTURE_ONLY_NOT_DATED_EVENTS")
        self.assertEqual(len(opener.calls), 1)
        request, timeout = opener.calls[0]
        self.assertEqual(request.full_url, "https://whereismytrain.org.in/?trainNo=20689")
        self.assertEqual(request.get_method(), "GET")
        self.assertEqual(dict((k.lower(), v) for k, v in request.header_items()),
                         {k.lower(): v for k, v in contract.headers.items()})
        self.assertEqual(timeout, 4.0)
        self.assertFalse(any(k.lower() in ("cookie", "referer", "authorization")
                             for k, _ in request.header_items()))

    def test_cdn_refusal_never_reads_body_or_leaks_id(self):
        for status in (403, 429):
            with self.subTest(status=status):
                opener = FakeOpener(refusal(status))
                answer = trial.classify("20689", opener)
                self.assertFalse(answer["pageAccepted"])
                self.assertEqual(answer["verdict"], "HTTP_REFUSED_NO_BODY_READ")
                self.assertTrue(answer["cdnServer"])
                self.assertTrue(answer["cdnRequestIdPresent"])
                self.assertEqual(len(opener.calls), 1)
                self.assertNotIn("private-cdn", str(answer))
                self.assertEqual(answer["retryAfterSeconds"], 900 if status == 429 else None)

    def test_hostile_retry_header_is_never_echoed_or_parsed_as_unbounded_int(self):
        blocked = refusal(429)
        blocked.headers.replace_header("Retry-After", "9" * 8_000)
        answer = trial.classify("20689", FakeOpener(blocked))
        self.assertEqual(answer["verdict"], "HTTP_REFUSED_NO_BODY_READ")
        self.assertIsNone(answer["retryAfterSeconds"])
        self.assertLess(len(str(answer)), 500)

    def test_200_challenge_wrong_train_generic_page_and_gzip_are_not_success(self):
        bodies = [
            (b"<h1>Checking your browser before accessing</h1>", "BROWSER_CHECK_NOT_TRAIN_DATA"),
            (b"<h1>16227</h1><!-- Station Details Card -->", "NO_MATCHED_TRAIN_STATION_CARDS"),
            (b"<h1>20689</h1> no station details", "NO_MATCHED_TRAIN_STATION_CARDS"),
        ]
        for body, expected in bodies:
            with self.subTest(expected=expected):
                self.assertEqual(trial.classify("20689", FakeOpener(Response(body)))["verdict"],
                                 expected)
        zipped = Response(gzip.compress(b"<h1>20689</h1><!-- Station Details Card -->"),
                          headers={"Content-Encoding": "gzip"})
        self.assertTrue(trial.classify("20689", FakeOpener(zipped))["pageAccepted"])
        not_html = Response(b"<h1>20689</h1><!-- Station Details Card -->")
        not_html.headers.replace_header("Content-Type", "application/json")
        self.assertEqual(trial.classify("20689", FakeOpener(not_html))["verdict"],
                         "UNEXPECTED_CONTENT_TYPE_NO_BODY_READ")

    def test_error_body_limit_redirect_and_input_guard(self):
        big = FakeOpener(Response(b"a" * (trial.LIMIT + 1)))
        self.assertEqual(trial.classify("20689", big)["verdict"], "TRANSPORT_OR_BODY_ERROR")
        moved = FakeOpener(refusal(302))
        self.assertEqual(trial.classify("20689", moved)["verdict"], "REDIRECT_NOT_FOLLOWED")
        self.assertIsNone(trial.NoRedirect().redirect_request(None, None, 302, "", {},
                                                               "https://elsewhere.example/"))
        for input_value in ("２０６８９", "20689&x=1", "2068", " 20689", "20689\n"):
            with self.subTest(input_value=input_value):
                never_open = FakeOpener(Response(b""))
                with self.assertRaises(trial.ContractError):
                    trial.classify(input_value, never_open)
                self.assertEqual(never_open.calls, [], "invalid train must not make a GET")

    def test_contract_drift_cannot_silently_turn_green(self):
        page = trial.PAGE.read_text()
        http = trial.HTTP.read_text()
        changes = [
            (page.replace("timeoutMillis = 4_000", "timeoutMillis = 8_000"), http),
            (page.replace('ORG -> mapOf("Accept" to "text/html", "Accept-Language" to "en-IN,en;q=0.8")',
                          'ORG -> mapOf("Accept" to "*/*")'), http),
            (page.replace('"Accept-Language" to "en-IN,en;q=0.8"',
                          '"Accept-Language" to "xx"'), http),
            (page.replace("allowCachedOnFailure = name != ORG",
                          "allowCachedOnFailure = true"), http),
            (page.replace("followRedirects = name != ORG",
                          "followRedirects = true"), http),
            (page.replace("retriesFor(name: String): Int = 0",
                          "retriesFor(name: String): Int = 2"), http),
            (page, http.replace('"OpenTrackIndia/1.0', '"Chrome/120')),
            (page, http.replace('!httpStatusResponse(last)', 'true')),
        ]
        for drifted_page, drifted_http in changes:
            with self.subTest(index=changes.index((drifted_page, drifted_http))):
                with self.assertRaises(trial.ContractError):
                    trial.app_contract(drifted_page, drifted_http)


if __name__ == "__main__":
    unittest.main()
