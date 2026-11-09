#!/usr/bin/env python3
"""
OpenTrack India — reference database ETL.

Builds the SQLite file that ships inside the APK (`app/src/main/assets/ref/refdb.dbgz`).
Everything the app must answer *with no network at all* comes from here:

  * station resolution from free text (code / name / initials)
  * "which trains connect A and B" for a -24h..+24h window on a chosen date
  * the scheduled timetable of any train, with per-stop and per-segment distances
  * day-of-week running flags, so "does it run on this date" is decidable offline

Design decisions that shape the output
--------------------------------------
1. **Times are normalised to a monotonic minute axis.** The upstream dumps cannot be consumed
   as-is: ordering is unreliable (a few hundred trains, e.g. 55034 Gonda-Sitapur Passenger, are
   stored *reversed*), the `day` column is inconsistent, and ~6% of rows have no usable times.
   We repair all three at build time (`plan_stops`), because an unordered stop list makes the live
   marker move backwards - the most visible way a tracker can lose user trust.
2. **Halts are the default profile (the "light" decision).** Keeping every stop costs 36 MB of
   SQLite (~17 MB gzipped here) for 4x the rows. The halt-only profile remains
   small, but cannot answer a request for between-halt station names. A small,
   explicitly historical 16227 corridor extracted from `--profile full` is now
   bundled separately; it is checked against the selected official halt list at
   runtime. The whole full profile is NOT the current timetable or shipped asset.
3. **Distances are reconstructed when the source has none.** The primary schedule dump carries
   `distance = 0` at almost every stop, but the EXP cut carries real per-stop km, so we prefer
   those and otherwise sum geodesic legs and rescale to the train's authoritative total
   (validated over 3,554 trains: geodesic/official ratio median 0.97, p10 0.92, p90 1.01).
   Progress percentage and "km remaining" are therefore credible, not decorative.
4. **Binary SQLite, gzipped - not a SQL text dump.** gzip(SQL text) for this dataset is 15.8 MB,
   i.e. *larger* than gzip(SQLite binary), because page headers and b-tree structure compress
   better than literal INSERT statements. We emit a real SQLite file, `VACUUM` it, gzip it, and the
   app streams it out of assets once at first launch.
5. **Multiple source dumps, merged by role.** No single free dump is complete: the primary cut has
   every train but no running-days; EXP-TRAINS has running-days and km but 2.5k trains; a third
   repo carries running-days for the primary set. Roles make "add a source" a data change rather
   than a code change, which is how we survive a schedule refresh breaking format.
6. **Every hot query must be index-served.** `--verify` (default) materialises the DB, asserts
   `EXPLAIN QUERY PLAN` for the exact SQL the app issues, and sanity-checks known trains. A bad
   schedule refresh fails the build instead of shipping.

Usage:
    python3 tools/build_refdb.py                       # -> app/src/main/assets/ref/refdb.dbgz
    python3 tools/build_refdb.py --profile full --skip-assets
    python3 tools/build_refdb.py --no-clone            # reuse .cache/raildata (offline)
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Iterable

# --------------------------------------------------------------------------- configuration

# role semantics:
#   primary    -> station master + train master + every train's stop list (authoritative route set)
#   km_source  -> authoritative per-stop distance / running days for trains already in primary
#   extra      -> whole trains (with their own route) for numbers primary does not cover
#   run_days   -> running-day bitmap keyed by train number, applied as an overlay
SOURCES = [
    {"role": "primary", "repo": "Spin1234/IndianRailwayOpenReference.github.io",
     "files": ["schedules.json", "stations.json", "trains.json"]},
    {"role": "extra", "repo": "Spin1234/IndianRailwayOpenReference.github.io",
     "files": ["EXP-TRAINS.json"]},
    {"role": "run_days", "repo": "prasenjit-27/Indian-Railway-Data",
     "files": ["trains.json"]},
]
SCHEMA_VERSION = 3
EPOCH_ENV = "SOURCE_DATE_EPOCH"
DAYS = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"]
BACKWARD_TOL_MIN = 5           # sources round to the minute; tolerate that much jitter
MAX_RUN_DAYS = 20              # longest plausible single run, in days
ASSET_SOFT_LIMIT_MB = 6.0      # warn (not fail) if the gzipped asset would break the APK budget
ENDPOINT_PAT = re.compile(r"^(?P<name>.*?)\s*-\s*(?P<code>[A-Z0-9]{2,6})$")


# Filled by the source loader: the commit date at the tip of each upstream clone. Recorded in `meta` as
# `source_vintage`, because the app has to tell a traveller how old their timetable *is* - and the ETL's own
# build stamp answers a different question (when we repacked it), which is not the one anyone asks.
SOURCE_VINTAGES: dict[str, str] = {}


def git_head_date(dest: str) -> str:
    """`yyyy-mm-dd` of the clone's HEAD commit, or "" if git will not say."""
    r = subprocess.run(["git", "-C", dest, "log", "-1", "--format=%cd", "--date=short"],
                       capture_output=True, text=True)
    return (r.stdout or "").strip()


def built_at() -> str:
    """Timestamp for the manifest/meta.

    SOURCE_DATE_EPOCH is honoured so a CI rebuild is byte-for-byte identical to the artefact whose
    sha256 we publish: with a wall-clock timestamp baked into `meta`, every rebuild changes the
    checksum and the download-verify step becomes useless.
    """
    e = os.environ.get(EPOCH_ENV)
    if e and e.isdigit():
        return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(int(e)))
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def log(msg: str) -> None:
    print(f"[etl] {msg}", flush=True)


