# End-to-end flows: the list the emulator workflow is measured against

This file is the definition of "tested" for this app, and it is machine-checked: `tools/ci/flow_coverage.py`
fails the build if a flow listed here has no test that claims it, or if a test claims a flow that is not
listed here. The alternative — "we have 118 tests and they pass" — is a count, not a coverage claim, and
counts are how the suggestion bug shipped.

Every flow is driven from **outside the app**, through the real window, on a real emulator, against the
**built APK** (`UiAutomator`, i.e. taps on pixels and reads the accessibility tree a person's finger and a
screen reader would see). Nothing here pokes at internals: if a flow needs a reflection hook to test, the
flow is not user-visible and belongs in the JVM tests instead.

Reading the table: *What the traveller sees* is the assertion. A flow whose expectation depends on what the
railway happens to be reporting today says so, and its test asserts the invariant instead of the number
(a test that goes red because a train was on time is worse than no test).

## Covered flows

| Flow | Screen | What the traveller sees | Test | Runners |
| --- | --- | --- | --- | --- |
| `cold-start` | search | First launch after `pm clear` reports the offline timetable being installed with a real percentage, then a usable search screen — never a blank frame, never an ANR | `ColdStartFlowTest#coldStartInstallsThenAnswers` | cold, all |
| `data-chip` | search | The data chip states what is installed (counts or "not installed"), which is what makes "no results" interpretable | `ColdStartFlowTest#dataChipStatesWhatIsInstalled` | all |
| `compact-home-search` | search | Four-row form: mode; board-at; get-off-at with swap at right; date/Find side by side; logo returns to Home; tracking default is not on Home | `StationSearchFlowTest#fourRowHomeKeepsTheSwapDateAndFindInReach` | all |
| `honest-date-limit` | search | Until 20 independently verified dated actual journeys exist, no archive calendar is offered; the date control stops at yesterday | `StationSearchFlowTest#fourRowHomeKeepsTheSwapDateAndFindInReach` | all |
| `type-ahead-station` | search | Three letters in Board-at or Get-off-at open a list of matches; tapping one puts the *name* in the field and the code in the "Selected" chip | `StationSearchFlowTest#threeLettersOfferMatchesThatFillTheField` | all |
| `type-ahead-train` | search | Three digits in the train box offer trains; tapping one fills the number | `TrainSearchFlowTest#trainBoxSuggestsAndFills` | all |
| `type-ahead-min-length` | search | Two characters open nothing, on purpose: "B" matches 400 stations | `StationSearchFlowTest#twoLettersAreSilentByDesign` | all |
| `type-ahead-never-silent` | search | Every query ≥3 chars answers with rows or with a sentence saying why there are none — "still installing", "nothing matches", or a failure. Never an empty box | `StationSearchFlowTest#everyQueryAnswersWithRowsOrWithAReason` | all |
| `both-ends-search` | search | Both ends filled (by tapping suggestions) runs the search and shows results or a named reason | `StationSearchFlowTest#bothEndsRunASearch` | all |
| `empty-query-feedback` | search | Find with a blank field states exactly what is missing | `StationSearchFlowTest#findWithBlankFieldsSaysWhatsMissing` | all |
| `unknown-station` | search | A string that matches no station is refused with the code advice, not silence | `StationSearchFlowTest#unknownStationNamesTheFix` | all |
| `same-station` | search | Boarding and alighting at the same station is refused explicitly | `StationSearchFlowTest#sameStationBothEndsIsRefused` | all |
| `day-shift` | search | −1d / now / +1d move the travelling date, and the selected timetable window is labelled for accessibility; older journeys are not presented as verified actuals | `StationSearchFlowTest#dayButtonsMoveTheDate` | all |
| `swap` | search | Swap exchanges the two ends, including their resolved codes, and the result set follows | `StationSearchFlowTest#swapMovesTheResolvedCodes` | all |
| `train-mode-search` | search | "Train no. / name" mode searches the offline master by number or name | `TrainSearchFlowTest#trainModeFindsByNumber` | all |
| `populated-home-logo` | search | Tapping the brand after a populated train search keeps its rows, heading, sort and source/count summary; opening the form cannot strand them under a weighted blank gap | `StationSearchFlowTest#homeLogoKeepsPopulatedResultsDirectlyBelowTheOpenSearch` | all |
| `compact-results` | search | A successful train-number search hides the long list/feed paragraph, preserves a source explanation for accessibility and offers a full-width Edit search control | `TrainSearchFlowTest#resultsDropVerboseBannerAndOfferFullWidthEdit` | all |
| `wrong-date` | search | A date with nothing running in the window says so and suggests ±1 day | `TrainSearchFlowTest#noRunOnThatDateSaysSo` | all |
| `bogus-train-number` | search | Five digits that exist nowhere produce the no-train sentence, not an empty list | `TrainSearchFlowTest#nonexistentTrainNumberIsExplicit` | all |
| `settings-entry` | search | The Settings button opens the settings screen | `SettingsFlowTest#everyControlIsReachableAndBackReturns` | all |
| `track-open` | tracking | A result's Track button opens that train, that date | `TrackingFlowTest#openingAResultShowsThatTrain` | all |
| `live-pair-list` | search | The pair list names its source — rows carry `via erail`, or the screen states the feed died; unnamed rows are the founding bug's door and fail the row | `StationSearchFlowTest#pairRowsNameTheirSource` | all |
| `details-live-identity` | tracking | The details screen asks erail what this number runs TODAY and relabels the route line — confirmed, or both readings shown on mismatch, or the untouched timetable label when nothing answered | `TrackingFlowTest#routeLabelGainsTheLiveIdentityConfirmation` | all |
| `hero-collapse` | tracking | First load opens the journey card; the traveller can smoothly collapse to delay, next stop and source, then restore the evidence without a new network request | `TrackingFlowTest#summaryStartsExpandedAndCollapsesToDelayNextAndSource` | all |
| `summary-stability` | tracking | Refresh changes only the report text within the height-locked summary: no duration/freshness/check score obscures the text and no receipt row pushes the route. More shrinks, Less restores the card without clipping the source chooser or Auto switch; ACTUAL stays pinned | `TrackingFlowTest#refreshReceiptOverlaysWithoutMovingTheRouteAndCollapseAnimates` | all |
| `headline-position` | tracking | The headline names where the train is or says plainly that only the timetable is known | `TrackingFlowTest#headlineAlwaysNamesAState` | all |
| `stop-table-columns` | tracking | Without tapping, each stop shows visible ↓ arrival and ↑ departure pairs: dim scheduled on the left and selected-source reports on the right. An unreported source minute stays a dash rather than copying the timetable (except START/END boundary labels) | `TrackingFlowTest#liveCellIsAMinuteOrADashNeverTheTimetable` | all |
| `distance-ladder` | tracking | Distance is 0 at the first halt, the route total at the last, and never decreases between | `TrackingFlowTest#distancesRunFromZeroToTheTotal` | all |
| `boarding-alighting` | tracking | The two searched stations are tagged board / get off | `TrackingFlowTest#boardAndAlightAreMarked` | all |
| `full-route-table` | tracking | A full stop timeline is primary; arrival/departure sheets and smaller scroll-bounded map points disclose on tap, while the vertical train ruler stays distance-scaled and the next stop remains visible | `TrackingFlowTest#fullRouteIsPrimaryWithoutASeparateWindowControl` | all |
| `projected-leg-honesty` | tracking | No time-interpolated train or device-GPS claim; all sources use the same vertical ruler, with a stationary train pinned at its last reported stop and motion between stops explicitly unknown | `TrackingFlowTest#actualPositionOnlyDoesNotRenderInterpolatedTrain` | all |
| `table-geometry` | tracking | The timeline has usable dimensions, named rows, and no blank band between summary and route at the tested screen size | `TrackingFlowTest#tableHasGeometryAtThisDensity` | all |
| `age-and-provenance` | tracking | The provenance and age chips are never blank; "live" cannot appear on a screen nothing fetched for | `TrackingFlowTest#ageChipNeverOverClaims` | all |
| `source-disclosure` | tracking | Long-pressing the full-width source picker explains Act versus Est, position provenance, and that WIMT web cache is not the phone app; no About button occupies the selector row | `TrackingFlowTest#sourceBlockExplainsTheAnswer` | all |
| `public-source-manual-check` | tracking | Only NTES and the explicitly labelled Where Is My Train website appear in the built APK picker; no ixigo or web-cache phone-app impersonation | `TrackingFlowTest#onlyOfficialAndWebsiteAreTravellerSelectable` | all |
| `source-identity-and-admission` | tracking | Select the named website on the installed APK: its badge and amber, parser-qualified past-card clocks retain website attribution and never borrow NTES actuals or imply measured movement; a failed answer leaves a timetable | `TrackingFlowTest#orgWebsiteSelectionKeepsSourceSpecificMinutesDistinctFromNtes` | all |
| `refresh` | tracking | Refresh requests the selected source again; the updated report text and source badge’s accessible receipt separate a checked-but-unchanged stop from a new report without recentering the route | `TrackingFlowTest#refreshKeepsTheScreenCoherent` | all |
| `optional-minute-refresh` | tracking | Turning on Auto on the tracking screen produces an accessible source-badge Refresh receipt after one minute without a persistent row; the same selected-source-first chain backs the button | `TrackingFlowTest#autoRefreshActuallyRunsOnceAfterOneMinute` | all |
| `long-list-scroll` | tracking | A several-hundred-halt route scrolls to the end with rows still readable (recycling correctness) | `TrackingFlowTest#longRouteScrollsWithoutBlankRows` | all |
| `back-nav` | tracking → search | Back returns to the search screen with the previous query intact | `TrackingFlowTest#backKeepsTheSearchQuery` | all |
| `density-change` | tracking | The stop timeline remains within the screen at the tested density (no overflow) | `TrackingFlowTest#tableHasGeometryAtThisDensity` | large, density |
| `settings-open` | settings | The screen shows the install state, endpoint field and column map | `SettingsFlowTest#everyControlIsReachableAndBackReturns` | all |
| `tracking-default-settings` | settings | Selecting a default tracking source is possible only in Settings, has an accessible selected label and persists when reopened; the test restores NTES | `SettingsFlowTest#trackingSourceDefaultMovesToSettingsAndPersists` | all |
| `appearance-modes` | settings | Light, dark and follow-system selections visibly repaint Settings, keep the source control reachable and persist after reopening | `SettingsFlowTest#lightDarkAndSystemSettingsRemainReadableAndPersist` | all |
| `diag-log-collector` | settings | The diagnostic log is inert until the user turns it on, says so on screen once they do, and is emptied by Clear without losing the switch | `SettingsFlowTest#diagnosticCollectorIsInertThenLiveThenEmpty` | all |
| `endpoint-save-reload` | settings | A typed endpoint survives Save and reopening | `SettingsFlowTest#endpointPersistsAcrossReopen` | all |
| `endpoint-rejects-non-http` | settings | A non-URL endpoint is rejected in Settings with visible correction instead of being saved silently | `SettingsFlowTest#junkEndpointSaysSo` | all |
| `column-hint` | settings | A column map is accepted and persisted (the escape hatch for a feed format change must be reachable) | `SettingsFlowTest#columnHintPersists` | all |
| `clear-cache` | settings | Clear live cache is followed by an offline screen that admits it has no snapshot | `SettingsFlowTest#clearingCacheIsVisibleOnTheTrackScreen` | all |
| `manual-refresh-only-settings` | settings | Auto chooser is gone, source override remains | `SettingsFlowTest#settingsHasNoAutoRefreshChoice` | all |
| `airplane-search` | search | With the radio off, search still answers from the device — the whole point of offline-first | `DegradationFlowTest#searchWorksWithNoNetwork` | api34, min24 |
| `airplane-track` | tracking | With no network and no snapshot the screen says there is no live report and never claims "live" | `DegradationFlowTest#trackScreenAdmitsNoLiveReport` | api34, min24 |
| `location-denied` | tracking | With no location permission the app works and never presents a GPS-derived position | `DegradationFlowTest#noLocationPermissionIsNotAnError` | all |
| `location-granted` | tracking | With permission granted, the provenance label is one of the known set and never a bare "live" | `DegradationFlowTest#grantedLocationNeverFabricatesPosition` | api34 |
| `source-network-evidence` | live source | Request the required website host for the chosen train on the device; separately record HTTP/cache/digest, parsed site comparison and **zero** admitted actuals. If unreachable, log it as unverified rather than pretending the source supplied fresh train data | `LiveFeedFieldTest#requiredWebsiteWireIsNamedAndAnswerIsNotPromotedToActual` | api34, net |
| `feed-per-stop-data` | live source | The erail response is measured, not asserted: how many halt rows it actually reported today; this is **not** a direct NTES stop-data guarantee | `LiveFeedFieldTest#whatThePublicFeedActuallyPublishes` | api34, net |
| `screen-explains-itself` | live source | A live-labelled screen with zero reported rows must carry an explanation, on real data | `LiveFeedFieldTest#theScreenExplainsItselfWhateverTheFeedDidOrDidNotSend` | api34, net |
| `override-endpoint` | live source | A configured endpoint wins, and an answer about another train is refused | `LiveFeedFieldTest#aConfiguredEndpointIsPreferredAndChecked` | api34, net |
| `release-smoke` | whole app | The **minified, resource-shrunk release APK** cold-starts, searches and opens a tracking screen with no crash — R8 and shrinking break exactly this and nothing else tests it | `ReleaseSmokeFlowTest#theShippingApkDoesTheBasicFlow` | release |
| `monkey-stability` | whole app | A bounded random-event smoke pass (600+600 events on API 34; 200+200 on API 24 and the dense screen), with no FATAL EXCEPTION or ANR; not a 30 000-event endurance claim | `tools/ci/monkey_stability.sh` (job, not a test class) | monkey |

