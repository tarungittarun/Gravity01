#!/usr/bin/env python3
"""Bounded read-only trial. A RailYatri page may call FUTURE projections 'Departed at'.

Do not parse it into an app actual until the raw event schema differentiates observations
from estimates and the page proves the selected train/run. Prints only tiny HTML contexts.
"""
import html
from html.parser import HTMLParser
import json
import re
import time
import urllib.error
import urllib.request


class Visible(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
        self.skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'):
            self.skip += 1

    def handle_endtag(self, tag):
        if tag in ('script', 'style'):
            self.skip = max(0, self.skip - 1)

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)


def probe(number, query=''):
    url = f'https://www.railyatri.in/live-train-status/{number}{query}'
    req = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml',
    })
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=7) as r:
            raw = r.read(350_000).decode('utf-8', 'replace')
            print(f'{number} {query or "default"}: HTTP {r.status}, {len(raw)} chars in {time.monotonic()-start:.1f}s', flush=True)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        print(f'{number} {query or "default"}: {type(exc).__name__}: {str(exc)[:120]} in {time.monotonic()-start:.1f}s', flush=True)
        return
    p = Visible()
    p.feed(raw)
    text = re.sub(r'\s+', ' ', html.unescape(' '.join(p.parts)))
    for phrase in ('Start Date', 'Last Reported', 'As of', 'MALLESWARAM', 'YESVANTPUR', 'KSR BENGALURU', 'KADUR'):
        m = re.search(re.escape(phrase), text, re.I)
        if m:
            print(f'  visible around {phrase}: {text[max(0,m.start()-75):m.start()+490]}', flush=True)
    for phrase in ('actualArrival', 'actualDeparture', 'actual_arrival', 'arrival_status', 'estimatedArrival',
                   'lastReported', 'last_reported', 'statusData', 'trainStatus', 'isActual', 'eventType'):
        hits = list(re.finditer(re.escape(phrase), raw, re.I))
        if hits:
            print(f'  raw {phrase} {len(hits)} hits: ' + ' | '.join(
                re.sub(r'\s+', ' ', raw[max(0,m.start()-100):m.start()+220]) for m in hits[:2])[:750], flush=True)
    for code in ('YPR', 'MWM', 'SBC'):
        # Prefer a row near the status table instead of a JavaScript autocomplete item.
        pattern = rf'<tr[^>]*>[^<]*?(?:<[^>]*>[^<]*){{0,18}}?\b{code}\b[\s\S]*?</tr>'
        m = re.search(pattern, raw, re.I)
        if m:
            snippet = re.sub(r'\s+', ' ', m.group(0))[:1200]
            print(f'  {code} tr sample: {snippet}', flush=True)
    for m in list(re.finditer(r'"data_from"\s*:\s*"[^"]+"', raw))[:5]:
        print(f'  provenance: {raw[max(0,m.start()-120):m.end()+100]}', flush=True)
    data = re.search(r'<script[^>]+id="__NEXT_DATA__"[^>]*>([\s\S]*?)</script>', raw, re.I)
    if data:
        try:
            doc = json.loads(data.group(1))
            print('  NEXT_DATA root=' + str(list(doc)[:12]), flush=True)
            hits = []
            allow = ('data_from', 'station_code', 'actual_arrival_time', 'actual_departure_time',
                     'actual_arrival', 'actual_departure', 'estimated_arrival_time', 'estimated_departure_time',
                     'status', 'last_reported_station', 'current_station', 'station_name', 'sta', 'std',
                     'eta', 'etd', 'arrival_time', 'departure_time', 'reached', 'train_start_date')
            def walk(node, path='root', depth=0):
                if depth > 8 or len(hits) >= 12:
                    return
                if isinstance(node, dict):
                    selected = {k: str(v)[:80] for k,v in node.items()
                                if k.lower() in allow and not isinstance(v, (dict, list))}
                    if selected and ('data_from' in selected or 'station_code' in selected or
                                     'actual_arrival_time' in selected or 'last_reported_station' in selected):
                        hits.append((path, selected))
                    for k,v in node.items():
                        walk(v, path + '.' + k, depth + 1)
                elif isinstance(node, list):
                    for i, value in enumerate(node[:25]):
                        walk(value, path + f'[{i}]', depth + 1)
            walk(doc)
            for path, fields in hits:
                print(f'  structured {path}: {fields}', flush=True)
        except (ValueError, TypeError) as exc:
            print(f'  NEXT_DATA parse error: {exc}', flush=True)
    else:
        print('  NEXT_DATA not found', flush=True)
    print(f'  raw prefix={raw[:100]!r}', flush=True)


def api_probe(number, day=0):
    url = (f'https://livestatus.railyatri.in/api/v3/train_eta_data/{number}/{day}.json'
           f'?start_day={day}&user_id=-1&device_type_id=4&change_name=seo_train_name'
           '&lat=&lng=&claim_on_train=false')
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 Chrome/124 Safari/537.36',
                                               'Accept': 'application/json'})
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=7) as r:
            raw = r.read(260_000).decode('utf-8', 'replace')
            doc = json.loads(raw)
            if not isinstance(doc, dict):
                print(f'  {number}/{day} API: non-object {len(raw)}B', flush=True)
                return
            keys = ('success','train_number','train_start_date','source','destination','data_from',
                    'status_as_of','update_time','status','current_station_code','actual_arrival_time')
            summary = {k:doc.get(k) for k in keys if k in doc}
            print(f'  {number}/{day} API: HTTP {r.status} {len(raw)}B {time.monotonic()-start:.1f}s ' +
                  f'{summary} rootKeys={list(doc)[:32]}', flush=True)
            for group in ('previous_stations', 'upcoming_stations'):
                rows = doc.get(group) or []
                print(f'    {group} count={len(rows)}', flush=True)
                for row in rows[-3:] if group == 'previous_stations' else rows[:2]:
                    if isinstance(row, dict):
                        print('    ' + str({k:row.get(k) for k in ('station_code','sta','std','eta','etd',
                            'arrival_delay','departure_delay','status','data_from','actual_arrival_time',
                            'actual_departure_time') if k in row}), flush=True)
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
        print(f'  {number}/{day} API: {type(exc).__name__}: {str(exc)[:120]} in {time.monotonic()-start:.1f}s', flush=True)


if __name__ == '__main__':
    print('RailYatri trial. A future row can say "Departed at": it is NOT an observed event on that basis.', flush=True)
    # Same run as the owner's 03:10 16227 diary, not just unrelated fast pages.
    probe('16227', '?start_day=1&start_date=20261014')
    probe('20652')
    probe('20689')
    probe('20652', '?start_day=1&start_date=20261013')
    for number in ('20652', '20689', '16556'):
        api_probe(number)
    api_probe('20652', 1)
    api_probe('16227', 1)
