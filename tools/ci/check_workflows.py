#!/usr/bin/env python3
"""Checks the things Actions refuses to parse but a YAML parser happily accepts.

Why this exists: two pushes to this repo produced a run with *no jobs at all*, because GitHub rejects the
whole file for a duplicated mapping key (`if:` written twice on one job) and for an action input declaring
both `required: true` and a `default`. PyYAML loads both files without complaint, so the only way to know
before pushing was to burn a run and then go hunting for an annotation the API does not expose. Every rule
here is one of those "the runner is right and nothing local told me" cases, found the hard way.
"""
from __future__ import annotations

import pathlib
import re
import sys

try:  # PyYAML is not on a runner's python by default; the indentation checks are the ones that matter most.
    import yaml
    HAVE_YAML = True
except ModuleNotFoundError:
    yaml = None
    HAVE_YAML = False

ROOT = pathlib.Path(__file__).resolve().parents[2]


def disp_path(path: pathlib.Path) -> str:
    """Repo-relative when it is in the repo, absolute otherwise. The checker is also pointed at scratch files
    while it is being developed, and a gate that crashes over a path display teaches nothing."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def dup_keys(path: pathlib.Path) -> list[str]:
    """A key written twice in the same mapping block: fatal to Actions, invisible to yaml.safe_load.

    PyYAML keeps the last value of a duplicated key, so loading the document hides exactly the mistake being
    looked for - which is what cost four pushes here (a job with `if:` twice, an input with `default:` twice).
    The whole file is rejected, no job starts, the run is named by its path and the API shows no annotation.

    So this is structural, line-oriented, and reads the document the way the runner's parser does:

      - a frame is a mapping whose *children's* column is discovered on the first child (workflow files are
        written with a consistent indent, and the frame model must not care what that width is);
      - `key:` with nothing after it opens a frame for the block that follows;
      - a `- ` line closes the previous list item's frame and opens a fresh one, so two steps in a list
        never share keys with each other;
      - inside a block scalar (`run: |`, `description: >`) nothing is YAML, including lines that look like
        keys - so they are skipped by column, which is what makes `echo "x: y"` in a harness script safe.
    """
    out: list[str] = []
    stack: list[list] = []          # [child_col, owner, {key: line}]
    skip_col: int | None = None     # while set, lines deeper than this are block-scalar text
    for n, raw in enumerate(path.read_text(encoding="utf-8").split("\n"), 1):
        if not raw.strip():
            continue
        col = len(raw) - len(raw.lstrip(" "))
        if skip_col is not None:
            if col > skip_col:
                continue
            skip_col = None
        stripped = raw.strip()
        if stripped.startswith("#"):
            continue

        if stripped.startswith("- "):
            while stack and stack[-1][0] is not None and stack[-1][0] >= col + 2:
                stack.pop()
            rest = stripped[2:]
            m = re.match(r"^([\w.\-]+):(.*)$", rest)
            if not m:
                continue                       # a scalar list item (`- foo`), no keys to track
            stack.append([col + 2, rest.split(":")[0], {}])
            key, val = m.group(1), m.group(2).strip()
            stack[-1][2][key] = n
            if val.startswith("|") or val.startswith(">"):
                skip_col = col + 2
            continue

        m = re.match(r"^([\w.\-]+):(.*)$", stripped)
        if not m:
            continue
        key, val = m.group(1), m.group(2).strip()
        while stack and stack[-1][0] is not None and stack[-1][0] > col:
            stack.pop()
        if stack and stack[-1][0] is None:     # first child of a block: it decides the column
            stack[-1][0] = col
        if not stack or stack[-1][0] != col:
            stack.append([col, key, {}])
        frame = stack[-1]
        seen = frame[2]
        if key in seen:
            out.append(f"{disp_path(path)}:{n}: '{key}' is set twice in the block opened by "
                       f"'{frame[1]}' (first at line {seen[key]}) - Actions rejects the whole file for this, "
                       f"and PyYAML will not: the later value simply wins")
        seen[key] = n
        if val == "":
            stack.append([None, key, {}])      # a block follows; its column is not known yet
        elif val.startswith("|") or val.startswith(">"):
            skip_col = col
    return out


# --------------------------------------------------------------------------- a stdlib block-YAML reader
# Why this exists: `check_action` and `check_workflows` used to return [] when PyYAML was missing, which is
# the state of a bare runner. The gate then reported "0 problems" for the rules that matter most — an action
# input with `required: true` and a `default`, a composite step with `run:` and no `shell:`, `${{ inputs.x }}`
# naming an undeclared input — while the developer's machine, which has PyYAML, said the same. A checker whose
# answer depends on what happens to be installed is worse than no checker, because it prints a green light.
# So the structural rules now always read the file through this reader, and PyYAML — when present — is used
# only to cross-check it. The subset supported is the subset these files use: block mappings, block
# sequences, quoted and plain scalars, and block scalars whose bodies are text rather than structure.
_KEY = re.compile(r"^([\w.\-]+):(?:[ \t]*(.*))?$")
# The two YAML quote characters, built with chr() so that no escaping in this file has to be right for this
# line to be — a quoting mistake in a gate's own source is the worst possible place for one.
QUOTES = chr(34) + chr(39)


def _split_flow(body: str) -> list:
    """Split a flow collection's interior on top-level commas, keeping quotes and nesting intact."""
    parts, depth, cur, quote = [], 0, [], ""
    for ch in body:
        if quote:
            cur.append(ch)
            if ch == quote:
                quote = ""
            continue
        if ch in "\"'":
            quote = ch
            cur.append(ch)
        elif ch in "{[":
            depth += 1
            cur.append(ch)
        elif ch in "}]":
            depth -= 1
            cur.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    tail = "".join(cur).strip()
    if tail:
        parts.append(tail)
    return parts


