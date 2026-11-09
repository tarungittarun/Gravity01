# R138 — WIMT screen-distance POC (offline only)

**Verdict:** the proportional placement calculation is feasible. Automatic access to a freshly refreshed **Where is my Train Android app screen is NOT demonstrated**. Nothing here reads that app, refreshes it, pulls its private API, makes a network request, or changes TrackTrain's displayed position. Do not ship a “live WIMT” source based on this POC. The WIMT phone app and the earlier WIMT *web cache* are different sources; a changing response timestamp does not validate a distance.

## Reproduce the bounded calculation

`tools/poc_wimt_screen_distance.py` accepts **user-supplied visible text**, expected train number, immediately previous station, a validated ordered route-distance axis, and a timezone-aware capture time. Test with the **synthetic R137 rendering fixture**, not an observation of train 12089:

```bash
python3 tools/poc_wimt_screen_distance.py \
  --text '12089 Birur Jn 1 km to SPV' --train 12089 --previous RRB \
  --points RRB:211,SPV:215,KRNH:220,TKE:235 \
  --captured-at 2026-10-19T14:56:00+00:00 \
  --now 2026-10-19T14:56:08+00:00 --benchmark 10000
python3 -m unittest tools/ci/test_poc_wimt_screen_distance.py -v
```

With RRB 211 and next *mapped point* SPV 215, `1 km to SPV` yields `(215 - 211 - 1) / (215 - 211) = 0.75` along **RRB→SPV**, not 0.75 of RRB→TKE. The marker would remain **screen-derived schematic position**, not measured GPS. The output includes original km and capture time, and explicitly says unverified; it must not overwrite `Act` or an independently sourced position. `1 km to NYH` is rejected for the RRB→SPV fixture: spelling/code must match the *immediately next* validated route point. No interpolation is possible if the route lacks a validated km span or if the app's "next" is not comparable to the mapped axis.

The POC rejects wrong train, duplicate distance matches, wrong or skipped station code, out-of-range km, malformed map, a future screenshot and a screenshot more than two minutes old. A fresh screenshot is still not a fresh **provider observation**: an older on-screen estimate may survive a refresh. Run identity/date, source timestamp and displayed content must be independently checked before any product integration.

## Timing: measured vs unknown

On the agent's sandbox, one 10,000-iteration offline run measured **0.0058 ms median / 0.0070 ms p95 parser+geometry**, with 0.199 ms startup parse in the example invocation. These numbers exclude launching WIMT, its network/refresh, displaying a new report, Android consent/app-switch, screen capture, OCR, and returning to TrackTrain. They cannot be used to claim tap-to-position latency.

**Click Refresh → TrackTrain screen populated: not measured / unknown.** There is no running WIMT app or authorized phone-screen capture in this workspace. Reading another Android app's pixels/tree on a real device requires user-granted screen capture or accessibility access, foreground cooperation, and testing against a legitimate installed app. Frontend/source layout code does not contain its current data. Automating another app's UI can be unreliable and must respect platform/provider policies; do not impersonate its client or bypass access controls.

For an end-to-end trial, use an owner-controlled Android phone with both apps installed, consent to an explicit capture flow, one **current** WIMT screen showing train/run/next-point distance and time, and a verified route for that run. Record monotonic timestamps for: TrackTrain Refresh tap → WIMT refresh requested → WIMT displays a new observation → capture finished → text extraction finished → route/age checks finished → TrackTrain label and marker rendered. Include failures/timeouts and stale/unchanged WIMT screens; report a measured distribution on multiple refreshes, not just an ideal path. Until those preconditions are met, a manual screenshot/text import is the only honest screen-based path, and even that does not auto-refresh.
