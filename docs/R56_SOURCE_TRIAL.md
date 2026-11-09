# R56 · low-connectivity source trial (13–14 Oct 2026)

## What was measured on the owner's cellular connection

Train 16227, origin run 13 Oct, selected MYS→TLGP. These are diagnostic-log measurements, **not** claims that another source is equivalent to NTES:

| Candidate | Observed result | Safe role |
| --- | --- | --- |
| Direct official NTES encrypted POST | 00:04:49, 61285 bytes in ~0.5 s; 29 dated stops and actual times. Repeat at 00:06:53 in 0.58 s, 00:14:13 in 0.47 s, 00:15:01 in 0.66 s. | First live witness. Route + past stop actuals; never promote future ETA/ETD. |
| erail live list | Identified 16227 MYS→TLGP and endpoints; 00:04:44 list stamp dated 12 Oct. | Train identity/search, **not** confirmed actual stops. |
| erail status/route page | 704 text bytes after 8.15 s; halt list did not align to full 29-stop NTES route. | Do not ask after NTES succeeds; no actuals. |
| runningstatus.in | 5,341 text bytes after ~11 s, no validated dated per-stop result in this run. | Candidate independent witness on NTES failure, not a replacement for official actuals. |
| confirmtkt | No independently corroborated same-run per-stop actuals in supplied capture. | Only cross-check dated, plausible actuals; no promotion of forecasts. |
| ixigo / crowd reports | No verified stop minutes in this capture (crowd read 0 reports). | Unverified probe / opt-in traveller report. |

## Other apps / research (not a claim of compatible APIs)

- [Where is my Train's Play description](https://play.google.com/store/apps/details?id=com.whereismytrain.android) says its **on-board** offline tracking uses cell towers and cached route information, without internet or GPS. That requires a measured tower-to-railway map and users on the train. There is no demonstrated public, permitted Where is my Train per-stop API or tower database for this project. Its excellent on-board mode is **not** evidence that scraping an app endpoint will supply dependable actual times. Do not represent our schedule interpolation as equivalent.
- [RailYatri's explanation](https://www.railyatri.in/live-train-status) describes phone GPS/cell-tower crowd location; it is a potential *on-board* fallback, not proof of downloadable per-stop actuals. Opt-in GPS here already exists and must remain explicitly labelled `DERIVED · GPS` rather than NTES actuals.
- [RailRadar API documentation](https://railradar.in/docs) lists train live/route endpoints but also an API access/keys section. A reachable TLS handshake in the diary is not an authenticated successful same-run per-stop response; don't silently add a key-dependent provider to a keyless app.
- `tarungittarun/kilotrain` ADR-0012 implements official NTES and explicitly did not capture per-stop actuals; its statement that another app uses NTES is an unverified assumption. The owner's earlier 3–4 s source might be different; this evidence does not identify it.

## Design chosen

Keep the measured subsecond official direct POST first. Do not spend ~8–11 s of concurrent cellular bandwidth on page probes when it has answered. Persist *only* a successful, run-bound official route **with** its observed stop snapshot, in private app storage keyed by train and origin day. Paint it immediately during a tunnel, label it last-known with the original stamp and age, keep position unknown beyond its last observation, never carry its delay into an unseen future stop. Retry the official request in the background; on failure, let independent sources try, keeping their evidence and provenance separate. The 29 official halts already include the observed intermediate stops; no pass-through station gets an invented minute. No proprietary app scraping, unproven host, purchased key, or fabricated cell-tower position ships.

## Further candidate validation before adding any new live feed

For one train and **origin run**, collect a bounded response on a real cellular device and in a tunnel: response code/bytes/latency, train and run identity, station codes in sequence, schedule vs actual vs forecast columns, source update stamp, whether future rows echo the schedule, and the site's published API/access policy. Test one known intermediate halt and midnight rollover. Only then add a provider that can actually return verified past minutes; do not promote a reachable TLS socket or marketing claim to a sixth working feed.

## R57 manual source trial and latency hypothesis (14 Oct)

The R56 phone log reconfirms four official POSTs answered in 0.39–0.58 s, with 29 STNS scheduled halts, 15 currently accepted as reported, feed timestamp ~5–6 minutes behind the fetch. It does **not** measure the other sources in R56: their calls were explicitly skipped, so do not quote the old ~8/11 s as a network lower bound. The older page-probe code intentionally waited 6–10 seconds *after* onPageFinished; R57 trials reduce that settle interval to 1.2 seconds and the direct mirror GET timeout to 3.5 seconds. A short settle can miss client-rendered content; manual selection now gives an honest negative result, and an actual phone diary is needed before claiming a two-second fallback.

Selecting runningstatus.in or confirmtkt explicitly asks those sources even when official NTES is healthy, preserving the official table until run/date and per-stop actuals pass the existing validation. An erail selection explicitly probes its separate status endpoint. ixigo remains a labelled probe slot without a validated per-stop endpoint; it cannot be selected as a live actuals source. Verified mirror selection uses **only that mirror's** dated rows (not NTES actuals relabelled as a mirror). Ahead of the immediate next halt, columns stay the published schedule: a carried delay cannot be extrapolated to every later station.

The official STNS data proved on this phone contains **29 scheduled halts**, shown by scrolling the complete table. The user also observes additional static intermediate/pass-through stations in NTES's own UI; we do not yet have the wire field or a validated ordered code list for those *non-halt* stations. 29 halts are not a claim that every intervening railway station is present. Capture the official station-list response or a specific missing code before interleaving unnamed stations: a code guessed from distance or schedule would be invented route data.
