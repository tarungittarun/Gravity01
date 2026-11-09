# KICKOFF — project operating brief

Saved verbatim on 2026-10-11 by the session on `arena/508929e1-checktrain02` (action 0.1 of the brief itself).
Owner-issued. Updated only when a rule changes, and only via an ADR (18.1). Gate: exists + dated.

---

PROJECT OPERATING BRIEF - OpenTrack India (repo: tarungittarun/Checktrain02), v2 rebuild.
You are the sole engineer acting as PM + architect + dev + QA + release manager. The owner is the acceptance
authority: nothing ships because CI is green; it ships because the owner's phone shows the right thing.
Work ONLY on your session branch. Never create/switch/push other branches. Never touch main. Do not re-plan,
re-audit or re-litigate anything already recorded in docs/v2/ - extend it.

================================================================ ACTIONS 0-4 (before any feature work)
0.1 Save this whole brief as docs/v2/KICKOFF.md (first commit), so the next session inherits it.
0.2 git fetch origin. Confirm: main=9a0514d (untouched), v2-fresh = Milestone 1 (merged by owner),
    previous session branch head was ac06f62. Merge origin/v2-fresh into your session branch (fast-forward).
    If you cannot see commit 3e795a5 (a HANDOFF note), it existed only in a closed sandbox: use section 12 here.
0.3 Read the CI runs for ac06f62 and 67e7e02:
      gh api repos/<owner>/<repo>/commits/<sha>/check-runs --jq '.check_runs[]|"\(.status)/\(.conclusion//"-")\t\(.name)"'
    The v2 contract (Provenance/Facts/ContractTest) has NOT met a Kotlin compiler: this sandbox has no
    ANDROID_HOME, so CI is the compiler. If red, fixing it IS round one. Quote the run in your report.
0.4 Read the probe comment on those commits (gh api .../commits/<sha>/comments, look for "### live-feed probe").
    It decides the branch point in section 7.
0.5 Read docs/v2/{STATUS,PLAN,COMPONENTS,LEARNINGS}.md. Resume at the first DOING, else first NEXT, line in
    STATUS.md. Keep these docs current in the same commit as the code they describe.

================================================================ 1. PRODUCT
1.1 Who: an Indian passenger with a mid-range Android phone, often on flaky Wi-Fi or mobile data, who needs to
    know (a) what runs between two stations, (b) is it on time, (c) which platform, (d) when to leave.
1.2 Reference specification the owner uses: "Where Is My Train". Row-level target wording:
    "Left SMET at 05:01 PM" / "Leaving SMET at 11:02 PM - Platform 1". Match that information architecture.
1.3 Definition of done (owner-agreed): results show trains that EXIST on that route TODAY, with the NUMBER and
    NAME the railway uses, the time they are due, live running status and platform where published; every value
    carries provenance; when live data is unreachable the screen SAYS SO. Offline never looks live.
1.4 Non-goals: booking, payments, accounts, ads, social, chat, analytics server, push infrastructure, watches
    (later), anything needing our own backend.
1.5 Quality bar: a passenger must never be sent to a platform for a train that does not arrive. That is the
    failure that outranks every other defect, including crashes.

================================================================ 2. ABSOLUTE CONSTRAINTS (violations were rejected before)
2.1 No API key handed to the user. No relay/proxy of ours. No ads. No accounts. Third-party keyed APIs and our
    own server are forbidden; constants extracted from the railway's own app are acceptable and are NOT secrets
    (they ship in a public APK) - but they buy no right to hammer the service.
2.2 Data honesty: schedule/timetable values must never be presented as live/actual. Provenance labels are the
    only allowed vocabulary: timetable | via <source> | cached <age> | live data <age> old | no fetch time.
2.3 Missing data: an explicit user-facing message. Never a silent fallback, never an empty list that really
    meant "I could not look". "Answered empty" and "could not reach" are different types and must stay distinct.
2.4 Never fabricate, interpolate or infer railway data. Unknown = em dash. No delay=0 for unreported times, no
    "Platform 1" defaults, no invented train numbers, no name cleanup that changes identity.
2.5 Scraping etiquette / legal: at most a handful of requests per user action, no bulk crawling from the app, no
    re-publishing large verbatim datasets in this repo (fixtures are small, redacted, attributed, dated), honour
    robots/ToS changes, and treat the endpoint as someone else's production system that must not be harmed.
2.6 Privacy: logs are opt-in, off by default, nothing written while disabled, stored locally, exported only when
    the user taps it. No PII in logs. No Firebase Crashlytics / Play analytics / any third-party SDK that ships
    data off-device without a written decision + owner sign-off (current answer: no).
2.7 No dynamic code loading, no remote-config-controlled classes, no self-updating binaries (Play policy + our
    trust model). Data may update; code updates only through a signed release.

================================================================ 3. METHOD (stage gates; nothing starts before the previous gate exits)
S0 RESEARCH/POC ....... question answerable with one command + one artefact. Exit: written verdict per
                       candidate (usable | usable-but-labelled | blocked-by-network | blocked-by-shape).
S1 SPEC ................ every screen/state has a written contract (inputs, outputs, failure text).
                       Exit: acceptance card per story; owner-visual items (labels, states) are explicit.
S2 ARCHITECTURE/CONTRACT  types + invariants + purity boundary. Exit: contract tests pass in CI.
S3 COMPONENT POCs ...... see section 4. Exit: every inherited or new component has a passing PoC command.
S4 IMPLEMENTATION ...... vertical slices, one behaviour per commit, feature-flagged where risky.
S5 QA .................. section 8 matrix, plus the never-again list (section 11). Exit: matrix rows green or
                       explicitly waived with a reason in STATUS.md.
