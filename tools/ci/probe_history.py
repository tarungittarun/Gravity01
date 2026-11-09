#!/usr/bin/env python3
"""Bounded live-source reconnaissance, NOT a historical actual-time certification.

Two distinct trains × ten run days. A reply must identify the requested run and
contain dated actual fields; schedule/ETA or an HTTP 200 never counts. Even 20
positive replies would still require an independent accuracy comparison before a
past-journey calendar could be promised to travellers.
"""
from __future__ import annotations
import datetime as dt
import json
import os
import re
from concurrent.futures import ThreadPoolExecutor, as_completed
from probe_live_feed import call

TRAINS = ("16227", "12627")
DAY_OFFSETS = tuple(range(-1, -11, -1))
ACTUAL = ("ATA", "ATD", "ACTARR", "ACTDEP", "ACTUALARRIVAL", "ACTUALDEPARTURE")


def evidence(doc: object, train: str, date: dt.date) -> tuple[int, str]:
    if not isinstance(doc, dict):
        return 0, "no JSON record"
    no = next((str(doc[k]).strip() for k in ("TrainNo", "trainNo", "TRAIN_NO")
               if doc.get(k)), None)
    if no and no != train:
        return 0, "different train"
    rows = doc.get("STNS")
    if not isinstance(rows, list) or not rows:
        return 0, "no STNS rows"
    date_tokens = {date.strftime("%d-%b-%Y").lower(),
                   (date + dt.timedelta(days=1)).strftime("%d-%b-%Y").lower(),
                   (date + dt.timedelta(days=2)).strftime("%d-%b-%Y").lower()}
    top_date = next((str(doc[k]).lower() for k in ("StartDate", "startDate", "RunDate")
                     if doc.get(k)), "")
    if top_date and date.strftime("%d-%b-%Y").lower() not in top_date:
        return 0, "different run date"
    # Do not treat an old archive's undated clock as belonging to the selected run.
    actuals = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        for key, value in row.items():
            if (key.upper() in ACTUAL and isinstance(value, str)
                    and any(token in value.lower() for token in date_tokens)
                    and re.search(r"\b\d{1,2}:\d{2}\b", value)):
                actuals.append((row.get("SC", "?"), key))
    return len(actuals), "dated actual cells" if actuals else "no dated actual cells"


def sample(train: str, offset: int, today: dt.date) -> dict:
    date = today + dt.timedelta(days=offset)
    plain = (f"service=TrainRunningMob&subService=ShowFullRunJson&trainNo={train}"
             f"&startDate={date.strftime('%d-%b-%Y')}")
    verdict, doc = call(plain, timeout=7)
    n, reason = evidence(doc, train, date)
    return {"train": train, "day": offset, "date": date.isoformat(), "transport": verdict,
            "dated_actual_cells": n, "finding": reason}


def main() -> None:
    today = dt.datetime.now(dt.timezone(dt.timedelta(hours=5, minutes=30))).date()
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(sample, train, offset, today) for train in TRAINS for offset in DAY_OFFSETS]
        results = sorted((f.result() for f in as_completed(futures)),
                         key=lambda r: (r["train"], -r["day"]))
    assert len(results) == 20
    print(json.dumps({"tested_at": dt.datetime.now(dt.timezone.utc).isoformat(),
                      "source": "NTES ShowFullRunJson, one dated request per train/day",
                      "attempts": 20,
                      "with_dated_actual_cells": sum(r["dated_actual_cells"] > 0 for r in results),
                      "independent_accuracy_verified": 0,
                      "samples": results}, indent=2))


if __name__ == "__main__":
    main()
