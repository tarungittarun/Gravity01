# Assessment: externally researched "zero-cost live tracking" spec (owner-provided, 2026-10-12)

The owner supplied a three-part spec from outside research: (1) a system architecture, (2) a
Python/FastAPI scraping proxy with cache, (3) an Android Retrofit client bound to that proxy.
Verdict per part, against this project's measured evidence map (rounds 13–26, both vantages:
the owner's phone and CI datacenter runners).

## Part 1 — architecture

| Claim | Verdict |
|---|---|
| Static layer local in the APK (routes, codes, coords, scheduled times); never fetched at runtime | **Already ours since v1** (offline asset, 6,775 trains; the UNVERIFIED banner exists precisely because this copy can be stale). |
| Dynamic layer = "parse open NTES endpoints by emulating valid browser sessions" | **Built and measured dead.** Fifteen-plus request shapes and a real browser engine (cookies, headers, referer, origin) have been tried from the phone: the app's stack is mid-session killed ("IOException: closed", the owner's pastes #7/#9/#10/#11), and the browser engine's POST is *accepted and never answered* (10–13 s hangs, pastes #9–#11). From datacenter IPs (six CI runs) NTES TLS-times out. There is no third vantage. |
| Cache 60–120 s per train, shared across users | **Adopted (this round)** for the one live pipe that exists — the traveller-report relay: reader polls are now throttled to ≥90 s (the midpoint of the proposed window), except immediately after the user's own publish. Note the spec's premise ("multiple users must be served from cache") presumes a shared server; our users share nothing but the public relay, which is itself the cache. |

## Part 2 — the FastAPI scraping proxy

**Rejected — and not on principle first, on measurement.**

1. **The vantage it depends on is the one we falsified.** The proxy scrapes from a server —
   i.e. a datacenter IP. Every datacenter attempt we have on record (GitHub runners ×6) ended
   in TLS timeouts / 403 / 404 against NTES and erail. A Docker host would be born behind the
   exact wall we spent rounds mapping. The spec's load-bearing assumption — "a server can
   reach these endpoints" — is the one assumption this project has already tested and buried.
2. **It never ran.** The script's tracking URL is malformed (`indianrail.gov.inq?opt=…`), its
   form fields (`jStation`, `language`) and CSS selectors (`station-row`, `trnRunStatTbl`) are
   invented — the kind of plausible code an AI produces without executing. This project's
   method forbids exactly that: parsers are written only against CAPTURED payloads
   (`app/src/test/resources/captures/`), and every capture so far shows refusals, not tables.
3. **It violates the product's standing constraints** (no server of ours, no fees, no ops
   burden) and re-creates the exact death pattern ADR-006 documented for scraper apps: one
   host, one IP, one ban / one unpaid bill away from dead — while presenting as "live".

## Part 3 — Retrofit client bound to "your new free tracking server"

Rejected as specified (it binds the APK to the Part-2 proxy). What survives is its *shape*:
a client that renders `station_code / scheduled / actual / delay` rows is precisely Phase A's
rendered-page parser (the pending `webview-erail-rstrain-dom-tap` paste decides whether erail's
page yields such a table) — and precisely what the traveller-report reader column already
renders from real witnesses. The transport, not the table, is what the evidence kills.

## The closing questions the spec asked

- "Docker compose + production Redis?" — **No.** There is no server in this architecture, by
  the evidence above; a cache layer in front of a blocked scraper caches the block.
- "Android background service for offline layout caching?" — **Already shipped, differently:**
  the offline asset, the 24-h-refusal snapshot cache with honest ages, the pair cache, and the
  report column rendering from the last poll. A background service would drain battery for
  data every measured door refuses to give.

## What this assessment changes in code

One change, this round: relay-reader poll discipline (≥90 s, publish-bypassed) —
`LiveViewModel.CROWD_POLL_MIN_MILLIS`. Everything else the spec proposes is either already
shipped or already measured dead; nothing is adopted on hope.

---

## Addendum (same day): the owner's second research piece — the "NTES Android app gateway"

Claim: `POST https://enquiry.indianrail.gov.in/crisns/AppServAnd`, JSON body
`{"queryType":"6","trainNo":…,"startDate":"12-Oct-2026","stationCode":"","lang":"en"}`,
dalvik UA, answering `trainFullStatus[]` with `schArrTime/actArrTime/actDepTime/arrDelay/depDelay`.