S6 RELEASE/DEPLOY ...... section 9. Exit: artefact + changelog + rollback path recorded.
S7 ADOPT/MONITOR ......  section 10. Exit: telemetry-free signals (CI probe results, gate verdicts, owner
                       reports) reviewed; backlog updated.
Roles: you hold all hats; the owner is the only release approver. Decisions that change a rule or a constraint
require a short ADR: docs/v2/adr/NNN-<slug>.md (context/decision/consequences/reversibility). Create the folder
on first use. No ADR, no rule change.
Story template (use verbatim in STATUS.md or docs/v2/stories/):
  TITLE / USER VISIBLE OUTCOME / ENTRY CRITERIA / EXIT CRITERIA (executable) / FAILURE MODES + EXACT TEXT /
  SIZE BUDGET / ROLLBACK / NEEDS-DEVICE? / OWNER ACTION?

================================================================ 4. PROOF OF CONCEPT - the gate that decides whether code may exist
4.1 No component enters a shipping screen without a PoC. A PoC = one file, one question, one command that
    proves it works, plus one artefact (a captured response, a rendered screen, a measured number).
4.2 Location: poc/<component>.py or poc/<Component>PoC.kt (JVM-only where possible). It must run standalone and
    print PASS/FAIL with the reason. If it needs a device, say so in one line and mark needs-device: yes.
4.3 PoC graduation rule: either the PoC's assertion becomes a real test in the module, or the PoC is deleted.
    No third state, no "prototype" surviving into main.
4.4 PoC of the PoC: every diagnostic, probe and gate gets its own test. (Our probe once reported a clean result
    while silently skipping its fallback; and it truncated SMET to MET. Both were caught only by reading output
    - then fixed with tests.) If a tool exists to answer a question, test that it asks the question.
4.5 A parser may only be written against a CAPTURED payload. No key-name guessing, ever. If no capture exists,
    the deliverable is the capture mechanism, not the parser.
4.6 A fallback ships with a test in which the thing it falls back from is already dead.

================================================================ 5. DEVELOPMENT STANDARDS
5.1 Module layout (v2): app/src/main/java/in/opentrack/india/v2/{contract,source,store,domain,ui}/.
    v2 core layers stay pure JVM: no android.* imports, no SQLite, no HTTP inside contract/domain. Transport is
    injected as a function/interface so parsing is testable on a laptop.
5.2 Legacy freeze: ui/, remote/FeedProbe.kt, core/BrowserFetch.kt, core/Diary.kt change only for a blocking fix
    or to expose something v2 needs, and the commit message must state why it could not live in v2.
5.3 Strangler pattern for migration: v2 screens replace v1 screens one at a time; a v1 file is deleted only when
    its v2 replacement passes CI matrix + one owner-device check. Never big-bang rewrite.
5.4 Kotlin: no silent catch, no runCatching without saying what failure is tolerated; every loop/await has a
    wall-clock budget and a cancellation path; one comparator must never serve two decisions (proximity picks
    WHICH run, intent picks the ORDER shown); nullable stays nullable into the UI, which renders the dash.
5.5 Compat: minSdk 24; java.time needs API 26 or desugaring (prefer epoch-day arithmetic); WebView/cookies and
    cleartext policy differ by API; R8 release build must be exercised in CI ("release" e2e row) because
    resource shrinking has bitten us before.
5.6 Budgets (numbers, not vibes): offline asset <= 6.0 MB gzipped; APK delta <= 1.5 MB per release unless an
    ADR says otherwise; on-device search p95 <= 250 ms on a 2 GB RAM Android 8-class device; screen must render
    cached content in <= 1.5 s and must never block on a network call; refresh renders instantly from cache then
    upgrades in place.
5.7 Dependencies: adding one is an ADR (size, minSdk, licenses, network surface, R8 impact). Multi-MB SDKs
    (e.g. cronet) need owner sign-off.
5.8 Local pre-push gates (must pass; absence of an SDK is never a reason to skip them):
      python3 tools/ci/check_kotlin_balance.py check_members.py check_refs.py check_literals.py check_resources.py
      check_workflows.py test_check_workflows.py test_probe_live_feed.py   (each via its own path)
      python3 -m py_compile <every edited .py>    bash -n <every edited .sh>
    An edit tool reporting "ok" only proves the anchor matched, not that the file parses.
5.9 Git: one push per round; then read that run (a second push cancels the first one's in-flight runs and
    destroys its evidence). Commit messages say WHY. HEREDOC for messages (git commit -F - << 'MSG'), never -m
    with backticks (shell substitution ate a message once). Never git clean/reset --hard to "tidy up".
5.10 Local files that must stay out of git: built assets, /tmp captures, gradle caches, emulator logs. If a
     generated artefact is needed by CI, commit the generator, not the artefact.

================================================================ 6. CI / PIPELINE EXPECTATIONS
6.1 Jobs that must exist and stay informative: checks (static gates), debug APK (rolls the latest-apk release,
    tees the build log, posts the first errors as a commit comment), tests + screens (Robolectric), e2e rows on
    real emulators (API 34 Pixel 6, API 24 Nexus 5 floor, density/font-scale row, release row), report
    (rollup + coverage floor --min-lines 45 + flow coverage), release (tag-only).
6.2 Every job prints: what it proved, what it skipped and why. A skipped emulator row is "untested, with a
    reason", never a pass. Cancel off-direction runs; add intermediate validation logs so a stalled job tells us
    where it stalled; extract them in the report step.
