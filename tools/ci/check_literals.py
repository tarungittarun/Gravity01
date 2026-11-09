#!/usr/bin/env python3
"""Fail the build when a patch script's escaping leaks into Kotlin string text.

This tree has now shipped the same defect three times: a Python heredoc wrote `${'$'}{value}` (or its
half-substituted corpse, `{D}{value}`) into a string instead of `${value}`. Kotlin accepts every one of those
as valid syntax - they are simply templates that produce literal characters - so nothing but a reading of the
device screen notices, and the user is the one who reads the screen. The strings that carry this app's
diagnostics are the ones that matter most and the ones an automated edit touches most, so the rule is
mechanical instead: no template placeholder that renders as literal text, and no patch marker, anywhere in a
string or a comment in shipped sources.

Deliberately dumb (regexes over the raw bytes, no parser) and dependency-free, so it runs in CI on a bare
runner exactly as it does on a machine with a Kotlin toolchain.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = [ROOT / "app" / "src" / "main" / "java", ROOT / "app" / "src" / "test" / "java",
       ROOT / "app" / "src" / "androidTest" / "java"]

RULES = [
    # A template whose expression is the literal-character idiom: `"$" + "{" + name"}"`, i.e. the escape
    # leaking into the source. Legitimate uses of ${'$'} exist (printing a currency sign), so only flag it
    # when a brace follows straight after - that shape has never been intentional here.
    (re.compile(r"\$\{'\$'\}\{"), "template that renders a literal ${...}: concatenate instead"),
    (re.compile(r"\{\{?[A-D]\}\}?\{"), "an f-string marker survived into the source"),
    (re.compile(r"\{[a-zA-Z_][a-zA-Z0-9_.]*\}(?![a-zA-Z0-9_])\}"), "a doubled closing brace in a placeholder"),
    # A concatenation spliced onto another concatenation operator: `x.size + +` is not a typo a reader
    # catches in a 200-line diff, and it does not compile. `attempt++` is legitimate, so only the
    # whitespace-separated form is flagged.
    (re.compile(r"(?<![+\w])\+\s+\+"), "a doubled concatenation operator (patch damage)"),
]


def main() -> int:
    problems: list[str] = []
    scanned = 0
    for base in SRC:
        if not base.is_dir():
            continue
        for f in sorted(base.rglob("*.kt")):
            scanned += 1
            text = f.read_text(encoding="utf-8", errors="replace")
            for i, line in enumerate(text.splitlines(), start=1):
                stripped = line.strip()
                for rx, why in RULES:
                    if rx.search(line):
                        problems.append(
                            "%s:%d: %s\n      %s" % (f.relative_to(ROOT), i, why, stripped[:140])
                        )
    if problems:
        print("\n".join(problems))
        print("checked %d Kotlin files, %d leak(s) - fix by concatenation, not by another template"
              % (scanned, len(problems)))
        return 1
    print("checked %d Kotlin files, 0 literal-escape leaks" % scanned)
    return 0


if __name__ == "__main__":
    sys.exit(main())
