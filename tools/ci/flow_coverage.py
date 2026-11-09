#!/usr/bin/env python3
"""Gate: does every flow the app promises have a test that drives it on a device?

`docs/E2E_FLOWS.md` is the contract. This script reads it and cross-checks three things, none of which any
other checker in this repo can see:

  1. every flow id in the *covered* table is claimed by at least one `// @flow <id>` marker in
     `app/src/androidTest` — so "we have 118 tests" cannot stand in for "the tracking screen is tested";
  2. every marker names a flow that exists — so a test cannot retire a flow by renaming it, and a
     copy-pasted marker on the wrong test is caught;
  3. every `Class#method` the table cites actually exists in the tree — so the table cannot point at a
     test that was deleted, which is the quiet way a coverage claim goes stale.

Rows in the *not covered* table need a reason: an omission is only honest while it is written down.

With `--jacoco <report.xml>` it additionally enforces a line-coverage floor across `in.opentrack.india.*`
as measured by the device runs, and prints the per-package numbers. Exits non-zero on any problem, with
`::error::` annotations so the failure is visible in the run, not only in the log.
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DOC = ROOT / "docs" / "E2E_FLOWS.md"
ANDROID_TEST = ROOT / "app" / "src" / "androidTest" / "java"
MAIN_SRC = ROOT / "app" / "src" / "main" / "java"

FLOW_ID = re.compile(r"`([a-z0-9][a-z0-9-]*)`")
TEST_REF = re.compile(r"`([A-Za-z0-9_]+)#([A-Za-z0-9_]+)`")
MARKER = re.compile(r"@flow\s+([a-z0-9][a-z0-9-]*)")
SCRIPT_REF = re.compile(r"`(tools/[A-Za-z0-9_./-]+)`")


def rows(section: str) -> list[list[str]]:
    """Markdown table rows under the `## <section>` heading, cells stripped."""
    text = DOC.read_text(encoding="utf-8")
    body = re.split(r"^## ", text, flags=re.M)
    for chunk in body:
        if chunk.startswith(section):
            out = []
            for line in chunk.splitlines():
                line = line.strip()
                if not line.startswith("|"):
                    continue
                cells = [c.strip() for c in line.strip("|").split("|")]
                if not cells or set("".join(cells)) <= set("-: "):
                    continue  # the header rule row
                if cells[0].lower() in ("flow", "not covered"):
                    continue
                out.append(cells)
            return out
    return []


def markers() -> dict[str, set[str]]:
    """flow id -> the test functions that claim it."""
    found: dict[str, set[str]] = {}
    for f in sorted(ANDROID_TEST.rglob("*.kt")):
        src = f.read_text(encoding="utf-8")
        # A marker belongs to the next `fun name(` below it; walking lines keeps that association exact
        # without needing a Kotlin parser.
        pending: list[str] = []
        for line in src.splitlines():
            m = MARKER.search(line)
            if m:
                pending.append(m.group(1))
                continue
            fn = re.match(r"\s*(?:@\w+\s+)?fun\s+(\w+)", line)
            if fn and pending:
                for fid in pending:
                    found.setdefault(fid, set()).add(f"{f.stem}#{fn.group(1)}")
                pending = []
        if pending:  # trailing markers before the file's end are still real claims
            for fid in pending:
                found.setdefault(fid, set()).add(f"{f.stem}#(file)")
    return found


def check_tests_exist(claims: list[str], problems: list[str]) -> None:
    sources = {f.stem: f for f in ANDROID_TEST.rglob("*.kt")}
    for ref in claims:
        m = TEST_REF.search(ref)
        if m:
            cls, method = m.group(1), m.group(2)
            f = sources.get(cls)
            if f is None:
                problems.append(f"{ref}: no androidTest file {cls}.kt")
                continue
            body = f.read_text(encoding="utf-8")
            if not re.search(r"fun\s+" + re.escape(method) + r"\s*\(", body):
                problems.append(f"{ref}: {cls}.kt has no fun {method}()")
            continue
        s = SCRIPT_REF.search(ref)
        if s and not (ROOT / s.group(1)).is_file():
            problems.append(f"{ref}: missing script {s.group(1)}")
        elif s:
            # A flow "covered by the harness" is only a claim that CI runs the harness. Without this the
            # Test column could cite any script in the repo and the row would read as covered forever.
            name = Path(s.group(1)).name
            runs = list((ROOT / ".github" / "workflows").glob("*.yml"))
            if not any(name in f.read_text(encoding="utf-8") for f in runs):
                problems.append(f"{ref}: {s.group(1)} exists but no workflow runs it, so the flow is not covered")
        elif not s:
            problems.append(f"'{ref}': the Test column must cite `Class#method` or `tools/…sh`")


def jacoco(path: Path) -> tuple[int, int, list[tuple[str, int, int]]]:
    """(covered, missed, per-package lines) from a JaCoCo report XML."""
    root = ET.parse(path).getroot()
    per: list[tuple[str, int, int]] = []
    for pkg in root.findall("package"):
        c = pkg.find("counter[@type='LINE']")
        if c is not None:
            per.append((pkg.get("name"), int(c.get("covered")), int(c.get("missed"))))
    top = root.find("counter[@type='LINE']")
    if top is None:
        raise SystemExit(f"{path}: no LINE counter — is this a JaCoCo XML report?")
    return int(top.get("covered")), int(top.get("missed")), per


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--jacoco", type=Path, help="JaCoCo XML report from the device runs")
    ap.add_argument("--min-lines", type=float, default=0.0, help="floor, percent of lines covered")
    ap.add_argument("--summary", action="store_true", help="append a table to $GITHUB_STEP_SUMMARY")
    args = ap.parse_args()

    problems: list[str] = []
    covered = rows("Covered flows")
    uncovered = rows("Deliberately not covered")
    claimed = markers()

    ids = []
    for r in covered:
        if not r or not r[0].startswith("`"):
            problems.append(f"covered-flow row without a `flow-id` first cell: {' | '.join(r)[:80]}")
            continue
        fid = FLOW_ID.search(r[0]).group(1)
        ids.append(fid)
        by_script = bool(SCRIPT_REF.search(r[3])) if len(r) > 3 else False
        if by_script:
            # A flow driven by the harness itself (a monkey run, a `svc` toggle) has no test method to
            # carry a marker, so the script path in its Test column *is* the claim of coverage.
            claimed.setdefault(fid, set()).add("harness")
        if fid not in claimed:
            problems.append(f"flow `{fid}` is promised in the table but no androidTest claims @flow {fid}")
        if len(r) < 5:
            problems.append(f"flow `{fid}` needs Flow | Screen | Expectation | Test | Runners")
        elif r[3]:
            check_tests_exist([r[3]], problems)
        if len(r) >= 5 and not r[4].strip():
            problems.append(f"flow `{fid}` names no runner")
    for r in uncovered:
        if not r or not r[0]:
            continue
        if len(r) < 2 or not r[1].strip():
            problems.append(f"uncovered row '{r[0][:40]}' has no reason; omissions must be written down")

    for fid in sorted(set(claimed) - set(ids)):
        who = ", ".join(sorted(claimed[fid]))
        problems.append(f"@flow {fid} (in {who}) is not in the covered table — add the row or drop the marker")
    if len(ids) != len(set(ids)):
        dupes = {i for i in ids if ids.count(i) > 1}
        problems.append(f"duplicate flow ids in the table: {', '.join(sorted(dupes))}")

    total = len(ids)
    driven = len(set(ids) & set(claimed))
    print(f"flows promised: {total}   driven by a device test: {driven}   "
          f"declared not coverable: {len(uncovered)}")

    if args.jacoco and args.jacoco.is_file():
        cov, miss, per = jacoco(args.jacoco)
        pct = 100.0 * cov / max(1, cov + miss)
        print(f"line coverage of the app (device runs): {pct:.1f}%  ({cov}/{cov + miss})")
        for name, c, m in sorted(per, key=lambda t: -(t[1] + t[2])):
            print(f"  {pct_of(c, m):>6}  {name}")
        if args.min_lines and pct < args.min_lines:
            problems.append(f"line coverage {pct:.1f}% is under the {args.min_lines:.0f}% floor")
    elif args.min_lines:
        problems.append("--min-lines given but no JaCoCo report was produced")

    if args.summary:
        target = os.environ.get("GITHUB_STEP_SUMMARY")
        if target:
            with open(target, "a", encoding="utf-8") as fh:
                fh.write("### Flow coverage\n\n")
                fh.write(f"{driven}/{total} promised flows are driven on a device; "
                         f"{len(uncovered)} are declared uncoverable with reasons.\n\n")
                if args.jacoco and args.jacoco.is_file():
                    cov, miss, _ = jacoco(args.jacoco)
                    fh.write(f"JaCoCo line coverage from the device runs: "
                             f"{pct_of(cov, miss)} ({cov}/{cov + miss} lines).\n")

    # On GitHub Actions the same text goes out as an annotation: a red check whose reason is only in a
    # 400-line log is a round trip, and the annotations are what the commit comment is built from.
    ci = os.environ.get("GITHUB_ACTIONS") == "true"
    for p in problems:
        print(f"::error::{p}" if ci else f"FAIL  {p}")
    print(f"\n{len(problems)} problem(s).")
    return 1 if problems else 0


def pct_of(covered: int, missed: int) -> str:
    return f"{100.0 * covered / max(1, covered + missed):.1f}%"


if __name__ == "__main__":
    sys.exit(main())
