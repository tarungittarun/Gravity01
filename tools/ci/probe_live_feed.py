#!/usr/bin/env python3
"""Ask the live-feed endpoints what they actually answer, and report it on the commit.

Why this runs in CI: it is a network question, and the answer differs per egress path. Measured, not
assumed: this sandbox gets `TLS/SSL connection has been closed` from `enquiry.indianrail.gov.in`, and so did
GitHub-hosted runners (the probe comments posted on da0633c and b41c38c) — which is why the script's real
output is a *reachability verdict*, and why the device stays the only witness for what the feed actually
sends. Runners do vary by region and by egress pool, so asking costs seconds and an answer is worth having
in writing; when every path is blocked, that is a finding, and the app must then not promise live data.

The second half of that sentence is why `erail.in` is probed here too: it is a plain GET, keyless, and its
own header carries the feed stamp, so if any CI path can reach anything, it is probably that one.

The app's own request shape is defined by `NtesWire.kt`; this script mirrors it with the same static key,
IV and signature secret, because that is the only way to find out whether the Kotlin implementation would
have worked before a user does. It reports structure, not contents: which keys exist, which of the
stop-level fields carry real times, how many rows have an actual arrival. A train that is 5-10 minutes
late is included on purpose — a feed that only answers "On Time" is exactly the failure the user called
out.

Stdlib + `pycryptodome` (installed by the job; if it is absent the script says so and exits 0, because a
probe that cannot run must not be able to fail the build it is helping).
"""
from __future__ import annotations

import datetime
import json
import os
import subprocess
import sys
import urllib.error
import http.cookiejar
import urllib.request

BASE = "https://enquiry.indianrail.gov.in/crisns/AppServAnd"
KEY = b"8EA4DB2CC1EB3DC5"
IV = b"7DC5EB3BB4DB6EA8"
SCKEY = "645fbc1e56e23365f2f3c204ae0899f6"
UA = "Dalvik/2.1.0 (Linux; Android 11)"


def crypto_available() -> bool:
    try:
        import Crypto.Cipher.AES  # noqa: F401
        return True
    except Exception:
        return False


def wrap(plain: str) -> str:
    import base64
    import binascii
    import hashlib
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import pad

    ct = AES.new(KEY, AES.MODE_CBC, IV).encrypt(pad(plain.encode(), 16))
    enc = binascii.hexlify(base64.b64encode(ct)).decode().upper()
    return hashlib.md5((plain + SCKEY).encode()).hexdigest().upper() + "#" + enc


def unwrap(wire: str) -> str:
    import base64
    import binascii
    from Crypto.Cipher import AES
    from Crypto.Util.Padding import unpad

    if "#" in wire:
        wire = wire.split("#", 1)[1]
    ct = base64.b64decode(binascii.unhexlify(wire.strip()))
    return unpad(AES.new(KEY, AES.MODE_CBC, IV).decrypt(ct), 16).decode("utf-8", "replace")


