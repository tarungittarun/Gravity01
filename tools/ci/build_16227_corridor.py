#!/usr/bin/env python3
"""Extract a *historical infrastructure corridor*, not the current 16227 timetable.

Input: tools/build_refdb.py --profile full --out /tmp/refdb-full.dbgz, from the
Spin1234/IndianRailwayOpenReference.github.io public schedule/station dump. The
old 16227 is SBC->SMET, NOT today's MYS->TLGP: deliberately use three separately
identified historical routes to describe the physical corridor. At runtime each
point is accepted only between consecutive stops of the current dated timetable.
Do not copy historical arrival/departure or stopping classifications into the app.
"""
import argparse
import gzip
import json
import sqlite3

PARTS = (("16221", "MYS", "SBC"), ("16227", "SBC", "SMET"),
         ("16206", "SMET", "TLGP"))


def extract(db):
    vintage = db.execute("SELECT v FROM meta WHERE k='source_vintage'").fetchone()
    assert vintage == ("2026-06-20",), "upstream revised: re-audit this old corridor"
    corridor = []
    offset = 0
    for train, first, last in PARTS:
        rows = db.execute("""SELECT s.station_code, st.name, s.sched_km
          FROM stops s JOIN stations st ON st.code=s.station_code
          WHERE s.train_no=? ORDER BY s.seq""", (train,)).fetchall()
        codes = [r[0] for r in rows]
        assert codes.count(first) == codes.count(last) == 1, (train, first, last)
        a, b = codes.index(first), codes.index(last)
        assert a < b, train
        span = rows[a:b + 1]
        assert all(span[i][2] < span[i + 1][2] for i in range(len(span) - 1)), train
        if corridor:
            assert corridor[-1][0] == first
            corridor.pop()  # do not duplicate the shared junction
        corridor.extend((code, name, offset + km - span[0][2]) for code, name, km in span)
        offset += span[-1][2] - span[0][2]
    assert corridor[0][0] == "MYS" and corridor[-1][0] == "TLGP"
    assert len({r[0] for r in corridor}) == len(corridor)
    return {"train": "16227", "kind": "historical corridor, not current schedule or location",
            "source": "Spin1234/IndianRailwayOpenReference.github.io",
            "source_vintage": vintage[0], "routes": [list(p) for p in PARTS],
            "points": [list(r) for r in corridor]}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("full_db_gz", help="ETL --profile full output")
    ap.add_argument("output", help="app/src/main/assets/corridors/16227.json")
    args = ap.parse_args()
    with sqlite3.connect(":memory:") as db:
        db.deserialize(gzip.open(args.full_db_gz, "rb").read())
        data = extract(db)
    with open(args.output, "w", encoding="utf-8") as out:
        json.dump(data, out, ensure_ascii=False, separators=(",", ":"))
        out.write("\n")
    print(f"{len(data['points'])} historical corridor stations -> {args.output}")