# --------------------------------------------------------------------------- small utilities


def _safe_day(value: Any) -> int:
    try:
        return max(1, int(value or 1))
    except (TypeError, ValueError):
        return 1


def norm_time(value: Any) -> int | None:
    """'HH:MM[:SS]' | 'HHMM' | minutes -> minutes since midnight. Junk/None -> None."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        v = int(value)
        if v > 1440:
            v = (v // 100) * 60 + v % 100
        return v % 1440
    s = str(value).strip()
    if not s or s.lower() in {"none", "null", "-", "--:--", "source", "destination"}:
        return None
    m = re.match(r"^(\d{1,2}):(\d{2})", s) or re.match(r"^(\d{3,4})$", s)
    if not m:
        return None
    if ":" in s:
        hh, mm = int(m.group(1)), int(m.group(2))
    else:
        v = int(m.group(1))
        hh, mm = v // 100, v % 100
    if hh > 23:
        hh -= 24
    return (hh * 60 + mm) % 1440


def norm_km(value: Any) -> int | None:
    """'1 187 kms' | '25 kms' | 47 | '' -> km, else None."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return int(value)
    m = re.search(r"([0-9][0-9,\.]*)", str(value))
    if not m:
        return None
    try:
        return int(round(float(m.group(1).replace(",", ""))))
    except ValueError:
        return None


def norm_name(value: str) -> str:
    """ASCII-fold, upper-case, strip punctuation, collapse whitespace -> comparable token base."""
    if not value:
        return ""
    s = unicodedata.normalize("NFKD", value)
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = re.sub(r"[^A-Z0-9 ]+", " ", s.upper())
    return re.sub(r"\s+", " ", s).strip()


DROP_WORDS = {"JUNCTION", "JN", "CITY", "CANTT", "CANTONMENT", "TERM", "TERMINUS", "MAIN",
              "ROAD", "RD", "EXPRESS", "SF", "SPL", "SPECIAL", "PASS", "PASSENGER", "MEMU",
              "DEMU", "LOCAL", "H", "NG", "MG", "BG", "AC", "SUPERFAST", "SUPERFAST", "SUPERFAST"}


def name_tokens(value: str) -> list[str]:
    toks = [t for t in norm_name(value).split(" ") if len(t) >= 2]
    return [t for t in toks if t not in DROP_WORDS] or toks


def initials_of(value: str) -> str:
    toks = name_tokens(value)
    return "".join(t[0] for t in toks)[:6] if toks else ""


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r, p = 6371.0088, math.pi / 180.0
    dlat, dlon = (lat2 - lat1) * p, (lon2 - lon1) * p
    a = (math.sin(dlat / 2) ** 2
         + math.cos(lat1 * p) * math.cos(lat2 * p) * math.sin(dlon / 2) ** 2)
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def run_days_bitmap(running_days: Any) -> int | None:
    """Bit i == runs on weekday i, i=0 for Sunday. Returns None when the source says nothing, so
    callers can distinguish 'daily' from 'unknown' instead of silently assuming."""
    if not isinstance(running_days, dict) or not running_days:
        return None
    bits = 0
    for i, day in enumerate(DAYS):
        if running_days.get(day):
            bits |= 1 << i
    return bits if bits else None


# --------------------------------------------------------------------------- stop ordering


@dataclass
class RawStop:
    code: str
    arr: int | None
    dep: int | None
    day: int
    km: int | None = None


@dataclass
class StopPlan:
    code: str
    arr: int | None
    dep: int | None
    day: int
    # `None` means "the dump did not say". It must not be 0: 0 was indistinguishable from a real
    # reading, and the cumulative-distance step below trusted it, which put 0 km on all 141 908 stop
    # rows and silently deleted every distance in the app - the KM column, the progress bar and the
    # chart's vertical axis. Found by a phone screenshot, not by CI, because no query was asserting it.
    km: int | None = None
    halt: bool = False


@dataclass
class TrainModel:
    no: str
    name: str
    src: str
    dst: str
    train_type: str | None = None
    zone: str | None = None
    return_no: str | None = None
    official_km: int | None = None
    official_min: int | None = None
    run_days: int | None = None
    stops: list[RawStop] = field(default_factory=list)
    source_role: str = "primary"


