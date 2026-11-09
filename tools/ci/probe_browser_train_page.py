#!/usr/bin/env python3
"""Read-only trial of a user-shared *independent website*, not Google's WIMT app.

A page that renders stop times can be useful while still lacking an origin run,
per-event update times, or a measured position. Print only bounded provenance and
station excerpts, never assume a green row or a train glyph means observed passage.
"""
from html.parser import HTMLParser
from html import unescape
from urllib.parse import urljoin, urlparse
import hashlib
import re
import time
import urllib.request

URL = 'https://whereismytrain.org.in/?trainNo=16227'
HEADERS = {'User-Agent': 'Mozilla/5.0 (Linux; Android 13) Chrome/120 Safari/537.36',
           'Accept': 'text/html,application/xhtml+xml'}


class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.visible = []
        self.scripts = []
        self.inline = []
        self.hidden = []

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'script':
            if a.get('src'): self.scripts.append(a['src'])
            self.hidden.append(tag)
        elif tag in ('style', 'noscript'):
            self.hidden.append(tag)

    def handle_endtag(self, tag):
        if self.hidden and self.hidden[-1] == tag: self.hidden.pop()

    def handle_data(self, value):
        if self.hidden:
            if self.hidden[-1] == 'script': self.inline.append(value)
        else:
            value = ' '.join(value.split())
            if value: self.visible.append(value)


def get(url, cap=300_000):
    begin = time.monotonic()
    request = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(request, timeout=8) as response:
        data = response.read(cap + 1)
        return response.status, response.headers.get('Date', ''), \
            data[:cap].decode('utf-8', 'replace'), round(time.monotonic() - begin, 2), len(data) > cap


def excerpt(words, term):
    for i, word in enumerate(words):
        if re.search(r'(?<![A-Z])' + re.escape(term) + r'(?![A-Z])', word, re.I):
            return ' | '.join(words[i:i+14])[:200]
    return ''