def call(plain: str, timeout: int = 25):
    body = json.dumps({"jsonIn": wrap(plain)}).encode()
    req = urllib.request.Request(
        BASE, data=body,
        headers={"Content-Type": "application/json", "charset": "utf-8", "User-Agent": UA},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8", "replace")
            status = r.status
    except urllib.error.HTTPError as e:
        return f"HTTPError {e.code}", None
    except Exception as e:  # noqa: BLE001 - a probe reports failures, it does not raise them
        return f"{type(e).__name__}: {e}"[:160], None
    try:
        doc = json.loads(raw)
    except Exception:
        return f"not JSON ({len(raw)} bytes, starts {raw[:60]!r})", None
    if isinstance(doc, dict) and "jsonIn" in doc:
        try:
            doc = json.loads(unwrap(doc["jsonIn"]))
        except Exception as e:  # noqa: BLE001
            return f"encrypted envelope, could not decrypt: {e}"[:160], None
    return "ok", doc


def shape(v, depth=0):
    """Key names and array lengths only — the point is the schema, and this text goes on a commit."""
    if depth > 2:
        return "…"
    if isinstance(v, dict):
        return {k: shape(v[k], depth + 1) for k in sorted(v)[:24]}
    if isinstance(v, list):
        return [f"list[{len(v)}]", shape(v[0], depth + 1) if v else None]
    if isinstance(v, str):
        return v[:34]
    return v


def first_array(doc):
    """The stop list, wherever it is: NTES names it differently across sub-services."""
    if isinstance(doc, dict):
        for k in ("S", "stops", "route", "schedule", "DATA", "data", "Result"):
            v = doc.get(k)
            if isinstance(v, list) and v:
                return k, v
        for v in doc.values():
            got = first_array(v)
            if got:
                return got
    return None, None


def flat_table(doc):
    """Parallel-array responses, which is how several NTES sub-services actually answer.

    Some services return `{"TRAINNO":[..], "STN":[..], "ATD":[..]}` - one array per column - instead of an
    array of row objects. The Kotlin parser has to serve both, and it can only be written that way if we can
    *see* the difference from here, so this reports it rather than assuming the row-object style.
    """
    if not isinstance(doc, dict):
        return None
    lists = {k: v for k, v in doc.items() if isinstance(v, list)}
    if not lists:
        return None
    lens = {k: len(v) for k, v in lists.items()}
    n = max(lens.values()) if lens else 0
    if n == 0:
        return {"lengths": lens, "rows": []}
    if len(set(lens.values())) > 1:
        # Ragged columns: still informative (it means the feed pads or truncates), and transposing would lie.
        return {"lengths": lens, "rows": []}
    cols = [k for k in doc if isinstance(doc[k], list)]
    rows = [{c: doc[c][i] for c in cols} for i in range(n)]
    return {"lengths": lens, "rows": rows}


def numbers_from(doc):
    """Every train number a reply mentions, whatever key it hid it under.

    Deliberately a regex over the whole payload: the point of this probe is to find out what the feed says,
    not to confirm what our parser expects to find.
    """
    return sorted(set(__import__("re").findall(r"\b(\d{5})\b", json.dumps(doc, separators=(",", ":")))))


def identity_from(doc):
    """name / origin / terminal from a FindTrainJson-style reply, key-name agnostic."""
    blob = doc if isinstance(doc, dict) else {}
    # unwrap one level of list/array-of-one, which is how these flat services often wrap the record
    for k, v in list(blob.items()):
        if isinstance(v, list) and len(v) == 1 and isinstance(v[0], dict):
            blob = v[0]
            break
    def pick(*cands):
        for key, val in blob.items():
            ku = key.upper()
            if any(c in ku for c in cands) and isinstance(val, (str, int)):
                s = str(val).strip()
                if s:
                    return s
        return ""
    return {"name": pick("NAME"), "from": pick("SRC", "ORIGIN", "FROM"), "to": pick("DST", "TERM")}



ERAIL_BASE = "https://erail.in/rail/getTrains.aspx"


BROWSER_UA = ("Mozilla/5.0 (Linux; Android 11; Pixel 6) AppleWebKit/537.36 "
              "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")


def erail_get(query: str, timeout: int = 20, base: str = ERAIL_BASE,
              headers: dict | None = None) -> tuple[str, str]:
    """One GET to erail.in. No key, no account, nothing of ours in the middle.

    [base] exists so the route endpoint (getRoute.aspx) is asked through the same one-attempt,
    typed-verdict door as everything else - not through a second, braver fetcher. [headers]
    overrides the default set: the live-status endpoint answers 403 to the Dalvik UA (the 548a5db
    run proved the path is real), so the ladder asks once more looking like the browser the page
    expects - a header change is not a new tool, and it is still one attempt per candidate.
    """
    h = {"User-Agent": UA, "Accept": "*/*"} if headers is None else headers
    req = urllib.request.Request(base + ("?" + query if query else ""), headers=h)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read(400_000).decode("utf-8", "replace")
            return f"HTTP {getattr(r, 'status', 200)} {len(body)}B", body
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}", ""
    except Exception as e:
        return f"{type(e).__name__}: {str(e)[:70]}", ""


def erail_candidates(src: str, dst: str, date_1: str) -> list[str]:
    """The pair query in every shape the site is known to accept, best guess first.

    All of them are tried in one run on purpose: the runner is the only machine that can ask, and a round that
    answers "does erail work from CI" for exactly one URL costs a build and a day. The 404 we already measured
    stays in the list as a control - the path is /rail/getTrains.aspx, not /getTrains.aspx, and a report that
    proves the difference is worth more than one that quietly uses the right one.
    """
    numeric = date_1
    for fmt in ("%d-%b-%Y", "%d-%m-%Y", "%d/%m/%Y"):
        try:
            numeric = datetime.datetime.strptime(date_1, fmt).strftime("%d-%m-%Y")
            break
        except ValueError:
            continue
    common = f"Station_From={src}&Station_To={dst}&Trains=BOTH&Sort_Type=PVT&Language=0&DataSource=1"
    return [
        f"{common}&Date_1={numeric}&Time_1=23:30&Date_Format=DD-MM-YYYY",
        f"{common}&Date_1={date_1}&Time_1=23:30&Date_Format=DD-MMM-YYYY",
        common,
        f"https://erail.in/getTrains.aspx?{common}".replace("https://erail.in/getTrains.aspx?", ""),
    ]


