#!/usr/bin/env python3
"""Read-only temporal/HTML trial of whereismytrain.org.in. No station value is called GPS/Act.

Runs on GitHub Actions, because the local coding sandbox cannot connect to this host.
Prints bounded structural diagnostics, not raw HTML/identifiers or credentials. One train, two
snapshots 45 seconds apart, plus one explicit-date query to test whether the server honours it.
"""
import datetime as dt
import hashlib
import html
import re
import sys
import time
import urllib.parse
import urllib.request
import urllib.error

ROOT = "https://whereismytrain.org.in/"
TRAIN = re.fullmatch(r"\d{5}", sys.argv[1] if len(sys.argv) > 1 else "16227")
if TRAIN is None:
    raise SystemExit("train number must be five digits")
TRAIN = TRAIN.group()
CLOCK = re.compile(r"\b\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)\b", re.I)
CARD = re.compile(r"<!--\s*Station Details Card\s*-->", re.I)
HEADING = re.compile(r"<h3\b[^>]*>\s*[^<]*?\(([A-Z0-9]{1,6})\)\s*</h3>", re.I | re.S)


def get(params):
    url = ROOT + "?" + urllib.parse.urlencode(params)
    t = time.monotonic()
    try:
        request = urllib.request.Request(url, headers={
            "User-Agent": "OpenTrackIndia/1.0 (offline-first railway tracker; no accounts, no tracking)",
            "Accept": "text/html"})
        with urllib.request.urlopen(request, timeout=12) as response:
            body = response.read(500_001)
            headers = response.headers
            code = response.status
        if len(body) > 500_000:
            raise ValueError("HTML exceeds 500 kB cap")
        return body.decode("utf-8", "replace"), round(time.monotonic() - t, 3), code, headers
    except urllib.error.HTTPError as exc:
        # The refusal is a response, not a parser failure. Log only a fixed allowlist
        # of non-identifying response headers; never dump HTML challenges, cookies,
        # origin identifiers or any token from the error body.
        diagnostic = {key: exc.headers.get(key, "")[:80] for key in
                      ("Server", "Via", "Content-Type", "Retry-After", "CF-Mitigated", "X-Cache")
                      if exc.headers.get(key)}
        # Diagnose the *kind* of refusal without copying a challenge page, cookie,
        # token, client fingerprint, IP address or raw response into a commit comment.
        body = exc.read(8_193)
        page = body.decode("utf-8", "replace").lower()
        markers = [word for word in ("access denied", "forbidden", "security check",
                   "bot protection", "challenge", "captcha", "temporarily blocked",
                   "request blocked", "website suspended", "rate limit", "hostinger")
                   if word in page]
        print("request refused after %.2f s: HTTP %d, response headers=%s, "
              "body-prefix=%dB%s, markers=%s" %
              (time.monotonic() - t, exc.code, diagnostic, len(body),
               "+" if len(body) > 8_192 else "", markers), flush=True)
        return "", round(time.monotonic() - t, 3), exc.code, {}
    except Exception as exc:
        print("request failed after %.2f s: %s: %s" % (time.monotonic() - t,
              type(exc).__name__, str(exc)[:120]), flush=True)
        return "", round(time.monotonic() - t, 3), 0, {}


def card_rows(body):
    rows = {}
    for piece in CARD.split(body)[1:]:
        match = HEADING.search(piece)
        if not match:
            continue
        code = match.group(1)
        cls = re.search(r'^\s*<div\s+class="([^"]+)', piece, re.I)
        past = bool(cls and "bg-green-50" in cls.group(1) and "opacity-90" in cls.group(1))
        arr = piece.split("<!-- Arrival -->", 1)[-1].split("<!-- Departure -->", 1)[0]
        dep = piece.split("<!-- Departure -->", 1)[-1][:950]
        class_text = cls.group(1)[:180] if cls else '(no outer div class)'
        # Record the exact card style: a current blue card may contain a reached
        # arrival while the old parser only accepts green cards. Do not call it
        # actual until its clock and run pass independent gates.
        values = (class_text, past, CLOCK.findall(arr[:650])[:2],
                  CLOCK.findall(dep[:650])[:2])
        # Same stop can have both a past and a future card; report both, without deduping it away.
        rows.setdefault(code, []).append(values)
    return rows