def plan_stops(parsed: list[RawStop], halts_only: bool,
               endpoints: tuple[str, str] | None = None) -> tuple[list[StopPlan], dict[str, int]]:
    """Repair ordering/day semantics, then return stops on a monotonic minute axis.

    Upstream dumps are not safe to consume as-is:
      * ordering is unreliable - most trains list stops in travel order keyed by `id`, but a few
        hundred (e.g. 55034 Gonda-Sitapur Passenger) are stored reversed;
      * `day` is unreliable - reversed rows often all claim day 1;
      * some pass-through rows carry no times at all.

    Algorithm
      1. wall minute = min(arrival, departure); the source row has only departure and the
         destination row only arrival, hence the min();
      2. choose dump order or a stable ascending wall-time sort, whichever accumulates less forward
         time (wrap-aware, modulo 1440). The sort is adopted only when the timespan fits a single
         run (<20h), so legitimate midnight-crossing trains are never touched;
      3. unwrap onto an absolute axis: whole-day jumps forward, <=BACKWARD_TOL_MIN jitter clamped,
         anything else dropped and counted;
      4. enforce arrival <= departure within a stop; the halts profile keeps `dep > arr` rows;
      5. `endpoints` are pinned back in even when they fail the halt test - that is exactly how the
         dumps encode origin/destination (no arrival at source, no departure at destination), so a
         naive halt filter silently deletes the two most important stops of every train.
    """
    stats: dict[str, int] = defaultdict(int)

    def wall(it: RawStop) -> int | None:
        if it.arr is not None and it.dep is not None:
            return min(it.arr, it.dep)
        return it.arr if it.arr is not None else it.dep

    timed = [i for i, it in enumerate(parsed) if wall(it) is not None]
    stats["dropped_no_time"] = len(parsed) - len(timed)
    if len(timed) < 2:
        return [], stats

    def forward_cost(order: list[int]) -> int:
        cost = 0
        for a, b in zip(order, order[1:]):
            d = wall(parsed[b]) - wall(parsed[a])
            cost += d if d >= 0 else d + 1440
        return cost

    ascending = sorted(timed, key=lambda i: (wall(parsed[i]), i))
    span = max(wall(parsed[i]) for i in timed) - min(wall(parsed[i]) for i in timed)
    reorder = forward_cost(ascending) < forward_cost(timed) and span < 20 * 60
    if reorder:
        stats["reordered"] = 1

    out: list[StopPlan] = []
    prev: int | None = None

    def place(x: int | None) -> int | None:
        """Push a wall time onto the monotonic axis (day jumps forward, small jitter clamped)."""
        if x is None or prev is None:
            return x
        while x < prev - BACKWARD_TOL_MIN:
            x += 1440
        return prev if x < prev else x

    for i in (ascending if reorder else timed):
        it = parsed[i]
        arr, dep = place(it.arr), place(it.dep)
        if arr is None and dep is None:
            stats["dropped_no_time"] += 1
            continue
        if arr is None:
            arr = dep
        if dep is None or dep < arr:
            dep = arr
        if dep is None or dep > MAX_RUN_DAYS * 1440:
            stats["dropped_out_of_range"] += 1
            continue
        halt = arr is not None and dep is not None and dep > arr
        pinned = bool(endpoints) and it.code in (endpoints[0], endpoints[1])
        if halts_only and not halt and not pinned:
            stats["dropped_non_halt"] += 1
            prev = dep
            continue
        out.append(StopPlan(code=it.code, arr=arr, dep=dep, day=1 + dep // 1440, halt=halt,
                             km=it.km))
        prev = dep
    if len(out) < 2:
        stats["dropped_unusable_train"] = len(out)
        return [], stats
    if out[0].dep is None:
        out[0].dep = out[0].arr
    if out[-1].arr is None:
        out[-1].arr = out[-1].dep
    return out, stats


# --------------------------------------------------------------------------- source loading


def needed_files_by_repo() -> dict[str, list[str]]:
    """Union of files per repo, so one clone/sparse-set serves every role that reads from it."""
    acc: dict[str, list[str]] = defaultdict(list)
    for spec in SOURCES:
        for f in spec["files"]:
            if f not in acc[spec["repo"]]:
                acc[spec["repo"]].append(f)
    return dict(acc)


def fetch_sources(cache_dir: str, allow_clone: bool) -> dict[str, dict[str, str]]:
    """Ensure every declared source file exists locally; return {repo: {basename: abspath}}.

    Network policy: we only ever `git clone` from github.com - reproducible, identical in CI, and
    nothing is uploaded anywhere. Clones are depth-1 + sparse so we pull the handful of JSON files
    we need instead of a repo's history.
    """
    os.makedirs(cache_dir, exist_ok=True)
    out: dict[str, dict[str, str]] = {}
    for repo, files in needed_files_by_repo().items():
        dest = os.path.join(cache_dir, repo.replace("/", "__"))
        want = sorted(files)
        missing = [f for f in want if not os.path.isfile(os.path.join(dest, f))]
        if missing:
            if not allow_clone:
                raise SystemExit(f"[etl] missing {missing} in {dest} and --no-clone was set")
            log(f"cloning {repo} (depth 1, sparse: {want})")
            if os.path.isdir(os.path.join(dest, ".git")):
                # already cloned for another role: widen the sparse cone, do not re-clone
                subprocess.run(["git", "-C", dest, "sparse-checkout", "set", "--no-cone",
                                *["/" + f for f in want], *["!" + f for f in []]],
                              capture_output=True, text=True)
                subprocess.run(["git", "-C", dest, "checkout", "HEAD", "--", *want],
                              capture_output=True, text=True)
            else:
                subprocess.run(["rm", "-rf", dest], capture_output=True, text=True)
                subprocess.run(["git", "clone", "--depth", "1", "--filter=blob:none", "--sparse",
                               f"https://github.com/{repo}.git", dest],
                              check=True, capture_output=True, text=True)
                subprocess.run(["git", "-C", dest, "sparse-checkout", "set", "--no-cone",
                               *["/" + f for f in want]],
                              check=True, capture_output=True, text=True)
            still = [f for f in want if not os.path.isfile(os.path.join(dest, f))]
            if still:
                raise SystemExit(f"[etl] source still missing after clone: {repo} {still}")
        out[repo] = {f: os.path.join(dest, f) for f in want}
        SOURCE_VINTAGES[repo] = git_head_date(dest)
        log(f"{repo}: upstream data last revised {SOURCE_VINTAGES[repo] or 'unknown'}")
    return out


