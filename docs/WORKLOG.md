# Worklog — where this is at, and how to continue

Read this first, every time, before anything else. Update the three top lines before you stop.
It exists because CI knowledge is worthless if it lives only in the session that earned it.

## Head

- **HEAD / branch**: `arena/edd38b02-checktrain02` (push only here; one branch per session).
- **What the last commit changed (and why it took four rounds)**: the app now speaks the railway's own
  mobile enquiry protocol — `remote/NtesWire.kt` (AES-framed POST, cross-checked against a reference
  implementation's byte-exact vectors in `NtesWireTest`) and `remote/LiveProvider.kt:NtesProvider`, registered
  ahead of the timetable source. Until this commit every provider we had could only answer with the
  *schedule*, which is exactly the complaint: a screen of planned times dressed up as tracking. NTES answers
  with per-stop actual arrivals/departures, platforms, delays and its own last-update stamp, and it needs no
  key, no account and no server of ours — the crypto constants are inside the official app and shared by
  every client, so this satisfies "no keys, no proxies, no ads" rather than bending it. The timetable source
  stays in the chain as the labelled alternate, and a shape we cannot open yields a sentence on screen, never
  a silent dash. `Http.post` was added for it (deliberately uncached: caching a live-status answer is how a
  tracker starts lying about age).
- **New CI job `probe`**: a development sandbox is not allowed to reach `enquiry.indianrail.gov.in`, so the
  runner POSTs each sub-service and posts the *schema* (key names, how many rows carry actuals, the header
  fields) as a commit comment. Read that comment before trusting or rewriting the parser — `tools/ci/probe_live_feed.py`.
- **State of the device sweep** (as of `53399c4`): the run got further than any before — `plan` ✓, `build` ✓
  and all four rows (`api34`, `min24`, `density`, `release`) at step 5 of the row action 18 minutes in, i.e.
  inside the boot step. That run was superseded by the live-feed commit, which changes no harness code, so the
  next run answers the same question with the fix riding along.
- **Open questions the next run answers**: (1) does the flow suite execute on the booted emulator now that
  `adb` is on the job's `PATH` and `avdmanager` gets `-b/--abi`; (2) does `ShowFullRunJson` answer our envelope
  from a GitHub runner, and does the row comment from `LiveFeedFieldTest` report `reported > 0` — that number
  is the product claim, and "0 rows reported" with a note is the honest version of the same result.
- **Gate discipline** (learned the hard way): after *any* workflow YAML edit run
  `python3 tools/ci/test_check_workflows.py && python3 tools/ci/check_workflows.py`; a duplicated key inside a
  mapping once passed the old scanner and killed a run as an invalid file. `check_refs` no longer reads
  `x == y` inside a constructor call as a named argument, and `check_members` knows the JDK/org.json calls the
  crypto client makes — both were false positives, fixed in the checkers rather than by bending the app.

## What a real device told us, and what the app now does about it

The first field report from a phone on Indian LTE (train 56227, 18:36 IST) came back as:

```
providers PROVIDER_OVERRIDE=miss PROVIDER_NTES=miss PROVIDER=miss CACHED=miss
note LIVE · running status: The live feed refused the request (closed).
rows 40: 0 reported / 0 projected / 40 timetable
```

Read it precisely, because two different conclusions are available and only one is supported:

- **what is not wrong**: the parser. No response body ever arrived, so nothing was mis-parsed; and the screen
  said `OFFLINE · schedule only`, `no live fetch`, `no live feed` — the honesty machinery worked exactly as
  designed on a train with 40 halts.
- **what is wrong**: the request. `Http` reported the failure as *closed*, which is a server hanging up on us,
  and "closed" is all it knew: `e.message` without the exception class cannot separate a TLS handshake refusal,
  an HTTP 403 from a policy layer, and a carrier middlebox reset. Those need three different fixes.
- **what this does not prove**: that the device had a working route. The bundled timetable is on-device, so a
  schedule rendering is not evidence about the network — which is why the probe below includes a control
  against a host this app is known to reach.

So `remote/FeedProbe.kt` runs the request matrix **on the device** — the only vantage point that can — after a
refusal: seven shapes (shipped envelope, same over an explicit TLS 1.2 socket, browser agent, unencrypted
plain body, the legacy `liveStatusJsonNew` GET *with the Referer and X-Requested-With the NTES site has always
needed*, an independent mirror, and a control host). It is single-flight, 7 s per try, and it *adopts* the first
variant that yields a usable snapshot: `NtesProvider` asks with that shape from the next refresh, labels the
answer `via post-tls12` in the report, and stops if the payload ever stops being usable. Adoption is the reason
this is a fix rather than a telemetry exercise: on a network where only one shape answers, the app starts
working for that user without waiting for a rebuild.

Standing rule, restated because the pressure to bend it is coming: **no API key ships in this APK**, not even
a publicly documented sample credential. If the probe shows that only a keyed source answers from India, the
correct output is an evidence-backed proposal to the user (endpoint, what the key costs, what it means for the
no-accounts promise), not a quiet exception in the provider chain.


## The opt-in diagnostic log, and why the second field report was from a stale build

The third device paste looked like a re-test of the fix and was not one: it still listed `get-howtherail`, a
variant deleted in the previous commit, and its GET lines still lacked the exception class that commit added.
Same words, older build. Nothing about that is the user's fault - a rolling release asset is easy to believe
is current - but it cost a round trip, so the report now starts with the truth:

    build 1.0.0 code 53 · diagSchema 1 · log off

A report that cannot say which build wrote it is a rumour. That line is also why `flow_coverage` and the
`checks` job must never be the only gate: `117a465`'s probe shipped a control host (`howtherail.in`) that
does not resolve anywhere, and every gate was green.

### What was built instead of another screenshot request

`core/Diary.kt`, driven from the Data source screen (the one screen the user had already found):

* **Off until switched on.** `log()` returns before it touches the buffer or the disk. No shadow capture that
  merely has not been flushed - the first byte of the file exists because the user asked for it, and the
  screen says how many bytes and when the last entry was written, because "I enabled it and nothing happened"
  is otherwise indistinguishable from a broken switch.
* **Content by rule, not by taste.** Host and verdict per request attempt, provider verdicts, parse refusals
  with only the JSON *key names*, the probe's matrix. No query strings (they carry the train number and the
  travel date), no response bodies, no locations, no accounts. A redaction pass drops `app_key`/`api_key`/
  `token`/`secret`/`password`/`sckey` values and any bare 32-hex digest, and there is a unit test asserting
  each - so the pass cannot quietly rot while new fields are added around it.