6.3 Scheduled (weekly) workflow to add: run the ETL + the known-truths gate + the live-feed probe, publish a
    data-trust report, and open an issue/comment if reality moved. That is our data-freshness "monitoring"
    without a server, and it is how a four-month data bug becomes a one-week data bug.
6.4 Nothing is claimed green without the run id. Quote run status verbatim in the round report.
6.5 Do not poll CI in sleep loops. One API call per turn, report what it returned. Long local commands run in
    background with bounded slices checked in between.

================================================================ 7. DATA STRATEGY (the thing that broke, and how it is fixed)
7.1 Facts, measured: the offline asset is built by tools/build_refdb.py from public dumps whose CONTENT predates
    the 2022-23 renumbering - 741/6775 trains (10.9%) still use retired 0-prefixed numbers; 16228 in our copy is
    a DIFFERENT TRAIN than the railway's 16228; 16221 is keyed MYS->MAS so it vanishes from SMET->MYS; upstream
    repos last committed 2026-06-20 and 2026-08-30. A git commit date does not describe a data vintage.
7.2 There is no fresher public bulk timetable (GitHub search, 2026-10-12: 11 candidate repos, only the two we
    already ingest carry schedules). So the offline asset is permanently demoted to a labelled cold-start
    fallback, unless an official IR open timetable becomes available - that is a documented trigger to revisit.
7.3 Known-truths gate (tools/ci/data_truths.py + fixtures/known_truths.json) is the only check in the repo whose
    facts come from outside it. It asserts IDENTITY, ROUTE and PRESENCE - never minutes (times legitimately
    move, and a flaky gate trains everyone to ignore it). It supports must_not_include for phantom trains; that
    list is intentionally EMPTY until one live pair answer exists (a retired number is not proof of a
    nonexistent train - some zones still run 0-prefixed specials). Suspected is not asserted.
7.4 The gate's verdict ships inside the asset (meta.data_trust) and the UI must honour it; the build does NOT go
    red on a data contradiction (a red build leaves the owner on an older APK than the one they have).
7.5 Live-first sources, in priority order, all keyless: (1) NTES via the app's own wire (remote/NtesWire.kt;
    POST to enquiry.indianrail.gov.in; envelope is AES-CBC+MD5 framing with app-wide constants) for
    TrainBtwStnJson (pair), FindTrainJson (identity), ShowFullRunJson (route+actuals), GetTrainSchedule;
    (2) erail.in flat `~` text via /rail/getTrains.aspx (remote/ErailWire.kt), which embeds its own feed stamp
    and is label-worthy as "via erail"; (3) the on-device cache; (4) the offline asset. Adopted shapes are
    labelled "· via <name>". A cache read is never "live".
7.6 Reachability, verified not assumed: enquiry.indianrail.gov.in dies at the TLS handshake from this sandbox AND
    from GitHub runners (three independent confirmations). The WebView transport is the only thing that has
    returned real NTES bytes on the owner's device. erail.in answers from a runner (was 404 for the wrong path;
    the correct path is /rail/getTrains.aspx) - the section 0.4 probe comment settles the rest.
7.7 DECISION BRANCH from 0.4:
    (a) erail reachable+200 from CI -> build the CI capture (PROBE_CAPTURE artefact), a PRINT-ONLY refresher
        tools/ci/known_truths_refresh.py (a human applies proposed fixture edits - an auto-edit to the file the
        app trusts is the app believing itself), and C15 with a parser written against the captured text.
    (b) not reachable -> the v2 source layer ships with an injected transport + fake fixtures; the real capture
        comes from ONE device log paste (needs-device: yes), and no parser is written before it arrives.
7.8 Self-healing without re-sourcing: when a live answer arrives for a train number, store a per-train override
    (identity + route + fetchedAt + source) with TTL, so an opened train CORRECTS the local copy instead of
    merely contradicting it. Overrides never widen coverage they did not observe; a miss is not evidence of
    absence.

================================================================ 8. QA (matrix is the deliverable, not the test count)
8.1 Layers: (L1) JVM unit/contract tests - logic, ordering, delay maths, provenance wording; (L2) Robolectric
    screens - inflation, states, string resources; (L3) emulator instrumented - flows, back stack, config
    changes, process death; (L4) emulator + MOCK/FIXTURE network - local server at 10.0.2.2 serving captured
    bodies so fetch->parse->store->UI is proven without railway egress; (L5) stability (monkey) + coverage floor;
    (L6) data truth (known-truths gate, run against the asset in CI AND inside the emulator); (L7) owner device
    - only for what a device is the only witness for (real TLS/egress, notifications on Doze, exact-alarm
    permission, platform look on a real phone).
8.2 States that must be tested per screen, each with its exact copy asserted: LIVE, LIVE_STALE, CACHED,
    OFFLINE_UNVERIFIED, ANSWERED_EMPTY, COULD_NOT_LOOK, PARTIAL (some rows live, some timetable), and
    PROTOCOL_CHANGED (we could not open the envelope - that is our bug, and must be distinguishable).
8.3 Edge battery that this product owes: midnight wrap (due 23:58, seen 00:02 = 4 min late, never 14 h early);
    day-2 arrivals; run-days filter on a train that does not run that date; -24 h/+24 h search window; a search
    at 00:03 for last night's train; 4-letter station codes (SMET/TLGP) never truncated; station pairs with one
    station unknown; a train number that exists in two zones' dumps with different routes; duplicate numbers with
    different names; timezone fixed to IST regardless of device locale; device clock wrong by hours (compare
    against the feed stamp, not System.now, for "is this fresh"); airplane mode; captive portal; DNS blocked
    (AdGuard private DNS case); TLS middlebox; low storage; low RAM kill + restore; screen rotation mid-refresh;
    font scale 1.15 and 440 dpi (Robolectric cannot fake density - that is an emulator row); Hindi/regional
    station names with diacritics; long names ("TALAGUPPA MYSORE INTERCITY EXPRESS") without ellipsis loss.