## Source identity and evidence gates — not HTTP 200 = live train

- **Same-commit fixture/contract job (`source_integrity`).** Replays the supplied 20652 SRF→ANF contradiction: ANF arrival 06:15 must be rejected against the site's 06:14:20 publication label. Tests `OrgWebsiteStatusTest`, `PublicPageChecksTest`, `SourceEvidencePolicyTest`, and `RefreshReceiptTextTest` fail the E2E report when chronology, cache attribution, digest/bytes, selected-source admission or receipt wording regresses. Fixture truth is not field truth.
- **Installed APK selector (`source-identity-and-admission`).** Select the explicitly named website; assert qualified website past-card times remain amber source claims, not independently verified actuals or measured GPS, with no borrowed NTES rows. A failed website answer remains timetable-only. An NTES answer is checked independently by the existing tracking flows. These device assertions check display integrity even when the public site refuses the request; they do not claim the site answered.
- **Real-network observation (`source-network-evidence`).** Log the requested website endpoint, HTTP/network-cache result, body length/digest, parsed comparison and zero primary actuals. The existing `feed-per-stop-data` probes erail, not NTES; the live repository/NTES paths may fail to supply dated actual minutes on a given day. **Never count an unreachable endpoint or an unchanged page as verified movement.** For affirmative same-run NTES actuals, retain the device diary's `EVIDENCE` line with train/origin, request verdict, actual count, last event and feed stamp. If the gate has no dated actuals, the E2E outcome is *display integrity passed; fresh train position not demonstrated*, not “live fetch verified”.
- **Remaining evidence gap.** A web page cannot prove the phone app's one-minute telemetry. Without an authorized phone-app feed and a ground-truth observation for this train/run, this workflow cannot certify the owner's reported 3–4-stop/two-minute case or guarantee that any fetched bytes reflect real train movement. Capture timed owner-device diaries for that separate field claim.

