# R161 — WIMT website refusal, tested recovery path (1 Nov 2026)

## What was actually measured

- The 1 Nov phone image shows NTES selected, with its 14:20 Birur Jn event, and a separate `whereismytrain.org.in (website)` blocked-request warning. The attached 268-byte phone diary has only collection-resumed/version lines; it does **not** record this phone's request or HTTP status. The UI label in R160 covers both HTTP 403 and HTTP 429.
- The read-only [20689 runner trial](https://github.com/tarungittarun/Checktrain02/actions/runs/41296959708) on 1 Nov returned HTTP 403 three times (baseline, explicit old date and repeat). This proves refusal from that runner, **not** an HTTP 403 on the phone, a particular server-side motive, or a permanent outage. Older 28 Oct phone diaries contain successful `.org.in` GET replies.
- A later [same-host control trial](https://github.com/tarungittarun/Checktrain02/actions/runs/41301502145) fetched 20689 and a different train, 16227, once each from the runner: **both returned HTTP 403**. That rules out a 20689-only refusal on this runner but does not establish whether the phone's response was 403 or 429.
- This endpoint is a public, independent `.org.in` website; it is not the Where Is My Train phone-app feed, and the `.in` cache candidate is another source. No phone-app API, genuine-client capture, provider permission or entitlement has been established.

## R161 code and bounded tests

- A failed selected website refresh can show NTES. The saved source preference now remains the **manual** Refresh target after this fallback, so a later deliberate tap retries that same site first. Auto's next tick uses the displayed NTES fallback rather than repeatedly polling a refused/rate-limited website. Choosing NTES in Settings (or changing to NTES from a displayed website) changes the saved preference back to official. No NTES clock becomes a WIMT clock.
- The failure cue exposes website HTTP 403 versus 429 and, after fallback, says NTES is shown instead of saying the previous report is unchanged. The Refresh accessibility label discloses the pending site retry.
- An actual 403/429 transport response no longer causes an automatic pinned-IPv4 repeat GET. The ordinary socket-error IPv4 recovery remains intact. Wire-level local tests cover both refused statuses; deterministic tests cover manual/auto targeting and warning attribution.
- The read-only CI site probe now checks one different train on the same host if 20689's first request has no page. This differentiates a train-number-specific refusal from broad runner refusal without repeatedly requesting the blocked URL. It never treats an HTTP refusal as a fresh train report.

**Limit:** No code can force an external site to answer HTTP 403. If the same refused response persists on the owner's phone, WIMT website data remains unavailable; the app must show the attributed NTES fallback or an honestly aged saved report. A phone diary collected *after* tapping Refresh is needed to establish the phone's exact code and whether access ever recovers. PR #3 must remain unmerged until phone acceptance.
