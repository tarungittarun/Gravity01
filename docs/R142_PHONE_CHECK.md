# R142 · summary card correction after owner phone screenshots

APK: [rolling debug APK](https://github.com/tarungittarun/Checktrain02/releases/download/latest-apk/app-debug.apk), versionCode 71 / `1.0.70-r142` **only after CI publishes that exact version**. This supersedes the R141 phone checklist's *visible score* expectation; source/date limitations and unverified map candidates there are unchanged.

- On train 20652, tap **Less**. The blue summary card must shrink to its delay, next-stop and source controls, leaving more room for the station list. Tap **More**: its evidence, headline and notes return, and the card grows smoothly to fit them. There must be no large empty blue slab in either state.
- Tap the floating Refresh in both states. The card stays the *chosen size*, the route scroll position remains, and **Took / Freshness / Checked** never covers the report or appears as a new row. The visible answer is the updated source/headline/station text, including an unchanged or stale report when that is all the source provided. A transport failure remains a clearly colored warning in its reserved slot, not a fabricated new train position.
- The expanded summary's report, age, delay and next stop are larger, with high-contrast ink in light and dark mode. The date of the *event* must still be distinguishable from the *page check*; the full timing and source receipt remains in the source badge's accessibility description and Info/diagnostics, not over the report.
- Verify that ACTUAL remains visible over the clock column and the station list is reachable with normal scrolling on your phone. Emulator pixels cannot establish real-phone appearance.

PR #3 stays open until you confirm this APK works on your phone.
