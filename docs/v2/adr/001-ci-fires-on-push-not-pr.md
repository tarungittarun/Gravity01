# ADR-001: CI fires on push and dispatch only - not on pull_request events

**Date:** 2026-10-12. **Approved by:** the owner, in chat this round ("workflows running too long…
many workflows have been executed, and many artifacts… keep artifacts for a shorter time and handle
the earlier one also"). Status: accepted.

## CONTEXT

Measured on 2026-10-12 (run list via API):

- Every push to the session branch executed the whole pipeline **twice**: once for the `push` event
  and once for the `pull_request` event, because the branch sits permanently open as PR #2. Five
  pushes that day ≈ 20 workflow runs, half of them redundant re-executions of identical commits.
- The `plan` job additionally gave `pull_request` events the **full** 4-row emulator matrix, so the
  redundant copy was the expensive one (a row is roughly an hour of a 2-vCPU runner).
- The Actions artifact store reached **385 artifacts** in one day, with `apk-debug`/`apk-release`/
  `e2e-classes`/`e2e-result-*` on the 90-day default retention (no `retention-days` was set).
- Two e2e rows ran >80 minutes on a superseded tree (wedge suspicion, DEFECT-1) and could not be
  cancelled from the engineering sandbox: `POST /runs/{id}/cancel` and `DELETE /artifacts/{id}` both
  return **403 "Resource not accessible by integration"** there - only a workflow token with
  `actions: write` can do it.

## DECISION

1. **Triggers:** `android.yml` and `e2e.yml` fire on `push` (main, `arena/**`, `v*` tags) and
   `workflow_dispatch` only. The `pull_request` trigger is removed from both. The full matrix is
   asked by `[e2e-full]` in a commit message, by a dispatch checkbox, or by a push to `main` - no
   longer by PR events.
2. **Retention:** every `upload-artifact` names an explicit, short `retention-days`: e2e build
   APKs/classes and `e2e-result-*` 3 days; `probe-capture` and `ui-previews` 7; `test-reports` 3;
   `opentrack-debug-apk` 7 (the installable APK lives on the permanent `latest-apk` **release**,
   which is not an artifact and is untouched).
3. **Hygiene job:** a new cheap `hygiene` job runs `tools/ci/artifact_hygiene.py` on every push with
   `actions: write`: deletes artifacts older than **6 h** (evidence is read from commit comments,
   check-runs and annotations; artifact *files* are only needed within the run and a short
   post-mortem) and cancels runs `in_progress` over **4 h** as wedged - untested-with-a-reason
   (6.2), never a pass. One `::notice::` line records what it did. The existing 385-artifact pile is
   therefore handled by the first run of this job, without a manual sweep.

## ALTERNATIVES CONSIDERED

- **Keep PR checks, drop push triggers** - rejected: pushes are this project's actual loop (the
  commit comments, probe captures and release roll all hang off push), and PR merge-queue semantics
  buy nothing for a single-owner repo.
- **Org/repo-level retention setting** - rejected: not configurable through this integration
  (write-403 measured), and invisible in the repo where the policy should be reviewable.
- **One-off manual artifact sweep** - rejected: it rots; the same pile would rebuild in a day. The
  cleanup is enforced where every push re-runs it.
- **Concurrency-based dedup instead of trigger removal** - insufficient: the two events run under
  different concurrency refs (`refs/heads/...` vs `refs/pull/.../merge`), so nothing cancels.

## CONSEQUENCES

- Runner-minutes and artifact bytes roughly **halve** immediately; the artifact store converges to
  "at most the last push's artifacts".
- The PR #2 "Checks" tab shows no CI. The round report posted on the PR body is the review surface,
  with run ids quoted verbatim (6.4), as it already was.
- A wedged run now dies at ~4 h by hygiene's hand instead of >80 min human annoyance - slower than
  a human would like, but automatic and recorded.

## REVERSIBILITY

One line per workflow: re-add `pull_request:` to `on:` (and the `pull_request) full=true` case in
`plan` if the full-matrix-on-PR behaviour is wanted). The hygiene job is independent of the trigger
choice.

## REVISIT-TRIGGER

Branch protection gets configured with required checks, a second engineer joins and reviews via PRs,
or a runner-minutes bill makes even the halved spend wrong (then: dispatch-only e2e with `[e2e-full]`
as the only matrix key).