* **Egress facts the app cannot otherwise learn.** On enable: device/OS, transport (incl. whether a VPN is
  the active route), whether Private DNS is `off`/`opportunistic`/`hostname` - with the resolver reduced to
  its last two labels because those hostnames embed account ids - and a name-resolution plus TCP/443 probe of
  the four hosts this app needs. `howtherail.in` returning NXDOMAIN on a phone while Chrome loads the railway
  site is exactly the fact that distinguishes "the feed refuses us" from "one app's traffic is being filtered",
  and no CI runner can produce it.
* **Shares only how the user chooses.** `Share` (plain-text intent) and `Save to file` (system document
  picker). There is no upload path in the code, which is the only version of "log collector" compatible with a
  screen that reads "no accounts, no API keys, no ads, no analytics".
* `Clear` deletes and writes nothing afterwards, so the card goes back to "nothing collected yet" while the
  switch stays on - a user mid-repro must not have to re-enable and start the capture over.

The first line of the file is written synchronously and the rest on a daemon executor: an append per socket
attempt must never be able to slow a refresh, and an unbounded log must never be the reason a phone lags.
Rotation keeps the newer half and records the cut, because a log that eats its tail destroys the one capture
worth having.

Covered on-device by `SettingsFlowTest#diagnosticCollectorIsInertThenLiveThenEmpty` (flow `diag-log-collector`):
inert, then visibly live, then emptied by Clear. The system share sheet and document picker are deliberately
not driven - a chooser is Android's UI, and a row that fails when Google changes it tells us nothing.

### Where the network question now stands

