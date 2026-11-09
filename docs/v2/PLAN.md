# OpenTrack India — v2 plan (from zero, to a working app, to updates)

Written 2026-10-11 after the owner rejected the build on data grounds: *"most of the trains showing here are
incorrect… even train numbers are incorrect… I need the latest and running info."* They asked for the
learnings of the whole project to be distilled and applied as a fresh start with real product methodology,
per-component proof of concept, emulator-driven verification, and a status record that lets work resume
instead of repeat. This directory is that restart. Note: this session is pinned to the branch
`arena/edd38b02-checktrain02` by the harness, so "new branch" is implemented as a **new tree
(`docs/v2/`) and a new architecture**, not a new ref — the branch constraint is documented in STATUS.md so
nobody wastes a round rediscovering it.

## 1. What "working" means (definition of done, agreed with the owner)

A passenger opens the app, types two stations, and within ~2 s sees the trains that **actually exist on that
route today**, each with the **number and name the railway uses**, the **time they must be at the station**,
and — where the railway publishes it — **whether it is running late and from which platform**. Anything they
cannot verify from a live source is **labelled as such on the row**. If the app cannot reach live data, it
says so **on the results screen**, not in a log file.

Non-goals: ticket booking, coach booking, ads, accounts.

## 2. Standing constraints (owner-stated, not inferred)

1. **No API key handed to the user, no relay/proxy of ours, no ads.** The app talks to the railway directly.
   Constants extracted from the railway's own public app are acceptable; a third-party key or our server is not.
2. **Schedule data must never look like live data.** Every displayed time carries a provenance label
   (`timetable` / `· via NTES` / `cached 4 h ago`). Fallbacks are labelled; missing live data produces an
   explicit end-user message.
3. **Do not ship the owner an APK as a test loop.** CI + emulator must prove behaviour; the phone is only
   needed for questions the emulator cannot answer (real network egress, real notifications).
4. Every round must either change what the phone shows or state plainly why it does not. Diagnostic-only
   progress is not progress.
5. Work resumes from `docs/v2/STATUS.md`; a `DONE` line is re-opened only by a failing verification, never by
   curiosity.

## 3. Root causes, with the evidence that found them