Verdict: **worth probing — and only the phone can probe it.** Unlike the previous piece's proxy
(this project's one unfalsifiable-in-advance claim), this targets the SAME host the owner's phone
already TLS-handshakes with (`enquiry… handshake: ok in 352 ms`, ENV lines of pastes #9–#11) on a
PATH this project has never tried. The claims are unverified — the endpoint name, payload keys and
response schema come from a write-up that may never have run — but this project's method has a
mechanism for exactly this: the probe ladder, on the one vantage where the host answers, with the
result captured and judged by the in-app time-token scan. Shipped as two variants this round:
`post-appservand-json` (the app's own stack, dalvik signature) and `webview-appservand` (the
browser engine, warm on the NTES page — the transport axis that has separated this phone's two
stacks all along). They replace the two getRunTrain spellings, measured 404 twice (pastes #10/#11)
and retired with evidence banked.

Adopted implicitly from the same write-up: the dd-MMM-yyyy date format ("12-Oct-2026") — the same
lesson this project learned from erail's 404 (the date string is part of the request's
fingerprint); generated by `FeedProbe.mmmDate`, never hand-typed.

Rejected from the same write-up:
- **`verify=False` on TLS.** Never. If CRIS cycles certificates, the system trust store handles
  it; a disable-verification flag turns "live actuals" into a channel a network hop could poison
  with fabricated arrivals — the exact class of untruth this product exists to prevent.
- **The offered Retrofit/Node clients.** Premised on the gateway working. The capture decides
  first; a client is written against a captured payload or not at all.
- **The 90-second cache** — already adopted last round (relay-reader poll discipline).


---

## Final addendum: the erail page-render door is CLOSED (complete chain, owner's device)

Five escalating attempts, each captured and scanned in the owner's own pastes (#10–#14):

1. instant rendered-text read — 426–513 B of navigation, 0 time tokens
2. settled text read (12 s after finish, 16 KB) — halt NAMES only, 0 time tokens
3. raw-DOM read (64 KB, 10 s settle) — 3 tokens, all `<meta>` SEO text
4. in-page click on their own "Show Status" button — `tapped`, DOM byte-identical
5. the human sequence: settle 20 s → date chip → "Show Status" (fired) → 15 s patience →
   parent text + same-origin iframes — **0 time tokens**, no navigation

Conclusion: erail's running table renders only inside a full interactive session driven by
their own scripts and channel. Reading it keylessly from an app's embedded engine is not a
parsing problem; it is a session problem, and faking the session at scale is the scraper-proxy
pattern this project rejected on evidence. The page door is closed with measurements — the
second door closed by this device in one day.

The per-stop-actuals doors that remain: travellers (ADR-006 — shipped: reporter, reader lines,
loopback) and nothing else within the keyless constraints. The honest screen says exactly this
per state; it will not fabricate what no reachable source reports.

---

## Addendum 3 (evening): the owner's third research piece — CHECKED, with receipts

The owner asked directly: "Are you still there. Have checked these?" Checked, piece by piece:

1. **"64kbytelabs/ntes-client"** — the repository DOES NOT EXIST (`git clone` → not found). The
   research output hallucinated the install URL. BUT GitHub code search found the real client it
   garbled: **x64vbhv/ntes-client** — cloned and read in full. What the REAL code proves:
   - `AppServAnd` is the true endpoint — but the protocol is an **ENCRYPTED** `{"jsonIn": …}`
     body (AES-128-CBC, key `8EA4DB2CC1EB3DC5`, MD5(plain+secret) framing). The earlier
     research's plain `queryType-6` JSON was never the protocol; its instant "closed" verdict
     stands, and for the right reason now.
   - **Our shipped shape already matched the working client exactly** — same endpoint, same key,
     same envelope (NtesWire). The ONE thing it names that we never tried: the server accepts
     only **legacy static-RSA TLS suites** modern stacks drop ("SSLV3_ALERT_HANDSHAKE_FAILURE
     unless the cipher list is widened"). SHIPPED this round as `post-appservand-legacytls` —
     the same correct bytes with the legacy suites added to the OFFER (trust untouched). This
     variant discriminates the last two candidate causes of every "closed": stack defaults vs
     the owner's local filter hop.
   - The library itself (Python, server-side) stays rejected: the datacenter vantage is
     measured dead six ways; its knowledge, however, is now in our phone probe.

2. **"Enterprise mirrors" (RailMitra / ConfirmTkt / eRail-tracker pages)** — the class is
   legitimate (third-party sites whose whitelisted servers render the central cluster's data as
   plain HTML), the specific selectors are chat-output unverifiable. Shipped as two settled
   page-read variants with the time-token scan: `webview-confirmtkt` (real URL pattern) and
   `webview-railmitra` (path honestly labelled a guess). Same method as erail: read, scan,
   verdict in the paste.

3. **"Hybrid in-app crowdsourcing" (the WIMT architecture)** — already built and shipped today
   (reporter, reader lines, keyless relay, loopback, reader on by default). The one piece we
   have not built is its "Cell Tower Check-Ins" auto-detection: needs location/telephony
   permissions and a station→cell-ID database; the manual stop-tap reporter achieves the same
   data with zero permissions. Designed as a later enhancement, not a blocker.

---

## Addendum 4: the owner's two SOURCE LINKS, fetched and mined

The owner re-sent the two source URLs themselves; both were fetched directly (2026-10-12, late):

1. **share.google/… → `x64vbhv/ntes-client`** — the same real repository already cloned and read
   in full (Addendum 3). Its own README now headlines the fix this assessment shipped from it:
   "Fix SSLV3_ALERT_HANDSHAKE_FAILURE against indianrail.gov.in … mounting a session with
   DEFAULT@SECLEVEL=1 ciphers" — independent confirmation of the legacy-cipher fact behind
   `post-appservand-legacytls`. Fully mined; nothing further.

2. **share.google/… → `bevigil.com/report/cris.icms.ntes`** — a static-analysis report of the
   OFFICIAL NTES Android app. Mined for anything a keyless client can use:
   - **NEW HOST: `gw.crisapis.indianrail.gov.in`** — appears nowhere in the OSS client, in any
     capture, or in any prior research. A "gw." prefix says API gateway; this may be where the
     official app's traffic actually goes today. SHIPPED: joins the diagnostics ENV reachability
     map (DNS/TCP/TLS from the owner's phone in every paste) AND a probe variant
     (`get-crisapis-gateway`, a root GET with the app's dalvik signature) — any HTTP status
     proves the host answers a phone; a TLS kill extends the refusal map. Paths are invented
     NOWHERE: a 401/404 from the root is a door found, not opened.
   - `Crypto.java` with weak crypto (LOW): consistent with the static AES key already in hand —
     no new fact.
   - "Accept Self Signed Certificate" (2 findings): app-internal pinning hygiene; nothing
     usable by a keyless client, and deliberately not imitated.
   - The rest (SQL, logs, trackers, firebaseio) is app-internal surface, irrelevant to us.

## Addendum 5 — THE MIRROR ANSWER: actual arrival/departure minutes exist, keyless, server-rendered (fetched 2026-10-12 ~18:05 IST)

The owner's priority restated ("Peak priority is ACTUAL DEPARTURE AND ARRIVAL times. Get it from
anywhere, but bring") forced the question the probe ladder had left implicit: which public page
serves PER-STOP ACTUAL minutes in plain fetchable HTML? Answer, verified by direct fetch of both
pages minutes ago (fetch_page, not a screenshot):

1. **runningstatus.in/status/20651 — THE SOURCE.** Server-rendered table, per halt:
   `Sch. Arr/Dep | Actual Arr/Dep | Delay`. For the LIVE run of 12 Oct (train mid-run, departed
   Ballekere Halt 18:00): YPR actual 15:22/15:24 (12 min), TK 16:20/16:22 (20), TTR 17:11/17:13
   (23), ASK 17:33/17:39 (20/24); future halts "Update Awaited" or RT repeats of the schedule;
   "Updated just now". Station codes in parens ((SBC)…(TLGP)) = stable parse anchors; a 12-hour
   clock; near-station row carries a 24-hour time and no code — cannot contaminate a block.
2. **confirmtkt.com/train-running-status/20651 — usable but blended.** One time column that mixes
   actuals (passed) and estimates (ahead) with "Delay by N min", station NAMES not codes
   (Tumakuru vs our Tumkur) — kept as the existing reachability probe only, not parsed this round.
3. **Adoption:** runningstatus.in is now a real DATA PATH (v2/mirror/RunningStatusMirror), parser
   written against a capture of the page taken today, tested on that capture; the app fetches it
   only when no live data reached the device, and every on-screen line names the source
   ("via runningstatus.in (24 min late)") — the labelled-second-witness rule generalized.
4. **Schedule-vintage discrepancy the mirror exposed (open, not blocking):** our offline/erail tail
   says 20651 reaches TLGP ~19:40; the mirror says scheduled 21:45 and ConfirmTkt 22:05. SMET
   agrees (~19:05–19:10). The tail halts' scheduled minutes on the device may be a stale vintage —
   worth one look when the mirror answers on the owner's phone.
5. **Paste #16 (owner, 17:56–17:59 IST) — VERIFIED LATEST build** (installed=1791807989299 =
   12:26:29Z = 17:56:29 IST, five minutes after the 17:51 publish; first paste ever from the
   current build). Its tail was TRUNCATED mid-line at 17:59:30 — the probe window vs[6..15]
   (confirmtkt, railmitra, get-crisapis-gateway, post-appservand-legacytls) RAN AFTER the cut, so
   the discriminator's answer exists on the device but was never pasted. Diary line for line 6 of
   the window onward is the single most valuable missing artifact; the app's copy gave out first.