The fix that the stale paste did not test (`258a28b`) is still the leading hypothesis: `Http` used to call
`disconnect()` in a `finally` on a process-shared instance, and Android pools keep-alive sockets per process,
so one thread tearing down a connection closes the socket another thread is handshaking on - `IOException:
closed`, uniform across unrelated hosts, sub-second, and invisible to a CI runner that issues one request at a
time. The next report either shows that fixed (attempts now serialised, sockets returned to the pool) or it
carries the class names and the DNS/VPN/Private-DNS block that says why. If the log shows `tcp443=false` for
every host while Chrome loads the same pages, the answer is a per-app network policy on the device, and this
app's honest state remains "schedule only, labelled" - not a silent fallback, and not a key baked into an APK.

## What CI can and cannot prove about the live feed

`AppServAnd` at `enquiry.indianrail.gov.in` **refuses connections from GitHub's runner IPs**: two runs, three
trains, two sub-services each — six TLS/urllib timeouts in a row, reported by the `probe` job as a commit
comment. It is a geo/IP policy on the railway's side, not a bug in the framing (the request our client sends
is byte-identical to the reference client's, which is what `NtesWireTest` pins). Consequences worth knowing:

- a device row will therefore show the *transport failure* sentence and fall back to the labelled timetable,
  and that is the correct behaviour of a resilient chain — not a passing test of live data;
- the only instrument that can see the real payload is a device on an Indian network, i.e. the user's phone.
  Long-pressing the provenance badge on the tracking screen copies a report (sources tried, per-source
  outcome, how many rows carry a reported minute, the stop table as shown, the feed's own stamp); the same
  text goes to `logcat -s LiveFeedReport`. Paste it here and the parser gets pinned to reality in one round;
- so "the CI is green" and "the live feed works on your phone" are two separate claims. Do not accept the
  first as proof of the second, and do not read the second's absence from CI as a failure of the app.

## Reading a run from here (the only channels that work)

```bash
R=tarungittarun/Checktrain02
gh run list --workflow e2e.yml --branch arena/edd38b02-checktrain02 --limit 1 \
  --json databaseId,headSha,status,conclusion
gh api "repos/$R/actions/runs/<run>/jobs" --jq '.jobs[]|"\(.id) \(.name[0:24]) \(.status) \(.conclusion//"")"'
gh api "repos/$R/check-runs/<job id>/annotations?per_page=100" \
  --jq '.[]|"[\(.annotation_level)] L\(.start_line) \(.message[0:220])"'
gh api "repos/$R/commits/<sha>/comments?per_page=100" \
  --jq '[.[].body|select(startswith("### e2e"))]'
```

- Job logs redirect to blob storage and are **not fetchable here**. The check-run annotations are.
- Annotations are **not published until a step ends** — a quiet in-progress row is not necessarily stuck.
  The per-step `[row] step N:` notices exist so that the stream, once readable, says how far it got.
- `gh run cancel` and `gh workflow run` answer **403** for this credential. A push supersedes an in-flight
  run; `[e2e-full]` in the commit message selects the 4-row matrix, `[e2e-focus: Class]` one class.

## Solved — do not re-litigate

| Symptom | Cause | Where the fix lives |
|---|---|---|
| Run named by file path, `jobs: null`, ~25 s red | duplicate mapping key (`if: always()` twice) — PyYAML accepts it, Actions rejects the file | `tools/ci/check_workflows.py` |
| Same, for an action input | `required: true` **and** `default:` on one input | same checker |
| Job dies as one step, no sub-steps | `always()`/`failure()` in a **composite step** `if:` | verdict step reads `steps.<id>.outcome` |
| `Can't find 'action.yml'` | a `uses: ./local/action` needs the **job's** checkout, not the action's | `e2e.yml` row jobs |
| `exit code 127` mid-row, no other annotation | `adb`/`sdkmanager` not on the **job's** PATH (the boot script's export is local) | `$GITHUB_PATH` in `emulator_boot.sh`, visible `gh run download` fetch step |
| `Found AVD target architecture: arm` on x86_64 | hand-written AVD lacked `systemImage.arch` | `emulator_boot.sh` |
| `avdmanager create avd` prints its usage | needs `-b/--abi` named; global options go before the subcommand | `emulator_boot.sh`, three forms tried per run |
| Row comment never posted | `set -e` + `grep -q … && verdict=FAIL` aborts the step | `if` statements in the action |
| `1/1 passes green` on a row with no device | roll-up read `variant_row=` as a test | `e2e_rollup.py` (verdict-shaped values only) |
| False `rc=0` for every failed avdmanager form | an `if` with no `else` exits 0 | capture `rc` from the command |
| `jobs: null` right after editing an input | two `default:` keys on one input — and the linter blind to it | `dup_keys` rewritten, `tools/ci/test_check_workflows.py` runs first |