def load_stations(features: list[dict]) -> dict[str, dict[str, Any]]:
    stations: dict[str, dict[str, Any]] = {}
    for f in features:
        p = f.get("properties") or {}
        code = (p.get("code") or "").strip().upper()
        if not code:
            continue
        coords = ((f.get("geometry") or {}).get("coordinates")) or [None, None]
        lon, lat = (list(coords) + [None, None])[:2]
        name = (p.get("name") or "").strip()
        stations[code] = {"code": code, "name": name,
                          "state": (p.get("state") or "").strip(), "zone": (p.get("zone") or "").strip(),
                          "lat": lat if isinstance(lat, (int, float)) else None,
                          "lon": lon if isinstance(lon, (int, float)) else None,
                          "tok": norm_name(name), "ini": initials_of(name)}
    return stations


def load_primary(by_repo: dict[str, dict[str, str]], stats: dict[str, int]) -> dict[str, TrainModel]:
    """schedules.json (stop rows) + trains.json (GeoJSON train master) -> TrainModel per train."""
    repo = SOURCES[0]["repo"]
    schedules = json.load(open(by_repo[repo]["schedules.json"], encoding="utf-8"))
    trains_gj = json.load(open(by_repo[repo]["trains.json"], encoding="utf-8"))["features"]
    props_by_no = {(f.get("properties") or {}).get("number", ""): f.get("properties") or {}
                   for f in trains_gj}
    log(f"  primary: schedule rows={len(schedules)} train master={len(props_by_no)}")

    grouped: dict[str, list[dict]] = defaultdict(list)
    for r in schedules:
        num = str(r.get("train_number") or "").strip()
        code = str(r.get("station_code") or "").strip().upper()
        if num and code:
            grouped[num].append(r)

    models: dict[str, TrainModel] = {}
    for num, rows in grouped.items():
        rows = sorted(rows, key=lambda r: r.get("id") if isinstance(r.get("id"), int) else 0)
        p = props_by_no.get(num, {})
        stops = [RawStop(str(r["station_code"]).upper(), norm_time(r.get("arrival")),
                        norm_time(r.get("departure")), _safe_day(r.get("day")),
                        norm_km(r.get("distance"))) for r in rows]
        models[num] = TrainModel(
            no=num,
            name=str(p.get("name") or rows[0].get("train_name") or f"TRAIN {num}").strip().upper(),
            src=str(p.get("from_station_code") or stops[0].code).upper(),
            dst=str(p.get("to_station_code") or stops[-1].code).upper(),
            train_type=str(p.get("type") or "").strip() or None,
            zone=str(p.get("zone") or "").strip() or None,
            return_no=str(p.get("return_train") or "").strip() or None,
            official_km=norm_km(p.get("distance")),
            official_min=(int(p["duration_m"]) if isinstance(p.get("duration_m"), (int, float)) else None),
            stops=stops)
        stats["primary_trains"] += 1
    return models


def load_extra(by_repo: dict[str, dict[str, str]], stats: dict[str, int],
               existing: dict[str, TrainModel]) -> None:
    """EXP-TRAINS.json: whole trains (route + km + running days) for numbers we do not have.

    Format differs from the primary dump on purpose (`"MARWAR JN - MJ"` with `"25 kms"` strings), so
    the parser splits code out of the label and reads km from the stop row. We only *add* missing
    trains: the primary cut is more current for stops it does cover, and silently merging two route
    lists for the same number would create a timetable that matches neither.
    """
    repo = next(s["repo"] for s in SOURCES if s["role"] == "extra")
    data = json.load(open(by_repo[repo]["EXP-TRAINS.json"], encoding="utf-8"))
    for t in data:
        num = str(t.get("trainNumber") or "").strip()
        if not num or num in existing:
            stats["extra_already_covered"] += 1
            continue
        stops: list[RawStop] = []
        for r in (t.get("trainRoute") or []):
            label = str(r.get("stationName") or "").strip().upper()
            m = ENDPOINT_PAT.match(label)
            code = (m.group("code") if m else "").strip()
            if not code:
                continue
            stops.append(RawStop(code, norm_time(r.get("arrives")), norm_time(r.get("departs")),
                                 _safe_day(r.get("day")), norm_km(r.get("distance"))))
        if len(stops) < 2:
            stats["extra_dropped"] += 1
            continue
        total = next((s.km for s in reversed(stops) if s.km), None)
        existing[num] = TrainModel(
            no=num, name=str(t.get("trainName") or f"TRAIN {num}").strip().upper(),
            src=stops[0].code, dst=stops[-1].code, train_type=None, zone=None, return_no=None,
            official_km=total,
            official_min=None,
            run_days=run_days_bitmap(t.get("runningDays")),
            stops=stops, source_role="extra")
        stats["extra_trains"] += 1
    log(f"  extra: +{stats['extra_trains']} trains "
        f"({stats['extra_already_covered']} already covered, {stats['extra_dropped']} unusable)")


def apply_run_days(by_repo: dict[str, dict[str, str]], stats: dict[str, int],
                   models: dict[str, TrainModel]) -> None:
    """Overlay weekday flags from a dump that carries them. Only fills gaps - never overwrites."""
    spec = next((s for s in SOURCES if s["role"] == "run_days"), None)
    if not spec:
        return
    try:
        data = json.load(open(by_repo[spec["repo"]]["trains.json"], encoding="utf-8"))
    except (KeyError, FileNotFoundError):
        log("  run_days source unavailable; leaving flags unknown (app falls back to 'daily?')")
        return
    for t in data:
        num = str(t.get("trainNumber") or "").strip()
        m = models.get(num)
        if not m or m.run_days is not None:
            continue
        bits = run_days_bitmap(t.get("runningDays"))
        if bits is not None:
            m.run_days = bits
            stats["run_days_overlay"] += 1
    log(f"  run_days overlay: filled {stats['run_days_overlay']} trains")