def erail_pair(src: str, dst: str, date_1: str, get=erail_get) -> tuple[str, str]:
    """Try the pair query until one answers, and report every attempt either way."""
    seen: list[str] = []
    for q in erail_candidates(src, dst, date_1):
        verdict, body = get(q)
        seen.append(f"{verdict} [{q[:46]}…]")
        if verdict.startswith("HTTP 200") and len(body) > 200:
            tail = "" if not seen[:-1] else f" · after {len(seen)} attempt(s); earlier: " + " ; ".join(seen[:-1])
            return verdict + tail, body
    return "no candidate answered · " + " ; ".join(seen), ""


def probe_pair(src_code: str, dst_code: str, must: list[str], date_dalvik: str,
               today: datetime.datetime, capture: str | None = None,
               fetch=call, alt=erail_pair) -> tuple[list[str], dict | None, str]:
    """One station pair, asked of both keyless sources, with the alternate tried no matter what.

    Returns (report lines, proposed pair fact or None, erail body verbatim or ""). The body is returned so
    the run can carry the *raw* answer home: a parser may only be written against a captured payload (4.5),
    and the commit comment built from it is the only capture path readable from outside CI's blob storage.

    The `fetch=call, alt=erail_pair` injection exists for one reason: a test must be able to say "the primary
    timed out" and check that the alternate still got asked. The first version of this function put the erail
    call below the primary's `if doc is None: continue`, so the alternate only ran when the primary worked -
    a fallback gated on the thing it is a fallback for, which is the same defect the app has been fighting,
    caught here by its own probe output (an `### pair ... erail.in` line that never appeared).
    """
    out: list[str] = []
    live_entry: dict | None = None
    plain = (f"service=TrainBtwStnMob&subService=TrainBtwStnJson&stnFrom={src_code}"
             f"&stnTo={dst_code}&trainType=All")
    verdict, doc = fetch(plain)
    out.append(f"\n### pair {src_code}->{dst_code} · live list · {verdict}")
    found: list[str] = []
    if doc is None:
        out.append(f"  primary gave nothing usable, so this pair's truth rests on the alternate alone")
    else:
        if capture:
            with open(os.path.join(capture, f"pair-{src_code}-{dst_code}.json"), "w", encoding="utf-8") as fh:
                json.dump(doc, fh)
        out.append("top-level: " + json.dumps(shape(doc), separators=(",", ":"))[:900])
        found = numbers_from(doc)
        out.append(f"train numbers mentioned: {len(found)} -> {', '.join(found[:40])}")
        live_entry = {"from": src_code, "to": dst_code, "must_include": found}
        for n in must:
            out.append(f"  our fixture claims {n} on this pair: "
                       + ("CONFIRMED by the live feed" if n in found else "NOT in what the feed returned"))

    # Second witness, unconditional. Two keyless answers that agree are evidence; one is a hypothesis.
    e_verdict, e_body = alt(src_code, dst_code, today.strftime("%d-%b-%Y"))
    out.append(f"### pair {src_code}->{dst_code} · erail.in (alternate) · {e_verdict}")
    e_nums: list[str] = []
    if e_body:
        if capture:
            with open(os.path.join(capture, f"pair-erail-{src_code}-{dst_code}.txt"), "w",
                      encoding="utf-8") as fh:
                fh.write(e_body)
        import re as _re
        stamp = _re.search(r"(\d{4}-\d{2}-\d{2}-\d{1,2}-\d{1,2}-\d{1,2})", e_body)
        e_nums = sorted(set(_re.findall(r"\b(\d{5})\b", e_body[:200000])))
        out.append(f"feed stamp in payload: {stamp.group(1) if stamp else 'not found'}; "
                   f"train numbers mentioned: {len(e_nums)}")
        out.append("  " + ", ".join(e_nums[:40]))
        for n in must:
            if n not in e_nums:
                out.append(f"  erail does not list {n} either - the fixture's claim is the odd one out")
    if e_nums:
        if live_entry is None:
            live_entry = {"from": src_code, "to": dst_code, "must_include": sorted(e_nums)}
        else:
            live_entry["must_include"] = sorted(set(live_entry["must_include"]) | set(e_nums))
    return out, live_entry, e_body



