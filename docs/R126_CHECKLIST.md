# R126 · search/results spacing and tracking summary · checklist

Screenshot: 2026-10-17 23:45 TrackTrain; diagnostic diary 2026-10-18 00:07.

| Item | Change | Evidence / state |
| --- | --- | --- |
| Gap when Edit search reopens | Wrap search-card content when results exist instead of reserving a weighted 2/3 viewport; cap it to 58% of screen height on a short/large-font phone so Find remains scrollable. Count/source/date summary occupies the recovered gap. | Layout and device geometry assertions added; [Android 39060344171](https://github.com/tarungittarun/Checktrain02/actions/runs/39060344171) passed; full E2E pending. |
| Concise results provenance | Show the **displayed** count; distinguish on-device timetable, erail and cached erail; only show a source-data date when an independently parsed erail feed stamp exists. Source stamp is not a fetch clock or train movement. | Pure date/absence tests and device count assertions added; [Android 39060344171](https://github.com/tarungittarun/Checktrain02/actions/runs/39060344171) passed; full E2E pending. |
| Sort dropdown | Group Sort by, value, and high-contrast arrow inside right half of results header. Keep departure/arrival/duration behavior, accessible selected label and minimum 48dp target. | Layout + device assertions passed Android render; full E2E pending. |
| Names | SEARCH BOX inside search card; SEARCHED TRAINS as the results header; SUMMARY BOX above the dynamically sourced tracking headline. | Render and device checks passed Android render; full E2E pending. |
| Run of date | Box upper-right run date, with bold 14sp text and day/night palette. | Render checks passed Android render; full E2E pending. |
| Update/install | versionCode 56; unchanged package/update key. | Android publication passed Android render; full E2E pending. |
| E2E | Explicit R126 opt-in, same broad 31-row API 24/API 34/density/release matrix and strict audit. Aim **<900s** measured workflow; do not substitute a longer timeout or fewer asserted behaviors. | Opt-in run requested; measured result pending. |

## Attached diary review

- At 23:45:02 MYS→TLGP painted **1 offline** row; at 23:45:03 erail upgraded it to **3**. SBC→TLGP similarly rose from **1 to 4** at 23:54:19. The brief count change is an asynchronous source transition, not a fourth departure invented by the UI. The summary must update from the result observer *and* the later provenance observer.
- The erail stamps in these answers were `2026-10-17-19-1-0` (MYS→TLGP) and `2026-10-17-17-30-39` (SBC→TLGP). These are source data vintage, **not** the time the train last moved and **not** the 23:45/23:54 fetch clocks. Do not use the example date 15 Oct if the source says 17 Oct.
- 16227 and 20651 received NTES station-minute reports; the 537-byte RailGeo response instead announces temporary maintenance. The route-map fallback needs continued checking; a 200 response here is not a valid route. No evidence of an app crash in this excerpt.
- ConfirmTkt and runningstatus corroboration had no matched actual minutes; one direct request returned HTTP 403. WIMT web-cache candidates were stale or undated, and the website's page-check time is not a movement event. Do not elevate these to verified actuals. The `requested run=20743` line is an **epoch-day key**, not train number 20743.
- User screenshot shows the native sort selector's arrow effectively absent and a large blank space between the still-open card and its list. These are addressed by the visible arrow and wrap-content list state, not by weakening the screenshot assertions.

Workflow dispatch returned HTTP 403 (integration scope), so the explicit `[e2e-request]` push path is used. Android and E2E both run for that push; no time claim until the matrix finishes.
