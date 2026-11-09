#!/usr/bin/env python3
"""OFFLINE proof of concept: *supplied screen text* -> schematic rail fraction.

This script DOES NOT open, refresh, inspect, or read the Where is my Train app.
It makes no network requests. A human/test fixture must supply the text and a
capture time. The distance is a claim copied from that screen, not GPS/telemetry.
Never write the result into a train's Actual column or call it a live fix.
"""

import argparse
import datetime as dt
import json
import re
import statistics
import time
from dataclasses import asdict, dataclass

# Accept an explicit station CODE only; guessed names/ambiguous OCR are not positions.
DISTANCE = re.compile(
    r"(?<![\w.\-])(?P<km>\d{1,3}(?:\.\d{1,2})?)\s*km\s+to\s+"
    r"(?P<next>[A-Z][A-Z0-9]{1,4})(?![\w])", re.IGNORECASE
)


class Rejected(ValueError):
    """Screen observation cannot safely be put on this route."""


@dataclass(frozen=True)
class Point:
    code: str
    km: float


@dataclass(frozen=True)
class ScreenProjection:
    train: str
    previous: str
    next: str
    km_remaining_as_displayed: float
    map_leg_km: float
    schematic_fraction: float
    captured_at: str
    source: str = "user-supplied WIMT screen text; unverified"
    position_kind: str = "schematic; not GPS, an actual event or an automatic live feed"


def parse_points(spec: str) -> list[Point]:
    """Caller-supplied *validated* ordered map, not a guessed route."""
    try:
        points = [Point(code.strip().upper(), float(km)) for token in spec.split(",")
                  for code, km in [token.split(":")]]
    except ValueError as exc:
        raise Rejected("expected ordered map codes and km: RRB:211,SPV:215") from exc
    if (len(points) < 2 or len({p.code for p in points}) != len(points)
            or any(not re.fullmatch(r"[A-Z][A-Z0-9]{1,4}", p.code) for p in points)
            or any(not 0 <= p.km < 10000 for p in points)
            or any(b.km <= a.km for a, b in zip(points, points[1:]))):
        raise Rejected("route must contain distinct ordered codes with increasing finite km")
    return points


def project(text: str, train: str, previous: str, points: list[Point],
            captured_at: dt.datetime, now: dt.datetime) -> ScreenProjection:
    if not re.fullmatch(r"\d{4,5}", train):
        raise Rejected("invalid train number")
    if not re.search(r"(?<!\d)" + re.escape(train) + r"(?!\d)", text):
        raise Rejected("screen text does not identify the selected train")
    if captured_at.tzinfo is None or now.tzinfo is None:
        raise Rejected("capture time and now must include a timezone")
    age = (now - captured_at).total_seconds()
    if age < -10 or age > 120:
        raise Rejected("screen capture is future-dated or older than 2 minutes")
    matches = list(DISTANCE.finditer(text))
    if len(matches) != 1:
        raise Rejected("need exactly one readable 'N km to CODE' on the screen")
    match = matches[0]
    previous = previous.upper()
    next_code = match.group("next").upper()
    starts = [i for i, p in enumerate(points[:-1])
              if p.code == previous and points[i + 1].code == next_code]
    if len(starts) != 1:
        raise Rejected("next screen code is not adjacent to previous on the validated route")
    leg = points[starts[0] + 1].km - points[starts[0]].km
    remaining = float(match.group("km"))
    if remaining > leg:
        raise Rejected("screen's remaining km exceeds the validated leg")
    return ScreenProjection(train, previous, next_code, remaining, leg,
                            round((leg - remaining) / leg, 6),
                            captured_at.isoformat())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--text", required=True, help="User-provided visible screen text; no app access")
    parser.add_argument("--train", required=True, help="Expected train number")
    parser.add_argument("--previous", required=True, help="Validated immediately previous station code")
    parser.add_argument("--points", required=True, help="Ordered independently validated map, e.g. RRB:211,SPV:215")
    parser.add_argument("--captured-at", required=True, help="ISO-8601 time the screen was captured, with timezone")
    parser.add_argument("--now", help="Override clock for a reproducible offline demo only")
    parser.add_argument("--benchmark", type=int, default=0, help="Repeat parser; excludes app refresh, screen capture and OCR")
    args = parser.parse_args()
    start = time.perf_counter_ns()
    try:
        captured = dt.datetime.fromisoformat(args.captured_at)
        now = dt.datetime.fromisoformat(args.now) if args.now else dt.datetime.now(dt.timezone.utc)
        points = parse_points(args.points)
        result = project(args.text, args.train, args.previous, points, captured, now)
    except (ValueError, Rejected) as exc:
        parser.exit(2, f"REJECTED: {exc}\n")
    elapsed_ms = (time.perf_counter_ns() - start) / 1_000_000
    output = asdict(result)
    output["local_parsing_ms"] = round(elapsed_ms, 3)
    if args.benchmark > 0:
        if args.benchmark > 100_000:
            parser.error("benchmark maximum is 100000")
        samples = []
        for _ in range(args.benchmark):
            tick = time.perf_counter_ns()
            project(args.text, args.train, args.previous, points, captured, now)
            samples.append((time.perf_counter_ns() - tick) / 1_000_000)
        output["parser_only_median_ms"] = round(statistics.median(samples), 4)
        output["parser_only_p95_ms"] = round(sorted(samples)[int(.95 * (len(samples) - 1))], 4)
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
