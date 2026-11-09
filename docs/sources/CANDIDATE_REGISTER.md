# The 20 suggested services · evidence register (19 Oct 2026)

This is a **repository integration inventory**, not a ranking of providers, accuracy, licensing or current public reachability. `Connected` = TrackTrain requests/parses a specific surface and has code or preserved response evidence. `Diagnostic only` = attempted/guessed path without a qualified product answer. `No integration found` = no implementation or captured usable response in this checkout; **not** a claim that the external service lacks the feature. Candidate descriptions of GPS, CRIS origin, offline mode, track-circuit accuracy and update rate were supplied by the user; none should be treated as measured without source-specific evidence.

| # | Suggested service | TrackTrain connection and what it can establish |
| --- | --- | --- |
| 1 | NTES (official) | **Connected**: [dated enquiry contract](NTES.md); timetable and some reported stop minutes on an owner device. Not continuous train position. The `mntes/` website is not the encrypted app enquiry. |
| 2 | IRCTC Rail Connect | **No integration found** with IRCTC booking app/status. NTES being a railway enquiry does not imply this app is connected. |
| 3 | Where Is My Train | **Two websites connected, phone app not**: [`.org.in` HTML](WHEREISMYTRAIN_ORG_IN.md) and [`.in` cache](WIMT_WEB_CACHE.md). Neither proves Google's Android app GPS/cell-tower/offline stream or permission to reuse it. |
| 4 | ixigo | **Connected**: [website HTML comparison](PUBLIC_COMPARISONS.md#ixigo), not qualified station events or GPS. |
| 5 | ConfirmTkt | **Connected**: [HTML running-status and embedded static `Schedule`](MIRROR_PAGES.md#confirmtkt); future estimates must not become actuals. |
| 6 | RailYatri | **Connected**: [dated JSON comparison](PUBLIC_COMPARISONS.md#railyatri); reported `data_from=ntes` in sampled reply, not proven independent GPS. |
| 7 | eTrain Info | **No integration found** for an eTrain Info app/site. Do not conflate with erail.in. |
| 8 | Erail | **Connected**: [pair, tile and route contracts](ERAIL.md), primarily identity and static data; speculative live paths remain diagnostic only. |
| 9 | Trainman | **No integration found**; advertised status is not a TrackTrain response. |
| 10 | RailMitra | **Diagnostic only**: guessed public request in `remote/FeedProbe.kt` without a qualified reply/admitted product source. Not connected to a usable feed. |
| 11 | RailRestro | **No integration found**. |
| 12 | MakeMyTrip | **No integration found**. |
| 13 | Goibibo | **No integration found**. |
| 14 | ClearTrip | **No integration found**. |
| 15 | Paytm Travel | **No integration found**. |
| 16 | EaseMyTrip | **No integration found**. |
| 17 | Yatra | **No integration found**. |
| 18 | m-Indicator | **No integration found**; a suggested suburban ETA is not this app's observed intercity train feed. |
| 19 | UTS on Mobile | **No integration found**; ticketing infrastructure does not imply a live positional API. |
| 20 | Google Maps Transit | **No integration found** for Maps Transit/GTFS-RT; neither a Maps page nor an offline map is an observed Indian Railways train position. |

**Connected outside the 20:** [runningstatus.in HTML](MIRROR_PAGES.md#runningstatusin), [RailGeo route attempt and opt-in ntfy relay](ROUTE_AND_COMMUNITY.md), plus the app's packaged offline reference data. RailGeo's documented phone reply was maintenance text, *not* a successful route. A separate `railradar` guess in `FeedProbe` is also diagnostic-only. These are distinct from independent live GPS feeds. To add a candidate, preserve a consented/requestable wire sample, train + origin-day + corridor echo, station/event timestamp versus response clock, payload structure and rejection cases before mapping anything to `Act` or a position; honour access restrictions rather than probing around them.