def _scalar(text: str):
    """A YAML scalar in the two styles these files actually use: block plain/quoted, and flow."""
    s = text.strip()
    if len(s) >= 2 and s[0] == s[-1] and s[0] in "\"'":
        return s[1:-1]
    if s.startswith("{") and s.endswith("}"):
        out = {}
        for part in _split_flow(s[1:-1]):
            k, sep, v = part.partition(":")
            if sep:
                out[k.strip().strip(QUOTES)] = _scalar(v)
        return out
    if s.startswith("[") and s.endswith("]"):
        return [_scalar(x) for x in _split_flow(s[1:-1]) if x.strip()]
    if s in ("~", "null"):
        return None
    if s in ("true", "false"):
        return s == "true"
    if re.fullmatch(r"-?\d+", s):
        return int(s)
    return s


def tokenize(text: str) -> list:
    """(kind, column, key, value, line) tokens; `kind` is key | seq | val | other."""
    out = []
    lines = text.split("\n")
    n = 0
    while n < len(lines):
        raw = lines[n]
        s = raw.strip()
        col = len(raw) - len(raw.lstrip(" "))
        n += 1
        if not s or s.startswith("#"):
            continue
        if s == "-" or s.startswith("- "):
            out.append(("seq", col, None, None, n))
            rest = s[2:].strip()
            if rest:
                m = _KEY.match(rest)
                if m:
                    out.append(("key", col + 2, m.group(1), m.group(2) or "", n))
                else:
                    out.append(("val", col + 2, None, rest, n))
            continue
        m = _KEY.match(s)
        if m:
            key, val = m.group(1), (m.group(2) or "")
            if val[:1] in ("|", ">", "|-", ">-", "|+", ">+"):
                body = []
                while n < len(lines):
                    r2 = lines[n]
                    c2 = len(r2) - len(r2.lstrip(" "))
                    if r2.strip() and c2 <= col:
                        break
                    body.append(r2.strip())
                    n += 1
                val = "\n".join(x for x in body if x)
            out.append(("key", col, key, val, n))
            continue
        out.append(("other", col, None, s, n))
    return out