8.4 Performance gates as tests, not opinions: cold start, first-result latency, query p95 on the shipped asset,
    refresh-with-cache latency, memory ceiling on a 2 GB device, no ANR in the 3-minute monkey burst, and a
    hard rule: NO diagnostic may extend the time to first row.
8.5 Accessibility: every row's provenance label readable by TalkBack; no colour-only meaning (amber dotted =
    implied position is already captioned - keep captions); touch targets >= 48 dp; contrast on dark theme.
8.6 Regression list from real screenshots (must stay green forever): SMET->MYS list contents; 16228 identity;
    catchable-first ordering; "no live feed" copy when nothing arrived; the "timetable" suffix on every row when
    offline; the data-trust line being visible without scrolling.
8.7 Bug policy: every fixed bug adds a failing-first test named after the symptom (e.g.
    aTrainLeavingInNineMinutesOutranksOneThatLeftFortyOneMinutesAgo). A fix without a test is unfinished work.
8.8 Release sign-off checklist (paste in the PR body each release): CI run ids + statuses; matrix rows run and
    their results; waivers with reasons; size/APK delta; new permissions/dependencies/ADR links; data-trust
    verdict; known gaps listed plainly; the exact install link + artefact size/date read from the release, not
    assumed; needs-device items and what to look for; rollback instruction.

================================================================ 9. DEPLOY
9.1 Channels: (a) debug APK on the rolling GitHub release "latest-apk" for the owner to sideload - this is the
    primary loop; (b) signed release APK/AAB on git tags via the release job; (c) Play closed testing later, only
    when the data story is honest and stable. F-Droid is a stretch goal and needs the build to be fully
    reproducible and keyless (it can be: no proprietary SDKs).
9.2 Versioning: versionName semver-ish (2.M.k), versionCode monotonically increasing and derived in ONE place
    (never bump by hand in two files). Asset schema has its own number (BuildConfig.REFDB_SCHEMA_VERSION vs
    meta.schema_version) and a mismatch must degrade gracefully with a stated message, not a crash.
9.3 Signing: keystore stays out of the repo (CI secrets, owner-held); if a signing secret is missing the release
    job publishes the debug artefact and says why release was skipped. Never paste a keystore or password into a
    commit, log or chat; if one appears, tell the owner to rotate immediately.
9.4 Install instructions for the owner (keep them stable, one line):
    https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk
    Verify the asset's size+date from the release API before telling the owner which build they have.
9.5 Rollback: every accepted milestone is tagged (v2-m1, v2-m2...); rolling back = re-publish the tagged
    artefact and re-point latest-apk; data-asset rollback = the previous generated asset (CI regenerates it, so
    the generator + its inputs are the unit of rollback).
9.6 Feature flags for risky live paths: a build-time flag per source (ntesPair, erailPair, liveActuals) so a
    broken upstream is disabled in one commit, and the UI shows "disabled by policy" rather than a wrong answer.
9.7 Changelog: docs/v2/CHANGELOG.md, one entry per published artefact: what changed on screen, what CI proved,
    what is still broken. Written for a user, not for us.

================================================================ 10. UPDATES, MONITORING, FUTURE UPGRADES
10.1 Data updates without app updates: per-route live fetch on demand + TTL-bounded cache + per-train overrides
     (section 7.8); a weekly CI job rebuilds the asset and reports the data-trust verdict; the app shows its
     copy's age on the results screen, not only in a chip.
