# R158 — source control row outside the capped report viewport

## Phone evidence, 28 Oct 2026 10:09

The user's R157 screenshot shows a long selected-website report for 20652:
last card Bhadravati 10:07, following halt Tarikere Jn with an already elapsed
07:47 timetable clock. The **Source/Auto row is almost entirely covered by the
bottom edge of the expanded Summary Box**. The attached R157 diary confirms
the selected website report and the SMET departure correction; it does not
indicate another SMET parsing error. The issue is layout, not a feed clock.

The report card has two independent limits: the fitted inner card height and
the `BoundedScrollView` viewport cap that protects the station table. When
wrapped text makes the card taller than that viewport, putting the Source/Auto
row at the *bottom of the card* makes a primary control scroll out of view,
even if its 40dp child measures correctly. A view having nonzero height is not
proof it is painted in the viewport (the previous render check only asserted
`sourceSpinner.height > 0`). The extra `Following halt` text in a half-width
Next-stop value exacerbated the wrapping.

## Layout and verification

* Source and opt-in Auto now occupy their own padded, fixed-height 40dp control
  row **immediately below the bounded report viewport and above the route
  headings**. They are not descendants of either report scroll view. This
  row remains visible in More and Less and when the report overflows; changing
  website text cannot push it below the card viewport. The row is still
  visually labelled and the source can still be changed directly.
* The source viewport budget reserves another 56dp for that row without
  trading away the three-halt route space. The 210dp minimum is rounded up,
  not truncated below 210dp on nonintegral density phones. The following-halt
  text no longer repeats the `Next stop` heading; the 07:47 clock remains
  labelled timetable and explicitly not ETA. The report, failure notice,
  badge, and delay/next retain separate scroll access. A two-second refresh
  receipt is measured against the already sized card, not included in its
  height; on compact screens its text has its own scrolling viewport rather
  than enlarging the Summary Box or covering the source controls.
* A screenshot-sized render test forces a longer-than-viewport report at
  1.15× text and verifies Source and Auto are fully inside their pinned row,
  ahead of the route table, above the screen fold, **both with More and Less**.
  The route budget also tests the row's reserved space.

The website's 07:10 SMET departure remains omitted per R157; no website clock
is promoted into an NTES actual. The rolling debug APK is for real-phone
validation after Android CI passes. The separate E2E workflow stays manual-only;
PR #3 stays open.