def once():
    status, http_date, body, elapsed, truncated = get(URL)
    parsed = Page()
    parsed.feed(body)
    text = ' '.join(parsed.visible)
    # Neither the page render time nor a 12-hour clock alone identifies the *origin run*.
    dates = re.findall(r'\b(?:\d{1,2}[-/ ](?:Oct|10)[-/ ](?:20)?26|20\d\d-\d\d-\d\d)\b',
                       text, re.I)[:4]
    # This digest excludes the usually advancing Last Updated response clock.
    stops = tuple(excerpt(parsed.visible, code) for code in ('RRB', 'TKE', 'BDVT', 'SME'))
    digest = hashlib.sha256(repr(stops).encode()).hexdigest()[:12]
    print(f'website HTTP {status} in {elapsed}s, {len(body)} chars truncated={truncated}; '
          f'HTTP Date={http_date!r}; visible origin dates={dates}; '
          f'lastUpdated={excerpt(parsed.visible, "Updated:")[:85]!r}; '
          f'URL trainNo=16227, no origin-run date in request; rowDigest={digest}', flush=True)
    for code, s in zip(('RRB', 'TKE', 'BDVT', 'SME'), stops):
        print(f'  {code}: {s[:180]!r}', flush=True)
    srcs = [urljoin(URL, s) for s in parsed.scripts]
    srcs = list(dict.fromkeys(u for u in srcs if urlparse(u).hostname == 'whereismytrain.org.in'))
    inline = '\n'.join(parsed.inline)
    patterns = r'(?:fetch\(|axios\.|/api/|trainNo|lastUpdated|currentStation|train-status|running-status)'
    for label, js in [('inline', inline)]:
        matches = list(re.finditer(patterns, js, re.I))[:8]
        print(f'  {label} script: {len(js)} chars; network/status contexts=' +
              repr([js[max(0,m.start()-75):m.end()+110].replace('\n', ' ')[:190] for m in matches]),
              flush=True)
    print(f'  same-origin script URLs={srcs[:6]}', flush=True)
    # Mirror the *public* card-field separation the app now uses: first rendered
    # clock versus grey timetable clock, plus the site's past/upcoming class.
    # Comparing these tuples with Web/Sch on the device detects a wrong field map
    # without claiming the site's own past styling proves a measured train fix.
    print('  bounded card-field comparison (first/grey are page/schedule, not proven Act):', flush=True)
    for piece in re.split(r'<!--\s*Station Details Card\s*-->', body, flags=re.I)[1:]:
        code_match = re.search(r'<h3\b[^>]*>\s*[^<]*?\(([A-Z0-9]{1,6})\)\s*</h3>', piece, re.I | re.S)
        if not code_match or code_match.group(1) not in ('SMET', 'KMSI', 'ARU', 'ANF'):
            continue
        css = re.search(r'^\s*<div\s+class="([^"]+)"', piece, re.I)
        past = bool(css and 'bg-green-50' in css.group(1) and 'opacity-90' in css.group(1))
        arr = piece.split('<!-- Arrival -->', 1)[-1].split('<!-- Departure -->', 1)[0]
        dep = piece.split('<!-- Departure -->', 1)[-1][:900]
        times = lambda section: re.findall(r'\b\d{1,2}:\d{2}\s*(?:AM|PM)\b', section[:650], re.I)[:2]
        print(f'    {code_match.group(1)} past-card={past} page/sch arr={times(arr)} '
              f'dep={times(dep)}', flush=True)
    # The flattened page says ARRIVAL 05:31 05:29 without saying which is observed and
    # which is projected. Examine the surrounding *public HTML attributes* to see if the
    # upstream marks passed stops, forecast clocks, run identity, or a provider field.
    # Keep the trial bounded: never post the entire page or any cookies to a commit.
    for code in ('SMET', 'KMSI', 'ARU', 'ANF'):
        match = re.search(r'(?<![A-Z])' + code + r'(?![A-Z])', body, re.I)
        if match:
            fragment = unescape(body[max(0, match.start()-420): match.end()+1300])
            fragment = re.sub(r'\s+', ' ', fragment)[:1550]
            print(f'  html {code}: {fragment!r}', flush=True)
    markers = ('actual', 'estimated', 'predicted', 'isReached', 'trainStartDate',
               'journeyDate', 'currentStation', '/api/', 'fetch(', 'axios')
    for marker in markers:
        hits = list(re.finditer(re.escape(marker), body, re.I))[:2]
        if hits:
            snippets = [re.sub(r'\s+', ' ', body[max(0,h.start()-75):h.end()+130])[:210]
                        for h in hits]
            print(f'  html marker {marker!r}: {snippets!r}', flush=True)
    for src in srcs[:2]:
        try:
            st, _, js, sec, cut = get(src, cap=110_000)
            matches = list(re.finditer(patterns, js, re.I))[:8]
            print(f'  JS {urlparse(src).path} HTTP {st} {sec}s truncated={cut}; '
                  f'contexts=' + repr([js[max(0,m.start()-70):m.end()+100]
                    .replace('\n', ' ')[:170] for m in matches]), flush=True)
        except Exception as e:
            print(f'  JS {urlparse(src).path}: {type(e).__name__} {str(e)[:95]}', flush=True)
    return digest


if __name__ == '__main__':
    print('16227 browser-page trial, 15 Oct 2026; website is NOT Google WIMT app or an independently verified live feed.', flush=True)
    try:
        try:
            a = once()
        except OSError as exc:
            print(f'  first website request failed ({type(exc).__name__}: {str(exc)[:100]}); '
                  'retrying once after 1s, not treating a connection failure as an empty train', flush=True)
            time.sleep(1)
            a = once()
        time.sleep(12)
        b = once()
        print(f'  12s repeat: rowDigest unchanged={a == b}; a stable page plus advancing clock is NOT movement', flush=True)
    except Exception as exc:
        print(f'  website probe failed: {type(exc).__name__}: {str(exc)[:170]}', flush=True)