def load_block(path: pathlib.Path):
    """Parse a workflow/action file into plain python objects, with no third-party dependency."""
    tokens = tokenize(path.read_text(encoding="utf-8"))
    if not tokens:
        return {}
    # The document's own block starts at the first token's column.
    base = tokens[0][1]
    if tokens[0][0] == "seq":
        raise ValueError("a workflow file must start with a mapping")
    container: dict | list = {}
    # stack entries: (container, column of its keys/items, parent, key-in-parent)
    stack: list = [[container, base, None, None]]
    i = 0
    while i < len(tokens):
        kind, col, key, val, _ln = tokens[i]
        i += 1
        while len(stack) > 1 and col < stack[-1][1]:
            stack.pop()
        top = stack[-1]
        if kind == "seq":
            if not isinstance(top[0], list):
                # a sequence beginning where a mapping was expected: open it in the parent
                if len(stack) > 1 and isinstance(stack[-2][0], dict):
                    pass
                parent = stack[-2][0] if len(stack) > 1 else None
                pkey = stack[-1][2]
                new = []
                if isinstance(parent, dict):
                    parent[pkey] = new
                stack[-1][0] = new
                top = stack[-1]
            item: dict = {}
            top[0].append(item)
            stack.append([item, col + 2, top[0], None])
            continue
        if kind == "val":
            if isinstance(top[0], list):
                top[0].append(_scalar(val or ""))
            continue
        if kind != "key":
            continue
        if isinstance(top[0], list) and len(stack) > 1:
            pass
        target = top[0]
        if not isinstance(target, dict):
            if len(stack) > 1:
                target = stack[-2][0]
            else:
                continue
        if val == "":
            # A block follows; decide dict-or-list by the next token, and let it fix its own column.
            nxt = tokens[i] if i < len(tokens) else None
            child: object
            if nxt is not None and (nxt[0] == "seq" or (nxt[0] == "key" and nxt[1] > col)):
                child = [] if nxt[0] == "seq" else {}
            else:
                child = ""
            target[key] = child
            if isinstance(child, dict):
                stack.append([child, nxt[1] if nxt else col + 2, target, key])
            elif isinstance(child, list):
                stack.append([child, nxt[1] if nxt else col + 2, target, key])
            continue
        target[key] = _scalar(val)
        # A mapping that opened lazily (unknown column) takes its column from this key.
        if top[1] is None:
            top[1] = col
    return container