## Standing rules for edits in this tree

- After any script that rewrites `.github/**` by hand: `git diff --numstat` must show **additions with no
  deletions** unless the deletion is the point. One such script silently deleted 12 step bodies.
- Never chain an `assert`-guarded patch and a `git commit`/`push` in one shell command: a failed assert
  aborts the patch while the commit still runs.
- CI checkers are **stdlib-only**; PyYAML is optional and `check_workflows.py` degrades to indentation
  rules when it is absent (a runner's python has no PyYAML).
- Before pushing: `python3 tools/ci/test_check_workflows.py && python3 tools/ci/check_workflows.py &&
  `python3 tools/ci/flow_coverage.py &&
  for c in check_kotlin_balance check_refs check_resources check_members; do python3 tools/ci/$c.py; done`
  and `bash -n` on every harness script. All must be 0 problems.
- Keep the workspace lean: no generated artifacts, no scratch dirs in the repo, nothing outside it.

## What the pipeline is for

The app (offline timetables, live feed stamped and labelled, never presenting schedule as actual) is built by
`.github/workflows/android.yml`; `.github/workflows/e2e.yml` builds the APKs and then **drives them on
emulators on four parallel runners** across the 46 flows listed in `docs/E2E_FLOWS.md`, with the manifest
gated against `@flow` annotations on every event. `report` fails the run when no row booted or the minSdk
row's tests failed, and ratchets line coverage (`--min-lines 45`) — to be raised once the first green device
row produces a JaCoCo number.

## Resume here (state as of this push)

Rolling asset as of the last green build: **10 611 585 B, updated 2026-10-11T17:28:15Z = 22:58:15 IST**, from
`71fa6bd` (`checks ✓ debug APK ✓ tests + screens ✓`, `release` skipped as always), sha256
`61908be6…803c7c29`. Previous verified build: 10 607 777 B at 22:29:50 IST from `07d050f` — `gh api repos/tarungittarun/Checktrain02/releases/tags/latest-apk` said so, which is the only
acceptable source for the number in a test card. If your download does not match that size, you are being served
a cached build and nothing in the new log means anything yet.

Verified states, so nobody re-derives them: `b7c42c5` failed with one compiler error —
`Modifier 'inner' is not applicable inside 'standalone object'` at `BrowserFetch.kt:201` — fixed by making
`Call` nested and naming the singleton where it is used, which is also what `check_members.object_inner` now
catches locally. `a9efa01` (the fix, plus the serialised engine and the adopted-origin change) and `eaac1cd`
are both `checks ✓ debug APK ✓ tests + screens ✓`; `release` is tag-only, `skipped` is normal, and an
`e2e` row cancelled by a newer push is that same concurrency working as designed. Read the verdict of a run
whose log you cannot fetch from here through the **commit comments**:
`gh api repos/tarungittarun/Checktrain02/commits/<sha>/check-runs` → the failed check-run's annotations, then
`gh api repos/…/commits/<sha>/comments` — the workflow's own "Report the failure on the commit" step pastes the
```e: file:///…``` lines there, which is how `b7c42c5` was diagnosed in one call when
`gh run view --log` could not reach `results-receiver.actions.githubusercontent.com` from this sandbox.

Download link, published by the `debug APK` job of the runs above:
`https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk`. **Re-check the size
and `updated_at` with `gh api` before announcing a link** — twice now a pushed commit produced no new asset
because the job that publishes it had failed.

`f00be21`'s log appeared to answer the five-round question — `POST … answered 159B in 1086ms` — and it took
the next report from `b367522`/`7f84cd6` to see what those 159 bytes were:

    This page contains the following errors: error on line 1 at column 1: Start tag expected, '<' not found
    Below is a rendering of the page up to the fir

Android's own message for "the server sent me something that is not markup". **The endpoint never answered at
all through that path**: a JSON body cannot be rendered, so WebKit's parse error became the document and the
payload was lost at column 1. Two things follow, and the second one is a lesson about diagnostics generally.

1. What still stands is the *control*: `webview-get-ntes` returned 506 bytes of the real NTES page
   ("National Train Enquiry System Spot Your Train…") on the same URL where `get-ntes-web` in the same run got
   `IOException: closed`. Chromium reaches this host from this app's process while `HttpURLConnection` does
   not. That is a transport difference, measured twice in one file, and it is the reason the app keeps a
   browser path at all.
2. The latched `browserWorks` flag was set by that 159-byte artifact, so the next refresh asked *everything*
   through the renderer and the traveller's sentence became "The live feed answered with an empty body" —
   a worse lie than the one it replaced. A diagnostic that can change what the user sees is a user-facing
   feature and needs the same evidence standard: the latch now requires a 2xx *body*, never a document.

So the round-6 change is one of instrumentation, and it is the smallest thing that could actually read the
answer: load a same-origin page, `fetch()` the API from inside it, read the text back out of a named global.
That yields what no earlier shape could — the HTTP status and `Content-Type` of `AppServAnd`'s reply, logged on
the same line — plus the site's cookies and a genuine `Referer` for free, since the request now originates from
their own origin rather than being dressed to look like it. It also finally *tests* the header questions from
rounds 1–3: `postUrl` fixed Content-Type at urlencoded and discarded every other header, so "we tried it
through a browser and it still refused" was never a finding about headers or payloads. Every browser probe now
declares the content type its body actually is, and a probe variant asserts it.

Two facts to carry forward, because each cost a round trip:

1. `258a28b` never compiled - unescaped quotes inside a leaked `${'$'}`-style template - so the link silently kept
   serving `b3a0925`, and the user's "Auto off" re-test ran the old HTTP layer. Verify a paste's build line
   before interpreting it; verify the asset size before telling anyone to install.
2. The gate set is textual by design and cannot see types, operators or string contents. Three of this session's
   build breaks were invisible to all of them. What the project gained instead: `tools/ci/check_literals.py`
   (leaked escaping, doubled concat operators), `check_members` receiver checks, `companion_twins`, and the
   practice of reading the failed build's own error list from the commit comment rather than guessing.

Open question the collector exists to answer: does any request shape reach `enquiry.indianrail.gov.in` from
this phone once sockets stop being torn down? Reading the shared log answers it. If `tcp443=true` for
`one.one.one.one` and `erail.in` but every railway host hangs up, the answer is upstream policy or a per-app
network rule, and the honest product behaviour stays "labelled schedule" - not a key baked into an APK.

## The phone answered the question, and the answer was "use the browser's client"

Round four of the live-feed problem, settled by one field log instead of another theory. Reading it in order
matters, because three of my own hypotheses died in it:

| Evidence in the log | What it killed |
| --- | --- |
| `enquiry.indianrail.gov.in: 1 addr(s) A=103.110.246.84 AAAA=none` and `TCP … 443: v4=ok v6=absent in 94ms` | The address-family theory. There is no v6 to mispreference, the socket opens in 94 ms, and `post-ipv4-pinned` failing the same way was therefore expected. The pin stays as a fallback, not as the fix. |
| `api.railradar.in … v4=ok v6=ok` then `HTTP 401 in 222ms` | "The app cannot do TLS" and "the device blocks this app". Java TLS from the app, on that Wi-Fi, gets a real HTTP answer from a modern API. |
| unchanged `IOException: closed` with the socket-pool fix shipped | My keep-alive race theory. Real hygiene, not the cause. |

What survives is the shape of a client filter: **the connection opens, the TLS handshake is hung up on** -
~400 ms, eleven times, every request shape, both TLS profiles, while Chrome on the same phone in the same
minute renders the running status of the same train (`20651`, *Arrived at SAGAR JAMBAGARU at 20:55, Delay
00:22*). The remaining variable was the client, so the app now has the browser's client: `core/BrowserFetch.kt`
issues the request through the system web view - present on the device, accepted by this endpoint, no key, no
proxy of ours, nothing to sign up for. It is never attached to a window, has no `addJavascriptInterface` (so a
document cannot reach into the process, which is the one real risk of running JS at all), no file/content/
database/cache access, and reads the API document back through a JSON decode rather than a string splice. It is
abandoned and torn down if a load stalls, so one stuck page cannot poison the next refresh.

Adoption did not need to change, which is why this was affordable at all: `FeedProbe.Variant` gained one
boolean, so `webview-post` and `webview-get-ntes` are measured exactly like a header or a TLS profile, and the
first shape that produces a usable snapshot becomes that device's transport on the next refresh - live data
with no second install. The provenance line still names how the row was fetched.

Two more findings from the same file were bugs in my own instrument, both fixed: the burst of
`UnknownHostException` across five hosts within 70 ms, after all five had resolved a minute earlier, is a
filtering private-DNS profile (`privateDns=opportunistic host=adguard.com`) blinking under load - `Http` now
treats that as a blink (one 600 ms pause, one retry, message unchanged if the name is truly gone) and the
collector records the active network's nameservers, `[redacted]` when on-device, plus the network kind, which
is how "a security app sits between this app and every socket" gets distinguished from "the railway refused us"
without asking the user to read their own settings.

Standing rule re-confirmed: **CI is the only type checker here.** The `NetworkOnMainThreadException` in the
previous log, `snap.rows`, `x.size + +`, and `setFixedRouteToAddress` resolving against the JDK's copy of
`java.net.HttpURLConnection` were all invisible to eight green textual gates. Where an API exists only on the
platform, reach it the way that survives both classpaths and log why.

## Round 5, in one paragraph: the instrument was the last bug

The most useful sentence in the newest log was not about the railway: the 159-byte answer appeared on the
`[PROBE]` line as `webview-post … answered 159B, not running data` in the *copied report* and nowhere in the
*log file*, because the log line dropped `r.head`. A diagnostic that shows the payload to the person holding
the phone and hides it from the person reading the attachment loses the information exactly once, and the
round trip costs a day. Every probe line now carries the head.

Two smaller lessons from the same diff: a batch patch script that `assert`ed mid-way left one file edited and
five not, which is the desired failure mode but only if the anchors are re-read before resuming (a block I had
already rewritten twice in the session no longer matched what I expected, so a removal pair was dropped and
the additions were anchored by line number instead of by prose); and my own member checker reported a Kotlin
keyword error on the phrase "as a form field. `postUrl`" in a doc comment, which is a false positive worth
fixing in the tool rather than dodging in the prose — `strip_noise` works line-by-line and cannot see a KDoc's
interior, so block comments are masked before that rule looks at a file.

## Round 6: the numbers a swallowed body produced, so the next reader does not repeat them

| Line in the 22:03–22:04 report | What it really meant |
| --- | --- |
| `[BROWSER] POST … -> answered 159B` | WebKit's error page, not the endpoint's answer. Zero information about the payload; the request *did* complete, which is not nothing. |
| `[BROWSER] GET … -> answered 506B` + NTES page text | The endpoint answers a mis-called JSON URL with its own HTML page. Genuine, and the transport's proof. |
| `[PROBE] webview-post-form: TIMEOUT … got no document in 10000ms in 41621ms` | `postUrl` of JSON has no renderable document, so the engine waits on a download decision ~30 s past its own timeout. A renderer is the wrong instrument for an API; that variant is deleted rather than tuned. |
| `[BROWSER] POST … -> EMPTY browser answered with an empty document in 123ms` | The latched flag doing damage one refresh later: the reused engine had no document to give. Fast now, wrong then. |
| `[CHAIN] … LIVE · cached: Nothing this device has stored about 20651 is new enough to show` | Working as designed: a >24 h-old snapshot is refused rather than shown as live. The kind label `LIVE` in that line is a provider name, not a claim to the traveller. |
| `get-egress-trace: IOException: closed` for `one.one.one.one` while `api.railradar.in` answered `HTTP 401` | Not "the whole network blocks TLS". Some hosts close this app's Java handshake, others answer — which is why address-family and DNS theories were abandoned and why per-host reachability has to be measured rather than inferred. |

## Round 7: the 22:38 log is about the Wi-Fi, and the app was the slow part

Two user complaints, both real, both ours:

1. **20-40 seconds to paint a train.** Not the railway's doing. `Http`'s ladder - a retry, a pinned-v4
   second chance, a resolver-blip retry, each granted the *full* per-attempt timeout - multiplies to 31.5 s
   and 44.1 s in the two `[CHAIN]` lines that report, with the first attempt failing at 5 s every time. One
   probe variant logged **162 s** while the traveller waited, because the probe's own budget was only checked
   *between* variants and nothing bounded a variant from inside. So: `Http.get/post` take a wall-clock
   `budgetMillis`, every rung is clamped to what remains (and skipped below 900 ms, because an attempt that
   cannot finish is not a second chance), the live fetch gets 6.5 s and the alternate source 4 s, and the
   probe now yields the moment a manual refresh is asked for (`FeedProbe.requestCancel`, checked between
   variants, which `break`s instead of `continue`-ing through the rest of the matrix).
2. **"Save to file is not working."** It read `pendingExport`, a field that was never assigned, so every
   outcome - including a file saved perfectly - took the "you closed the picker" branch. The URI the document
   picker returns lives in `result.data?.data`; that is what is written to now, and the toast names the
   destination because a log exported to an unknown path cannot be attached to a report.

The analytic addition is the one this saga needed from the start: `[TLS] <host> 443 handshake: ok|fail:X in
Nms`, the app's own HTTPS client completing a handshake and nothing else, from the collector's thread. "The
socket opens and then `IOException: closed`" is the *same sentence* for a far end that refuses this client's
TLS fingerprint and for a congested link that drops a long handshake - six rounds of logs could not tell those
apart, and they have opposite answers. Paired with the two-try TCP probe (`v4=fail:SocketTimeoutException`
here, `v4=ok in 94ms` in the 21:47 run, same phone, 50 minutes apart) and with the browser engine's verdict,
a single report now separates: network congested / TLS refused / payload refused.

And the honesty note for this round's own reading: the 22:38 report **cannot** say anything about the
railway's policy, because its `[TCP]` line shows the phone could not open the socket at all - 2.6 s timeout to
the feed, 2.2 s to erail, 2.0 s to Cloudflare, and `UnknownHostException` bursts for hosts that had resolved
minutes earlier. Asking it to confirm or deny a fingerprint block is asking a question the log does not
contain. The user's filter is also now exonerated by their own evidence: no filter app exists on that phone,
which retires my "security app judging the client" theory along with the address-family one before it.

## Round 8 (11 Oct, late) - the data itself, and what the app said about it

User verdict: most trains shown are wrong, even the numbers; the app must show latest + running info.
Evidence gathered, not theorised:

- `tools/build_refdb.py` owns no data. It clones Spin1234/IndianRailwayOpenReference.github.io (schedules,
  last commit 2026-06-20) + prasenjit-27/Indian-Railway-Data (run_days, 2026-08-30). Verified by building the
  asset here: 6,775 trains, and SMET->MYS contains `06228 SHIMOGA MYSORE EXPRESS 22:10->07:00(+1)` and no
  SMET->MYS row for 16221. So the numbers are not invented; they are four months stale, which is one full IR
  timetable revision. No amount of UI/transport work could have fixed this - it is a sourcing problem.
- The chip quoted `meta.built_at` (our ETL stamp, minutes old) as "timetable 11 Oct 2026". That made stale data
  look current and turned a data complaint into a trust problem. `R.string.data_as_of` was dead code.
- `TrainFinder` ordered by |now - departure| and that same order drove BOTH dedupe selection and display, so
  a train that left 41 min ago headed a list where the next one leaves in 9 min.

Fixes in this commit: ETL records `source_vintage` (min over upstream commit dates, so an overlay cannot
overstate freshness) and logs each source date; the chip reads "N trains . timetable data as of <date> .
<k> days old, may have changed" (>90 days) or "timetable date unknown - check a live source" for old assets;
`TrainFinder.runsBetween` keeps proximity for *which instance of a number* to keep and adds a separate
*display* order (still-catchable first, then the ones already gone, nearest first). The ordering test that
pinned the old display rule is rewritten with the reason. `python3 tools/build_refdb.py --profile halts --out
/tmp/refdb.dbgz` here: builds, 5.88 MB, meta source_vintage=2026-06-20. Gates + the new JVM ordering tests
ride on CI (no Android SDK in this sandbox).

Still open after this round, in order: live per-row running status is the actual ask and the ETL fix only buys
honesty; a runtime schedule refresh (so the phone is not pinned to whatever HEAD CI saw) is not built; the
NTES browser transport works on the device but the parse has never been fed a real payload.

## Round 9 (11 Oct, 23:4x) — v2 restart: reality as a test, and a plan that says what it cannot prove

Owner rejected the build again and asked for a fresh start with real product methodology, per-component PoCs,
emulator verification (so they stop installing APKs), and a resumable status record. Delivered this round:

- `docs/v2/{PLAN,COMPONENTS,STATUS,LEARNINGS}.md` — the restart: stages 0-6 with exit criteria and the
  user-visible delta per stage; a component register where every entry names the check that proves it (and two
  honest `MISSING`/`BLOCKED` states); a micro-task tracker whose first `DOING`/`NEXT` line is the resume point;
  twelve lessons, each pointing at the artifact that enforces it.
- `tools/ci/data_truths.py` + `fixtures/known_truths.json` — the repo's first check against *reality*. Run on
  tonight's asset it fails 5 of 8 facts: 16228 is a different train than ours, 16221 is keyed backwards (which
  is why it "vanished" from one direction of search), two SMET->MYS services missing, and 10.9% of our numbers
  are the retired 0-prefix style. Non-fatal by design (a red build strands the owner on an older APK), stamped
  into `meta.data_trust`, reported in the job summary via `prepare_asset.sh`, and read by the ETL log.
