# R141 phone review · source-qualified route UI

The rolling [debug test APK](https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk) replaces the previous R140 build only after Android CI publishes it. Confirm the release description says **1.0.69-r141** (versionCode **70**) and gives the latest branch commit before installing.

## Focused checks from the 20 Oct screenshots

- On train 20652, leave the route at an intermediate point and tap Refresh repeatedly in More and Less. The summary and table must retain their heights and scroll offset. The brief score overlays the summary, not a third receipt card below it. The source badge retains the full check/event explanation for accessibility.
- See **TIMETABLE · STATIONS · ACTUAL** together. At every next-day main stop, `day2`/`day3` appears under its departure time; no Day 1 or `(+1d)` clock suffix. If actual and timetable agree on the day, only one day label appears. When they differ, the actual column gets its own truthful day.
- The train marker must not claim it is between two stations based on a tentative website departure. `N km route gap to [station]` is *fixed station-to-station geometry*, not measured remaining distance; `as on HH:mm` dates only the last reported stop. With no mapped next point, the distance remains unavailable. A source-verified intermediate past card instead says `At [station] · As on [event time]`.
- Open an origin run whose scheduled departure is still in the future: the summary says **Journey not started yet by timetable · no departure reported**, not an asserted live location. For a previous day's run, this message must not appear.
- Open the 16582 route: a completed run does **not** hide mapped intermediates. Its screenshot also shows a live identity saying **TLGP → YPR** while the device timetable says **SMET → YPR**. Intermediates from the conflicting old route must **not** be spliced into the live run; a verified ordered route for this number and selected day is needed. Retry map remains available for non-conflicting routes, including completed runs.
- The other app's screenshot names *Yesvantpur Bypass Running Line* at 8 km on train 12089. TrackTrain can now retain a named, unknown-code-to-dictionary passing point when an independent ordered RailGeo response supplies it, or accept it from the existing validated dated ConfirmTkt schedule. **No** screenshot-only station code, kilometre, arrival, or stop status has been manufactured. See [the source candidate](sources/SCREENSHOT_STATION_CANDIDATES.md).
- For an erail-only result with no vetted weekly calendar, the row now says *listed for selected date; weekly days unverified* instead of implying that today's appearance proves a Mon–Sun schedule. Known bundled weekly days remain underlined when available.

Tests and release evidence should be linked to the **same build**. The screenshot comparison is not a permitted feed or measured locomotive position. Do not merge this PR until the owner tests this APK on their phone.

## Validation note

The Android pipeline builds the rolling APK and installs its **published bytes** in six independent traveller partitions; the full E2E request separately exercises API 24, API 34, density/large font, source contracts and a minified release smoke. A successful matrix verifies presentation and source honesty, **not** permission or field truth for the other phone app's telemetry.