## Deliberately not covered, with the reason

A coverage claim that quietly omits the hard parts is worth less than a smaller claim. These are the parts
this workflow cannot reach, and what it would take:

| Not covered | Why | What it would take |
| --- | --- | --- |
| Real motion on a real train | No hardware in a CI runner, and a simulated GPS track is a fixture, not a measurement | a phone in a pocket, once, with `./gradlew :app:connectedDebugAndroidTest` |
| `SensorPositioner` ground-truth correctness | The emulator can feed a mock location, but a mock *speed + accuracy + bearing over a route* is a fixture again; the rules it obeys are pinned in JVM tests (`PositionEngineTest` GPS rules) | an on-device harness that replays a GPX file |
| OEM keyboards and IME quirks | Robolectric and the emulator both use AOSP; the type-ahead path is tested against the framework IME only | a device matrix (Firebase Test Lab / AWS Device Farm), which needs an account and a key — against this project's no-keys rule |
| Notification, widget, share sheet | The app has none, by design | n/a |
| Play-store signing, upgrade over a previous version | Debug-signed APK only; an upgrade test needs two signed versions and a keystore in CI | a second signing config in CI |
| "Recent searches" reopen | `Prefs.pushRecent`/`prefsRecent` exist, and the *persistence* is covered by JVM tests, but no UI exposes the list yet, so there is no flow to drive | add the row to the search screen, then a test |
| Telephony state changes mid-refresh | `svc data` toggling is covered (`airplane-*`); the radio *dropping* a connection mid-request is a modem event | a rootable device with `svc` race conditions |
| Font scales beyond `large`, right-to-left locales, 120 % display size | `resConfigs "en"` and a `large`-only scale pass exist in the layout checks; the rest is not claimed | extend `LargeScreenRenderTest`'s matrix, which is JVM-side and cheap |
| Per-provider latency budget on flaky networks | The `Http` timeouts are pinned against a local server in JVM tests; `tc netem` shaping on the runner host is a bigger project | `tc` in a privileged container |

