#!/usr/bin/env python3
"""Read-only, bounded *same-run* trial of three other position candidates.

This is intentionally not an availability check: an HTML page is NOT an observed
position. Print run-date evidence and context around any explicit position claim;
never turn a 200 or a page row into a verified actual. No raw pages persisted.
Run alongside probe_railyatri_actuals.py and probe_wimt.py (five public lanes,
plus direct NTES proven separately by the user's on-device diary).
"""
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
import re
import time
import urllib.error
import urllib.request

TRAIN = '16227'
RUN_DAY = '14-Oct-2026'
URLS = {
    'runningstatus.in': f'https://runningstatus.in/status/{TRAIN}',
    'ConfirmTkt': f'https://www.confirmtkt.com/train-running-status/{TRAIN}',
    'eRail': f'https://erail.in/train-running-status/{TRAIN}',
}

class Text(HTMLParser):
    def __init__(self):
        super().__init__()
        self.words = []
        self.hidden = 0
    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'): self.hidden += 1
    def handle_endtag(self, tag):
        if tag in ('script', 'style'): self.hidden = max(0, self.hidden - 1)
    def handle_data(self, data):
        if not self.hidden: self.words.append(data)

def trial(item):
    name, url = item
    start = time.monotonic()
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Linux; Android 13) Chrome/120 Safari/537.36',
                                                'Accept': 'text/html'})
    try:
        with urllib.request.urlopen(req, timeout=7) as r:
            raw = r.read(200_000).decode('utf-8', 'replace')
            status = r.status
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return f'{name}: {type(exc).__name__}: {str(exc)[:100]} ({time.monotonic()-start:.1f}s); NOT verified'
    parser = Text()
    parser.feed(raw)
    text = re.sub(r'\s+', ' ', ' '.join(parser.words))
    # A calendar date printed on a page may be its scrape timestamp, NOT the train's origin run.
    dates = re.findall(r'\b\d{1,2}[-/ ](?:Oct|October|10)[-/ ](?:20)?26\b', text, re.I)[:5]
    matching = bool(re.search(r'\b14[-/ ](?:Oct|October|10)[-/ ](?:20)?26\b', text, re.I))
    positions = []
    for phrase in ('Last Reported', 'Current Station', 'Departed from', 'Arrived at', 'Arsikere', 'Birur'):
        m = re.search(re.escape(phrase), text, re.I)
        if m: positions.append(text[max(0,m.start()-35):m.start()+125])
    return (f'{name}: HTTP {status}, {len(raw)} chars, {time.monotonic()-start:.1f}s; '
            f'visible dates={dates}, matching date mentioned={matching}; '
            f'position-context={positions[:2]}; NOT verified without matching origin run, event time, '
            f'freshness and non-projected status')[:750]

if __name__ == '__main__':
    print(f'Five-lane investigation: {TRAIN} origin run {RUN_DAY}. The other two lanes are '
          'RailYatri and WIMT web; official NTES is the separate baseline.', flush=True)
    with ThreadPoolExecutor(max_workers=3) as pool:
        for result in pool.map(trial, URLS.items()): print(result, flush=True)