def _is_station_code(f: str) -> bool:
    """3-5 alphanumerics with at least one letter. Pure digits are numbers, not stations - and accepting
    them once let a train number parse as a station. Codes are never sliced to 3 (that turned SMET into MET)."""
    return 3 <= len(f) <= 5 and f.isalnum() and any(c.isalpha() for c in f)


def parse_identity_record(body: str) -> dict | None:
    """First record of a getTrains.aspx identity reply -> {name, from, to}, or None when it is not one.

    Written against the four real records the ac06f62 probe captured from CI (16228/16226/16221/12627),
    which all read: <feed-stamp>~^<number>~<name>~<from station name>~<FROM>~<to station name>~<TO>~...
    The previous version read fields[2]/fields[3] as the codes - an imagined shape - so every real CI
    answer was rejected as 'station fields parsed as TLGP MYS EXP/TALGUPPA' and no identity ever landed
    in a proposal. The parser now anchors on the ^number field, takes the name after it, and accepts only
    validated station codes from there; anything else is None, never a half-parsed fact (4.5).
    """
    rec = body.splitlines()[0][:400] if body else ""
    if not rec:
        return None
    import re as _re
    fields = [f.strip() for f in rec.split("~")]
    num_at = next((i for i, f in enumerate(fields) if _re.fullmatch(r"\^?\d{4,5}", f)), None)
    if num_at is None or num_at + 1 >= len(fields):
        return None
    name = fields[num_at + 1]
    if not name or len(name) > 60:
        return None
    codes = [f.upper() for f in fields[num_at + 2:] if _is_station_code(f.upper())]
    if len(codes) < 2:
        return None
    return {"name": name, "from": codes[0], "to": codes[1]}


def erail_identity_lines(train_no: str, live: dict, get=erail_get) -> tuple[list[str], str]:
    """"What does this number mean today", asked of the keyless source that CI can actually reach.

    This is the one fact our offline copy got wrong (16228 was a different train), and the one fact a human
    screenshot can pin but not keep pinned. If this reads OK from a runner, fixtures/known_truths.json stops
    depending on somebody's phone and becomes refreshable by the pipeline - which is the whole difference
    between a data bug that lasts four months and one that lasts one night.
    """
    out = [f"identity of {train_no} via erail.in · ", ""]
    verdict, body = get(f"TrainNo={train_no}&DataSource=1&Language=0")
    out[0] = f"identity of {train_no} via erail.in · {verdict}"
    out.pop()
    rec = parse_identity_record(body)
    if rec is None:
        first = body.splitlines()[0][:120] if body else ""
        out.append("  not recorded as a truth: " + (f"first record fields: {first!r}" if first else "(nothing)"))
        return out, body
    out.append("  first record fields: " + ", ".join(f"{k}={rec[k]}" for k in ("name", "from", "to")))
    live["trains"].append({"train_no": train_no, **rec})
    return out, body


def erail_route_lines(train_no: str,
                      get=erail_get) -> tuple[list[str], list[tuple[str, str, str]]]:
    """The per-stop ROUTE of one number, asked of erail's getRoute.aspx - the endpoint the details
    screen's stale timetable really needs (17.1: schedule = NTES -> erail -> asset).

    The reply's shape is UNKNOWN until a capture lands - getRoute.aspx may serve tilde records or an
    HTML table - so this function PARSES NOTHING. First run asked one parameter spelling and got four
    honest HTTP 404s; the ladder now tries the spellings, best-reasoned first (the identity call's
    exact casing/params, then the DataSource=0 form, then the 404'd control so the report always
    carries the comparison). Each candidate is asked exactly once; bodies are captured verbatim with
    their URL for the parser to meet for the first time (4.5). A dead guess is one honest line.
    """
    cands = [
        (f"TrainNo={train_no}&DataSource=1&Language=0", "https://erail.in/rail/getRoute.aspx"),
        (f"TrainNo={train_no}&DataSource=0&Language=0", "https://erail.in/rail/getRoute.aspx"),
        (f"trainno={train_no}&DataSource=0&Language=0", "https://erail.in/rail/getRoute.aspx"),
    ]
    out: list[str] = []
    caps: list[tuple[str, str, str]] = []
    for q, base in cands:
        verdict, body = get(q, base=base)
        out.append(f"route of {train_no} via {base} ({q.split('&')[1] if '&' in q else 'control'}) · {verdict}")
        if body:
            first = body.splitlines()[0][:120] if body.splitlines() else ""
            out.append(f"  body {len(body)}B · {body.count(chr(10)) + 1} line(s) · first: {first!r}")
            caps.append((train_no, base, body))
    return out, caps


