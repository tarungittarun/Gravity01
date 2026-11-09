#!/usr/bin/env python3
"""Put the rendered screens in front of whoever has to judge them, without a device.

The `ui-preview` CI job paints every screen with Robolectric and writes PNGs, which are uploaded as an
artifact. An artifact is no use to the sandbox that has to review this work (the Actions file host is not
reachable from it), so the small JPEGs are additionally posted as base64 onto the commit — the REST API is
reachable, and GitHub's comment cap (65 536 characters) is what the `--max-kb` downscale respects.

Decode one locally with:

    gh api repos/OWNER/REPO/commits/SHA/comments --jq '.[].body' | \\
      sed -n '/```/{s/```//;h};/base64/,/base64/{g;/base64/d;p}'   # or: copy the block, `base64 -d`
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

LIMIT = 60_000  # characters of a comment body; GitHub rejects 65 537
FENCE = "```"


def post(url: str, token: str, payload: dict) -> tuple[int, str]:
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
            "User-Agent": "opentrack-ui-preview",
            "X-GitHub-Api-Version": "2022-11-28",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read()[:200].decode(errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read()[:400].decode(errors="replace")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="app/build/reports/previews")
    ap.add_argument("--glob", default="*.jpg")
    ap.add_argument("--label", default="ui-preview")
    ap.add_argument("--max-kb", type=int, default=44, help="skip images whose base64 would exceed this")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    repo = os.environ.get("GITHUB_REPOSITORY", "")
    sha = os.environ.get("GITHUB_SHA", "")
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or ""
    api = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")

    files = sorted(Path(args.dir).glob(args.glob))
    if not files:
        # Not an error here: when the compile itself failed there are no previews, and the job is already
        # red for the real reason. A second red step would only bury that.
        print(f"::notice::no previews in {args.dir} — the render tests did not run", file=sys.stderr)
        return 0

    posted = 0
    for f in files:
        b64 = base64.b64encode(f.read_bytes()).decode()
        head = f"### {args.label} · `{f.name}`\n\n" \
               f"Painted by `:app:testDebugUnitTest` under Robolectric (360×760dp @ xhdpi). " \
               f"Decode: `base64 -d` the block below.\n\n"
        body = head + FENCE + "\n" + b64 + "\n" + FENCE + "\n"
        if len(b64) > args.max_kb * 1024:
            print(f"{f.name}: {len(b64)} chars of base64 > {args.max_kb} KiB budget, not posted "
                  "(it is in the ui-previews artifact)")
            continue
        if args.dry_run:
            print(f"{f.name}: {len(body)} chars — would post")
            posted += 1
            continue
        if not (repo and sha and token):
            print("GITHUB_REPOSITORY / GITHUB_SHA / token missing; nothing to post to", file=sys.stderr)
            return 0 if len(files) else 1
        status, detail = post(f"{api}/repos/{repo}/commits/{sha}/comments", token, {"body": body})
        print(f"{f.name}: HTTP {status} ({len(body)} chars) {detail if status >= 300 else ''}")
        if status >= 300:
            return 1
        posted += 1
    print(f"posted {posted}/{len(files)} previews")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
