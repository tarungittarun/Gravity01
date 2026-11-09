# Components — each needs a proof of concept before it is allowed inside the app

Rule from the owner: *"each of the component should have proof of concept, so that it should not have problem
in end user's phone."* This file is the register. A component whose PoC is `MISSING` may not be relied on by a
screen that ships. States: `POC ✔` (an executable check proves it works, where it runs), `PARTIAL`,
`MISSING`, `BLOCKED:<why>`.

| # | Component | Job it must prove | PoC / test | State | Evidence & gap |
|---|---|---|---|---|---|
| C1 | `core/Http` | GET/POST with budget, retry, IPv4 pin, cache; a probe never outranks the user's request | `HttpTest` (JVM) + `[HTTP]` diary lines | POC ✔ | 31–44 s freeze fixed by budget + `MIN_ATTEMPT_MS` + "first attempt always runs" (`71fa6bd`) |
| C2 | `core/BrowserFetch` | WebView as transport: `fetch` with `credentials:'include'`, cookie jar, JS eval result | on-device only (WebView needed) | PARTIAL | It is the only transport that ever returned bytes on the owner's phone (506 B NTES HTML, 779 B erail). No CI coverage — needs the Stage-4 emulator row with a mock |
| C3 | `remote/NtesWire` | AES-CBC/MD5 envelope both ways; parse of `ShowFullRunJson`/`TrainBtwStnJson` | `NtesWireTest` (round-trip of *our own* encode) | **BLOCKED: never seen a real payload** | Encode/decode symmetry proves nothing about the railway's response shape; library docs say live status may be **PascalCase** (`TrainNo`, `CurrentStation`, `Platform`) while `parse` looks for `STN/ARR/ATD/PLAT`. Stage 0 fixture is the unlock |
| C4 | `remote/LiveProvider` chain | Erail → NTES → override → cache, each labelled; `portDead` short-circuit | `ProviderChainTest` + `probe_live_feed.py` in CI | PARTIAL | Chain logic tested with fakes; on-device it returns "no live feed" (owner screenshot 23:20 IST) → the failing link is transport/shape, not the chain |
| C5 | `remote/FeedProbe` | Which of ~15 request shapes works, bounded, cancellable | `FeedProbeTest` + diary `FEEDPROBE` lines | POC ✔ (as a probe) | Must never gate a refresh; cancel-on-manual-refresh implemented |
| C6 | `data/RefDb` | Asset install, gzip inflate, schema negotiation (`REFDB_SCHEMA_VERSION`) | render-test job opens it; `peekVersion` | POC ✔ | ASSET_NAME↔filename pinned by `prepare_asset.sh` after the aapt `.gz` incident |
| C7 | `data/RefQueries` | SQL correctness, station-pair search, `dataVersion()` honesty | `ShippedDataTest`, `RefQueries*` | PARTIAL | It queries a dataset that fails the known-truths gate; label is honest now (commit adds `UNVERIFIED`), correctness still wrong until live-first lands |
| C8 | `domain/TrainFinder` | ±24 h window, run-days filter, dedupe vs display order | `TrainFinderTest` (incl. the 22:51 screenshot case) | POC ✔ | Two comparators, two decisions — the fix that came from a screenshot, not a theory |
| C9 | `tools/build_refdb.py` (ETL) | Produce a queryable SQLite asset, index-served, internally consistent | `--skip-verify` off; plan checks; distance gates | POC ✔ | **and it is the whole source of the data bug**: no data of its own, clones a 2022-23-era public dump |
| C10 | `tools/ci/data_truths.py` | Does the dataset agree with the railway? | runs in ETL + `prepare_asset.sh`; exit 1 standalone on today's asset | POC ✔ **new** | 5 FAILs found on first run — the first check in this repo that ever caught it |
| C11 | `core/Diary` | Opt-in ring buffer, port verdicts, export to file | save/share from Settings; owner produced a diary at 23:20 | POC ✔ | Nothing recorded while disabled (owner rule). Files must be re-attached — last one did not reach the sandbox |
| C12 | `ui/*` screens | Render, empty/error/stale states, no ANR | CI `tests + screens`, monkey job, flow coverage `--min-lines 45` | PARTIAL | Proven against *fixtures of our own DB*, so "wrong train" could never be red. Stage 4 fixes by asserting known-truths in-app |
| C13 | Alarm / Coach / Share | The row already carries the data; needs its own PoC on a real device (Doze, exact alarms) | none | MISSING | Stage 6, after correctness |
| C14 | Live override store | Cache live schedule per train number, TTL, provenance, wins over offline copy | none | MISSING | This is the self-healing piece (Stage 1 schema v4 + Stage 2) |
| C15 | Station-pair live search | `TrainBtwStnJson`/erail pair → list of trains between two stations, labelled `· via <source>` | `PairSourceTest` (JVM: typed 8.2 states) + probe capture pipeline (`probe-capture` artefact + raw-body commit comment) | **PARTIAL** | Orchestration, feature flag, typed failures AND the real erail pair parser proven: parser written against the byte-exact capture of 2026-10-12 (4.5), 10 tests incl. 16221-KUVEMPU-on-the-pair and boardable-first on live data; the capture itself (`app/src/test/resources/captures/…`) is the shape contract. Remaining: real HTTPS transport, merge/cache/overrides (7.8), UI states, emulator-vs-mock proof. Erail pair feed measured timetable-vintage (stamp 2026-8-6) - label `via erail`, stamp in note (T15.4) |

## The one-line reading of this table

Everything that is *our* code has a PoC and passes. What is broken is (a) the data we ingest and (b) the live
shapes we have never actually seen — so the next rounds are C15/C3/C14/C10-hardening, in that order, each with
its own gate.