- `RefQueries.dataVersion()` no longer lets a date vouch for unverified data: verified / never-checked /
  UNVERIFIED are three different sentences, and the third one tells the passenger to verify before travelling.
- Removed the dead `R.string.data_as_of` and the `RailTime` import its removal orphaned.

What this changes on the phone: the status line stops implying the timetable is current, and CI prints the
contradictions between our data and the railway's. What it does NOT fix: the data is still wrong until the live
pair search (C15) lands - that is round 10, and it is now the first `NEXT` line in `docs/v2/STATUS.md`.

## Round 11 (12 Oct, 00:4x) — v2-fresh accepted; the probe became a real instrument

Owner created `v2-fresh` from tag `v2-restart` (→ 52cd940) ✓ verified against `gh api .../branches`. Remote now:
`main` 9a0514d (untouched), `arena/...` = working ref, `v2-fresh` = the accepted baseline for milestone PRs.

CI on `db2e152`: checks/debug APK/tests+screens/probe all success. Its probe comment answered the Stage-0
question and exposed two of my own bugs:
1. `erail.in` from a runner = **HTTP 404, not a timeout** → the host is reachable from CI; my path was wrong.
   The app already knows the working path (`/rail/getTrains.aspx`, used by `ErailWire`/`LiveFeedFieldTest`), and
   my probe had invented `/getTrains.aspx`. Fixed: the probe now uses the app's own base, and tries 4 candidate
   query shapes in one run (numeric DD-MM-YYYY first, DD-MMM-YYYY, bare, plus the 404 path as a control) so a
   single CI pass settles "which URL works" instead of costing a build per guess.
2. The identity witness was placed below `if doc is None: continue` — lesson 14's exact defect, reintroduced
   while writing the fix for it. Extracted `erail_identity_lines()`, called unconditionally, and covered by
   `tools/ci/test_probe_live_feed.py` (now 9 tests). One test immediately caught a third bug: `fields[2][-3:]`
   truncated `SMET` to `MET`, i.e. my own probe was about to invent wrong station codes while gating the app on
   invented station codes.
Why it matters: erail answers "what is this number today" without a key, and CI can reach it. That is the first
path by which `fixtures/known_truths.json` - and therefore the app's data-trust verdict - refreshes itself
overnight instead of waiting on a phone screenshot.
