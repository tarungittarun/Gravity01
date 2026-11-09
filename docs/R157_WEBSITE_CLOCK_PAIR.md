# R157 — website station-card chronology, 28 October 2026

## Reproduction and provenance

User-supplied 20652 diary, captured at 09:54:45 IST, shows the selected WIMT
website column painting SMET **09:37 arrival, 07:10 departure**. The guarded
website event report actually held **SMET arrival 09:37, no departure**. Its
separately stored first printed station-card clocks retained **07:10**, which
exactly matched the SMET timetable departure, then the table displayed it as
"Web". A first printed card clock is not necessarily a station event. The
website does **not** establish SMET departure at 07:10 after arrival 09:37.

The diary's separate NTES reply had **SMET arrival 09:40, departure 09:48**
for that run; those are its own source's accepted actuals, not a correction to
the website's 09:37. The website's run day is inferred from the timetable,
not stated on the page. Page update time is not a station event.

## Correction

* In site-card extraction, reject a prominent clock that equals its own
  scheduled clock, and reject a departure earlier than the card's arrival.
  Keep genuinely distinct, later first printed clocks on elapsed cards,
  including a provisional departure independently offered by the site.
* The display layer refuses a backwards same-station pair from a legacy report
  too. Future/white-card and page-time gates remain in place.
* A following halt whose timetable clock is earlier than the website's last
  event is labelled `past clock, not ETA`; it is not presented as a predicted
  arrival or proof that the train passed that halt.
* The regression reproduces the diary's 09:37/07:10 pair, plus an unscheduled
  but still backwards 08:59, and covers an old report bypassing the parser.

This is a display/source-mapping correction, not evidence of new NTES actuals,
website departure, or current location. Device behaviour still needs a real
phone check after the CI APK is published; the manual E2E workflow stays off.
