# R72 — what R71's real emulator actually found

Replacement R71 e2e run `38634410193` finished **red**, with a booted API 34 Pixel 6 emulator (boot step 289 seconds), 52.4% measured JaCoCo app line coverage, a completed 600+600-event monkey pass without a reported crash, and test classes taking 0–215 seconds. It did **not** prove the 49 declared flows pass: 5 of 9 instrumented passes failed. The replacement Android compile/unit/render run `38634410458` passed. PR #3 is still open.

Search suggestions were no longer globally invisible: the station/train suites got into actual selection and result assertions. The next defects are more specific:

- Search results had a visible `resultsTitle` view that the Activity **never filled**. It now carries the same result sentence as the status line.
- Find while the keyboard is open hid the result/validation sentence below the keyboard. Find now closes the keyboard first, including invalid queries.
- A selected train or a return from tracking intentionally collapses the search form. Device tests tried to read the hidden field without opening **Edit search**; those assertions now reopen it rather than silently changing the app's requested compact flow.
- Tracking device tests still required the route-window toggle and top progress bar removed at the owner's request. The flow manifest and tests now assert the full table has rows, the old toggle is absent, and any in-table train marker explicitly says it is a distance projection, not GPS.
- The Settings Save control already closes Settings on success. Two tests pressed Back again after saving; they now read the reopened screen directly. The test tap helper retries a genuine accessibility stale-node race without assuming every stale click failed.
- The offline test treated an honest schedule-only provenance label as a missing explanation simply because the optional note card was hidden; schedule/offline/derived labels now qualify, but `live` still must not be claimed without a report.
- The device annotation now includes multiple assertion names/messages per class, so another red run can be diagnosed without access to the Actions artifact host.

The 52.4% is **line coverage of instrumented app code**, not proof of live-feed truth or a full API 24/release sweep. A phone installation and independent same-run actual times have not been verified. Do not merge based on this run.
