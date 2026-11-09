#!/usr/bin/env python3
"""Bounded, read-only CI experiment. Never label a socket as verified stop data."""
import datetime as dt
import json
import urllib.error
import urllib.parse
import urllib.request
import time


def inspect(url):
    t = time.monotonic()
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Android 13; Mobile) OpenTrackIndia/route-trial", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            body = response.read(160_000)
            status = response.status
            mime = response.headers.get("Content-Type", "?")
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, OSError) as e:
        return f"{type(e).__name__}: {str(e)[:100]} ({time.monotonic()-t:.1f}s)"
    try:
        doc = json.loads(body)
        if isinstance(doc, list):
            first = doc[0] if doc else {}
            codes = [row.get('SC') or row.get('stationCode') or row.get('STN') for row in doc if isinstance(row, dict)]
            shape = f"array {len(doc)} keys={list(first)[:15]} codes={codes[:8]}...{codes[-4:]}"
        else:
            shape = f"object keys={list(doc)[:18]}"
    except (ValueError, TypeError):
        shape = 'non-JSON body-prefix=' + body[:95].decode('utf-8', 'replace').replace('\n', ' ')
    return f"HTTP {status} {len(body)}B {mime} in {time.monotonic()-t:.1f}s; {shape}"


def main():
    day = dt.date.today()  # CI UTC date; test URL uses specified origin run, not this clock.
    print(f"Route/source trials at {day.isoformat()} UTC (not proof of a phone response)")
    for train, origin in [('20652', '14 Oct 2026'), ('16227', '13 Oct 2026')]:
        date = urllib.parse.quote(origin)
        urls = {
            'railgeo': f'https://enquiry.indianrail.gov.in/ntes/RailGeo?action=getTrainSchedule&trainNo={train}&trainStartDate={date}',
            'legacy-ntes': f'https://enquiry.indianrail.gov.in/ntes/NTES?action=getTrainData&trainNo={train}',
            'railradar-legacy': f'https://api.railradar.in/v1/legacy/trains/{train}?journeyDate=2026-10-{14 if train=="20652" else 13}&dataType=full&dataProvider=NTES',
            'railengine': f'https://railengine.vercel.app/v1/train/{train}/status',
        }
        for name, url in urls.items():
            print(f'{train} {name}: {inspect(url)}', flush=True)


if __name__ == '__main__':
    main()
