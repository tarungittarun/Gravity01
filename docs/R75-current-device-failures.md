# R75 — device run after R74 and readiness/selector corrections

The API 34 device run `38639071570` for R74 completed **red**: cold start, one station flow, one tracking flow and one offline flow failed; train-search, settings, online feed, standalone cold-start class and non-offline degradation class passed. It booted and measured 52.8% app line coverage; neither coverage nor a build pass means the flow suite is green.

A definite regression was introduced while fixing the blank cold first frame in R70: XML put `Opening built-in timetable…` into `dataChip`, but `E2E.awaitSearchScreen` regards a nonempty data chip as evidence the timetable is installed. It could therefore enter search while installation was still underway. The placeholder now lives in visible `statusLine`; `dataChip` remains empty until `Phase.Ready`. The wait also explicitly refuses an `Opening` chip. This keeps an honest first-frame message without mistaking it for readiness.

The cold and swap failures involved accessibility nodes invalidated between finding a view and reading its text or a child. The black-box test helper now re-finds those nodes once. A mode switch now waits for its new train input, and an offline failure will report whether the mode switched, text was accepted or a suggestion failed; this is instrumentation resilience, not a claim that the offline train database is working.

The previous `chart-painted` device test required a plot and legend that the owner's table-first redesign removed. The corresponding flow now verifies that the full station table has usable geometry and named rows instead. It does not claim a removed chart is present.

The rolling R74 debug APK is ready to install, but these R75 changes require a replacement build/run. PR #3 stays open. A user phone result and independent same-run verified actual arrival/departure times remain unproven.
