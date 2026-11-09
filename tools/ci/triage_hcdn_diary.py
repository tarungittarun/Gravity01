#!/usr/bin/env python3
"""Offline owner-diary triage. A real request ID goes ONLY into a private local draft.

No website request, public CI comment, artifact, raw-id stdout or automated email.
The absence of a header in WebView cannot identify the responding CDN/origin layer.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path
import os
import re

ROOT = Path(__file__).resolve().parents[2]
LINE = re.compile(
    r"^.*?\[SITE_REFUSAL_PRIVATE\] HTTP 403; x-hcdn-request-id=([^;]*); "
    r"atUtc=([^;]*); url=(\S+)\s*$", re.MULTILINE)
URL = re.compile(r"https://whereismytrain\.org\.in/\?trainNo=[0-9]{5}\Z")
ID = re.compile(r"[A-Za-z0-9._~:+/-]{1,200}(?: [A-Za-z0-9._~:+/-]{1,80})?\Z")


@dataclass(frozen=True)
class Finding:
    state: str
    request_id: str | None = None
    at_utc: str | None = None
    url: str | None = None


def classify(diary: str) -> Finding:
    matches = list(LINE.finditer(diary))
    if not matches:
        return Finding("NO_RECEIPT")
    request_id, at_utc, url = matches[-1].groups()  # latest opt-in 403 receipt only
    if not URL.fullmatch(url):
        return Finding("MALFORMED_RECEIPT")
    try:
        datetime.strptime(at_utc, "%Y-%m-%dT%H:%M:%S.%fZ")
    except ValueError:
        return Finding("MALFORMED_RECEIPT")
    if request_id == "<absent>":
        return Finding("ABSENT", at_utc=at_utc, url=url)
    if request_id == "<invalid-or-oversized>":
        return Finding("INVALID", at_utc=at_utc, url=url)
    if not ID.fullmatch(request_id):
        return Finding("MALFORMED_RECEIPT")
    return Finding("VALID", request_id, at_utc, url)


def draft(f: Finding) -> str:
    if f.state == "NO_RECEIPT":
        return ("# No private WebView 403 receipt found\n\n"
                "This does NOT mean the request was accepted or that the CDN omitted an ID. "
                "Check the R176 APK, enable Settings → Diagnostic log before the request, "
                "and wait out any existing 15-minute refusal pause. Do not repeat blocked GETs.\n")
    if f.state == "MALFORMED_RECEIPT":
        return ("# Unusable diagnostic receipt\n\n"
                "The provided diary had a malformed receipt. No operator request ID or site layer "
                "can be inferred. Keep the original diary private for troubleshooting.\n")
    when = datetime.strptime(f.at_utc or "", "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
    ist = when.astimezone(timezone(timedelta(hours=5, minutes=30))).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3]
    header = (f"x-hcdn-request-id: {f.request_id}\n" if f.state == "VALID" else
              "x-hcdn-request-id: not exposed in the WebView HTTP-error callback "
              f"({f.state.lower()})\n")
    correlation = ("Please correlate the **exact original request ID** below with your CDN and "
                   "origin logs to identify whether a WAF/bot rule, rate filter or origin policy "
                   "issued the response. We do not know which one fired.\n" if f.state == "VALID" else
                   "The missing/unusable header does **not** establish that the origin, rather "
                   "than Hostinger's CDN or an edge gateway, generated the 403. Could you check "
                   "both CDN and origin logs using the UTC time and URL below?\n")
    return ("# PRIVATE operator email draft — DO NOT post in a public issue\n\n"
            "To: [verified website operator / Hostinger support via the operator]\n"
            "Subject: Private request to diagnose one refused whereismytrain.org.in GET\n\n"
            "Hello,\n\n"
            "We maintain an independent Android train-status viewer. Its selected source is your "
            "whereismytrain.org.in website, not Google's Where Is My Train phone app. "
            "A single main-document navigation in the native Android WebView received HTTP 403; "
            "no train-status page or station rows were accepted. We did not retry the refusal.\n\n"
            "The WebView has JavaScript, DOM Storage and the legacy database setting enabled, "
            "accepts first-party cookies, and does not spoof Chrome or send copied browser cookies. "
            "Third-party cookies remain disabled. These are standard client settings, **not** a "
            "claim that we meet your security rules or have programmatic-use permission.\n\n"
            "Observed request (from an opt-in, on-device diagnostic diary):\n"
            f"- UTC callback time: {f.at_utc}\n"
            f"- IST callback time: {ist} IST\n"
            f"- Exact requested URL: {f.url}\n"
            "- Method: GET; main-frame HTTP status: 403\n"
            f"- {header}\n"
            f"{correlation}\n"
            "Would you please confirm whether automated third-party access is permitted? "
            "If so, could you provide a supported endpoint or a narrowly scoped, approved "
            "client policy, maximum polling rate, authentication and any usage terms? "
            "We will keep your source labelled distinctly, honor refusals and will not "
            "automate browser-verification challenges.\n\n"
            "Please reply privately. No response body, session cookie, challenge token or "
            "other customer data is included here.\n")


def save_private(output: Path, text: str) -> None:
    private = (ROOT / "private").resolve()
    if not output.resolve().is_relative_to(private):
        raise ValueError("operator draft must be saved under the repository's ignored private/ directory")
    private.mkdir(mode=0o700, exist_ok=True)
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(text)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("diary", type=Path)
    parser.add_argument("--output", required=True, type=Path,
                        help="new file under ignored private/; never an Actions artifact")
    args = parser.parse_args()
    finding = classify(args.diary.read_text(encoding="utf-8", errors="replace"))
    save_private(args.output, draft(finding))
    print(f"state={finding.state}; private draft saved to {args.output} (ID not printed)")


if __name__ == "__main__":
    main()
