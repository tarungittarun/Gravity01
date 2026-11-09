#!/usr/bin/env python3
"""Turn Gradle's JUnit XML into a failure report that fits in a commit comment.

Why not grep the console log: Gradle prints a test's first failure line and then truncates with `head -N`,
and the message that actually says what broke — the assertion text — is the part that falls off the end. The
XML that `testDebugUnitTest` writes has the exact message plus the stack, per test, so the report is built
from that instead. This is what makes a red `ui-preview` run debuggable from the commit alone, which matters
because the Actions log host is not readable from the sandbox this project is developed in.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path
import xml.etree.ElementTree as ET


def own_frame(stack: str) -> str:
    """The first stack line inside this project — the assertion's file and line, not JUnit's plumbing."""
    for line in stack.splitlines():
        m = re.search(r"at (in\.opentrack\.india\.[^(]+)\(([^)]+)\)", line)
        if m:
            return f"{m.group(1).split('.')[-1]}:{m.group(2).split(':')[-1] if ':' in m.group(2) else m.group(2)}"
    return ""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="app/build/test-results")
    ap.add_argument("--max", type=int, default=25, help="how many failures to print")
    ap.add_argument("--msg-chars", type=int, default=600)
    args = ap.parse_args()

    files = sorted(Path(args.dir).rglob("*.xml"))
    if not files:
        print(f"(no test-results XML under {args.dir} — the test task never ran)")
        return 0

    failed = total = skipped = 0
    lines: list[str] = []
    for f in files:
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError as e:
            lines.append(f"- `{f.name}`: unparseable ({e})")
            continue
        skipped += len(list(root.iter("skipped")))
        for case in root.iter("testcase"):
            total += 1
            for tag in ("failure", "error"):
                node = case.find(tag)
                if node is None:
                    continue
                failed += 1
                if len(lines) >= args.max:
                    break
                msg = (node.get("message") or node.text or "").strip()
                first = " ".join(msg.splitlines()[:3])[: args.msg_chars] or "(no message)"
                where = own_frame(node.text or "")
                name = f"{case.get('classname', '?').split('.')[-1]}.{case.get('name', '?')}"
                lines.append(f"- **{name}**{f' — `{where}`' if where else ''}\n  {first}")
    print(f"{failed} failing of {total} tests" + (f", {skipped} skipped" if skipped else ""))
    print()
    print("\n".join(lines))
    if failed > len(lines):
        print(f"\n(and {failed - len(lines)} more; full detail in the test-reports artifact)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
