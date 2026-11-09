#!/usr/bin/env python3
"""Known-truths gate: does our offline dataset agree with what the railway actually runs?

Why this exists: on 11 Oct 2026 a passenger compared the app with a live source and found that
`16228` in our dataset was a *different train* (we said SMET->SBC "Shimoga-Bangalore Express", the
railway said TLGP->MYS "Talguppa Mysuru Express"), that `16221` was keyed in the reverse direction so
it vanished from one whole direction of search, and that 10.9% of our train numbers were the retired
0-prefixed style that SWR renumbered away in 2022-23. Every one of those was invisible to every test
we had, because all of them assert *internal* consistency: schema, monotonicity, index plans, the
ETL's own sanity rows (12951/12002/12621 - three trains, all of which happened to be unchanged).
Internal consistency is not truth. This file is the only thing in the repo that talks to reality.

Contract:
  evaluate(db, truths) -> dict   # used by tools/build_refdb.py, which stamps the verdict into meta
  main() -> int                 # CI step: prints a report, exits 1 on any contradiction

The verdict is deliberately coarse - `verified` or `unverified` - because the app has to say one of
those to a user, and because a percentage would be read as a licence to show the other 96%.
"""
from __future__ import annotations

import json
import os
import re
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TRUTHS_PATH = os.path.join(HERE, "..", "..", "fixtures", "known_truths.json")


def load_truths(path: str | None = None) -> dict:
    with open(path or TRUTHS_PATH, encoding="utf-8") as fh:
        return json.load(fh)


def _norm(s: str | None) -> str:
    return re.sub(r"[^A-Z0-9]+", " ", (s or "").upper()).strip()


def name_agrees(ours: str | None, theirs: str) -> bool:
    """Loose on purpose: 'SMET MYS EXP' vs 'SHIVAMOGGA TOWN MYSURU EXPRESS' is the same service.

    A strict string equality here would fail on naming conventions and teach people to ignore the
    gate, which is worse than not having it. Route (from/to) is the strict part.
    """
    a, b = _norm(ours), _norm(theirs)
    if not a or not b:
        return False
    if a == b or a in b or b in a:
        return True
    ta, tb = set(a.split()), set(b.split())
    common = ta & tb - {"EXP", "EXPRESS", "SFB", "SF", "PASS", "INTCG", "INTERCITY"}
    return len(common) >= 2


def pair_trains(db: sqlite3.Connection, src: str, dst: str) -> set[str]:
    rows = db.execute(
        "SELECT DISTINCT a.train_no FROM station_trains a "
        "JOIN station_trains b ON b.train_no = a.train_no "
        "WHERE a.station_code = ? AND b.station_code = ?", (src, dst)).fetchall()
    return {r[0] for r in rows}


def evaluate(db: sqlite3.Connection, truths: dict) -> dict:
    """Everything the dataset gets judged on. Never raises: a broken gate must not look like bad data."""
    problems: list[str] = []
    checked = 0

    for t in truths.get("trains", []):
        checked += 1
        row = db.execute("SELECT name, src_code, dst_code FROM trains WHERE train_no = ?",
                         (t["train_no"],)).fetchone()
        want = f'{t["name"]} {t["from"]}->{t["to"]}'
        if row is None:
            problems.append(f'train {t["train_no"]} is absent (reality: {want})')
            continue
        name, src, dst = row
        if (src, dst) != (t["from"], t["to"]):
            rev = (src, dst) == (t["to"], t["from"])
            kind = "keyed in the reverse direction" if rev else "is a different train on the same number"
            problems.append(f'train {t["train_no"]} {kind}: we say {name} {src}->{dst}, reality says {want}')
        elif not name_agrees(name, t["name"]):
            problems.append(f'train {t["train_no"]} route ok but name differs: {name} vs {t["name"]}')

    for p in truths.get("pair_lists", []):
        have = pair_trains(db, p["from"], p["to"])
        for num in p.get("must_include", []):
            checked += 1
            if num not in have:
                # The "missing train" complaint, expressed as a fact. A train keyed in the opposite
                # direction, or one our sources predate, lands here - and no UI test can see it, because
                # the list is not empty, it is merely shorter than reality.
                problems.append(f'station pair {p["from"]}->{p["to"]} is missing train {num} '
                                f'(we list {len(have)} services for it)')
        for num in p.get("must_not_include", []):
            checked += 1
            if num in have:
                # The mirror case, and the more embarrassing one: a service we show that the railway does
                # not run on that pair. An extra train gets a passenger to a platform for something that
                # will not arrive, so it is worth a FAIL even though nothing in the app looks broken.
                problems.append(f'station pair {p["from"]}->{p["to"]} lists phantom train {num}, '
                                f'which the live feed does not run on that pair')

    numbering = truths.get("numbering") or {}
    total = db.execute("SELECT COUNT(*) FROM trains").fetchone()[0] or 0
    retired_pct = 0.0
    if total and numbering.get("retired_prefix"):
        pre = numbering["retired_prefix"]
        retired = db.execute("SELECT COUNT(*) FROM trains WHERE train_no LIKE ?", (f"{pre}%",)).fetchone()[0]
        retired_pct = round(100.0 * retired / total, 1)
        ceiling = float(numbering.get("max_retired_pct", 2.0))
        checked += 1
        if retired_pct > ceiling:
            problems.append(f'{retired}/{total} trains ({retired_pct}%) still carry the retired '
                            f'"{pre}"-prefixed numbering (ceiling {ceiling}%) - this dataset predates '
                            f'the renumbering, so its git commit date does not describe its data')

    return {
        "verdict": "verified" if not problems else "unverified",
        "checked": checked,
        "problems": problems,
        "trains": total,
        "retired_prefix_pct": retired_pct,
        "verified_on": truths.get("verifiedOn"),
        "verified_against": truths.get("verifiedAgainst"),
    }


def report(res: dict) -> str:
    lines = [f'data truths: {res["verdict"].upper()} - {res["checked"]} facts from '
             f'{res["verified_on"]} (checked against {res["verified_against"]})']
    for p in res["problems"]:
        lines.append(f'  FAIL {p}')
    if not res["problems"]:
        lines.append("  every known truth agrees with the shipped dataset")
    return "\n".join(lines)


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if not argv:
        print("usage: data_truths.py <refdb.db> | --path <db> [--json]", file=sys.stderr)
        return 2
    db_path = argv[1] if argv[0] == "--path" else argv[0]
    as_json = "--json" in argv
    db = sqlite3.connect(db_path)
    try:
        res = evaluate(db, load_truths())
    finally:
        db.close()
    if as_json:
        print(json.dumps(res, indent=1))
    else:
        print(report(res))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as fh:
            fh.write("### Offline data vs known truths\n\n```\n" + report(res) + "\n```\n")
    return 1 if res["problems"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
