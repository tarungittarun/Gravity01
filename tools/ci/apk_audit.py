#!/usr/bin/env python3
"""Strict, evidence-backed audit of the installable APK and the SAME SHA's CI runs.

Never call a skipped/queued test green. Exit red on a failed domain, missing artifact,
wrong release, unexamined device profile, or breached 180-second job/workflow budget.
No third-party Python packages, no live train-position claims, no hidden retries.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

from verify_acceptance_apk import cert_sha256

PARTS = ("ui-render", "ui-logic", "rules", "sources")
PROFILES = ("api34", "min24", "density")
EXPECTED_ROWS = {f"{p}-{row}" for p in PROFILES for row in
                 ("station0", "station1", "station2", "train", *(f"track{i}" for i in range(4)),
                  "settings-ui", "settings-net")} | {"release"}
BUDGET = 180


def command(*args: str, timeout: int = 90) -> str:
    p = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                       timeout=timeout, check=False)
    if p.returncode:
        raise RuntimeError(f"{' '.join(args[:5])} exited {p.returncode}: {p.stderr[-450:]}")
    return p.stdout


def gh(path: str) -> dict | list:
    return json.loads(command("gh", "api", "-X", "GET", "-f", "per_page=100", path))


def seconds(start: str | None, end: str | None) -> int | None:
    if not start or not end:
        return None
    def stamp(value: str) -> dt.datetime:
        return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    return max(0, int((stamp(end) - stamp(start)).total_seconds()))


def jobs(repo: str, run_id: int) -> list[dict]:
    result = gh(f"repos/{repo}/actions/runs/{run_id}/jobs")
    if result["total_count"] > len(result["jobs"]):
        raise RuntimeError("more than 100 jobs; refusing an incomplete audit")
    return result["jobs"]


def details(job: dict) -> str:
    bad = [s["name"] for s in job.get("steps", []) if s.get("conclusion") == "failure"]
    return f"{job['name']} ({job.get('conclusion')}); failing steps: {', '.join(bad) or 'not reported'}"


def unit_results(root: Path) -> tuple[dict, list[str]]:
    stats = {p: {"tests": 0, "skipped": 0, "failed": 0, "classes": set()} for p in PARTS}
    failures: list[str] = []
    for part in PARTS:
        files = list((root / f"unit-results-{part}").rglob("*.xml"))
        if not files:
            failures.append(f"{part}: no JUnit XML; no tests can be credited")
            continue
        for file in files:
            try:
                doc = ET.parse(file).getroot()
            except ET.ParseError as exc:
                failures.append(f"{part}/{file.name}: invalid JUnit XML: {exc}")
                continue
            for case in doc.iter("testcase"):
                stats[part]["tests"] += 1
                name = f"{case.get('classname', '?')}.{case.get('name', '?')}"
                stats[part]["classes"].add(case.get("classname", "?"))
                if case.find("skipped") is not None:
                    stats[part]["skipped"] += 1
                    failures.append(f"{part}: SKIPPED {name}")
                for tag in ("failure", "error"):
                    node = case.find(tag)
                    if node is not None:
                        stats[part]["failed"] += 1
                        msg = (node.get("message") or node.text or "unknown assertion").splitlines()[0]
                        failures.append(f"{part}: {name}: {msg[:280]}")
    return stats, failures


def apk_inspection(apk: Path, repo: Path) -> tuple[dict, list[str]]:
    outcome: dict = {"file": str(apk)}
    bad: list[str] = []
    if not apk.is_file():
        return outcome, [f"APK missing: {apk}"]
    data = apk.read_bytes()
    outcome["size"] = len(data)
    outcome["sha256"] = hashlib.sha256(data).hexdigest()
    if not 1_000_000 < len(data) < 20_000_000:
        bad.append(f"APK size {len(data)} violates 1–20 MB policy")
    try:
        with zipfile.ZipFile(apk) as z:
            damaged = z.testzip()
            if damaged:
                bad.append(f"APK ZIP CRC failure: {damaged}")
            names = set(z.namelist())
            outcome["dex"] = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", n))
            if not outcome["dex"] or "AndroidManifest.xml" not in names:
                bad.append("APK lacks compiled DEX or manifest")
            asset = "assets/ref/refdb.dbgz"
            if asset not in names:
                bad.append("offline timetable asset missing from APK")
            else:
                content = z.read(asset)
                outcome["offline_asset_bytes"] = len(content)
                if not content.startswith(b"\x1f\x8b") or len(content) < 100_000:
                    bad.append("offline timetable asset is empty or not gzip")
            outcome["dex_digests"] = {n: hashlib.sha256(z.read(n)).hexdigest()
                                      for n in outcome["dex"]}
    except (OSError, zipfile.BadZipFile) as exc:
        bad.append(f"APK cannot be opened: {exc}")
        return outcome, bad
    build = (repo / "app/build.gradle").read_text()
    expected_code = re.search(r"versionCode\s+(\d+)", build)
    expected_name = re.search(r'versionName\s+"([^"]+)"', build)
    sdk = Path(os.environ.get("ANDROID_HOME", "/usr/local/lib/android/sdk")) / "build-tools"
    tools = sorted(sdk.glob("*/aapt")) if sdk.exists() else []
    signers = sorted(sdk.glob("*/apksigner")) if sdk.exists() else []
    if not tools or not signers:
        bad.append("Android build tools missing; manifest/signature NOT verified")
        return outcome, bad
    try:
        info = command(str(tools[-1]), "dump", "badging", str(apk))
        pkg = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", info)
        if not pkg:
            bad.append("aapt could not decode package identity")
        else:
            outcome["package"] = pkg.groups()
            if pkg.groups() != ("in.opentrack.india", expected_code.group(1), expected_name.group(1)):
                bad.append(f"APK identity {pkg.groups()} differs from source version and package")
        raw = command(str(signers[-1]), "verify", "--verbose", "--print-certs", str(apk))
        cert = cert_sha256(raw)
        if not cert:
            # apksigner exited zero: it verified the cryptographic signature. The
            # certificate parser did not, so key continuity is UNKNOWN, not 'unsigned'.
            bad.append("apksigner verified the signature, but the certificate digest was not parsed; "
                       f"update-key continuity unverified. Raw: {raw[-500:].strip()!r}")
        else:
            outcome["cert_sha256"] = cert
            key = command("keytool", "-list", "-v", "-keystore",
                          str(repo / "tools/ci/test-only-debug.p12"), "-storetype", "PKCS12",
                          "-storepass", "android")
            fp = re.search(r"SHA256:\s*([\dA-Fa-f:]+)", key)
            if not fp or outcome["cert_sha256"] != fp.group(1).replace(":", "").lower():
                bad.append("APK signature differs from stable update key")
        perms = set(re.findall(r"uses-permission: name='(android.permission.[^']+)'",
                               command(str(tools[-1]), "dump", "permissions", str(apk))))
        allowed = set(re.findall(r'<uses-permission android:name="(android.permission.[^"]+)"',
                                 (repo / "app/src/main/AndroidManifest.xml").read_text()))
        outcome["permissions"] = sorted(perms)
        if perms != allowed:
            bad.append(f"APK permissions mismatch manifest: unexpected={sorted(perms - allowed)} "
                       f"missing={sorted(allowed - perms)}")
    except (RuntimeError, FileNotFoundError) as exc:
        bad.append(f"manifest/signing verification failed: {exc}")
    return outcome, bad


def summarize(android: dict, e2e: dict, android_jobs: list[dict], e2e_jobs: list[dict],
              unit: dict, unit_bad: list[str], apk: dict, apk_bad: list[str],
              release: dict, release_bad: list[str], comments: list[dict],
              sha: str, same_dex: bool | None, artifacts: list[str]) -> tuple[str, bool]:
    failures: list[str] = []
    findings: list[tuple[str, str, str]] = []
    def gate(area: str, ok: bool, evidence: str, cause: str) -> None:
        findings.append((area, "PASS" if ok else "FAIL", evidence))
        if not ok:
            failures.append(f"**{area}:** {cause}")
    aj = {j["name"]: j for j in android_jobs}
    ej = {j["name"]: j for j in e2e_jobs}
    for label, run in (("Android CI", android), ("E2E CI", e2e)):
        verdict = run.get("conclusion") == "success" and run.get("head_sha") == sha
        # The audit is a job INSIDE the E2E run, so that run cannot have a final
        # conclusion yet. All its prerequisites must have completed successfully.
        if label == "E2E CI" and run.get("status") == "in_progress":
            prereqs = ("plan", "build", "instrument", "release_build", "report", "release_smoke")
            verdict = run.get("head_sha") == sha and all(
                any(j["name"] == n and j.get("conclusion") == "success" for j in e2e_jobs)
                for n in prereqs)
        gate(label, verdict,
             f"[run {run.get('id')}]({run.get('html_url')}) · {run.get('conclusion') or 'audit running'} · SHA {str(run.get('head_sha'))[:7]}",
             f"current-SHA run {run.get('id')} is {run.get('conclusion') or 'incomplete'}")
    for label, job in (("APK compile", "debug APK"), ("Android field-test compile", "field-tests"),
                       ("Static/source gates", "checks"), ("Publish installable APK", "publish"),
                       ("Rendered-screen publication", "screens")):
        j = aj.get(job)
        gate(label, bool(j and j.get("conclusion") == "success"),
             details(j) if j else "job absent", details(j) if j else f"missing Android job {job}")
    acceptance = {f"{profile}/{group}": aj.get(f"traveller / published APK / {profile} / {group}")
                  for profile in ("online", "offline")
                  for group in ("journey", "trust", "refresh")}
    gate("End-user acceptance on exact published APK",
         all(j and j.get("conclusion") == "success" for j in acceptance.values()),
         "; ".join(f"{p}: {details(j) if j else 'job absent'}" for p, j in acceptance.items()),
         "; ".join(f"{p}: {details(j) if j else 'job absent'}" for p, j in acceptance.items()
                   if not j or j.get("conclusion") != "success"))
    total = sum(v["tests"] for v in unit.values())
    skipped = sum(v["skipped"] for v in unit.values())
    tested = ", ".join(f"{p} {unit[p]['tests']}" for p in PARTS)
    gate("Backend contracts/data & frontend rules", total >= 300 and not unit_bad and
         all(unit[p]["tests"] for p in PARTS), f"{total} JUnit cases ({tested}); {skipped} skipped",
         "; ".join(unit_bad[:5]) or f"only {total} cases / a partition ran no tests")
    gate("Visual/layout assertions", unit["ui-render"]["tests"] >= 20 and
         not unit["ui-render"]["failed"] and not unit["ui-render"]["skipped"],
         f"{unit['ui-render']['tests']} render cases · saved pixels + narrow/large-font bounds",
         "render test failure, skip, or fewer than 20 assertions")
    gate("Source/parser integrity", unit["sources"]["tests"] >= 30 and
         not unit["sources"]["failed"] and not unit["sources"]["skipped"],
         f"{unit['sources']['tests']} source contract/transport tests (NOT phone-app telemetry)",
         "source contract tests failed or insufficient source tests")
    gate("APK binary, asset, permissions and update signing", not apk_bad and bool(apk.get("sha256")),
         f"{apk.get('size', 'unknown')} bytes · SHA-256 {str(apk.get('sha256','?'))[:16]} · "
         f"{apk.get('package', '?')} · offline {apk.get('offline_asset_bytes','?')} bytes",
         "; ".join(apk_bad) or "APK not inspected")
    gate("Rolling download equals audited APK", not release_bad and release.get("sha256") == apk.get("sha256"),
         f"release SHA-256 {str(release.get('sha256','?'))[:16]} · "
         f"{release.get('size','?')} bytes", "; ".join(release_bad) or "downloaded bytes differ")
    gate("Independently built E2E APK correspondence", same_dex is True,
         "compiled DEX hashes " + ("match" if same_dex else "unverified/different"),
         "E2E matrix used a different/unavailable build; only a successful exact-published-APK acceptance job can certify the download")
    rows = {re.search(r"^e2e(?:_full)? \(([^,]+),", name).group(1): j
            for name, j in ej.items() if re.search(r"^e2e(?:_full)? \(([^,]+),", name)}
    # Release smoke is a separate job, not an `e2e (...)` matrix entry.
    if "release_smoke" in ej:
        rows["release"] = ej["release_smoke"]
    missing = EXPECTED_ROWS - rows.keys()
    failed_rows = [f"{r}: {details(j)}" for r, j in rows.items()
                   if j.get("conclusion") != "success"]
    gate("Installed UI/UX, offline, device/density & release smoke",
         not missing and not failed_rows and len(rows) == len(EXPECTED_ROWS) and
         ej.get("report", {}).get("conclusion") == "success",
         f"{sum(j.get('conclusion') == 'success' for j in rows.values())}/{len(rows)} rows passed · "
         f"{len(missing)} missing · API 24, API 34, density, release",
         "; ".join((failed_rows + [f"missing: {sorted(missing)}"] if missing else failed_rows)[:5])
         or f"roll-up job {ej.get('report', {}).get('conclusion','absent')}")
    # CI has no privileged Google phone-app telemetry feed. A web page refresh is not
    # a train position; a source probe is separate from a passing parser fixture.
    findings.append(("Live train data parity", "UNVERIFIED",
                     "No authenticated Where Is My Train phone-app feed; website/cache ≠ phone telemetry"))
    failures.append("**Live train data parity:** no verified phone-app feed; do not certify minute-by-minute movement from a website timestamp")
    durations = []
    for workflow, run, group in (("android", android, android_jobs), ("e2e", e2e, e2e_jobs)):
        wall = seconds(run.get("created_at"), run.get("updated_at") if run.get("status") == "completed"
                       else dt.datetime.now(dt.timezone.utc).isoformat())
        durations.append((f"**{workflow} workflow**", wall, "wall including GitHub queue"))
        for j in group:
            if j.get("name", "").startswith("audit / ") or j.get("name") == "audit":
                continue  # the measuring job cannot know its own final duration
            if j.get("conclusion") == "skipped":
                continue
            durations.append((f"{workflow} / {j['name']}",
                              seconds(j.get("started_at"), j.get("completed_at")),
                              j.get("conclusion", "unknown")))
    slow = [(n, sec) for n, sec, _ in durations if sec is None or sec > BUDGET]
    gate("Three-minute workflow & job budget", not slow,
         f"{len(slow)} of {len(durations)} measured jobs/workflows over {BUDGET}s or unmeasured",
         ", ".join(f"{n}: {sec if sec is not None else '?'}s" for n, sec in slow[:7]))
    msg = [f"# APK quality audit · {sha[:7]}", "",
           "**STRICT VERDICT: " + ("FAIL" if failures else "PASS") + "**. A green compile alone is not a release pass.",
           "", "## Scope and result", "", "| Area | Verdict | Evidence |", "| --- | --- | --- |"]
    for area, result, evidence in findings:
        msg.append(f"| {area} | **{result}** | {evidence.replace('|', '/')} |")
    msg += ["", "## Root causes and actionable failures", ""]
    msg += [f"{i}. {reason}" for i, reason in enumerate(failures, 1)] or ["No failed gates."]
    msg += ["", "## Performance · wall clocks (not timeouts)", "",
            "| Workflow / job | Seconds | Result / interpretation |", "| --- | ---: | --- |"]
    for name, sec, context in sorted(durations, key=lambda x: -(x[1] or 0)):
        msg.append(f"| {name.replace('|', '/')} | {sec if sec is not None else 'not measured'} | "
                   f"{'OVER 180s' if sec is None or sec > BUDGET else context} |")
    msg += ["", "## Specific test failures (source evidence)", ""]
    for item in (unit_bad[:12] or []):
        msg.append(f"- {item}")
    for row in failed_rows:
        msg.append(f"- Device: {row}")
    # The row harness posts its exact instrumentation failure onto the commit.
    for c in comments:
        body = c.get("body") or ""
        if not re.search(r"### e2e/[^\n]+(?:FAIL|failed)", body, re.I):
            continue
        header = body.splitlines()[0]
        error = re.search(r"Error in [^\n]+|java\.lang\.AssertionError:[^\n]+", body)
        msg.append(f"- {header}: {error.group(0)[:300] if error else 'inspect row artifact for stack'}")
    msg += ["", "## Limits and next action", "",
            "- Unit tests use deterministic fixtures; they do not prove that a live upstream changed or that the phone app exposed an API.",
            "- Device rows install an APK and measure coverage, cold-start, permissions, offline behaviour and screen geometry; unbooted/failed rows are red.",
            "- A performance breach is a **FAIL**, not a longer timeout masquerading as compliance. Split or speed the named critical path and measure the replacement run.",
            "- No APK is certified while a domain above is FAIL or UNVERIFIED; PR merging requires the owner's device confirmation.",
            "", "Artifact collection notes: " + ("; ".join(artifacts) if artifacts else "all requested artifacts obtained"), ""]
    return "\n".join(msg), not failures


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "tarungittarun/Checktrain02"))
    ap.add_argument("--sha", required=True)
    ap.add_argument("--e2e-run", required=True, type=int)
    ap.add_argument("--android-run", type=int)
    ap.add_argument("--out", default="apk-audit-report.md")
    ap.add_argument("--metadata-only", action="store_true", help="local investigation; do not pretend APK was inspected")
    args = ap.parse_args()
    out = Path(args.out)
    root = Path(__file__).resolve().parents[2]
    notes: list[str] = []
    try:
        e2e = gh(f"repos/{args.repo}/actions/runs/{args.e2e_run}")
        if e2e.get("head_sha") != args.sha:
            raise RuntimeError("E2E run belongs to a DIFFERENT SHA")
        if args.android_run:
            android_id = args.android_run
        else:
            runs = gh(f"repos/{args.repo}/actions/workflows/android.yml/runs?branch={e2e['head_branch']}")
            matching = [r for r in runs.get("workflow_runs", []) if r.get("head_sha") == args.sha]
            if not matching:
                raise RuntimeError("no Android workflow on the SAME SHA (never accept an older APK)")
            android_id = matching[0]["id"]
        android = gh(f"repos/{args.repo}/actions/runs/{android_id}")
        if android.get("head_sha") != args.sha:
            raise RuntimeError("Android run belongs to a DIFFERENT SHA")
        aj, ej = jobs(args.repo, android_id), jobs(args.repo, args.e2e_run)
        if args.metadata_only:
            notes.append("metadata-only local audit: APK/JUnit/artifacts not fetched; no product pass claimed")
        else:
            output = out.parent / "audit-input"
            output.mkdir(parents=True, exist_ok=True)
            for folder in ("unit", "android-apk", "device-apk", "release"):
                (output / folder).mkdir(exist_ok=True)
            for pattern, dest, run in (("unit-results-*", "unit", android_id),
                                        ("opentrack-debug-apk", "android-apk", android_id),
                                        ("apk-debug", "device-apk", args.e2e_run)):
                try:
                    selector = "-p" if "*" in pattern else "-n"
                    command("gh", "run", "download", str(run), selector, pattern,
                            "-D", str(output / dest), timeout=90)
                except (RuntimeError, subprocess.TimeoutExpired) as exc:
                    notes.append(f"{pattern}: {exc}")
            try:
                command("gh", "release", "download", "latest-apk", "-p", "app-debug.apk",
                        "-D", str(output / "release"), timeout=90)
            except (RuntimeError, subprocess.TimeoutExpired) as exc:
                notes.append(f"rolling release: {exc}")
        output = out.parent / "audit-input"
        unit, unit_bad = unit_results(output / "unit")
        apk, apk_bad = apk_inspection(output / "android-apk" / "app-debug.apk", root)
        release_path = output / "release" / "app-debug.apk"
        release = {"size": release_path.stat().st_size,
                   "sha256": hashlib.sha256(release_path.read_bytes()).hexdigest()} if release_path.is_file() else {}
        release_bad = [] if release else ["rolling release APK was not downloaded"]
        device, device_bad = apk_inspection(output / "device-apk" / "app-debug.apk", root)
        same_dex = apk.get("dex_digests") == device.get("dex_digests") if apk.get("dex_digests") and device.get("dex_digests") else None
        if device_bad:
            notes.append("device APK inspection: " + "; ".join(device_bad))
        meta = gh(f"repos/{args.repo}/releases/tags/latest-apk")
        if args.sha[:7] not in meta.get("body", ""):
            release_bad.append("rolling release notes point to a different commit")
        comments: list[dict] = []
        for page in range(1, 4):
            batch = gh(f"repos/{args.repo}/commits/{args.sha}/comments?page={page}")
            comments.extend(batch)
            if len(batch) < 100:
                break
        report, ok = summarize(android, e2e, aj, ej, unit, unit_bad, apk, apk_bad,
                               release, release_bad, comments, args.sha, same_dex, notes)
    except Exception as exc:
        report = f"# APK quality audit · {args.sha[:7]}\n\n**STRICT VERDICT: FAIL**\n\n" \
                 f"Audit infrastructure/root cause: {type(exc).__name__}: {exc}\n"
        ok = False
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(report, encoding="utf-8")
    print(report)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
