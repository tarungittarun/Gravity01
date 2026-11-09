# Learnings — what this project got wrong, and the rule that follows

Twelve rounds, ~100+ CI runs and ~10 hours produced an app whose **data was wrong**. Not because the tooling
failed — every gate stayed green the whole time. This is the list that must be true of all future work. Each
lesson names where it is *enforced*, because a lesson in prose is a wish.

1. **Internal consistency is not truth.** The ETL validated schema, index plans, monotonic distances and three
   fixture trains — all from the same stale dump, so nothing could disagree with itself.
   → Enforced by `tools/ci/data_truths.py`, whose facts come from *outside* the repo.
2. **A commit date is not a data vintage.** I told the owner the timetable was "113 days old" because the
   source repo's last commit was 2026-06-20 — while 10.9% of its trains still carry numbering retired in
   2022-23. I repeated the exact sin I had just fixed (build stamp → data stamp).
   → Enforced by the `retired_prefix_pct` check, which fails on the data's own anachronisms.
3. **Never present a fallback as authoritative.** A label like "as of <date>" that is really "when we packed
   it" converts a data problem into a trust problem.
   → Enforced by `dataVersion()`'s three states and the `check_literals`/`check_refs` gates on the string path.
4. **Reusing the source's identity assumptions is how a wrong train ships.** `16228` in our copy is a different
   train. Numbers are recycled; only a live source can say what a number means *today*.
   → Enforced by Stage 1 (provenance on every row) and C15 (live pair search).
5. **One ordering must not serve two decisions.** Proximity is right for "which instance of this number do we
   keep" and wrong for "what order does a passenger read". The bug survived because both used one comparator.
   → Enforced by `TrainFinderTest` (`…LeftAMinuteAgo…` for selection, `catchableTrainsComeFirst…` for display).
6. **A missing train is worse than a slow app, and it looked like a working one.** Trains keyed in the reverse
   direction simply vanish from one whole direction of search — no error, no empty state, just fewer trains.
   → Enforced by `pair_lists.must_include` in the gate (T9.7 adds `must_not_include`).
7. **A diagnostic must never outrank the user's request.** An unbounded probe ladder on one serialised socket
   lock made refresh take 31–44 s.
   → Enforced by budgets + `cancelRequested` + `portDead` short-circuit (C5).
8. **Green CI is not a user-visible result.** Rounds 1–7 were mostly provable plumbing while the phone showed
   wrong times.
   → Enforced by the round rule: change what the phone shows, or say plainly why you didn't (PLAN.md §2.4).
9. **I optimised the wrong layer first.** The one 20-minute check — query the shipped DB and compare with a live
   source — should have been round one. Cost: ~9 rounds.
   → Enforced by ordering in PLAN.md: data correctness is Stage 0/1, not a follow-up.
10. **Guessing at an API shape burns rounds.** `NtesWire.parse` has never seen a real payload; library docs
    imply PascalCase keys. Every fix there so far was a hypothesis.
    → Enforced by Stage 0's fixture requirement: no parser without a captured response.
11. **Local gates are not a type-checker.** `apply()` reporting `ok` proves an anchor matched, not that the file
    parses; `ast.parse`/`bash -n` in the same breath caught two of my own edits. And on 2026-10-12 they proved
    it again in the other direction: `check_refs` matched simple type names, so a moved-package import
    (`in.opentrack.india.v2.Provenance` after the type moved to `.v2.contract`) sailed through nine green gates
    and died in the compiler one push later - CI is the compiler, and now the gate checks the PACKAGE an
    import names, with a self-test that reproduces the exact miss.
    → Enforced by running `py_compile` + `bash -n` + `check_*` on every edit round; by `check_refs.py`'s
    import-package rule over main+test roots together; and by `tools/ci/test_check_refs.py`, whose first case
    is the real miss.
12. **Tooling-shaped promises need receipts.** Telling the owner "install this APK" as the test loop was a
    design failure, not a constraint. Emulators exist in CI; the missing piece is fixtures + a local mock.
    → Enforced by T9.9 and PLAN.md §6, which states what the emulator *cannot* prove (railway egress) instead
    of implying it can prove everything.

13. **A second push destroys the first push's evidence.** Pushing `52cd940` while `a06ac16` was still running
    cancelled `52cd940`'s build through the concurrency group, and left `a06ac16`'s e2e `report` job failing on
    "no results to roll up" - a red row that meant nothing, and a green build we never got to read. The fix is
    not a config tweak (cancelling stale builds is the right economy on 2 vCPU runners): it is **one push per
    round**, then read the run, then start the next round.
    → Enforced by this rule + the CI-state line in `docs/v2/STATUS.md`, which records each round's run id so a
    later round can tell "cancelled by me" from "failed on its own".

14. **A fallback needs a test in which the thing it falls back from is already dead.** My own CI probe put the
    erail attempt *below* the primary's `if doc is None: continue`, so when NTES timed out the alternate was
    never asked and the report looked complete. Caught only because the output was missing a line I expected -
    not because anything failed. The app's live path failed on the device the same way for rounds: chain logic
    "worked" in tests where every provider was reachable.
    → Enforced by `tools/ci/test_probe_live_feed.py` (run by the `checks` job), whose first case is literally
    "primary dies, alternate must still be asked". Any future fallback ships with that shape of test.

15. **A capture in hand outranks an imagined shape, and a proposal is not a diff.** Two defects of one family,
    both on 2026-10-12, neither of which reached a phone only because a test was written first. (a) The erail
    identity parser passed every test while never working on a single real payload - and the real records were
    sitting in our own probe comment all along; the moment a test was written FROM a captured record, the
    parser failed. It now anchors on the `^number` field the records actually carry. (b) The probe's
    "proposed refresh" carried `"trains": []` purely because NTES was unreachable - one careless paste from
    erasing three owner-verified identities; 16221 proved the flip side too, where erail contradicted the
    fixture and the OWNER (not the tool) resolved it. Proposals now carry their merge rule inside the JSON,
    and the refresher is print-only with an AST test proving it has no write power.
    → Enforced by `ErailIdentityParser` tests written from the captured records, `NoWritePower.
    test_the_tool_contains_no_write_mode_open`, and `test_the_proposal_skeleton_carries_the_merge_rule`.

## Method the next rounds follow (what "actual product development" means here)

Scope → contract → **component PoC with an executable pass criterion** → implementation → automated proof at
the lowest layer that can give it → device proof only where devices are the only witness → ship with a written
delta → record in `docs/WORKLOG.md` + `docs/v2/STATUS.md`. A component without a PoC does not enter a shipping
screen. A claim without a named artifact is not made. Fabricated data is never a fallback: `—` beats a guess.

16. **Two edits to one file do not both land if they run in parallel.** The PairCache TTL fix was applied as
    two same-message edits; both reported success; the second had read the file before the first wrote, so the
    caller got two arguments while the definition kept one - committed, pushed, and red again three rounds
    after the same class of miss (the round-16 hygiene job that never landed). The gate cannot see it: both
    halves are individually plausible Kotlin. Rule: edits to one file are sequential, and a structural change
    is verified by reading the file, not by the tool's ok.
    -> Enforced by this rule and by grep-verifying structure in the same breath as the edit (the hygiene job
    fix shows the working pattern: insert, `grep -c`, then commit).
