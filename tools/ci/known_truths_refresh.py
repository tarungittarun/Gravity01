#!/usr/bin/env python3
"""Propose a refresh of fixtures/known_truths.json from erail.in. PRINTS ONLY - never writes.

Why this exists (brief 7.7(a)): the CI probe proved erail.in answers from a runner, so pair- and
identity-truth is CI-refreshable. Why print-only: an automated edit to the file the app's gate trusts is
the app believing itself, and the ac06f62 probe comment showed how close the blade is - its "proposed
refresh" carried `"trains": []` purely because NTES was unreachable, so one careless paste would have
erased three owner-verified identities. This tool therefore works per fact, and:

  - AGREE         the live answer matches the fixture; proposed entry says so.
  - CONTRADICTION the live answer disagrees with an owner-verified fact; BOTH values are printed and the
                  proposal flags it for an owner word. It never substitutes the live value in place -
                  the 16221 incident is the proof this is right: erail said KUVEMPU, the fixture said
                  KAVERI, and the owner - not the tool - resolved it (to KUVEMPU, 2026-10-12).
  - UNVERIFIABLE  the fetch died or the shape was unreadable; the fact is left alone. Absent evidence is
                  not evidence of absence (7.8), so nothing is deleted for being uncheckable.

Exit codes: 0 when the report was produced (contradictions are a report, not a failure - 7.4); 1 only when
the tool itself could not run. Run by a human, or by the weekly data-trust job, which pastes the report.
"""
from __future__ import annotations

import datetime
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

from probe_live_feed import (  # noqa: E402
    ERAIL_BASE, PROPOSAL_RULE, erail_get, erail_pair, new_proposal, parse_identity_record,
)

TRUTHS_PATH = os.path.join(HERE, "..", "..", "fixtures", "known_truths.json")
STAMP_RE = re.compile(r"(\d{4}-\d{2}-\d{2}-\d{1,2}-\d{1,2}-\d{1,2})")


def _norm_name(s: str) -> str:
    return re.sub(r"[^A-Z0-9]+", " ", s.upper()).strip()


def names_agree(a: str, b: str) -> bool:
    """Same looseness as the gate's own name_agrees (data_truths.py): one service, many spellings."""
    x, y = _norm_name(a), _norm_name(b)
    if not x or not y:
        return False
    if x == y or x in y or y in x:
        return True
    stop = {"EXP", "EXPRESS", "SFB", "SF", "PASS", "INTCG", "INTERCITY"}
    common = (set(x.split()) & set(y.split())) - stop
    return len(common) >= 2


def refresh(truths: dict, today: datetime.datetime,
            get_identity=erail_get, get_pair=erail_pair) -> tuple[list[str], dict]:
    """Ask erail about every fact the fixture asserts, and return (report lines, proposed merge).

    Injection mirrors probe_live_feed.probe_pair so a test can kill the network and assert the tool
    proposes nothing for what it could not check (4.4/4.6).
    """
    out: list[str] = []
    proposal = new_proposal(today, f"erail.in live ({ERAIL_BASE}), printed {today.isoformat(timespec='seconds')} IST")
    proposal["trains"] = []
    proposal["pair_lists"] = []

    for t in truths.get("trains", []):
        no = t.get("train_no", "?")
        verdict, body = get_identity(f"TrainNo={no}&DataSource=1&Language=0")
        rec = parse_identity_record(body) if body else None
        if rec is None:
            out.append(f"train {no}: UNVERIFIABLE · {verdict} (fixture keeps {t.get('name')} "
                       f"{t.get('from')}->{t.get('to')}; nothing proposed)")
            proposal["trains"].append({"train_no": no, "status": "UNVERIFIABLE",
                                       "current": {"name": t.get("name"), "from": t.get("from"), "to": t.get("to")}})
            continue
        stamp = STAMP_RE.search(body)
        live = {"train_no": no, "name": rec["name"], "from": rec["from"], "to": rec["to"]}
        same_route = rec["from"] == t.get("from") and rec["to"] == t.get("to")
        same_name = names_agree(rec["name"], t.get("name", ""))
        if same_route and same_name:
            out.append(f"train {no}: AGREE · {rec['name']} {rec['from']}->{rec['to']} "
                       f"(stamp {stamp.group(1) if stamp else 'none'})")
            proposal["trains"].append({"train_no": no, "status": "AGREE", **live})
        else:
            # The whole point of print-only: a contradiction between a keyless source and an
            # owner-verified fact is an owner decision, not a merge (17.4 precedence of evidence).
            out.append(f"train {no}: CONTRADICTION · erail says {rec['name']} {rec['from']}->{rec['to']}, "
                       f"owner-verified fixture says {t.get('name')} {t.get('from')}->{t.get('to')} "
                       f"- needs an owner word, nothing applied")
            proposal["trains"].append({"train_no": no, "status": "CONTRADICTION",
                                       "current": {"name": t.get("name"), "from": t.get("from"), "to": t.get("to")},
                                       "live": live})

    for p in truths.get("pair_lists", []):
        src, dst = p.get("from", "?"), p.get("to", "?")
        verdict, body = get_pair(src, dst, today.strftime("%d-%b-%Y"))
        if not body:
            out.append(f"pair {src}->{dst}: UNVERIFIABLE · {verdict} (fixture keeps its "
                       f"{len(p.get('must_include', []))} must_include claims; nothing proposed)")
            proposal["pair_lists"].append({"from": src, "to": dst, "status": "UNVERIFIABLE"})
            continue
        stamp = STAMP_RE.search(body)
        nums = sorted(set(re.findall(r"\b(\d{5})\b", body[:200000])))
        have = set(p.get("must_include", []))
        missing = sorted(have - set(nums))
        added = [n for n in nums if n not in have]
        for n in missing:
            out.append(f"pair {src}->{dst}: erail does not list {n} today - a hypothesis, not an assertion; "
                       f"must_not_include stays untouched (7.3: a miss is not evidence of absence)")
        out.append(f"pair {src}->{dst}: {verdict} · stamp {stamp.group(1) if stamp else 'none'} · "
                   f"{len(nums)} numbers; fixture claims {len(have)}, missing today: "
                   f"{', '.join(missing) if missing else 'none'}; seen but unclaimed: "
                   f"{', '.join(added) if added else 'none'}")
        proposal["pair_lists"].append({"from": src, "to": dst, "status": "SEEN",
                                       "must_include": sorted(set(have) | set(nums)),
                                       "_note": f"erail stamp {stamp.group(1) if stamp else 'none'}; "
                                                f"review added numbers before asserting them"})

    out.insert(0, f"proposed refresh of fixtures/known_truths.json · rule: {proposal['_proposal_rule']}")
    return out, proposal


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    path = TRUTHS_PATH
    if "--truths" in argv:
        path = argv[argv.index("--truths") + 1]
    try:
        with open(path, encoding="utf-8") as fh:
            truths = json.load(fh)
    except Exception as e:  # the tool must say it could not run, not exit silently
        print(f"known_truths_refresh: could not read {path}: {e}")
        return 1
    tz = datetime.timezone(datetime.timedelta(hours=5, minutes=30))
    lines, proposal = refresh(truths, datetime.datetime.now(tz))
    print("\n".join(lines))
    print("\n### proposed merge (a human reads this; nothing was written)")
    print("```json")
    print(json.dumps(proposal, indent=1)[:6000])
    print("```")
    return 0


if __name__ == "__main__":
    sys.exit(main())
