# HTML mirrors · runningstatus.in and ConfirmTkt

Both are distinct from the official CRIS enquiry. Captured responses establish **page structure for specific past runs**, not GPS or a licence. Refusal/Cloudflare challenges must be respected; no bypass/identity changes are prescribed. Code: `v2/mirror/RunningStatusMirror.kt`, `ConfirmTktMirror.kt`, `ConfirmTktSchedule.kt`, `core/BrowserFetch.kt`, `ui/LiveViewModel.kt`. Captures: `app/src/test/resources/captures/mirror-runningstatus-20651-2026-10-12.txt`, `confirmtkt-20651-2026-10-12.txt`.

## runningstatus.in

| Layer | Response fields actually understood | Screen use / deliberate rejection |
| --- | --- | --- |
| Request | GET `https://runningstatus.in/status/<number>`; short direct GET, optionally site-origin browser-lane read on devices where that transport was observed to answer. Neither the user's candidate list nor NTES is this host. | Phone refusals (including HTTP 403) occurred. A browser challenge or connection alone is not an actual-stop answer. |
| HTML | Visible `Date dd-MMM-yyyy`, route station `(CODE)` headings, within each halt block pairs of scheduled/actual arrival and departure 12-hour clocks. Two-clock origin/destination cases are handled. `Update Awaited` means no report. Captured 20651 run contained passed-halt pairs. | Date must match selected run; route order/code and clock plausibility checked. Future schedule-equal “actual” values were seen and suppressed as echoes. A header clock, status colour and unpaired ETA are not station events. |
| UI | Eligible source-labelled alternate mirror minutes, at-stop delay and last defensible reported halt; not the primary NTES witness. | No confirmed continuous kilometre location or GPS. No changing request field proven to add remaining km. |

## ConfirmTkt

| Layer | Response fields actually understood | Screen use / deliberate rejection |
| --- | --- | --- |
| Request | GET `https://www.confirmtkt.com/train-running-status/<number>` (not the generic landing URL). A captured 20651 running HTML page is in the test resources. Direct refusals have occurred; page access and useful event data are different facts. | No token/signature/authorized GPS feed demonstrated. Do not circumvent a 403 or equate a web page with its phone app. |
| Visible running rows | `Day 1 dd-MMM`, ordered station **names** (may differ in spelling from timetable), first/second clock and `Delay by N min`; rows can combine already passed and future estimates. Parser walks names in route order and has a guarded spelling-prefix fallback. | Visible day label omits the year; month/day must align to selected run, and the embedded schedule additionally checks a visible update year. Raw page clocks alone cannot anchor a train-position icon: only cells corroborated against dated NTES or validated runningstatus.in overlap may be considered for actuals. Delay by N is not a departure event or km. |
| Embedded `Schedule` JSON | `TrainNo`, `StationCode`, `StationName`, `Day`, `ArrivalTime`, `DepartureTime`, static `Distance`, `intermediateStations` with code/name/km. Parsed only when page day/year and selected corridor/train agree; halts and pass-through points are kept distinct. | This is **static schedule/chainage**, not a moving train. It may provide route dots and timetable `Sch`, never a measured distance-to-next or automatically verified `Act`. Compare only along a validated common distance axis. |
| UI | Eligible route map/intermediates and explicitly attributed comparison minutes after independent run/chronology checks; unqualified rows remain estimates/empty. | Candidate table's “real-time GPS telemetry from users + CRIS” is not demonstrated by the captured HTML. |

**Request flexibility:** both sources accept a train number in their public page paths. A ConfirmTkt `Schedule` array is already present in a captured page—no novel request parameter is needed for static route km. Neither page capture established a supported query/signature for a measured live distance. Respect refusals and published access terms; don't upgrade a browser-only result into official status.