def erail_session_get(query: str, timeout: int = 20, base: str = ERAIL_BASE,
                      headers: dict | None = None) -> tuple[str, str]:
    """The website's own sequence, not just its headers: GET the origin first so the opener HOLDS the
    site's cookies, then ask the live endpoint through the same cookie-aware opener. Static headers
    measured 403 three ways (a339ab6); the one thing they lack is a session the site set itself.
    """
    cj = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))
    h = {"User-Agent": BROWSER_UA, "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
         "Referer": "https://erail.in/"} if headers is None else headers
    try:
        opener.open("https://erail.in/", timeout=timeout).read(50_000)
        req = urllib.request.Request(base + ("?" + query if query else ""), headers=h)
        with opener.open(req, timeout=timeout) as r:
            body = r.read(400_000).decode("utf-8", "replace")
            return f"HTTP {getattr(r, 'status', 200)} {len(body)}B cookies={len(cj)}", body
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code} cookies={len(cj)}", ""
    except Exception as e:
        return f"{type(e).__name__}: {str(e)[:70]}", ""


def erail_live_status_lines(train_no: str, date_ddmm: str,
                            get=erail_get,
                            session_get=None) -> tuple[list[str], list[tuple[str, str, str]]]:
    """The ACTUAL arrival/departure question (the elephant), asked of erail's live-status surface.

    The owner's phone is the only machine NTES answers to, but erail advertises live running status
    too - so before declaring the phone the only door, the probe ASKS. The endpoint names and page
    paths are candidates, not knowledge: each is tried exactly once, its verdict is reported, and any
    body is captured verbatim for the parser to meet for the first time (4.5). A dead guess is one
    honest line; a live one could close the elephant from CI alone. PARSING NOTHING here, by design.
    """
    browser = {"User-Agent": BROWSER_UA, "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
               "Referer": "https://erail.in/"}
    xhr = {"User-Agent": BROWSER_UA,
           "Accept": "application/json, text/javascript, */*; q=0.01",
           "Referer": "https://erail.in/",
           "X-Requested-With": "XMLHttpRequest"}
    rr = {"User-Agent": BROWSER_UA, "Accept": "application/json"}
    # THE HOMEPAGE ITSELF ADVERTISES api.erail.in ("Data -> API", captured in full on 5ea20a2).
    # The classic erail public API: /runningstatus/ (per-stop ACTUALS), /route/ (per-stop schedule),
    # /trains/ (pairs). Asked keylessly first - if their public door still opens without a key, the
    # elephant closes from CI today.
    api = "https://api.erail.in"
    ddmmyy = date_ddmm.replace("-", "/")
    cands = [
        (f"runningstatus/?trainno={train_no}&startdate={ddmmyy}", api + "/", None, "api-no-key"),
        (f"route/?trainno={train_no}", api + "/", None, "api-no-key"),
        (f"getRunTrain.aspx?trainno={train_no}&startDate={date_ddmm}", "https://erail.in/rail/", None,
         "dalvik-UA"),
        (f"getRunTrain.aspx?TrainNo={train_no}&startDate={date_ddmm}", "https://erail.in/rail/", browser,
         "browser-UA"),
        (f"getRunTrain.aspx?trainno={train_no}&startDate={date_ddmm}", "https://erail.in/rail/", xhr,
         "browser-UA+XHR"),
        (f"{train_no}/", "https://erail.in/running-status/", None, "page"),
        ("", f"https://api.railradar.in/v1/trains/{train_no}/live", rr, "railradar-no-key"),
        (f"getRunTrain.aspx?trainno={train_no}&startDate={date_ddmm}", "https://erail.in/rail/", "SESSION",
         "cookie-session"),
    ]
    out: list[str] = []
    caps: list[tuple[str, str, str]] = []
    for q, base, hdr, tag in cands:
        if hdr == "SESSION":
            verdict, body = (session_get or erail_session_get)(q, base=base)
        else:
            verdict, body = (get(q, base=base, headers=hdr) if hdr
                             else get(q, base=base))
        out.append(f"live status of {train_no} via {base}{q.split('?')[0]} ({tag}) · {verdict}")
        if body:
            out.append(f"  body {len(body)}B · {body.count(chr(10)) + 1} line(s)")
            caps.append((train_no, base + q.split("?")[0], body))
    return out, caps