10.2 Freshness policy (encode, don't eyeball): LIVE_STOPS 5 min, LIVE_PAIR 10 min, LIVE_IDENTITY 24 h, CACHE 6 h,
     ASSET 90 d (see v2/Freshness). Changing one is an ADR.
10.3 Upstream-breakage detection: the probe + gate are the "monitoring". If the envelope can't be opened or the
     shape signature changes, the app says "the live feed's protocol may have changed" and CI opens an issue with
     the captured shape. We do not paper over it with a guess.
10.4 Storage migrations: v2 store has a schema version + migration tests from every prior version; a migration
     failure clears the cache and says so, never shows stale rows as fresh.
10.5 Compatibility watch: yearly compileSdk/targetSdk bump; Android 14+ exact-alarm and foreground-service
     restrictions; Android 15/16 privacy and notification changes; WebView API behaviour drift is tested by the
     emulator rows on API 24 and 34 simultaneously (the floor row is not optional).
10.6 Roadmap after correctness (owner-approved order, do not reorder unilaterally): M2 live pair search -> M3
     per-row running status for VISIBLE rows only (bounded, cancellable, lazy) -> M4 emulator-with-fixtures proof
     of the whole app -> M5 update/refresh hardening + saved routes + pre-arrival alarm -> M6 polish (coach
     position, share-as-image, station board, widget, tablet, Hindi).
10.7 Deferred until someone proves need, with a written reason each time: multi-language beyond en/hi, watch
     tiles, offline maps, PNR/availability, crowdsourced delays, any backend.
10.8 Tech-debt policy: one debt item per round max, chosen from STATUS.md, and only when nothing user-visible is
     blocked on it. Deletion is preferred over refactoring: when a v2 screen replaces a v1 screen, the v1 code
     goes in the same PR or gets a delete-by milestone in STATUS.md.
10.9 Exit criteria for "v2 is the app": every screen served by v2 packages, legacy freeze lifted and the old
     packages deleted, 6 months of weekly CI data-trust reports green or self-healed, and one owner statement
     that the phone matches the live source.

================================================================ 11. NEVER AGAIN (each of these actually happened here)
- Presenting a build timestamp as a data vintage. / Trusting a repo commit date as data freshness.
- One comparator driving two decisions (dedupe proximity vs display order).
- A fallback that only fires when the primary works (probe skipped erail below `if doc is None: continue`).
- Truncating data to fit a guess (fields[2][-3:] turned SMET into MET).
- A diagnostic outranking the user's request (31-44 s refresh from an unbounded probe ladder on one socket).
- "Green CI" reported as progress while the phone showed wrong trains for seven rounds.
- Writing a parser against an imagined payload shape.
- Auto-editing a fixture that the app's correctness depends on.
- Relying on file attachments (uploads arrived empty repeatedly - ask for pasted text).
- Polling CI with sleep loops; pushing twice and cancelling your own evidence; running the same failed command
  again unchanged after a timeout; inventing tool/file/API names or process ids instead of listing them.
- Reusing one ordering for selection and display; conflating "answered empty" with "could not look".
- Claiming a build/test succeeded before the run says so, and speculating about a failure instead of reading it.

================================================================ 12. STATE AT HANDOFF (so nothing is re-derived)
Milestone 1 (merged into v2-fresh, tag v2-restart=52cd940): known-truths gate + fixtures; ETL stamps
meta.data_trust and logs upstream revision dates; RefQueries.dataVersion() has three honest states and prints
"timetable UNVERIFIED: ... - verify before travelling"; TrainFinder display order fixed with a screenshot-derived
test; NtesWire.shapeOf() logs reply structure on both failure paths (including parsed-empty, previously silent);
CI probe asks identity+pair via NTES and erail unconditionally with a 4-candidate ladder and PROBE_CAPTURE;
tools/ci/test_probe_live_feed.py (9 tests) runs in checks; docs/v2/{PLAN,COMPONENTS,STATUS,LEARNINGS}.md; dead
string data_as_of and an orphaned import removed.
Session-branch head at closure: ac06f62 = v2 Stage 1 CONTRACT: v2/Provenance.kt (SourceKind, Provenance with one
label() function, Freshness TTLs), v2/Facts.kt (TrainIdentity.disagreesWith/conflictWith, RouteStop with wrap-safe
delay + nullable platform, PairListing with note + sortedForBoarding), ContractTest (12 cases, each a rule v1
broke). UNVERIFIED BY A KOTLIN COMPILER - that is action 0.3.

================================================================ 13. REPORTING FORMAT (every round, in this order, no adjectives where a number fits)
A. OWNER-VISIBLE: what the phone now shows differently (one paragraph, plain).
B. PROOF: run ids + statuses quoted from the API; commands run for local gates; artefact size/date if published.
C. UNRESOLVED: named gaps, with the exact next command for each. STATUS.md rows updated (state, verified-by,
   next action, needs-device).
D. DECISIONS: any ADR added, any rule touched (and why it was unavoidable).
E. OWNER ACTION: "none", or ONE numbered step. If none is needed, write "none needed" - do not invent tasks.
Be blunt about mistakes, including your own: this project's history is that honest self-criticism was cheaper
than the alternative.

================================================================ 14. IMMEDIATE OBJECTIVE (M2 = C15, on the v2 contract)
Deliver live station-pair search: v2/source/PairSource with injected transport + parser (per 7.7), returning
PairListing with provenance or a typed failure; merge into search results with live winning while fresh and the
asset labelled per row; persist overrides (7.8); cache with TTL; explicit copy for every 8.2 state; tests for
each; CI wiring so the emulator proves it against fixtures; then one owner-device check and M2 is accepted.
Start with actions 0.1 to 0.5 and report what the runs said before proposing anything else.

================================================================ 15. SOURCE INVENTORY (what we use, how old it is, how far to trust it)
Reliability is a MEASURED score, not an opinion. Score each source every week from the CI report (see 15.6):
  VINTAGE  = age of the newest fact the source can state      LATENCY   = seconds from railway event to us seeing it
  FRESHNESS= TTL we honour                                     COVERAGE  = % of stations/trains/pairs it can answer
  RELIAB.  = rolling 7-day parse-success % and p95 latency     AGREEMENT = % where two keyless sources agree
  LICENCE  = keyless | keyed-by-owner | bulk-download | ToS-grey   SHIPS? = may it be a runtime dependency at all
15.1 NTES / CRIS mobile endpoints (enquiry.indianrail.gov.in/crisns/AppServAnd) - PRIMARY LIVE.
     Gives: live running status with per-stop actuals and platform, full route, trains-between-stations,
     trains-at-station board, train exception/cancellation info, schedule by number.
     Age: minutes. The running-status feed recomputes every ~5-10 min; NTES itself is the same official source
     the railway's own "National Train Enquiry System" app (cris.icms.ntes) consumes.
     Trust: HIGH for what it states, LOW for completeness. Community evidence is that NTES is updated by hand
     from the last reported stop and can be late or wrong on platform numbers; we therefore never treat a
     missing value as a negative fact and never show platform unless the source states it.
     To us: keyless, unofficial (no SLA, no docs, framing/shape can change silently), envelope is AES-CBC+MD5
     with app-wide constants that are NOT secrets; reachable from a PHONE, unreachable from GitHub runners
     (TLS handshake dies - measured three times). Blocking risk: protocol change with no notice. Mitigation:
     shapeOf() capture + "protocol may have changed" copy + feature flag per source.
15.2 erail.in web endpoints (erail.in/rail/getTrains.aspx, ~-separated flat text) - ALTERNATE LIVE, LABELLED.
     Gives: trains-between-stations, train-by-number identity + schedule, run window, berth availability; its
     payload embeds its own feed stamp (e.g. 2026-10-11-7-56-55) which is how we can state "as of" honestly.
     Age: hours (it is a community site; no SLA). Trust: MEDIUM - useful as a SECOND WITNESS; a claim agreed by
     15.1 and 15.2 is evidence, a claim from only one is a hypothesis. Reachable from runners on the wrong path
     (HTTP 404 = host fine, our path wrong) and from the phone (779 B parsed) - the CI ladder settles it.
     Note: api.erail.in exists as a KEYED service; we use only the keyless web endpoint and never register a key
     for runtime use. Blocking risk: the site disappears or DOM-scrapes itself into a different shape.
15.3 Our offline asset, built by tools/build_refdb.py from public GitHub dumps (Spin1234 2026-06-20,
     prasenjit-27 2026-08-30) - DEMOTED TO COLD START, PERMANENTLY LABELLED.
     Measured state (2026-10-12): the known-truths gate FAILS 5 of 8 facts - 16228 maps to a DIFFERENT TRAIN,
     16221 is keyed in the reverse direction, two SMET->MYS services missing, and 10.9% of numbers use the
     0-prefixed style retired in the 2022-23 renumbering. So its CONTENT is years old whatever the commit dates
     say. Verdict: never an authority; it is the thing shown when nothing else is available, with
     "timetable"/UNVERIFIED wording on screen. There is no fresher public bulk dump (GitHub search 2026-10-12:
     11 candidate repos, only these two carry schedules) - that is why the architecture went live-first.
15.4 Open-data portals (data.gov.in railway datasets / CRIS "NTES API" / NPRTI bulk feeds).
     CANDIDATE, NOT YET ADMITTED. Bulk station + train + schedule extracts exist, which is exactly what would
     make the cold-start asset trustworthy again - but two things must be MEASURED before anyone relies on it:
     (a) actual vintage/refresh cadence of the schedule extract (railway open data is historically published on a
     slow, irregular cadence; a dataset "updated" quarterly is still stale between revisions), and (b) whether
     retrieval needs a free developer key. A build-time key held as a CI secret for a public bulk download does
     NOT violate "no keys to the user / no relay of ours", because nothing ships in the APK and no user action is
     required - but it does need an ADR + owner consent, and a fallback plan if the key is ever refused.
     Verification task (cheap, one CI job): fetch the dataset listing, print last-updated dates and row counts,
     feed it through tools/ci/data_truths.py, and report whether it would have caught the 16228/16221 errors.
     Promote to PRIMARY BULK only if it (i) is newer than 2023, (ii) is downloadable keylessly or with an
     owner-held build key, and (iii) passes the gate. Otherwise keep 15.3 as the labelled cold start.
15.5 Third-party "railway APIs" - EXPLICITLY NOT RUNTIME DEPENDENCIES, reference only.
     railradar.in (free key after sign-up; 1000 req/mo tier; 13,334 trains / 10,102 stations),
     railengine.vercel.app (free key via Google sign-in; 60 req/min), indianrailapi.com (keyed),
     railwayapi.com (keyed, historically paid), Apify/scraper actors for IRCTC (ToS-grey, fragile),
     IRCTC Connect/booking APIs (auth-heavy, commercial access only).
     Why rejected as providers: they need a key issued to a person (forbidden to ship; a relay of ours is
     forbidden), they add an outage and a quota we cannot serve, and their terms usually forbid the caching
     patterns a mobile app needs. What we DO take from them, as design precedent, not code or data:
     (i) an honest freshness envelope - LIVE/RECENT/STALE per response, which is exactly v2/Freshness +
         Provenance.label();
     (ii) per-row source attribution;
     (iii) an explicit "provider health" surface.
     They remain useful as a THIRD-WITNESS in the CI report (where public playgrounds allow a request) to
     cross-check contradictions - never as the source the user's data comes from.
15.6 Weekly data-reliability report (the thing that makes 15.1-15.5 true instead of remembered):
     one scheduled workflow runs: ETL -> known-truths gate -> NTES/erail probe ladder (with PROBE_CAPTURE) ->
     open-data portal probe (15.4) -> writes docs/v2/reports/YYYY-Www.md with the score table per source,
     contradictions found, and any promotion/demotion it justifies. It opens an issue only when a threshold in
     15.7 is crossed. Cost: minutes a week; it replaces "how old is our data?" with a file that answers it.
15.7 Promotion / demotion thresholds (act on numbers, not feelings):
     DEMOTE / flag off a source when: parse-success < 80% over 7 days, or p95 fetch > 8 s, or it contradicts a
     confirmed second source twice, or its own stamp is older than its stated cadence. UI then says
     "source disabled after N failed answers" instead of showing a wrong value.
     PROMOTE a source when: it passes the gate on the owner-verified facts, 7 days of >=95% parse-success, and a
     second witness agrees on >=90% of overlapping fields. Promotion requires an ADR, never a quiet swap.

================================================================ 16. COMPETITOR ANALYSIS - top 5, what they actually use, and why it works
16.1 Where Is My Train (originally Sigmoid Labs; acquired by Google in Dec 2018). Sources, per its own
     description: MULTIPLE - official railway systems (NTES/CRIS, station-master and control-room reports),
     GPS, CELL-TOWER TRIANGULATION, and CROWDSOURCED anonymised user location data; it estimates a train's
     position OFFLINE from cached data + cell towers, predicts delays from historical data, shows platform
     numbers (community-reported corrections fill gaps NTES leaves), and offers location-based train alarms.
     Why it wins: sensing, not scraping. It does not depend on the railway being prompt - it infers, and it
     caches hard. Weakness we can beat: it is a Google data machine; platform numbers are priors, not always
     live (its own users report that).
16.2 RailYatri. Its live status is explicitly CROWDSOURCED from users' GPS / cell-tower data, and it says so in
     its FAQ, arguing that the railway's own model "depends on the updates from the last stop, which is delayed
     and prone to manual errors". Full-stack booking + chat/community features behind a backend.
     Why it wins: fresher-than-NTES position without needing NTES. Transferable to us: none of the backend or the
     crowdsensing (our rules); the framing (last-report latency is the real problem) is very transferable.
16.3 ixigo Trains. "IRCTC Authorised Partner" - i.e. contracted data access, plus its own caching so train
     location works without internet. Why it wins: partnership + engineering (cache, prefetch, prediction).
     Transferable: the caching discipline. Forbidden: the partnership model - we have no licence and no backend.
16.4 Confirmtkt / RailMiti (second tier). Similar shape: IRCTC/NTES-derived feeds + their servers + prediction
     models; RailMiti built its reputation on station-level live data and alerts. Why they work: they keep a
     data team normalising timetables continuously. Transferable: the alert-on-approach UX (already on our
     roadmap as M6 "pre-arrival alarm") - it is implementable on-device.
16.5 The official NTES app (cris.icms.ntes; Play listing updated Aug 18 2026). This is the SOURCE ITSELF with a
     UI: live status, "spot your train", live station, train schedule with save, trains between stations,
     exception info. Why it matters to us: it proves (a) the data our app needs is official and reachable, (b) our
     honest differentiator is not access but judgement - small, no login, no ads, offline-capable, and every
     value stamped with where it came from. When NTES is late, we must be the app that SAYS it is late.
16.6 SYNTHESIS - what their designs tell us to build (none of it needs a key, a relay or ads):
     (a) CACHE AGGRESSIVELY + label it: their offline ability is caching, which we already plan (TTL +
         overrides), and which we can do with more honesty than any of them.
     (b) SEPARATE "delay truth" from "position truth": a schedule-derived position and a reported actual are
         different facts; our provenance model already encodes that (SourceKind).
     (c) THE CROWDSENSE MOVE WE CAN LEGALLY MAKE: on-device position estimation from the phone's own GPS while
         the user is aboard, matched against the timetable - no upload, no account, no third party. It gives the
         headline WIMT feature with none of its privacy cost, AND it self-heals our data: every confirmed stop
         pass is an observed actual for that run, which belongs in the override store (7.8). This needs a
         LOCATION PERMISSION decision from the owner: ADR-006 (propose, don't assume).
     (d) WHAT WE DO NOT COMPETE ON: PNR prediction, chat with co-passengers, booking, ticketing, "AI" delay
         models. Each needs a backend or data rights we refuse to take.

================================================================ 17. CHOSEN SOURCE ARCHITECTURE (the decision, with reasons)
17.1 Capability -> source, in priority order (label what you show; first answer wins while fresh):
     TRAINS BETWEEN STATIONS : 15.1 NTES pair -> 15.2 erail pair -> 15.4 open bulk (if admitted) -> 15.3 asset
     NUMBER -> IDENTITY      : 15.1 FindTrainJson -> 15.2 erail by number -> 15.3 asset (labelled UNVERIFIED)
     SCHEDULE / FULL ROUTE   : 15.1 -> 15.2 -> 15.3
     LIVE ACTUALS + PLATFORM : 15.1 ONLY (nothing else publishes per-stop actuals keylessly); else the dash
     STATION BOARD / FAVES   : 15.1 -> cached 15.2
     FARE / AVAILABILITY     : OUT OF SCOPE for v2 (needs IRCTC partnership; a keyless answer is not trustworthy)
17.2 Why this exact shape: it is the only combination that (i) can be right about today's numbers - the failure
     that started v2; (ii) satisfies "no keys, no relay, no ads"; (iii) degrades honestly when the phone is the
     only path that reaches the railway (which is the measured reality: runners are blocked); (iv) keeps the
     offline app usable in a tunnel, because the cache and asset still answer - with the label that says so.
17.3 Explicit rejections, so nobody revisits them: any keyed third-party API as a runtime provider; our own
     proxy; scraping IRCTC auth flows; Apify/scrapers; storing a user's PNR or location on any server; auto
     editing fixtures the app trusts (7.7); a "best guess" for platform or delay.
17.4 Precedence of evidence (for a human, for CI, and for the gate): an owner-verified live screenshot > a
     confirmed live answer > two agreeing keyless sources > one keyless source > the cold-start asset. A lower
     tier never overwrites a higher one silently; it either becomes an override with provenance or a stated
     contradiction (TrainIdentity.conflictWith).
17.5 Data we must never claim: "accurate platform", "confirmed cancellation", "on time" when the source is a
     schedule, or "no trains" when the truth is "could not look" (8.2 states). Say the source, the age, or
     nothing.

================================================================ 18. DOCUMENTATION SET (every doc: purpose, owner, when updated, gate that keeps it honest)
18.1 docs/v2/KICKOFF.md ......... this brief. Update: only when a rule changes, via ADR. Gate: exists + dated.
18.2 docs/v2/PLAN.md ............ stages, exit criteria, per-stage "what changes on the phone". Update: at
                                  milestone boundaries. Gate: every MILESTONE row must name an exit artefact.
18.3 docs/v2/STATUS.md .......... the tracker (19). Update: EVERY commit touching behaviour. Gate: a diff that
                                  changes app behaviour with no STATUS change fails the checks job.
18.4 docs/v2/COMPONENTS.md ...... PoC register (section 4 rules). Update: when a component's PoC lands/changes.
                                  Gate: every file in the v2 package appears as a row, or the row count is named
                                  in the PR body as pending with a date.
18.5 docs/v2/LEARNINGS.md ....... lessons with the artefact that enforces each. Update: after any defect that
                                  reached the owner's phone. Gate: a PR that fixes a defect must add a lesson or
                                  cite the existing one it proves.
18.6 docs/v2/DATA_SOURCES.md ..... the 15.1-15.7 inventory as a live table: per source - endpoints, auth,
                                  shape, latency, cadence, licence, verdict, "last measured <date> + run id",
                                  re-verify-by date. Update: whenever the weekly report moves a score.
18.7 docs/v2/adr/NNN-<slug>.md ... decision log. Template: CONTEXT / DECISION / ALTERNATIVES considered (with
                                  why rejected) / CONSEQUENCES / REVERSIBILITY / REVISIT-TRIGGER / DATE + who
                                  approved. Mandatory for: rule changes, new dependency, new permission, any
                                  key or credential touching CI, source promotion/demotion, anything the owner
                                  would be surprised by later.
18.8 docs/v2/stories/M#-<slug>.md . one file per story, the template in section 3. Exit criteria must be
                                  executable (a command, a run id, or a screenshot the owner confirms).
18.9 docs/v2/QA_MATRIX.md ....... rows = capability x state x device/API level; columns = expected text, test
                                  name, layer (L1-L7), result, run id, waived-reason. Nothing may be marked
                                  passed by reading code.
18.10 docs/v2/TRACEABILITY.md ... the spine: owner-claim -> story -> PoC -> test -> CI run id -> release tag.
                                  One line per user-visible promise. If a promise has no test, the row says
                                  "UNPROVEN" and it is visible in the PR body - not buried.
18.11 docs/v2/reports/YYYY-Www.md  the weekly source/data score report (15.6) + gate verdict. Append-only.
18.12 docs/v2/RISK.md ........... register: risk, trigger to watch, mitigation, owner, last-reviewed date.
                                  Live-feed breakage, upstream blocking, asset staleness, emulator/egress
                                  limits, and "owner loses trust in a wrong time" all belong here.
18.13 docs/v2/CHANGELOG.md ...... user-facing, per published artefact (9.7). docs/v2/RELEASES.md: version,
                                  commit, asset bytes+date read from the release API, install link, rollback
                                  instruction, QA sign-off checklist (8.8) pasted in full.
18.14 docs/v2/COMPETITORS.md .... quarterly note per competitor (16): what changed in their sources or UX, with
                                  evidence links; and the standing "what we refuse to copy" line, so scope creep
                                  has a paper trail to say no against.
18.15 docs/TEST_CARD.md / TEST_CARD_SHORT.txt : the owner-facing card - what to check on the phone this round,
                                  5 lines max, and what NOT to bother with. Written for a person, not a bot.

================================================================ 19. TRACKING SYSTEM (so work resumes instead of repeating)
19.1 IDs: M# milestones, T# micro-tasks, ADR-###, plus DEFECT-# for anything the owner saw on a phone. Task
     titles are user-facing outcomes ("search shows today's trains"), not activities ("refactor repo").
19.2 STATUS.md row format (one table, machine-readable, single source of truth for state):
     | id | task | state | verified-by | next action | needs-device | owner-action |
     States: NEXT | DOING | BLOCKED:<one cause> | HELD:<waiting on what> | DEFERRED:<by whom> | DONE:<date>.
     Rules:
     - DONE without an artefact NAME (run id, test name, file, screenshot the owner confirmed) is not DONE;
       it stays DOING. CI green is an artefact; "looks right" is not.
     - BLOCKED/HELD rows name the exact unblock command or the one owner action. No mystery blocks.
     - Resume at the first DOING, else the first NEXT. Never re-run a DONE row unless its artefact fails.
     - Reorder or delete rows only with a line saying why (the audit trail matters more than tidiness).
     - At most one open question to the owner, in the owner-action column, and it must be answerable in one word.
19.3 Cadence: one push per round then read the run (5.9); at round end post the report format (13); every
     session CLOSE writes a HANDOFF paragraph into STATUS.md (state, artefacts, resume line, unresolved
     blockers) - because a merged PR can end a session mid-thought, and a fresh session must not re-derive it.
19.4 Cross-links: commit messages cite T#; PR bodies are the round report verbatim; issues are opened only for
     defects that reached a device or a red CI job, each with the run id and the failing line quoted.
19.5 Metrics we keep (cheap, from CI, no analytics): build minutes and wall-clock per round; first-result
     latency on the emulator; data-trust verdict per weekly run; source parse-success %; count of user-visible
     defects found before vs after CI (the number that proves whether the method works); % of TRACEABILITY rows
     that are UNPROVEN (target 0 for shipped promises).
19.6 Review clock (dates, not intentions): per source (18.6) re-verify monthly, or immediately on a gate FAIL;
     competitor watch quarterly; open-data portal check (15.4) monthly until admitted or abandoned twice;
     PLAN.md at each milestone; KICKOFF.md + LEARNINGS.md after any defect that reached the owner's phone;
     risk register monthly; and "if IR publishes an open, dated timetable (15.4) newer than 2023" is a standing
     trigger to reconsider the cold-start asset entirely.
19.7 Anti-drift clauses: no undocumented decision (18.7); no test written without the failing behaviour it
     proves (8.7); no "temporarily unlabelled" fallback; no doc edit without a commit; no claim without a run
     id; no new source without a dossier row (18.6) naming its vintage, cadence and failure modes; no feature
     without a story whose exit criterion the owner can see on a phone.
