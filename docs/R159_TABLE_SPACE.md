# R159 — give the route table its screen space

28 Oct 2026 phone evidence, 10:51/11:00: R158 now keeps Source visible,
but the Summary Box/Source row take too much height. The table ends above a
permanently reserved 60dp Refresh parking strip even after the owner dragged
Refresh into the route viewport. The 16582 page also repeats a long source
hostname, a missing-YPR explanation and an equivalent “no confirmed times”
note in the top card; the 20652 card repeats “no ETA” after a clearly labelled
timetable clock. This is layout/copy; no new train observation is implied.

* Keep the 40dp Source/Auto picker touch row but reduce its padding and margins.
  Its selected line now reads `WIMT site` or `NTES`; the dropdown and accessible
  label retain the full source identity. The website claim still says it is a
  past **site** card with inferred run day; timetable and website columns remain
  distinguished. The selected source's full name and request details stay in
  Info and the diary rather than being repeated in the Summary Box.
* Condense the missing-destination message to its actual reason, the last
  listed comparison code and “position unknown.” Hide only the duplicate
  routine “No confirmed times from this site” note when that explanation is
  already visible; never hide an error/identity warning. A site page-update
  clock stays in source details, never becomes an event or location.
* Reduce card insets, top badge margin, headline's unused minimum height,
  note spacing and gaps between report/picker/table. Keep readable headline
  text, warning/badge separation, independent report scrolling and More/Less.
  Do not draw a timetable clock as an ETA; remove redundant “no ETA” copy in
  the next-stop value without converting the clock to a prediction.
* Retain the clock-free 60dp dock on first load. Once the owner saves a dragged
  Refresh position, set the dock to zero height on that screen and on restart;
  the table gains the entire 60dp. Refresh itself stays at the saved root
  coordinates, never jumps when the table scrolls or reflows. The initial
  undragged button remains outside all clock cells as before.

Regression tests cover compressed website text, routine-note suppression,
full-warning retention, table growth after dock release, the original dock,
Next-stop timetable labelling and source/picker visibility. The manual-only
E2E workflow stays off. A green CI/published APK is not a real-phone result;
PR #3 remains open for phone confirmation.