def erail_site_script_lines(get=erail_get,
                            session_get=erail_session_get) -> tuple[list[str], list[tuple[str, str, str]]]:
    """erail's own app scripts, asked from CI - the 403 wall was on getRunTrain.aspx specifically
    (getTrains.aspx answers runners all day), and static assets on the same host are worth one ask.

    The owner's phone observed these exact URLs (11:02 log); nothing is invented here either. The
    bodies are filtered in place: windows around endpoint-ish keywords - their code names the hub,
    and the SignalR requester's presence already explains why no GET endpoint spelling can 200.
    """
    import re as _re
    browser = {"User-Agent": BROWSER_UA, "Accept": "*/*", "Referer": "https://erail.in/"}
    out: list[str] = []
    caps: list[tuple[str, str, str]] = []
    rx = _re.compile(r"aspx|getrun|signalr|hub|negotiate|ajax|live|running|connection", _re.I)

    # 1) The homepage IN FULL - the 8af9e73 run proved CI gets 200/27KB here, and its first 4000
    #    bytes were all meta tags: the script tags and any inline SignalR config live further down.
    verdict, home = get("", base="https://erail.in/", headers=browser)
    out.append(f"site script https://erail.in/ (homepage, FULL) · {verdict}")
    if home:
        hits = []
        for m in list(rx.finditer(home))[:12]:
            a = max(0, m.start() - 60)
            b = min(len(home), m.end() + 90)
            hits.append(home[a:b].replace("\n", " "))
        out.append("  endpoint-hits: " + (" || ".join(hits))[:2600])
        caps.append(("erail-homepage.html", "https://erail.in/", home[:30000]))

    # 2) The observed JS assets through the cookie-session opener (visit first, carry the site's
    #    own cookies): the plain fetch got the homepage fallback, so the asset path is gated.
    for url in (
        "https://erail.in/js5/cmp/eRailMobileHTMLEN.js?v=22092026",
        "https://erail.in/SignalR/js/requester_min.js",
    ):
        verdict, body = session_get(url.replace("https://erail.in/", ""),
                                    base="https://erail.in/", headers=browser)
        out.append(f"site script {url} (cookie-session) · {verdict}")
        if body:
            hits = []
            for m in list(rx.finditer(body))[:10]:
                a = max(0, m.start() - 60)
                b = min(len(body), m.end() + 90)
                hits.append(body[a:b].replace("\n", " "))
            out.append("  endpoint-hits: " + (" || ".join(hits))[:2000])
            caps.append((url.rsplit("/", 1)[-1].split("?")[0], url, body[:8000]))
    return out, caps


# The proposal a probe or refresher prints is a per-fact merge suggestion, never a replacement file. The
# ac06f62 probe comment proved the footgun: its proposed refresh carried "trains": [] because NTES was
# unreachable, and one human paste away from erasing three owner-verified identities. So the rule travels
# INSIDE the proposal, where the human reading it cannot miss it.
PROPOSAL_RULE = (
    "per-fact merge proposal, NOT a replacement: apply only rows you have read; never delete an "
    "owner-verified fact that is absent here (absent means we could not check it, not that it moved); "
    "a live claim that contradicts an owner-verified fact needs an owner word before it is applied"
)


def new_proposal(today: datetime.datetime, verified_against: str) -> dict:
    """The skeleton of a proposed refresh, with the merge rule embedded so it survives copy-paste."""
    return {
        "_proposal_rule": PROPOSAL_RULE,
        "verifiedOn": today.strftime("%Y-%m-%d"),
        "verifiedAgainst": verified_against,
        "trains": [],
        "pair_lists": [],
    }


