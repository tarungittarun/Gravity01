# R144 · compact Summary Box and transient Refresh receipt

Install the rolling [debug APK](https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk) **only if** its release description says versionCode **73**, versionName **1.0.72-r144**. The rolling URL can change after any later push; confirm the version before comparing screens.

On the owner's 12089 / 20 October screen and a second route:

1. The blue Summary Box should no longer reserve a large blank band or show the routine paragraph beginning “Website past-card times shown…”. The website badge, time of the last site stop, selected website and provenance details (long-press Source) remain; neither the page-check clock nor “run inferred” proves where the train is now.
2. After **Refresh finishes**, look for a **three-line Took / Checked / Freshness card laid OVER the Summary Box**, not a new row in it. It disappears after about **two seconds**. “Checked” is the request time, while “Freshness” ages the *last source event*; a repeat check must not turn an old stop into current movement.
3. The Summary Box and table must **not shift when the receipt appears or disappears**. Check Source and Auto · 1 min are fully painted and selectable in both expanded and collapsed views. Only tapping More/Less changes the box height; Auto remains opt-in.
4. The route retains one source-qualified train marker, and the timetable/ACTUAL clocks and station list are readable. Try a failed or offline request too: it must keep the old event date and show an honest failure, not a false new stop.

The APK is tested on emulators and against published-APK acceptance. It does **not** establish phone-app feed parity. PR #3 remains open until the owner confirms this build on the real phone.