| # | Cause | Evidence | Owned by |
|---|---|---|---|
| 1 | The offline dataset is **not a timetable snapshot, it is a years-old one**. 741/6,775 trains (10.9%) still carry the retired `0`-prefixed numbering (06228, 07301) that SWR renumbered in 2022-23. | `tools/ci/data_truths.py` on the asset built 2026-10-11 | Stage 1 |
| 2 | **Numbers are reused, not just re-timed**: our copy maps `16228` to "Shimoga-Bangalore Express SMET→SBC"; the railway maps it to "Talguppa–Mysuru Express TLGP→MYS" (owner's screenshot, 23:20 IST). | gate FAIL #1 | Stage 1 |
| 3 | Trains **keyed in the reverse direction** disappear from a whole direction of search (`16221` held as MYS→MAS, so SMET→MYS silently loses it) — this is what "most of the trains are missing" looked like from inside. | gate FAIL #2, #3 | Stage 1 |
| 4 | The app printed **its own build timestamp** as "timetable 11 Oct 2026, 22:27 IST", converting a data problem into a trust problem. | `RefQueries.dataVersion()` before commit `a06ac16` | fixed |
| 5 | One comparator drove **both** dedupe selection and display order, so a train that left 41 min ago headed the list. | `TrainFinder.kt:114` | fixed |
| 6 | **The live path still returns nothing on the owner's phone** ("Delay: no live feed", every row `—  timetable`) while another app on the same device shows actuals. The parse has never been fed a real payload; we guessed at key names. | owner screenshot 23:20 IST, 5G + Wi-Fi both up | Stage 0 |

Consequence that reframes the project: **an offline snapshot we cannot re-source is not a foundation.** GitHub
was searched for public IR schedule dumps pushed after 2026-06-10 — 11 repos, and the only ones containing
schedules are the two we already use, both older than the last two timetable revisions. So the offline DB is
demoted from *source of truth* to *cold-start cache*, and correctness comes from the live feed.

## 4. Target architecture (live-first, provenance everywhere)

```
              ┌───────────────────────────────────────────────┐
 stations ───▶│ Search  (station pair, date)                  │
              │   ├─ LiveSource   NTES TrainBtwStnJson        │  one call per search
              │   ├─ LiveSource   erail.in (labelled alternate)│
              │   └─ SnapshotStore (local, TTL, per-route)     │  what the phone keeps
              └───────────────┬───────────────────────────────┘
                              ▼
              ┌───────────────────────────────────────────────┐
              │ RouteStore  = offline refdb  ⊕  live overrides │
              │   override wins while fresh; provenance kept   │
              └───────────────┬───────────────────────────────┘
                              ▼
              ┌───────────────────────────────────────────────┐
              │ UI states: LIVE · LIVE_STALE · CACHED ·        │
              │              OFFLINE_UNVERIFIED · NO_DATA      │
              └───────────────────────────────────────────────┘
```

Data rules that make the current class of bug impossible:
- Every row the UI can show carries `(value, source, fetchedAt, confidence)`. **No time renders without its
  provenance.** A lint gate fails the build on an unlabelled time (`tools/ci/check_provenance.py`, Stage 1).
- Live overrides are stored per train number in `train_overrides`, with the schedule they replace, so an
  opened train *corrects* the offline copy instead of merely contradicting it. This is self-healing without a
  re-source, using only the railway's own data.
- The offline asset carries `meta.data_trust`; `unverified` (or absent) means the UI must show the doubt, and
  the results screen — not only a chip — says the list is from an unverified copy.
- No fabricated data, ever. If a source does not state a platform, we show `—`, never a guess.

## 5. Stages

Each stage ends with a demo the owner can see and an exit check that CI runs. A stage does not start with
code until its PoC exists.

**Stage 0 — Sources spike (no app code).** `tools/poc/livefeed.py` + an on-device PoC screen that fetch, parse
and *print* one response each for: `TrainBtwStnJson` (live trains between two stations), `ShowFullRunJson`
(live route with actuals + platform), `GetTrainSchedule` (schedule), `TrainsAtStnJson`.
Exit: for ≥1 source, a committed redacted fixture + a parser that maps it to `RouteStop` with a test, and an
explicit verdict line: `usable | usable-but-label-needed | blocked-by-network | blocked-by-shape`.
User-visible delta: none yet (stated, so it is not mistaken for progress).
Kill criterion: if no source is reachable from the phone at all, live features are **removed** from the app
and the product becomes an honest offline-only viewer with a data-freshness warning. That decision is made
here, not after six rounds.

**Stage 1 — Data contract + trust gates.** Schema v4 (provenance columns, override table), the known-truths
gate extended with direction-aware checks, `check_provenance.py` lint, ETL refuses to mark `verified`
without the gate.
User-visible: the results header states dataset trust; every row states its source.

**Stage 2 — Sync engine.** WorkManager + on-open fetch, TTLs (live 5 min, override 24 h, station master 90 d),
single-flight, budgets, cancel-on-new-search (rules already learned: every loop bounded, first attempt never
skipped, probe never outranks the user's request).
User-visible: search is live when the network allows; cached within TTL when it does not; "no live report
reached this device — every minute below is the timetable" when neither.

**Stage 3 — Live status per row, bounded.** Actuals + platform for the **visible** rows only (≤4 lazily),
never a ladder, never blocking the list. This is the "Left SMET at 05:01 PM · Platform 1" ask.
User-visible: the row-level running state, `—` where not reported.

**Stage 4 — Emulator proof of the whole app.** Firebase/AVD run in CI driving the release-shaped APK against
**local fixtures served by a mock** (`10.0.2.2`) so parse+UI+state are proven without railway egress;
golden-screen screenshots diffed; `data_truths` run against the asset inside the emulator.
User-visible: fewer installs — this is the answer to *"everytime I can't install the apk"*.

**Stage 5 — Update path.** Asset version negotiation (already partly in `RefDb.versionOkForSchema`),
incremental per-route refresh, opt-in log export (built), a "check for timetable updates" button that only
ever reports what the sources say, crash/ANR gate in release job.

**Stage 6 — Polish that the owner will judge anyway:** station-pair history, saved routes, alarm before
arrival (the row already has the data), offline map line, coach position, share-as-image.

## 6. Verification strategy (what proves what)

| Layer | Where | Proves | Cannot prove |
|---|---|---|---|
| JVM unit tests | `app/src/test` | ordering, parsing, formatting, day-window math | Android behaviour |
| Robolectric-ish render tests | CI `tests + screens` | screens inflate, strings exist | real network |
| Emulator + Espresso, fixtures via local mock | CI (Stage 4) | end-to-end flow, empty/error/late states, no ANR | railway reachability |
| Known-truths gate | `tools/ci/data_truths.py` | offline data agrees with reality | live-source correctness |
| Owner's device, opt-in diary | `core/Diary.kt` | TLS/DNS/egress reality on their network | everything else |
| Monkey/stability | CI `monkey_stability.sh` | crash resistance | correctness |

Emulator facts, so the plan is not built on a wish: CI **already boots API-34 x86_64 emulators** on GitHub
runners when `/dev/kvm` exists (`tools/ci/emulator_boot.sh`, four rows in `e2e.yml`). What the emulator
*cannot* do is reach `enquiry.indianrail.gov.in` — TLS dies in ~0.1 s from GitHub runners (twice evidenced).
Hence fixtures + mock, and hence the one thing only the phone can answer.

## 7. Release & update process

CI publishes the debug APK to the `latest-apk` release on every push to this branch (`debug APK` job, rolled
with `--clobber`), tagged with the commit; `release` runs on tags only. Each round appends to
`docs/WORKLOG.md`: what changed on the phone, what was verified where, what is still broken. The APK the owner
installs is never described as tested unless the named run says so.

## 8. What I will ask of the owner (deliberately rare)

1. **Once:** enable Settings → diagnostic log, open train 16228, Save to file, attach it. The file did not
   arrive in the last message (`/home/user/uploads/` was empty in the sandbox), and it is the single input
   that converts Stage 0 from guessing into a fixture.
2. Install a new APK **only** when a row in STATUS.md says `needs-device: yes`. Everything else gets proven in
   CI or the emulator.
