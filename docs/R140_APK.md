# R140 test APK

Download: [app-debug.apk](https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk) — rolling debug release, package `in.opentrack.india`, version `1.0.68-r140` (code 69). The release is replaced by each successful Android publish job; check its release description for the source commit before installation. This is the project's updateable **test/debug** signature, not a Play Store release.

## What to check on a phone

1. Search for a train and open its tracking screen. Try **More / Less** both before and during Refresh. The summary should animate without shifting the table on a refresh; any brief result score should overlay the reserved space and disappear. **ACTUAL** must remain visible.
2. Scroll the route: START fills both arrival cells at the first stop, END fills both departure cells at the last stop. Times/arrows must align without collision at your font size. Overnight departures should show one Day 2/3 marker under the departure, not a `(+1d)` suffix on each clock.
3. Expand/collapse intermediate stops. Check the reduced gap beneath a main stop and the caption beside the train marker. A static route gap must never be represented as measured distance remaining.
4. Select the **Where Is My Train · website** source and Refresh. Its last reported station event should be dated, distinguishable from the page-check time, and never described as a current GPS position. Network failure or unchanged page content must not be presented as movement.
5. If an older APK will not update, verify you are installing over this project's debug-signed build. Do not uninstall an existing app with valuable local data just to force an update.

The device suite verifies UI integrity across API 24, API 34, dense/large-font and minified-release builds. It cannot verify the proprietary phone app's live telemetry or certify a physical train's location; that requires an authorized source and independent field observations.
