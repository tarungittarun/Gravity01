#!/usr/bin/env python3
"""Regression set for the workflow/action linter, run by `plan` before the files that linter guards.

It exists because the linter missed a bug of its own making: a scripted patch added a second `default:` to a
`workflow_dispatch` input, `check_workflows.py` said zero problems, and Actions rejected the whole file - four
rows never started and the API showed nothing. A gate that is not tested is a gate that is being trusted.

Both directions matter. The first scanner emitted 155 false positives because every `- name:`/`run:` pair in
a job sits at the same column, and a checker that cries wolf gets a `|| true` added within a day - which is
how the duplicate `if:` in a report job survived six runs.
"""
from __future__ import annotations

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import check_workflows as cw  # noqa: E402

# (label, document, checker, expected number of findings)
CASES = [
    ("duplicate key inside one list item",
     "jobs:\n  a:\n    steps:\n      - name: one\n        if: always()\n        run: x\n        if: failure()\n",
     "dup", 1),
    ("the same key once per list item is not a duplicate",
     "jobs:\n  a:\n    steps:\n      - name: one\n        if: always()\n        run: x\n"
     "      - name: two\n        if: failure()\n        run: 'echo'\n",
     "dup", 0),
    ("workflow_dispatch input with two defaults",
     "on:\n  workflow_dispatch:\n    inputs:\n      x:\n        description: d\n        type: string\n"
     "        default: '1'\n        default: '2'\n",
     "dup", 1),
    ("block scalar containing key-shaped lines",
     "jobs:\n  a:\n    steps:\n      - name: one\n        run: |\n          case \"$x\" in\n"
     "            a) echo \"y: z\" ;;\n          esac\n          echo 'not: a key'\n"
     "      - name: two\n        run: echo second\n",
     "dup", 0),
    ("duplicate deep inside a job's env block",
     "jobs:\n  a:\n    env:\n      FOO: 1\n      BAR: 2\n      FOO: 3\n    steps: []\n",
     "dup", 1),
    ("job with needs twice",
     "jobs:\n  a:\n    needs: [b]\n    needs: [c]\n    steps: []\n",
     "dup", 1),
    ("two steps whose block scalars repeat a key",
     "jobs:\n  a:\n    steps:\n      - name: x\n        run: |\n          a: 1\n"
     "      - name: y\n        run: |\n          a: 2\n",
     "dup", 0),
    ("action input with required and default together",
     "inputs:\n  token:\n    description: t\n    required: true\n    default: x\n"
     "runs:\n  using: composite\n  steps:\n    - run: echo\n      shell: bash\n",
     "action", 1),
    ("composite run step without a shell",
     "inputs: {}\nruns:\n  using: composite\n  steps:\n    - name: oops\n      run: echo hi\n",
     "action", 1),
    ("composite step using always() in its condition",
     "inputs: {}\nruns:\n  using: composite\n  steps:\n    - name: oops\n      if: always()\n"
     "      run: echo\n      shell: bash\n",
     "action", 1),
    ("a well-formed composite action",
     "inputs:\n  token:\n    description: t\n    required: true\nruns:\n  using: composite\n  steps:\n"
     "    - name: ok\n      run: echo\n      shell: bash\n",
     "action", 0),
]


def main() -> int:
    tmp = pathlib.Path("/tmp/check_workflows_selftest")
    tmp.mkdir(exist_ok=True)
    bad = 0
    for i, (label, text, checker, want) in enumerate(CASES):
        f = tmp / f"case{i}.yml"
        f.write_text(text)
        got = len(cw.check_action(f) if checker == "action" else cw.dup_keys(f))
        if got != want:
            bad += 1
            print(f"  FAIL {label:52s} {got} finding(s), expected {want}")
        else:
            print(f"  ok   {label:52s} {want} as expected")
    print(f"\n{len(CASES) - bad}/{len(CASES)} linter cases behave as documented.")
    for f in tmp.glob("case*.yml"):
        f.unlink()
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
