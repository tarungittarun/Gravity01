#!/usr/bin/env python3
"""String/comment-aware brace-balance check for Kotlin sources.

This exists because there is no JVM in this sandbox: the only cheap way to catch a truncated file
(a write that stopped mid-expression) before pushing to GitHub Actions is a scanner that understands
enough of Kotlin lexing to ignore braces inside strings, char literals and comments. A naive
`text.count("{")` is useless here — `'{'}'` appears in real code.

Deliberately not a parser: it checks that the token stream leaves exactly one open scope, which is
the signature of a cut-off file, plus a couple of other lexical defects. Exit 0 = nothing found.
"""
from __future__ import annotations
import pathlib
import re
import sys

OPEN = {"{": "}", "(": ")", "[": "]"}
CLOSE = {v: k for k, v in OPEN.items()}


HARD_KEYWORD_SEGMENT = re.compile(r"^(?:package|import)\s+(as|in|is|object|fun|val|var|when|while|for|do|if|else|throw|return|true|false|null|typeof|package|interface|class|try|catch)\.")
BARE_FQN = re.compile(r"(?<![`\w.])(in|is|as)\.opentrack\.india")


def lexical_rules(text: str) -> list[str]:
    """Keyword-as-package-segment detection.

    `in` is a *hard* keyword in Kotlin, so an applicationId/package like `in.opentrack.india` is only
    legal in Kotlin sources as `` `in`.opentrack.india ``. It is legal unescaped in Java (the generated
    R/BuildConfig) and in the manifest, which is exactly why the mistake survives a careful read: the
    build file, the manifest and the directory layout all look right. This check exists because the
    first real compile of this project failed on nothing else, in 32 files.
    """
    out: list[str] = []
    for no, line in enumerate(text.split("\n"), 1):
        stripped = line.strip()
        if stripped.startswith(("*", "//", "/*")):
            continue  # KDoc links to [in.opentrack…] are prose, not code
        # String literals are not code. `By.res("in.opentrack.india", "id/fromBox")` and any
        # `Class.forName("in.opentrack.india.App")` have to name the package in a string, and flagging that
        # trains people to work around a checker instead of trusting it.
        code = re.sub('"[^"]*"', '""', line)
        if HARD_KEYWORD_SEGMENT.match(code.rstrip()):
            out.append(
                f"line {no}: package/import segment is an unescaped Kotlin keyword "
                f"— write it as `{line.split()[0]} `in`.…`"
            )
        elif BARE_FQN.search(code):
            out.append(f"line {no}: fully-qualified name starts with the keyword `in` — import the type instead")
    return out


def scan(text: str):
    """Yield (kind, detail) problems, and a final depth for eyeballing."""
    i, n = 0, len(text)
    stack: list[tuple[str, int]] = []
    line = 1
    problems: list[str] = lexical_rules(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
            i += 1
            continue
        # line comment
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        # block comment (Kotlin nests them, so we count)
        if c == "/" and nxt == "*":
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth += 1
                    j += 2
                elif text.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    if text[j] == "\n":
                        line += 1
                    j += 1
            i = j
            continue
        # raw string """ ... """ (may contain braces and single quotes freely)
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            if j < 0:
                problems.append(f"line {line}: unterminated raw string")
                break
            line += text.count("\n", i, j)
            i = j + 3
            continue
        # char or string literal
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    break
                if text[j] == "\n":
                    problems.append(f"line {line}: newline inside a string literal")
                    break
                j += 1
            if j >= n:
                problems.append(f"line {line}: unterminated string")
                break
            i = j + 1
            continue
        if c == "'":
            j = i + 1
            while j < n and text[j] != "'":
                if text[j] == "\\":
                    j += 1
                elif text[j] == "\n":
                    break
                j += 1
            if j >= n or text[j] != "'":
                problems.append(f"line {line}: unterminated char literal")
                break
            i = j + 1
            continue
        if c in OPEN:
            stack.append((c, line))
        elif c in CLOSE:
            if not stack:
                problems.append(f"line {line}: stray '{c}' with nothing open")
            elif OPEN[stack[-1][0]] != c:
                opener, oline = stack[-1]
                problems.append(
                    f"line {line}: '{c}' closes '{opener}' opened on line {oline}"
                )
                stack.pop()
            else:
                stack.pop()
        i += 1
    for opener, oline in stack:
        problems.append(f"line {oline}: '{opener}' never closed")
    return problems


def companion_twins(path: pathlib.Path) -> list[str]:
    """Two `companion object` blocks in one type: a Kotlin error a scripted patch produces easily.

    Why the checker cares when the compiler already does: this repo's app code is edited by scripts most of the
    time, and a script that inserts a constant happily creates a second companion object. That cost an APK here
    (the debug assemble failed and took the release job and the unit tests with it), and a CI compile error whose
    log a sandbox cannot read is the slowest possible feedback loop.

    Ownership is by indentation rather than by the last `class` line seen: a file with a top-level `class RefDb`
    and a sibling `fun interface Clock`, each with its own companion, is legal, and grouping by name called that
    an error on its first run. So a companion belongs to the nearest preceding type declaration that is indented
    *less* than it, and only two companions claiming the same declaration are a problem.
    """
    out: list[str] = []
    # `fun interface` is Kotlin's SAM declaration and the modifier list alone would miss it, which is how a
    # legal companion inside a sibling interface once got charged to the class above it.
    decl_re = re.compile(r"^(\s*)(?:(?:private|internal|public|abstract|open|final|sealed|data|enum|annotation|value|fun)\s+)*"
                         r"(?:class|interface|object)\s+(\w+)")
    type_re = re.compile(r"^(\s*)(?:(?:private|internal|public)\s+)?(?:companion\s+object|class|interface|object)\b")
    stack: list[tuple] = []          # (indent, name, [lines of companions owned])
    owners: dict = {}
    for n, raw in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
        if not raw.strip() or raw.strip().startswith(("//", "/*", "*")):
            continue
        ind = len(raw) - len(raw.lstrip(" "))
        d = decl_re.match(raw)
        if d and "companion" not in raw:
            while stack and stack[-1][0] >= ind:
                stack.pop()
            stack.append((ind, d.group(2), []))
            owners.setdefault(d.group(2), stack[-1][2])
        if type_re.match(raw) and re.search(r"\bcompanion\s+object\b", raw):
            while stack and stack[-1][0] >= ind:
                stack.pop()
            if stack:
                stack[-1][2].append(n)
    for name, lines in owners.items():
        if len(lines) > 1:
            out.append(f"{path.name}: {name} has {len(lines)} companion objects (lines {lines[:4]}) - Kotlin "
                       f"allows one per type; fold the constants into the existing one")
    return out


def main() -> int:
    roots = sys.argv[1:] or ["app/src"]
    bad = 0
    checked = 0
    for root in roots:
        p = pathlib.Path(root)
        files = sorted([x for x in p.rglob("*") if x.suffix in (".kt", ".java")])
        for f in files:
            checked += 1
            text = f.read_text(encoding="utf-8")
            problems = scan(text)
            for msg in problems:
                bad += 1
                print(f"{f}: {msg}")
            for msg in companion_twins(f):
                bad += 1
                print(f"{f}: {msg}")
            if text.endswith("\n\n"):
                print(f"{f}: warning: file ends with a blank line")
            if not text.endswith("\n"):
                print(f"{f}: warning: no trailing newline")
            if "\t" in text:
                print(f"{f}: warning: tab characters in indentation")
    print(f"checked {checked} files, {bad} problems")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