def inspect(tag, params):
    body, duration, code, headers = get(params)
    if not body:
        return {}
    text = html.unescape(re.sub(r"<[^>]+>", " ", re.sub(r"<script\b[^>]*>.*?</script>",
                          " ", body, flags=re.I | re.S)))
    update = re.search(r"Last\s+Updated\s*:\s*([\d: ]+(?:AM|PM))", text, re.I)
    now = dt.datetime.now(dt.timezone(dt.timedelta(hours=5, minutes=30)))
    print("%s local=%s http=%s elapsed=%.3fs bytes=%d sha256=%s LastUpdated=%s age=%s cache=%s date=%s" % (
        tag, now.isoformat(timespec="seconds"), code, duration, len(body.encode()),
        hashlib.sha256(body.encode()).hexdigest()[:14], update.group(1).strip() if update else "none",
        headers.get("Age", "none"), headers.get("Cache-Control", "none")[:50],
        headers.get("Date", "none")), flush=True)
    for key in ("run_date", "runDate", "start_date", "startDate", "trainDate", "journeyDate",
                "train_start_date", "originDate", "current_station", "currentStation",
                "distance", "lat", "longitude", "gps", "NTES", "ntes"):
        count = len(re.findall(r"(?i)\b" + re.escape(key) + r"\b", body))
        if count:
            print("  field '%s': %d occurrence(s)" % (key, count), flush=True)
    script_src = re.findall(r"<script[^>]*\bsrc=[\"']([^\"']+)", body, re.I)[:20]
    print("  scripts: %s" % [urllib.parse.urlsplit(s).path for s in script_src], flush=True)
    candidates = re.findall(r"(?:fetch\(|axios\.|/api/|\.json|XMLHttpRequest|WebSocket)", body, re.I)
    print("  network-hook tokens: %s" % {v: candidates.count(v) for v in set(candidates)}, flush=True)
    # Bounded source inspection: see whether 'distance' is merely FAQ prose and whether
    # fetch() addresses a usable dated or position endpoint. Strip query strings/secrets.
    for needle in ("fetch(", "/api/", "distance"):
        for hit in list(re.finditer(re.escape(needle), body, re.I))[:2]:
            snippet = re.sub(r"\s+", " ", body[max(0, hit.start()-65):hit.end()+95])
            snippet = re.sub(r"(https?://[^\s\"']+)\\?[^\s\"']+", r"\1?[redacted]", snippet)
            snippet = re.sub(r"[A-Za-z0-9_-]{28,}", "[redacted]", snippet)
            print("  %s context: %s" % (needle, snippet), flush=True)
    rows = card_rows(body)
    print("  cards=%d, codes=%d, shown(last six)=%s" % (sum(map(len, rows.values())), len(rows),
        {k: v for k, v in list(rows.items())[-6:]}), flush=True)
    for code, cards in rows.items():
        if code in ("KMSI", "SMET", "SME", "HNHL", "BEI", "BDVT", "MSS", "ANF", "SRF", "TLGP") or any(
                "bg-blue" in c[0] for c in cards):
            print("  focus %s: %s" % (code, cards), flush=True)
    # Preserve bounded source markup for a real parser fixture. This is public
    # station-card HTML only, not scripts, cookies, identifiers or phone-app data.
    # A screenshot cannot tell whether the server sent two clock spans, or if a
    # green card's first time is just the timetable echoed back as an estimate.
    if TRAIN == "16227" and tag == "baseline":
        for piece in CARD.split(body)[1:]:
            match = HEADING.search(piece)
            if match and match.group(1) in {"SME", "SMET", "HNHL", "KMSI", "ANF", "SRF"}:
                clean = re.sub(r"<script\b[^>]*>.*?</script>", "", piece, flags=re.I | re.S)
                print("  RAW-CARD %s <!-- Station Details Card -->%s" %
                      (match.group(1), clean[:2400].replace("\n", " ")), flush=True)
    return rows


print("Read-only site structure and drift trial. No claimed event is independently observed.", flush=True)
a = inspect("baseline", {"trainNo": TRAIN})
if TRAIN == "20689":
    # Earlier 20689 and control-train requests both returned 403. Test the honest
    # app identity once only; another train/date/repeat would just add traffic.
    print("single polite transport check; no automatic repeat or control request", flush=True)
    raise SystemExit(0)
# Does a date parameter actually select a different run? Merely echoing a URL is not proof.
inspect("explicit-old-date", {"trainNo": TRAIN, "date": "2026-10-14"})
print("waiting 45 seconds to compare row changes with page-update clock...", flush=True)
time.sleep(45)
b = inspect("repeat", {"trainNo": TRAIN})
for code in sorted(set(a) | set(b)):
    if a.get(code) != b.get(code):
        print("CHANGED %s: %s -> %s" % (code, a.get(code), b.get(code)), flush=True)
print("unchanged codes=%d, changed codes=%d" % (
    sum(a.get(c) == b.get(c) for c in set(a) | set(b)),
    sum(a.get(c) != b.get(c) for c in set(a) | set(b))), flush=True)
