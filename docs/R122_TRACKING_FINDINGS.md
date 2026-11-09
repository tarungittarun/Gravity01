# R122 tracking changes and evidence (2026-10-17)

## What changed

- TIMETABLE and the corresponding scheduled arrival/departure clocks now share the **left** edge of the timetable column. The fixed STATIONS and ACTUAL headings stay aligned with their cells.
- The last reported train glyph shows short **event** age below its logo (including `<1m` alongside the under-one-minute check); the event clock and source identity remain in the accessible description. It is not a GPS fix.
- A first usable source reply and a manual/opt-in Auto refresh can send a finite concentric wave from the **visible reported train glyph**. The overlay is clipped to the route viewport. No marker moves in response to the wave or to elapsed time, and there is no wave when only a timetable is available.
- The old always-on boxed paint-clock is replaced by a one-second unboxed growing/fading response score, such as `1 sec 14:52` or `1 sec 14:52:23`. It pairs the measured request/refresh duration with the selected source's **own data/publication update clock** when one is available; otherwise `—`. A page clock is not a train event. Second digits appear only for second-granular website/cache stamps, not for minute-granular NTES data. Source and caveat remain in accessibility/Info; refreshing a failed source cannot advance the old stop event.

## Why between-intermediate tracking is still unresolved

The attached 16225 diary (`opentrack-diary-20261017-151300.txt`) records NTES's TKE event at **14:54**, unchanged at 15:02. The selected `whereismytrain.org.in` website repeated TKE **14:52**, later exposed a **past** MSS 15:04 report in its 15:09 reply and subsequently a BDVT 15:09 report. These are discrete reports, not a distance measured between MSS and BDVT or between the map's named intermediate dots. The separately probed WIMT **web cache** did not produce a fresh, route-bracketed candidate. Neither selected source in this capture supports moving the blue train to an in-between location. The Where Is My Train **phone app** has a different data path; we do not possess its onboard cell-derived position or know the indicator's semantics. Do not present a schedule-based estimate or a website page-refresh clock as measured movement.

## Verification status

- Local static resource/reference/lexical checks: run before release; record results below.
- Local Gradle tests unavailable (no JDK or Android SDK in this sandbox); CI is the compiler and published-byte tester. Run the R122 Android and E2E workflows and report their **current commit** results separately. A green device partition does not override a failed strict artifact/parity/timing audit.
- Owner-phone acceptance and between-intermediate motion parity: **unverified**. PR #3 must stay open until the owner confirms the APK works.