## What "100 %" means here, stated plainly

1. **Every named user-visible flow above has an instrumented test**, partitioned across debug-emulator
   jobs for API 34, API 24 and a larger font/density. A separate minified-release smoke test checks that
   variant's launch/search/track path, not every debug flow. This is flow-inventory coverage, not a claim
   about live railway truth or OEM devices.
2. **JaCoCo line coverage of `in.opentrack.india.*` merges the raw execution data from ALL debug
   partitions**; the aggregate gate fails under the floor in `tools/ci/flow_coverage.py --min-lines`.
   Per-partition reports are diagnostic, not substitutes for merged coverage. This is the *quantitative*
   100 % and it is deliberately not claimed as achievable for every branch — hardware and OEM branches are
   in the table above.
3. **Anything the app shows on failure is asserted to be a sentence, not an absence** (`never-silent`,
   `airplane-track`, `screen-explains-itself`), because the two bugs that reached a real device in this
   project were both silence: an empty suggestion list, and a live column reprinting the timetable.

## Reading a red run that has no steps

A pipeline that dies before any step of the row runs has one readable channel, and it is not the log (those
URLs redirect to blob storage). The annotation is on the check run:

```bash
R=tarungittarun/Checktrain02; SHA=$(git rev-parse HEAD)
gh api "repos/$R/commits/$SHA/check-runs?per_page=100" \
  --jq '.check_runs[]|"\(.id) \(.name)"'          # find the row's check-run id
gh api "repos/$R/check-runs/<id>/annotations" \
  --jq '.[]|"\(.path) L\(.start_line) :: \(.message)"'
```

