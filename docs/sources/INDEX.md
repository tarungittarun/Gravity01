# TrackTrain source library · 19 Oct 2026

**Use this index for future source questions.** “Connected” here means the repository has a working request/parser path or a preserved real reply, **not** a guaranteed, licensed, independent or continuously live position feed. No live request was made for this inventory. Current coding workspace cannot reach most provider hosts; existing captures and code are the evidence. A fixture proves only parser behaviour. Never merge clocks/km across sources without labels, and never treat `HTTP 200`, page refresh or field names such as `actual_*` as movement.

## Evidence levels and actual integrations

| Source contract | App request / recorded answer | Product use today | Critical gap |
| --- | --- | --- | --- |
| [NTES/CRIS enquiry](NTES.md) | Dated encrypted enquiry POST; dated answers reported on owner's phone, but no verified current official-client HAR | Eligible per-stop official minutes + schedule; fallback if refused | No continuous GPS; protocol/permission and upstream reliability not guaranteed |
| [whereismytrain.org.in](WHEREISMYTRAIN_ORG_IN.md) | Undated train-number HTML GET; observed past-card pages | Selected-source amber `WEB` station claims | No observed next-km field or website origin day |
| [WIMT `.in` web cache](WIMT_WEB_CACHE.md) | Separate undocumented dated JSON cache; recorded responses | Opt-in unverified comparison candidate | Root clock/km inconsistent; **not Google phone app** |
| [RailYatri](PUBLIC_COMPARISONS.md#railyatri) | Dated JSON status query; prior same-run response | Source-labelled station **claim**, no actual minute | Captures named `mntes` or `tmt` upstream; ETA rows not events |
| [ixigo](PUBLIC_COMPARISONS.md#ixigo) | Per-train HTML page query/comparison | Page/text and printed-date checks; no admitted actual | No dated observed event established |
| [runningstatus.in](MIRROR_PAGES.md#runningstatusin) | Captured 20651 HTML with scheduled/actual stop pairs; phone also saw refusals | Guarded alternate stop-minute mirror if run/row valid | Future schedule echoes, dated-run and HTTP 403 risks |
| [ConfirmTkt](MIRROR_PAGES.md#confirmtkt) | Captured running HTML; embedded `Schedule` JSON | Validated static route/intermediates; qualified comparison minutes | Predictions mixed with past clocks; no proven GPS |
| [erail.in](ERAIL.md) | Captured pair/identity records and route HTML | Search/identity and static route; by-number tile is mainly timetable | Tile update ≠ train movement; no accepted continuous position |
| [RailGeo route, ntfy, offline](ROUTE_AND_COMMUNITY.md) | Railway route attempt; user reports via public relay; packaged timetable | Static points, optional explicit crowd reports, offline schedule | None is a provider-reported in-between train kilometre |

**Not independently connected:** IRCTC Rail Connect, eTrain Info, Trainman, RailRestro, MakeMyTrip, Goibibo, ClearTrip, Paytm Travel, EaseMyTrip, Yatra, m-Indicator, UTS on Mobile and Google Maps Transit. RailMitra was an exploratory **diagnostic URL only**, not an admitted feed. See the complete [20-candidate decision register](CANDIDATE_REGISTER.md); its accuracy/technology statements are user-provided hypotheses, **not verified provider facts**.

**Wire/diagnostic distinctions:** `FeedProbe`'s HTTP/browser requests are bounded experiments and **do not make those endpoints product sources**. `Http` can retain complete GET bodies in the app-private `cacheDir/http/` for a 24-hour fallback eligibility window; the repository usually retains only selected fixture captures, structural summaries and diagnostics, not a full current payload. A local source-scoped success is not a provider access agreement. Do not bypass anti-bot responses, invent auth, use another app's private protocol or claim phone-app parity.

**Best documented opportunity for between-station position:** only a **fresh, same-run, independently time-qualified** remaining-distance or coordinate **event** could place a measured rail fraction. NTES delivers last-stop events and static km, not continuous location. The `.org.in` `N km to CODE` detector is a diagnostic fixture-backed gate, **not a field observed from that website**. The separate `.in` cache's root `distance` can change implausibly while station events do not. Static points and schedule allow an honest *schematic* departure cue or named-station pin, not GPS.