# --------------------------------------------------------------------------- DB writing


DDL = """
PRAGMA page_size=4096;
CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID;

CREATE TABLE stations(
  code TEXT PRIMARY KEY, name TEXT NOT NULL, state TEXT, zone TEXT,
  lat REAL, lon REAL, search_token TEXT NOT NULL, initials TEXT, rank INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_stations_token ON stations(search_token);

CREATE TABLE trains(
  train_no TEXT PRIMARY KEY, name TEXT NOT NULL, search_token TEXT NOT NULL,
  src_code TEXT NOT NULL, dst_code TEXT NOT NULL, train_type TEXT,
  run_days INTEGER, total_km INTEGER, total_min INTEGER, return_no TEXT, zone TEXT,
  num_stops INTEGER NOT NULL DEFAULT 0, num_halts INTEGER NOT NULL DEFAULT 0,
  dep_hour INTEGER, arr_hour INTEGER, origin TEXT NOT NULL DEFAULT 'ref'
);
CREATE INDEX idx_trains_token ON trains(search_token);

-- Scheduled stops on a repaired minute axis: minutes from the origin's calendar midnight, so day
-- rollover is already folded in and values are strictly non-decreasing per train. `journey_day`
-- keeps the day offset for display.
CREATE TABLE stops(
  train_no TEXT NOT NULL, seq INTEGER NOT NULL, station_code TEXT NOT NULL,
  arrival_min INTEGER, departure_min INTEGER, journey_day INTEGER NOT NULL DEFAULT 1,
  sched_km INTEGER NOT NULL DEFAULT 0, is_halt INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(train_no, seq)
) WITHOUT ROWID;
CREATE INDEX idx_stops_station ON stops(station_code, train_no, seq);

-- Leg between stop `seq` and `seq+1`; the position engine integrates along these.
CREATE TABLE segments(
  train_no TEXT NOT NULL, seq INTEGER NOT NULL, from_code TEXT NOT NULL, to_code TEXT NOT NULL,
  leg_km INTEGER NOT NULL DEFAULT 0, leg_min INTEGER,
  PRIMARY KEY(train_no, seq)
) WITHOUT ROWID;

-- Materialised search index: one seek answers "trains touching this station".
CREATE TABLE station_trains(
  station_code TEXT NOT NULL, train_no TEXT NOT NULL, seq INTEGER NOT NULL,
  departure_min INTEGER, arrival_min INTEGER, journey_day INTEGER NOT NULL DEFAULT 1,
  is_src INTEGER NOT NULL DEFAULT 0, is_dst INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(station_code, train_no, seq)
) WITHOUT ROWID;
"""