That is how the row jobs that failed in ninety seconds were explained - `Can't find 'action.yml' ... Did you
forget to run actions/checkout before running your local action?` - a message no amount of YAML parsing
locally would have produced, and the reason the job now does the checkout itself. `plan` posts its findings
on the commit for the same reason: a red gate should be readable without opening the UI.

## Reading a red row

The matrix writes one status line per row and posts it on the commit, so a failure says which of the three
things happened. They need different responses, and conflating them is how a harness becomes decoration:

| The row says | It means | Do this |
| --- | --- | --- |
| `boot=failed` | the runner could not host an emulator (no usable `/dev/kvm` for an x86 image), so **nothing was tested** | run the workflow on a KVM-capable runner — `ubuntu-latest` qualifies where it exposes `/dev/kvm`, otherwise any self-hosted box with `kvm`; the product is not implicated |
| `boot=ok` with `all=failed (no test summary…)` | `am instrument` died before reporting, usually an install or signature problem | read `/tmp/instrument-all.log` in the `e2e-result-<row>` artifact; the debug-signed test APK and the app must share a key, which is why CI signs the release APK with the debug keystore |
| `all=failed` with a JUnit stack | a real assertion failed on a real device | the same artifact holds `screenshots.tgz` and the `*.xml` window hierarchy for every assertion, tagged per flow |

Each row posts a comment on the commit even when it is green, led by PASS / FAIL / NO DEVICE and carrying
the boot seconds, one line per instrumented class (pass or fail, and how long it took), the coverage figure
and the monkey verdict — the value of a run is proportional to how much of it you can read afterwards.

Ten independent API 34 partitions run on an ordinary push: station search, train search,
settings UI, settings/network/cold start, and six `AndroidJUnitRunner`-assigned tracking method
shards. `[e2e-full]` adds the same ten partitions on API 24 and at 440 dpi / font scale 1.15,
plus one R8-shrunk release-smoke job. The full matrix has **31 device jobs** (within the allowed
40), each booting its own emulator in parallel after one shared APK build. The tracking shard
indices partition the discovered methods automatically;
no hand-maintained method list can leave a new test out. The roll-up requires **every** partition
and merges their raw JaCoCo execution data before enforcing the 45% line floor. A missing,
unbooted or failed partition is red, not omitted. The smaller monkey pass runs on a settings
partition; it is a smoke check and **not** a long endurance test.

The **≤5-minute workflow** goal remains a target, not a measured result: on GitHub's uncached
runners the shared APK build alone has taken about five minutes, and runner queueing, API 24
boot, and tests add time. Splitting the slow shards shortens the device critical path but cannot
make a cold pipeline reliably meet that goal by itself. Each emulator job is capped at fifteen
minutes (twelve minutes to report a result). The CI result, not an elapsed-time target, gates
an APK handoff. A follow-up commit gets a separate concurrency group so an older composite
emulator that is slow to cancel cannot block its replacement. The new head's `plan` job
also requests cancellation of obsolete runs on this same branch with its restricted
`actions:write` token; this is best-effort, never an excuse to skip a device gate.
