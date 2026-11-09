#!/usr/bin/env python3
"""One ordinary Chrome navigation on a GitHub runner; capture metadata, not a session.

This is NOT Android Chrome, the Google phone app, permission to automate the site,
or a way to replay verification cookies. Never serialize the CDP event itself: it can
contain cookies, browser fingerprints and credentials. No page body is read.
"""
import json
import re
import shutil
import sys

HOST = "whereismytrain.org.in"


def target(number: str) -> str:
    if not re.fullmatch(r"[0-9]{5}", number):
        raise ValueError("expected one ASCII five-digit train number")
    return f"https://{HOST}/?trainNo={number}"


def result(events: list[dict], url: str, title: str) -> dict:
    """Only the first document navigation to the EXACT target, not subresources."""
    request_id = None
    method = None
    status = None
    server = None
    cdn_id_present = False
    for item in events:
        method_name, p = item.get("method"), item.get("params", {})
        if method_name == "Network.requestWillBeSent" and p.get("type") == "Document" and \
                p.get("request", {}).get("url") == url and request_id is None:
            request_id = p.get("requestId")
            method = p.get("request", {}).get("method")
        if request_id is None or p.get("requestId") != request_id:
            continue
        if method_name == "Network.responseReceived" and p.get("type") == "Document":
            response = p.get("response", {})
            status = response.get("status")
            headers = {k.lower(): v for k, v in response.get("headers", {}).items()}
            server = str(headers.get("server", ""))[:32]
            cdn_id_present = "x-hcdn-request-id" in headers
        elif method_name == "Network.responseReceivedExtraInfo":
            # Chrome can report the actual HTTP status here when the main response
            # event is withheld on an error. Never copy the raw headers.
            status = p.get("statusCode", status)
            headers = {k.lower(): v for k, v in p.get("headers", {}).items()}
            server = str(headers.get("server", server or ""))[:32]
            cdn_id_present = cdn_id_present or "x-hcdn-request-id" in headers
    title_is_challenge = "checking your browser" in title.lower()
    verdict = ("NO_DOCUMENT_CAPTURE" if request_id is None else
               "HTTP_REFUSED" if status in (403, 429) else
               "BROWSER_CHALLENGE" if title_is_challenge else
               "HTTP_ANSWER_NOT_A_VERIFIED_TRAIN_EVENT" if status == 200 else
               "OTHER_OR_UNKNOWN_RESPONSE")
    return {"client": "GitHub-runner Chrome, not owner's Android browser",
            "targetHost": HOST, "trainNumber": url.rsplit("=", 1)[-1],
            "navigationMethod": method, "httpStatus": status,
            "cdnServer": server.lower() == "hcdn" if server else False,
            "cdnRequestIdPresent": cdn_id_present,
            "browserCheckTitle": title_is_challenge, "verdict": verdict,
            "noCookiesHeadersBodiesOrRawRequestIdsSaved": True}


def self_test() -> None:
    assert target("20652") == f"https://{HOST}/?trainNo=20652"
    for bad in ("", "2065", "206520", "20652&x=1", "２０６５２"):
        try:
            target(bad)
        except ValueError:
            pass
        else:
            raise AssertionError("invalid train reached URL")
    u = target("20652")
    events = [
        {"method": "Network.requestWillBeSent", "params": {
            "requestId": "a", "type": "Document",
            "request": {"url": u, "method": "GET", "headers": {"Cookie": "PRIVATE"}},
        }},
        {"method": "Network.responseReceivedExtraInfo", "params": {
            "requestId": "a", "statusCode": 403,
            "headers": {"Server": "hcdn", "x-hcdn-request-id": "PRIVATE-ID", "Set-Cookie": "PRIVATE"},
        }},
    ]
    s = json.dumps(result(events, u, "Checking your browser"))
    assert "PRIVATE" not in s and "PRIVATE-ID" not in s and "Set-Cookie" not in s
    assert json.loads(s)["verdict"] == "HTTP_REFUSED"
    assert result(events, target("20689"), "")["verdict"] == "NO_DOCUMENT_CAPTURE"
    print("offline browser metadata/redaction tests passed")


def capture(number: str) -> dict:
    url = target(number)
    chrome = shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chromium-browser")
    if not chrome:
        return {"verdict": "CHROME_NOT_INSTALLED", "client": "GitHub runner; no website navigation"}
    from selenium import webdriver
    from selenium.webdriver.chrome.options import Options
    options = Options()
    options.binary_location = chrome
    options.page_load_strategy = "eager"
    options.add_argument("--headless=new")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--disable-background-networking")
    options.add_argument("--no-first-run")
    options.set_capability("goog:loggingPrefs", {"performance": "ALL"})
    driver = webdriver.Chrome(options=options)
    try:
        driver.set_page_load_timeout(12)
        try:
            driver.get(url)  # ONE navigation; no retries, no verification script or cookie reuse.
        except Exception:
            # A timeout is still diagnostic: CDP may have the initial HTTP response.
            pass
        events = []
        for entry in driver.get_log("performance"):
            try:
                events.append(json.loads(entry["message"])["message"])
            except (KeyError, ValueError, TypeError):
                continue
        return result(events, url, driver.title[:120])
    finally:
        driver.quit()


if __name__ == "__main__":
    if sys.argv[1:] == ["--self-test"]:
        self_test()
    else:
        try:
            answer = capture(sys.argv[1] if len(sys.argv) == 2 else "")
        except ValueError as exc:
            answer = {"verdict": "INVALID_TRAIN", "reason": str(exc)}
        except Exception as exc:
            answer = {"verdict": "BROWSER_SETUP_FAILED", "errorType": type(exc).__name__,
                      "client": "GitHub runner; no claim of a browser answer"}
        print(json.dumps(answer, sort_keys=True))
        if answer["verdict"] in ("INVALID_TRAIN", "BROWSER_SETUP_FAILED", "CHROME_NOT_INSTALLED"):
            sys.exit(2)