def write_db(db_path: str, stations: dict[str, dict[str, Any]], models: dict[str, TrainModel],
             halts_only: bool, profile: str, stats: dict[str, int]
             ) -> dict[str, list[list[float]]]:
    if os.path.exists(db_path):
        os.remove(db_path)
    db = sqlite3.connect(db_path)
    db.executescript(DDL)
    for c, s in stations.items():
        db.execute("INSERT INTO stations(code,name,state,zone,lat,lon,search_token,initials,rank) "
                   "VALUES(?,?,?,?,?,?,?,?,0)",
                   (c, s["name"] or c, s["state"] or None, s["zone"] or None, s["lat"], s["lon"],
                    s["tok"] or norm_name(s["name"]), s["ini"] or None))

    geo: dict[str, list[list[float]]] = {}
    for num in sorted(models):
        m = models[num]
        plan, s = plan_stops(m.stops, halts_only, (m.src, m.dst))
        if len(plan) < 2 and halts_only:
            # Suburban/MTS services halt ~everywhere but publish identical arr/dep minutes, so the
            # halt filter can empty them out. A train vanishing from offline search is a worse
            # failure than a few extra rows: fall back to every timed stop for just this train.
            plan, s2 = plan_stops(m.stops, False, (m.src, m.dst))
            stats["trains_halt_fallback"] += 1
            s = {**s, "dropped_no_time": s2.get("dropped_no_time", 0)}
        for k, v in s.items():
            stats[k] = stats.get(k, 0) + v
        if len(plan) < 2:
            stats["trains_dropped_unusable"] += 1
            continue
        stats["trains"] += 1
        stats["stops"] += len(plan)

        # ---- cumulative distance: trust the source's km when present, else geodesic rescaled
        known = [p.km for p in plan if p.km]
        # Authoritative per-stop distance must be positive, cover most of the *gaps* (a train with two
        # numbers for 90 stops is not coverage), and land on a plausible total. Anything else is treated
        # as "not stated", which is the honest reading of a 0.
        use_source_km = (len(known) >= max(2, int(0.6 * (len(plan) - 1)))
                         and (plan[-1].km or 0) > 25)
        if use_source_km:
            run = 0
            cum_km = []
            for p in plan:
                if p.km:
                    run = max(run, p.km)
                cum_km.append(run)
        else:
            legs: list[float] = []
            for i in range(1, len(plan)):
                a, b = stations.get(plan[i - 1].code), stations.get(plan[i].code)
                if a and b and a["lat"] is not None and b["lat"] is not None:
                    legs.append(haversine_km(a["lat"], a["lon"], b["lat"], b["lon"]))
                else:
                    legs.append(legs[-1] if legs else 1.0)
            geo_total = sum(legs)
            scale = (m.official_km / geo_total) if (m.official_km or 0) > 25 and geo_total > 0 else 1.0
            cum, cum_km = 0.0, [0]
            for leg in legs:
                cum += leg * scale
                cum_km.append(max(cum_km[-1], int(round(cum))))
            if m.official_km and cum_km[-1]:
                # make the final stop land exactly on the published total
                f = m.official_km / cum_km[-1]
                cum_km = [max(cum_km[i - 1], int(round(c * f))) if i else 0 for i, c in enumerate(cum_km)]
                cum_km[-1] = max(cum_km[-2], m.official_km)
        total_km = cum_km[-1] or m.official_km or None

        start = plan[0].dep if plan[0].dep is not None else plan[0].arr
        end = plan[-1].arr if plan[-1].arr is not None else plan[-1].dep
        total_min = (end - start) if (start is not None and end is not None and end > start) else None
        if total_min is None or total_min > MAX_RUN_DAYS * 1440:
            total_min = m.official_min or total_min
        if m.run_days is None:
            stats["run_days_unknown"] += 1
        halts = sum(1 for p in plan if p.halt)
        db.execute("INSERT INTO trains(train_no,name,search_token,src_code,dst_code,train_type,"
                   "run_days,total_km,total_min,return_no,zone,num_stops,num_halts,dep_hour,arr_hour,"
                   "origin) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                   (num, m.name, norm_name(m.name), m.src, m.dst, m.train_type, m.run_days,
                    total_km, total_min, m.return_no, m.zone, len(plan), halts,
                    (start // 60) % 24 if start is not None else None,
                    (end // 60) % 24 if end is not None else None, m.source_role))
        for i, p in enumerate(plan):
            db.execute("INSERT INTO stops VALUES(?,?,?,?,?,?,?,?)",
                       (num, i + 1, p.code, p.arr, p.dep, p.day, cum_km[i], 1 if p.halt else 0))
            db.execute("INSERT OR REPLACE INTO station_trains VALUES(?,?,?,?,?,?,?,?)",
                       (p.code, num, i + 1, p.dep, p.arr, p.day,
                        1 if p.code == m.src else 0, 1 if p.code == m.dst else 0))
            stats["index_rows"] += 1
        for i, p in enumerate(plan[1:], start=1):
            a, d = p.arr, plan[i - 1].dep
            if a is None:
                a = p.dep
            if d is None:
                d = plan[i - 1].arr
            leg_min = max(0, a - d) if (a is not None and d is not None) else None
            db.execute("INSERT INTO segments VALUES(?,?,?,?,?,?)",
                       (num, i, plan[i - 1].code, p.code,
                        max(0, cum_km[i] - cum_km[i - 1]), leg_min))
            stats["segments"] += 1
        coords = []
        for p in plan:
            stn = stations.get(p.code)
            if stn and stn["lat"] is not None and stn["lon"] is not None:
                coords.append([round(stn["lon"], 4), round(stn["lat"], 4)])
        if len(coords) >= 2:
            geo[num] = coords

    # ---- distance integrity. These are assertions about *non-degeneracy*, which the existing
    # "decreasing cumulative distance" check could never make: an all-zero column is perfectly
    # monotonic. A metric that ships as 0 is worse than one that is missing, because the UI has no
    # reason to doubt it.
    km_bad = db.execute("SELECT COUNT(*) FROM stops WHERE sched_km = 0 AND seq > 1").fetchone()[0]
    km_total_bad = db.execute(
        "SELECT COUNT(*) FROM trains t JOIN stops s ON s.train_no = t.train_no AND s.seq = t.num_stops"
        " WHERE IFNULL(t.total_km,0) > 0 AND ABS(s.sched_km - t.total_km) > 2").fetchone()[0]
    log(f"sanity: stops at 0 km beyond the origin = {km_bad}; trains whose last stop != total_km = {km_total_bad}")
    if km_total_bad:
        raise SystemExit(f"[etl] FAIL: {km_total_bad} trains whose stop distances do not sum to the "
                         "published total_km - the cumulative-distance step is broken")
    stop_rows = db.execute("SELECT COUNT(*) FROM stops").fetchone()[0]
    if stop_rows and km_bad * 20 > stop_rows:
        raise SystemExit(f"[etl] FAIL: {km_bad}/{stop_rows} stops have no distance - the fallback to "
                         "geodesic rescaling is not firing")

    # ---- reality check. Everything else in this file judges the dataset against itself, which is how a
    # wrong train number passed every gate for months: the fixtures it compares against came from the same
    # stale dump. `data_truths` is the one gate whose facts were typed by a human reading a live source.
    # A contradiction is deliberately NOT fatal - a red build leaves the passenger on an older APK, which is
    # worse than a build that admits it is unverified - but the verdict ships inside the asset, so the UI
    # cannot present these times as authoritative.
    trust: dict[str, Any] = {"verdict": "unverified", "problems": ["gate did not run"]}
    try:
        sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "ci"))
        import data_truths
        trust = data_truths.evaluate(db, data_truths.load_truths())
        log(data_truths.report(trust).replace("\n", "\n[etl]   "))
    except Exception as e:  # never let the gate's own breakage read as "the data is fine"
        log(f"data truths: gate failed to run ({e}) - asset marked unverified")
    db.commit()

    db.execute("UPDATE stations SET rank=(SELECT COUNT(*) FROM station_trains t "
               "WHERE t.station_code = stations.code)")
    for k, v in [("schema_version", SCHEMA_VERSION), ("profile", profile),
                 ("built_at", built_at()),
                 # The LEAST fresh upstream date, deliberately. The timetable comes from the primary dump; a
                 # run-days overlay revised two months later does not make the departure times newer, and
                 # reporting max() here would repeat the overstatement the build stamp was guilty of.
                 ("source_vintage", min((v for v in SOURCE_VINTAGES.values() if v), default="")),
                 ("source_vintages", ";".join(f"{k.split('/')[-1]}@{v}" for k, v in sorted(SOURCE_VINTAGES.items()))),
                 ("sources", ";".join(sorted({s["repo"] for s in SOURCES}))),
                 ("data_trust", trust.get("verdict", "unverified")),
                 ("data_truth_problems", "; ".join(trust.get("problems", []))[:1500]),
                 ("station_count", db.execute("SELECT COUNT(*) FROM stations").fetchone()[0]),
                 ("train_count", db.execute("SELECT COUNT(*) FROM trains").fetchone()[0]),
                 ("stop_count", db.execute("SELECT COUNT(*) FROM stops").fetchone()[0]),
                 ("segment_count", db.execute("SELECT COUNT(*) FROM segments").fetchone()[0])]:
        db.execute("INSERT OR REPLACE INTO meta VALUES(?,?)", (k, str(v)))
    db.commit()
    db.execute("VACUUM")
    db.execute("ANALYZE")
    db.commit()
    db.close()
    return geo


# --------------------------------------------------------------------------- verification


VERIFY_SQL: list[tuple[str, str, bool]] = [
    # (label, query, must_be_indexed)
    # `stations` (9k) and `trains` (7k) are small: a scan beats an index seek there, and forcing an
    # index on LIKE '%x%' would only add file weight to the APK. The 100k+ row tables must be served
    # by an index, because they run on a 2 GB RAM Android 8 phone.
    ("station_trains pair search",
     "EXPLAIN QUERY PLAN SELECT DISTINCT s.train_no FROM station_trains s WHERE s.station_code='BCT' "
     "AND s.train_no IN (SELECT train_no FROM station_trains WHERE station_code='NDLS')", True),
    ("stops by train", "EXPLAIN QUERY PLAN SELECT * FROM stops WHERE train_no='12951' ORDER BY seq", True),
    ("segments by train", "EXPLAIN QUERY PLAN SELECT * FROM segments WHERE train_no='12951' ORDER BY seq", True),
    ("station resolve", "EXPLAIN QUERY PLAN SELECT * FROM stations WHERE search_token LIKE 'MAS%' LIMIT 20", False),
    ("train name resolve", "EXPLAIN QUERY PLAN SELECT train_no,name FROM trains WHERE search_token LIKE '%RAJDHANI%' LIMIT 30", False),
]


def verify(db_path: str) -> None:
    """Materialise the built DB and assert the app's hot queries are index-served + sane."""
    db = sqlite3.connect(db_path)
    for label, q, must_index in VERIFY_SQL:
        plan = " | ".join(str(r[-1]) for r in db.execute(q))
        if must_index and "SCAN" in plan.upper():
            raise SystemExit(f"[etl] FAILED: hot query '{label}' is unindexed -> {plan}")
        log(f"plan[{label}] {plan}")

    pair = db.execute("SELECT COUNT(*) FROM (SELECT train_no FROM station_trains WHERE station_code='BCT' "
                      "INTERSECT SELECT train_no FROM station_trains WHERE station_code='NDLS')").fetchone()[0]
    hot = db.execute("SELECT MAX(c) FROM (SELECT COUNT(*) c FROM station_trains GROUP BY station_code)").fetchone()[0]
    log(f"sanity: trains halting at both BCT and NDLS = {pair}; busiest station serves {hot} trains")
    if pair < 3 or hot < 50:
        raise SystemExit(f"[etl] FAILED: search index broken (pair={pair} hot={hot})")

    bad = db.execute("""
      WITH edges AS (SELECT train_no, seq, departure_min,
                      LAG(departure_min) OVER (PARTITION BY train_no ORDER BY seq) pd,
                      arrival_min,
                      LAG(arrival_min) OVER (PARTITION BY train_no ORDER BY seq) pa
                     FROM stops)
      SELECT COUNT(*) FROM edges
      WHERE (pd IS NOT NULL AND departure_min < pd)
         OR (pa IS NOT NULL AND arrival_min IS NOT NULL AND arrival_min < pa)""").fetchone()[0]
    log(f"sanity: stops with backwards time = {bad}")
    if bad:
        raise SystemExit(f"[etl] FAILED: {bad} backwards time edges (schedule axis not monotonic)")

    # equal values are legitimate: a sub-1km leg between adjacent stops rounds away
    bad_km = db.execute("SELECT COUNT(*) FROM (SELECT sched_km, LAG(sched_km) OVER "
                        "(PARTITION BY train_no ORDER BY seq) p FROM stops) "
                        "WHERE p IS NOT NULL AND sched_km<p").fetchone()[0]
    log(f"sanity: decreasing cumulative distance rows = {bad_km}")
    if bad_km:
        raise SystemExit(f"[etl] FAILED: {bad_km} distance monotonicity violations")

    neg = db.execute("SELECT COUNT(*) FROM stops WHERE arrival_min<0 OR departure_min<0").fetchone()[0]
    if neg:
        raise SystemExit(f"[etl] FAILED: {neg} negative axis values")

    cov = db.execute("SELECT COUNT(*) FROM stations WHERE code IN "
                     "(SELECT DISTINCT station_code FROM station_trains)").fetchone()[0]
    tot = db.execute("SELECT COUNT(*) FROM stations").fetchone()[0]
    log(f"coverage: {cov}/{tot} stations reachable from at least one train's halt list")
    if cov < 0.6 * tot:
        raise SystemExit("[etl] FAILED: under 60% of the station master is searchable")

    for num, km_lo, km_hi, mn_lo, mn_hi in [("12951", 1300, 1460, 850, 1100),   # Mumbai Rajdhani
                                            ("12002", 680, 790, 380, 560),        # Bhopal Shatabdi
                                            ("12621", 2050, 2300, 1900, 2400)]:   # TN Express
        r = db.execute("SELECT name,total_km,total_min,num_stops FROM trains WHERE train_no=?",
                       (num,)).fetchone()
        if not r:
            log(f"note: {num} absent from this dump (coverage gap, not corruption)")
            continue
        log(f"sanity: {num} -> {r}")
        if r[1] is not None and not (km_lo <= r[1] <= km_hi):
            raise SystemExit(f"[etl] FAILED: {num} distance {r[1]} outside expected {km_lo}-{km_hi}")
        if r[2] is not None and not (mn_lo <= r[2] <= mn_hi):
            raise SystemExit(f"[etl] FAILED: {num} duration {r[2]} outside expected {mn_lo}-{mn_hi}")
    db.close()


# --------------------------------------------------------------------------- main


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", choices=["halts", "full"], default="halts",
                    help="halts (default, ships in the APK) or full (every stop incl. pass-throughs)")
    ap.add_argument("--build-dir", default="data/ref", help="where the .db / .db.gz / manifest land")
    ap.add_argument("--cache-dir", default=".cache/raildata")
    ap.add_argument("--no-clone", action="store_true", help="reuse the cache dir; fail instead of cloning")
    ap.add_argument("--skip-verify", action="store_true")
    # `.dbgz`, not `.gz`: aapt renames `.gz` assets while packaging, so an asset called refdb.db.gz
    # would not be findable under that name at runtime. See RefDb.ASSET_NAME.
    ap.add_argument("--out", default=None,
                    help="explicit destination for refdb.dbgz (Gradle's syncRefDb uses this); "
                         "default: app/src/main/assets/ref/refdb.dbgz")
    ap.add_argument("--skip-assets", action="store_true",
                    help="do not copy the .db.gz into app assets (data-only build)")
    ap.add_argument("--emit-geojson", action="store_true",
                    help="also write a gzipped per-train route polyline asset (download-only extra)")
    args = ap.parse_args(argv)

    t0 = time.time()
    os.makedirs(args.build_dir, exist_ok=True)
    stats: dict[str, int] = defaultdict(int)
    by_repo = fetch_sources(args.cache_dir, not args.no_clone)

    log("loading sources")
    repo0 = SOURCES[0]["repo"]
    stations = load_stations(json.load(open(by_repo[repo0]["stations.json"], encoding="utf-8"))["features"])
    log(f"  station master: {len(stations)}")
    models = load_primary(by_repo, stats)
    apply_run_days(by_repo, stats, models)
    if any(s["role"] == "extra" for s in SOURCES):
        load_extra(by_repo, stats, models)
    log(f"  merged train set: {len(models)}")

    halts_only = args.profile == "halts"
    db_path = os.path.join(args.build_dir, f"refdb-{args.profile}.db")
    geo = write_db(db_path, stations, models, halts_only, args.profile, stats)
    log(f"built {db_path} in {time.time()-t0:.1f}s stats={dict(stats)}")
    if not args.skip_verify:
        verify(db_path)

    raw = open(db_path, "rb").read()
    gz_path = os.path.join(args.build_dir, f"refdb-{args.profile}.db.gz")
    with gzip.GzipFile(gz_path, "wb", compresslevel=9, mtime=0) as fh:
        fh.write(raw)
    sha = hashlib.sha256(raw).hexdigest()
    manifest = {"profile": args.profile, "schema_version": SCHEMA_VERSION,
                "size_bytes": len(raw), "gz_bytes": os.path.getsize(gz_path), "sha256": sha,
                "stats": dict(stats),
                "built_at": built_at(), "sources": sorted({s["repo"] for s in SOURCES})}
    with open(os.path.join(args.build_dir, f"refdb-{args.profile}-manifest.json"), "w") as fh:
        json.dump(manifest, fh, indent=1)

    target = args.out or os.path.join("app", "src", "main", "assets", "ref", "refdb.dbgz")
    if not args.skip_assets:
        out_dir = os.path.dirname(target) or "."
        os.makedirs(out_dir, exist_ok=True)
        if os.path.abspath(target) != os.path.abspath(gz_path):
            shutil.copyfile(gz_path, target)
        with open(os.path.join(out_dir, "refdb-manifest.json"), "w") as fh:
            json.dump(manifest, fh, indent=1)
        mb = os.path.getsize(target) / 1e6
        log(f"asset: refdb.dbgz = {mb:.2f} MB"
            + ("  <-- OVER APK BUDGET" if mb > ASSET_SOFT_LIMIT_MB else f" (budget {ASSET_SOFT_LIMIT_MB} MB)"))

    if args.emit_geojson:
        blob = json.dumps(geo, separators=(",", ":")).encode()
        gz = gzip.compress(blob, 9)
        gp = os.path.join(args.build_dir, "routes.geo-ref.json.gz")
        with open(gp, "wb") as fh:
            fh.write(gz)
        log(f"route geometry: raw={len(blob)/1e6:.1f}MB gz={len(gz)/1e6:.2f}MB -> {gp}")
    log("done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
