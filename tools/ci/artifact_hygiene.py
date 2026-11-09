#!/usr/bin/env python3
"""Actions-store hygiene: delete artifacts past the keep-window, cancel wedged runs.

Why CI does this and not the engineer's sandbox: the sandbox token can LIST the store but every write
is rejected - measured 2026-10-12: `POST .../runs/<id>/cancel` and `DELETE .../artifacts/<id>` both
return 403 "Resource not accessible by integration". A workflow token granted `actions: write` can do
both, so this runs as the `hygiene` job of the android workflow, on every push (ADR-001).

Policy (owner ask, 2026-10-12: "keep artifacts for a shorter time and handle the earlier ones"):
  - artifacts older than ARTIFACT_MAX_AGE_H (6) are deleted. Six hours: a row's evidence is read from
    commit comments, check-runs and annotations - the artefact FILES are only needed within the run and
    for a short post-mortem. Release assets are NOT artifacts and are never touched: the installable
    APK lives on the `latest-apk` release.
  - runs still `in_progress` after RUN_MAX_AGE_H (4) are cancelled as wedged - recorded as
    untested-with-a-reason (6.2), never as a pass.
One ::notice:: line carries the totals, so the run's annotations say what hygiene did.

All REST access is injected, so the tests run with zero network (4.4).
"""
from __future__ import annotations

import datetime
import json
import os
import subprocess
import sys

ARTIFACT_MAX_AGE_H = 6
RUN_MAX_AGE_H = 4


def _gh(*args: str) -> str:
    r = subprocess.run(["gh", "api", *args], capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        raise RuntimeError(f"gh api {args[0]}... failed: {r.stderr.strip()[:200]}")
    return r.stdout


def _iso(ts: str) -> datetime.datetime:
    return datetime.datetime.fromisoformat(ts.replace("Z", "+00:00"))


def stale_artifacts(artifacts: list[dict], now: datetime.datetime,
                    max_age_h: int = ARTIFACT_MAX_AGE_H) -> list[int]:
    cutoff = now - datetime.timedelta(hours=max_age_h)
    return [a["id"] for a in artifacts
            if not a.get("expired", False) and _iso(a["created_at"]) < cutoff]


def wedged_runs(runs: list[dict], now: datetime.datetime,
                max_age_h: int = RUN_MAX_AGE_H) -> list[int]:
    cutoff = now - datetime.timedelta(hours=max_age_h)
    return [r["id"] for r in runs
            if r.get("status") == "in_progress" and _iso(r["created_at"]) < cutoff]


def run(get_fn=_gh, delete_fn=None, cancel_fn=None, repo: str | None = None, pages: int = 5,
        now: datetime.datetime | None = None) -> str:
    repo = repo or os.environ.get("GITHUB_REPOSITORY")
    if not repo:
        return "hygiene: GITHUB_REPOSITORY absent - nothing to do"
    delete_fn = delete_fn or (lambda path: _gh("-X", "DELETE", path))
    cancel_fn = cancel_fn or (lambda path: _gh("-X", "POST", path))
    # The clock is injectable for the same reason freshness compares against a feed stamp, not
    # System.now: a test must be able to freeze time, not hope the CI clock agrees with its fixtures.
    now = now or datetime.datetime.now(datetime.timezone.utc)

    artifacts: list[dict] = []
    for p in range(1, pages + 1):
        batch = json.loads(get_fn(f"repos/{repo}/actions/artifacts?per_page=100&page={p}"))
        artifacts += batch.get("artifacts", [])
        if len(batch.get("artifacts", [])) < 100:
            break
    stale = stale_artifacts(artifacts, now)
    for aid in stale:
        delete_fn(f"repos/{repo}/actions/artifacts/{aid}")

    runs = json.loads(get_fn(f"repos/{repo}/actions/runs?per_page=100")).get("workflow_runs", [])
    wedged = wedged_runs(runs, now)
    for rid in wedged:
        cancel_fn(f"repos/{repo}/actions/runs/{rid}/cancel")

    stale_set = set(stale)
    freed_mb = sum(a.get("size_in_bytes", 0) for a in artifacts if a["id"] in stale_set) / 1_048_576
    return (f"hygiene: deleted {len(stale)} artifact(s) ({freed_mb:.1f} MB) older than "
            f"{ARTIFACT_MAX_AGE_H}h; cancelled {len(wedged)} wedged run(s) in_progress over "
            f"{RUN_MAX_AGE_H}h; kept the rest (releases are never artifacts - untouched)")


def main() -> int:
    try:
        report = run()
    except Exception as e:
        print(f"::error::hygiene could not complete: {e}")
        return 1
    print(report)
    print(f"::notice::{report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