def _cross_check(path: pathlib.Path, doc) -> None:
    """When PyYAML is installed, make it agree with the internal reader before either is trusted.

    A drift here means the reader mis-seeded a mapping or swallowed a block scalar, and the honest response
    is to say that the checker is unsure — a gate silently reading a different document than the developer
    tested against is how an invalid workflow reaches Actions and four jobs never start.
    """
    if not HAVE_YAML:
        return
    try:
        ref = yaml.safe_load(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001 - PyYAML rejecting the file is itself the finding
        print(f"::warning file={disp_path(path)}::PyYAML cannot parse this file ({exc}); "
              f"the runner will reject it too")
        return
    if isinstance(ref, dict) and isinstance(doc, dict) and set(ref) != set(doc):
        print(f"::warning file={disp_path(path)}::the internal reader saw top-level keys {sorted(doc)} "
              f"where PyYAML saw {sorted(ref)} - the structural rules may have skipped something; "
              f"fix the reader, do not relax this warning")


def check_action(path: pathlib.Path) -> list[str]:
    out = []
    doc = load_block(path)
    if not isinstance(doc, dict):
        return [f"{disp_path(path)}: the reader expected a mapping at the top level and found a sequence"]
    _cross_check(path, doc)
    inputs = doc.get("inputs") or {}
    if not isinstance(inputs, dict):
        return [f"{disp_path(path)}: 'inputs:' is not a mapping, so its declarations cannot be checked"]
    for name, spec in inputs.items():
        if not isinstance(spec, dict):
            out.append(f"{path}: input '{name}' is not a mapping")
            continue
        if spec.get("required") and "default" in spec:
            out.append(f"{path}: input '{name}' has both required: true and a default - Actions rejects the file")
    text = path.read_text(encoding="utf-8")
    declared = set(inputs)
    for m in re.finditer(r"inputs\.([A-Za-z0-9_-]+)", text):
        if m.group(1) not in declared:
            out.append(f"{path}: ${{{{ inputs.{m.group(1)} }}}} is not declared under inputs:")
    runs = doc.get("runs") or {}
    if not isinstance(runs, dict):
        return out
    if runs.get("using") == "composite":
        for i, step in enumerate(runs.get("steps") or []):
            if not isinstance(step, dict):
                continue
            if "uses" in step and "run" in step:
                out.append(f"{path}: step {i} has both 'uses' and 'run'")
            if "run" in step and "shell" not in step:
                out.append(f"{path}: step {i} ('{step.get('name', step.get('id', i))}') runs a script with no shell:")
            for k in ("services", "container", "timeout-minutes"):
                if k in step:
                    out.append(f"{path}: step {i} uses '{k}', which composite actions do not allow")
            cond = step.get("if") or ""
            for fn in ("always()", "failure(", "success(", "cancelled("):
                if fn in cond:
                    out.append(f"{path}: step {i} ('{step.get('name', i)}') uses {fn} in if: - a composite "
                              f"action's step conditions have no such functions, and Actions refuses to run "
                              f"the action (use steps.<id>.outcome plus continue-on-error, and decide the "
                              f"verdict in a final step)")
    return out


def check_workflows(workflows: list[pathlib.Path], actions: dict[str, pathlib.Path]) -> list[str]:
    out = []
    for path in workflows:
        doc = load_block(path)
        for job, jd in (doc.get("jobs") or {}).items():
            if jd.get("needs"):
                needs = jd["needs"]
                needs = [needs] if isinstance(needs, str) else needs
                for dep in needs:
                    if dep not in (doc.get("jobs") or {}):
                        out.append(f"{path}: job '{job}' needs unknown job '{dep}'")
            for i, step in enumerate(jd.get("steps") or []):
                uses = str(step.get("uses") or "")
                if uses.startswith("./"):
                    # The runner resolves a path-based action from the workspace before the action's own
                    # steps run, so a checkout inside the action is too late - the job has to do it first.
                    if not any(str(s.get("uses") or "").startswith("actions/checkout")
                               for s in (jd.get("steps") or [])[:i]):
                        out.append(f"{path}: job '{job}' runs the local action '{uses}' at step {i+1} with "
                                  f"no actions/checkout step before it - add "
                                  f"'- uses: actions/checkout@v4' to the JOB, above it")
                    rel = uses[2:].lstrip("/")
                    apath = actions.get(rel) or (ROOT / rel / "action.yml")
                    if not pathlib.Path(apath).is_file():
                        out.append(f"{path}: job '{job}' step {i} uses '{uses}' - no action.yml there")
                        continue
                    spec = load_block(pathlib.Path(apath))
                    declared = set(spec.get("inputs") or {})
                    for key in (step.get("with") or {}):
                        if key not in declared:
                            out.append(f"{path}: job '{job}' passes 'with.{key}' to {uses}, which does not declare it")
                    declared_specs = {k: v for k, v in (spec.get("inputs") or {}).items() if isinstance(v, dict)}
                    required = {k for k, v in declared_specs.items() if v.get("required")}
                    for miss in sorted(required - set(step.get("with") or {})):
                        out.append(f"{path}: job '{job}' does not pass required input '{miss}' to {uses}")
    return out


def main() -> int:
    problems: list[str] = []
    workflows = sorted((ROOT / ".github" / "workflows").glob("*.yml"))
    actions = {p.parent.relative_to(ROOT).as_posix(): p
               for p in (ROOT / ".github" / "actions").rglob("action.yml")}
    for path in workflows + list(actions.values()):
        problems += dup_keys(path)
    for path in actions.values():
        problems += check_action(path)
    problems += check_workflows(workflows, actions)
    for p in problems:
        print(f"FAIL  {p.replace(str(ROOT) + '/', '')}")
    extra = "" if HAVE_YAML else "  (PyYAML is absent, so only the indentation checks ran: pip install pyyaml for the rest)"
    print(f"\n{len(problems)} problem(s) in {len(workflows)} workflow file(s), {len(actions)} action(s).{extra}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
