# R160 — battery-mode train sketch at the last source event

28 Oct 2026 phone screenshot at 11:56 (Battery mode, selected WIMT website,
20652): the monochrome table retained source-labelled times and the top box's
“Last site card · Banavar 11:47”, but no train symbol identified the *same*
reported point between halts. The website's inferred run and 9-minute-old
arrival/passage are source claims, **not a live position or GPS fix**.

Battery mode alone now paints a static, thin **white outline** of the original
train's body, two windows and wheels on black. No filled coloured halo, moving
rail, pulse, map raster or continuous animation is restored. The marker is
bound to the existing selected-source event, not to elapsed timetable time or
page refresh:

- A validated last **site intermediate** (e.g. Banavar) keeps the normal
  source-qualified leg coordinate and paints `At Banavar · last site` beside
  the sketch, with `As of 11:47 · 9m ago` directly below. The accessibility
  description explicitly says the present position is unknown.
- A timed last **site/NTES halt** sketches the train at that stop with “At”,
  “as of” event time, source, and original event age beside/below it. A
  reported NTES departure is pinned to its last *station*, not the quarter-leg
  departure cue that could suggest observed travel beyond the halt.
- No sketch is painted for future schedule rows, undated comparison clocks,
  page-update times, candidate web cache, provisional departures or a claim
  without an eligible event time/age. A rebound or source change clears it.
  Site comparison clocks never become NTES actuals.

The normal-mode blue/amber markers and leg rendering are unchanged. Battery
mode remains a separate opt-in; the full timetable, source, delay, next halt,
Refresh drag, report and More/Less remain as in R159. Tests cover an accepted
halt, the selected intermediate, departure cue, schedule-only suppression,
white pixels on black, source/age provenance and the normal-mode branch.
Manual-only E2E stays disabled; PR #3 is not merged without phone confirmation.