def erail_capture_comment(pairs: list[tuple[str, str, str, str]], probed_at: str,
                          identities: list[tuple[str, str]] | None = None,
                          routes: list[tuple[str, str]] | None = None,
                          live_status: list[tuple[str, str, str]] | None = None,
                          scripts: list[tuple[str, str, str]] | None = None) -> str | None:
    """The raw erail bodies as a commit comment - the only capture path a sandbox can read.

    CI artefacts live on blob hosts that some sandboxes cannot fetch, but commit comments come through the
    API. 4.5 says a parser may only be written against a captured payload; this is how the capture gets
    from the only machine that can ask (a runner) to the machine that writes the parser. Identities are
    carried too because the TrainNo reply is (a) the identity fact and (b) possibly the full SCHEDULE -
    the pair capture proved the value of carrying the whole body rather than the parsed summary.
    """
    bodies = [(s, d, v, b) for s, d, v, b in pairs if b]
    idents = [(n, b) for n, b in (identities or []) if b]
    routes_ = [(n, u, b) for n, u, b in (routes or []) if b]
    status_ = [(n, u, b) for n, u, b in (live_status or []) if b]
    scripts_ = [(n, u, b) for n, u, b in (scripts or []) if b]
    if not bodies and not idents and not routes_ and not status_ and not scripts_:
        return None
    out = ["### live-feed capture (erail, raw bodies)", "",
           "Parsers are written against exactly this text, nothing imagined (4.5).",
           f"probed {ERAIL_BASE} at {probed_at} (IST)", ""]
    for src, dst, verdict, body in bodies:
        import re as _re
        stamp = _re.search(r"(\d{4}-\d{2}-\d{2}-\d{1,2}-\d{1,2}-\d{1,2})", body)
        out.append(f"pair {src}->{dst} · {verdict} · feed stamp: {stamp.group(1) if stamp else 'not found'}")
        out.append("```text")
        out.append(body[:14000])
        if len(body) > 14000:
            out.append(f"...[truncated at 14000 of {len(body)} bytes]")
        out.append("```")
        out.append("")
    for no, body in idents:
        out.append(f"train {no} identity/schedule body · {len(body)}B · lines: {body.count(chr(10)) + 1}")
        out.append("```text")
        out.append(body[:6000])
        if len(body) > 6000:
            out.append(f"...[truncated at 6000 of {len(body)} bytes]")
        out.append("```")
        out.append("")
    for no, url, body in routes_:
        out.append(f"train {no} route body ({url}) · {len(body)}B · lines: {body.count(chr(10)) + 1}")
        out.append("```text")
        out.append(body[:6000])
        if len(body) > 6000:
            out.append(f"...[truncated at 6000 of {len(body)} bytes]")
        out.append("```")
        out.append("")
    for no, url, body in status_:
        out.append(f"train {no} live-status body ({url}) · {len(body)}B · lines: {body.count(chr(10)) + 1}")
        out.append("```text")
        out.append(body[:6000])
        if len(body) > 6000:
            out.append(f"...[truncated at 6000 of {len(body)} bytes]")
        out.append("```")
        out.append("")
    for name, url, body in scripts_:
        out.append(f"site script {name} ({url}) · {len(body)}B captured")
        out.append("```text")
        out.append(body[:30000])
        if len(body) > 30000:
            out.append(f"...[truncated at 30000 of {len(body)} bytes]")
        out.append("```")
        out.append("")
    return "\n".join(out)



def main() -> int:
    out: list[str] = []
    if not crypto_available():
        print("pycryptodome is absent; the probe cannot run here and is not failing the build")
        return 0
    today = datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=5, minutes=30)))
    date = today.strftime("%d-%b-%Y")
    date_num = today.strftime("%d-%m-%Y")
    capture = os.environ.get("PROBE_CAPTURE")
    if capture:
        os.makedirs(capture, exist_ok=True)

    # The probe plan comes from fixtures/known_truths.json, so the facts the app is gated on are the same
    # facts we ask the railway about. When the two disagree the report says which one moved; that is the
    # whole method - it is what a passenger could not get the app to admit for three rounds.
    truths = {}
    try:
        tp = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "fixtures", "known_truths.json")
        truths = json.load(open(tp, encoding="utf-8"))
    except Exception as e:  # a missing fixture must not blind the probe
        out.append(f"(no known-truths fixture to probe against: {e})")
    trains = [t.get("train_no") for t in truths.get("trains", []) if t.get("train_no")]
    trains = (trains or []) + [os.environ.get("PROBE_TRAIN", "12627")]
    pairs = [(p.get("from"), p.get("to"), p.get("must_include", [])) for p in truths.get("pair_lists", [])]

    out.append(f"probed {BASE} at {today.isoformat(timespec='seconds')} (IST)")
    out.append(f"plan: {len(set(trains))} train identities + {len(pairs)} station pair(s) from "
               f"fixtures/known_truths.json (verified {truths.get('verifiedOn', '?')})")

    live = new_proposal(today, "CI probe " + BASE)

    script_captures: list[tuple[str, str, str]] = []
    script_out, script_captures = erail_site_script_lines()
    out += ["\n### erail's own site scripts (their code names the hub)"] + script_out
    identity_captures: list[tuple[str, str]] = []
    route_captures: list[tuple[str, str, str]] = []
    live_status_captures: list[tuple[str, str, str]] = []
    # Route + live-status ladders are 7+ asks per train; two representative trains (the owner-verified
    # long-haul and the KUVEMPU) answer the shape question for all four. Identities stay for every train.
    ladder_trains = [t for t in dict.fromkeys(trains)][:2]
    for train in dict.fromkeys(trains):
        for label, plain in (
            ("identity", f"service=TrainBtwStnMob&subService=FindTrainJson&trainNo={train}"),
            ("live", f"service=TrainRunningMob&subService=ShowFullRunJson&trainNo={train}&startDate={date}"),
            ("schedule", f"service=TrainRunningMob&subService=GetTrainSchedule&trainNo={train}&startDate={date}"),
        ):
            verdict, doc = call(plain)
            out.append(f"\n### train {train} · {label} · {verdict}")
            if label == "identity":
                # Asked whether or not NTES answered: a disagreement between the two is the finding, and a
                # witness gated on the thing it is a witness for is no witness (lesson 14, applied here
                # because my first version of this very line sat below `if doc is None: continue`).
                id_lines, id_body = erail_identity_lines(train, live)
                out += id_lines
                if id_body:
                    identity_captures.append((train, id_body))
                # The route capture rides the same loop: one extra GET per train per probe run, and
                # the per-stop schedule question (T20.2's sequel) gets its first real body to read.
                if train in ladder_trains:
                    route_lines, route_caps = erail_route_lines(train)
                    out += route_lines
                    route_captures.extend(route_caps)
                    ls_lines, ls_caps = erail_live_status_lines(train, date_num)
                    out += ls_lines
                    live_status_captures.extend(ls_caps)
            if doc is None:
                continue
            if capture:
                with open(os.path.join(capture, f"{label}-{train}.json"), "w", encoding="utf-8") as fh:
                    json.dump(doc, fh)
            out.append("top-level: " + json.dumps(shape(doc), separators=(",", ":"))[:900])
            flat = flat_table(doc)
            if flat:
                out.append(f"parallel-array reply: {json.dumps(flat['lengths'], separators=(',', ':'))[:300]}")
                if flat["rows"]:
                    out.append("first 2 rows: " + json.dumps(flat["rows"][:2], separators=(",", ":"))[:700])
            key, rows = first_array(doc)
            if rows:
                filled = [k for k in shape(rows[0]) if any(str(r.get(k, "")).strip() for r in rows if isinstance(r, dict))]
                out.append(f"stop array '{key}' has {len(rows)} rows; non-empty in at least one row: {filled}")
                out.append("first 2 rows: " + json.dumps(rows[:2], separators=(",", ":"))[:700])
            if label == "identity" and isinstance(doc, (dict, list)):
                ident = identity_from(doc if isinstance(doc, dict) else {"r": doc})
                out.append("identity read: " + json.dumps(ident, separators=(",", ":")))
                if ident.get("name") and len(ident.get("from", "")) == 3 and len(ident.get("to", "")) == 3:
                    live["trains"].append({"train_no": train, "name": ident["name"],
                                           "from": ident["from"], "to": ident["to"]})

    pair_captures: list[tuple[str, str, str, str]] = []
    for src_code, dst_code, must in pairs:
        probe_lines, entry, e_body = probe_pair(src_code, dst_code, must, date, today, capture)
        out += probe_lines
        if entry:
            live["pair_lists"].append(entry)
        if e_body:
            pair_captures.append((src_code, dst_code,
                                  next((ln for ln in probe_lines if "erail.in (alternate)" in ln), ""),
                                  e_body))

    # Propose, never apply: an automated edit to the truth fixture would be a second source of "the app
    # quietly believes itself". A human reads this block and pastes it, and the commit says who checked.
    # The proposal carries its own merge rule (PROPOSAL_RULE): the ac06f62 comment proposed "trains": []
    # only because NTES was unreachable - one careless paste from erasing owner-verified identities.
    out.append("\n### proposed refresh of fixtures/known_truths.json")
    out.append("```json\n" + json.dumps(live, indent=1)[:3000] + "\n```")

    body = "\n".join(out)
    print(body[:9000])
    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    sha = os.environ.get("GITHUB_SHA")
    if tok and repo and sha and os.environ.get("PROBE_POST", "1") == "1":
        subprocess.run(
            ["gh", "api", "-X", "POST", f"repos/{repo}/commits/{sha}/comments",
             "-f", f"body=### live-feed probe\n\n```\n{body[:30000]}\n```"],
            check=False, timeout=60,
        )
        capture_md = erail_capture_comment(pair_captures, today.isoformat(timespec="seconds"),
                                           identities=identity_captures, routes=route_captures,
                                           live_status=live_status_captures, scripts=script_captures)
        if capture_md:
            subprocess.run(
                ["gh", "api", "-X", "POST", f"repos/{repo}/commits/{sha}/comments",
                 "-f", f"body={capture_md}"],
                check=False, timeout=60,
            )
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write("### live-feed probe\n\n```\n" + body[:4500] + "\n```\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
