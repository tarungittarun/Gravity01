package `in`.opentrack.india.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.App
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.data.RailRepository
import `in`.opentrack.india.remote.FeedProbe
import `in`.opentrack.india.remote.RailGeoRoute
import `in`.opentrack.india.remote.OfflineCorridorMap
import `in`.opentrack.india.remote.OfficialScheduleCache
import `in`.opentrack.india.remote.NtesWire
import `in`.opentrack.india.domain.PositionEngine
import `in`.opentrack.india.domain.RouteBetween
import `in`.opentrack.india.domain.Suggestion
import `in`.opentrack.india.domain.TrackModel
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import `in`.opentrack.india.v2.contract.TrainIdentity
import `in`.opentrack.india.v2.crowd.CrowdMerger
import `in`.opentrack.india.v2.crowd.NtfyRelay
import `in`.opentrack.india.v2.crowd.StopReport
import `in`.opentrack.india.core.BrowserFetch
import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.WebsiteBrowserRequest
import `in`.opentrack.india.v2.mirror.ConfirmTktMirror
import `in`.opentrack.india.v2.mirror.ConfirmTktSchedule
import `in`.opentrack.india.v2.mirror.ErailRouteMirror
import `in`.opentrack.india.v2.mirror.RunningStatusMirror
import `in`.opentrack.india.v2.mirror.PublicPageChecks
import `in`.opentrack.india.v2.mirror.WebsiteRequestPacing
import `in`.opentrack.india.v2.mirror.WimtCandidate
import `in`.opentrack.india.remote.WimtLiveProvider
import `in`.opentrack.india.v2.source.ErailPairParser
import `in`.opentrack.india.v2.source.ErailTransport
import `in`.opentrack.india.v2.source.PairParse
import `in`.opentrack.india.v2.source.PairWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * "What does this number mean today", as the details screen asks it - injected (5.1) so a laptop test
 * answers with a canned identity and no egress. The production answer comes from erail's keyless
 * by-number reply (the one source CI proved it can read, captures of 2026-10-12).
 */
/** The live tile's full payload for one number: identity PLUS its endpoint times. */
data class ErailLiveIdentity(
    val identity: TrainIdentity,
    val depMinute: Int?,
    val arrMinute: Int?,
    val alightDayOffset: Int,
)

fun interface IdentityFetch {
    suspend fun identityOf(trainNo: String): ErailLiveIdentity?
}

/**
 * The production fetch: one GET, parsed by the pair parser (the captures show the by-number reply IS
 * the same tile-record shape). Exactly one record must carry the asked-for number; anything else -
 * unreachable, unreadable, redirected to a different number - is null, and the screen stays on the
 * timetable with its honest label instead of guessing.
 */
internal suspend fun erailIdentityFetch(trainNo: String): ErailLiveIdentity? {
    val wire = withContext(Dispatchers.IO) { ErailTransport().fetchTrainNo(trainNo, 12_000) }
    if (wire !is PairWire.Ok) return null
    val rows = (ErailPairParser().parse(wire.body, wire.fetchedAtMillis) as? PairParse.Rows)
        ?.listing?.trains ?: return null
    val row = rows.singleOrNull { it.identity.number == trainNo.trim() } ?: return null
    return ErailLiveIdentity(row.identity, row.boardMin, row.alightMin, row.alightDayOffset)
}

/**
 * State for the live-tracking screen.
 *
 * Polling policy is a product decision, not a technical one: the reference apps refresh aggressively
 * and drain batteries on 12-hour journeys. Here the interval comes from [RailRepository.prefs]
 * (default 60 s, 0 = off), it only runs while the screen is STARTED, and every refresh is a *local*
 * DB read plus at most one HTTP call. A user who opens the app in a tunnel gets the cached snapshot
 * and an honest age label instead of a spinner.
 */
class LiveViewModel(
    private val app: App,
    private val trainNo: String,
    private val fromCode: String,
    private val toCode: String,
    private val originEpochDay: Long,
    private val identityFetch: IdentityFetch = IdentityFetch(::erailIdentityFetch),
    private val relay: NtfyRelay = NtfyRelay(),
) : ViewModel() {

    /** The live identity for [trainNo], once one reaches the device; null until then (and stays null on failure). */
    private var liveIdentity: ErailLiveIdentity? = null

    /**
     * @param model the whole screen, already stringified. The Activity binds and styles; it computes
     *   nothing, because a header derived separately from its own table is how this screen once said
     *   "departed TIPTUR" while listing Tiptur as not yet reached.
     */
    data class Ui(
        val route: TrainRoute,
        val answer: PositionEngine.Answer,
        val model: TrackModel.View,
        val boarding: TrackModel.Stop?,
        val alighting: TrackModel.Stop?,
        val nowRailMinute: Int,
        val tried: List<String>,
        val loading: Boolean,
        /** One reader sentence per stop code ("SMET departed 10:37 — reported by travellers…"), when the
            traveller opted in; empty = the feature is off, and the screen shows no report column at all. */
        val reports: Map<String, String> = emptyMap(),
        /** False when the relay itself was unreachable - the reports card must say so, never imply an empty train. */
        val relayOk: Boolean = true,
        /** The halt the mirror's actuals say the train is at/just past - the screen points here. */
        val anchorCode: String? = null,
        /** When the mirror speaks, its sentence IS the headline; null = render the schedule's. */
        val actualsHeadline: String? = null,
        val actualsNext: String? = null,
        /** "ACTUALS · runningstatus.in" - the badge the headline earned, never the schedule's. */
        val actualsBadge: String? = null,
        /** Measured lateness behind the mirror's own schedule pair; drives the badge colour. */
        val actualsLate: Int? = null,
        /** Which witnesses answered this refresh, for the age line: honesty about sources. */
        val sourcesLine: String? = null,
        /** Per-witness reader lines; the dropdown switches which one the table shows. */
        val sourceLines: Map<String, Map<String, String>> = emptyMap(),
        /** The witness the table is currently showing - the dropdown's selection. */
        val activeSource: String? = null,
        /** A witness answered with minutes, but none is an observation yet (no deviation to report). */
        val actualsSource: String? = null,
        val sourceStates: Map<String, Pair<String, String>> = emptyMap(),
        val lastUpdated: String? = null,
        /** Last successful selected-source load; not a website page clock or train event. */
        val selectedLoadReceiptMillis: Long? = null,
        /** The source's own publication clock, NEVER the HTTP receipt or a train event.
         * Null if the selected source supplies none. Precision is conservative (minutes). */
        val selectedSourceUpdateMillis: Long? = null,
        val selectedSourceUpdateHasSeconds: Boolean = false,
        val delayDetail: String? = null,
        /** Static-only ordered route points, never used to locate the train or generate actuals. */
        val staticRoute: TrainRoute? = null,
        val staticRouteSource: String? = null,
        /** Map validation is per leg: a changed/missing segment must still say unavailable. */
        val staticMappedLegStarts: Set<String> = emptySet(),
        /** WIMT's *web-cache* point, only after run/event/route gates; not phone-app GPS. */
        val wimtWebPoint: WimtCandidate.WebPoint? = null,
        /** Same-run, fresh provider station *claim*, never a measured position or Act. */
        val railYatriClaim: PublicPageChecks.StationClaim? = null,
        /** The .org.in site's own past-card claims, not Google's app or verified GPS. */
        val orgWebsiteReport: `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.Report? = null,
        /** Last eligible site past card beside *missing* NTES clocks. Never selects that site. */
        val orgWebsiteComparisonReport: `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.Report? = null,
        /** R139 diagnostic from that *same* reply; never drives the marker or Actual cells. */
        val orgWebsiteDistanceProbe: String? = null,
        /** A newer unverified comparison, not a silent switch away from NTES. */
        val alternateSiteHint: String? = null,
        /** Website did answer, but its comparison timeline omits the destination arrival. */
        val websiteGap: String? = null,
        /** This request's HTTP outcome, distinct from the last valid train observation. */
        val officialRequest: OfficialRequestReceipt? = null,
        val publicSourceAnswered: Map<String, Boolean> = emptyMap(),
        val wimtIntermediate: `in`.opentrack.india.remote.WimtLiveProvider.WimtIntermediate? = null,
    )

    private val _ui = MutableLiveData<Ui?>()
    val ui: LiveData<Ui?> = _ui

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error
    private val _mapRetryStatus = MutableLiveData<String?>()
    val mapRetryStatus: LiveData<String?> = _mapRetryStatus
    private var mapRetryJob: Job? = null

    /**
     * True while a look at the live world is in flight (the first load included). The screen shows a
     * moving bar for exactly this long: during a 12-second refusal a frozen frame reads as "broken" -
     * the owner's 1800s complaint - because the wait was invisible. The traveller is owed the sight
     * of the app working.
     */
    private val _checking = MutableLiveData(true)
    val checking: LiveData<Boolean> = _checking

    /** The reporter controls' last outcome, as a sentence ("published: SMET DEPARTED 14:02"). */
    private val _reportStatus = MutableLiveData<String?>()
    val reportStatus: LiveData<String?> = _reportStatus

    /** ADR-006 opt-in, read from prefs on every refresh: off means nothing polled, nothing published. */
    @Volatile
    private var crowdOn = false

    @Volatile
    private var lastCrowdPoll = 0L

    /** The mirror's own throttle - independent of the relay's, so one slow witness cannot starve the other. */
    private var lastMirrorPoll = 0L

    /**
     * The mirror's position shift, PERSISTED across refreshes: paste #24 showed the note and the
     * shifted clock evaporating on the next auto-tick because refreshBody rebuilds from scratch.
     * Freshness window 30 min - a stale shift is a stale train.
     */
    private var mirrorLate = 0
    private var delayDevice = 0
    private var delayDetail: String? = null
    private var mirrorCrossChecked = false
    private val historicalCorridorJson by lazy {
        runCatching { app.assets.open(OfflineCorridorMap.ASSET).bufferedReader().use { it.readText() } }
            .getOrNull()
    }
    private var geoRoute: TrainRoute? = null
    private var confirmHaltRoute: TrainRoute? = null
    private var confirmStaticRoute: TrainRoute? = null
    private var confirmScheduleAttemptAt = 0L
    private var geoAttemptAt = 0L
    private var officialStaticKnown = false
    private var mirrorSourceStates: Map<String, Pair<String, String>> = emptyMap()
    private var lastFetchAt = 0L
    private var lastOfficialMs: Long? = null
    private var lastOfficialRequest: OfficialRequestReceipt? = null
    private var mirrorNote: String? = null
    private var mirrorShiftAt = 0L
    private var mirrorAnchorCode: String? = null
    private var mirrorHeadline: String? = null
    private var mirrorNext: String? = null
    private var mirrorBadge: String? = null
    private var mirrorSources: String? = null
    /**
     * The FULL route (round 47): erail's page lists halts the device timetable lacks (16228: 27
     * TLGP→MYS vs the on-device 11 SMET→SBC). Merged ONCE per screen open, cached for a day; the
     * device halts keep their seqs, so the position answer stays valid on the grown route.
     */
    private var extentRoute: TrainRoute? = null
    private var extentAt = 0L
    private var extentAttemptAt = 0L
    private var mirrorSourceLines: Map<String, Map<String, String>> = emptyMap()
    /** Per-witness STRUCTURED minutes (code -> act arr/dep) - what the table's Act/Est columns carry. */
    private var mirrorStops: Map<String, List<RunningStatusMirror.Stop>> = emptyMap()
    // Preference is a view choice, not a claim that the source has answered. First
    // load still surveys NTES and the website; the selected table stays source-honest.
    private var mirrorActiveSource: String? = TrackingSources.valid(`in`.opentrack.india.data.Prefs(app).defaultTrackingSource)
    private var mirrorRefreshedAt = 0L
    private var cacheTrainNo: String? = null

    private fun freshShift(): Boolean =
        cacheTrainNo == trainNo && System.currentTimeMillis() - mirrorShiftAt < 1_800_000

    private var manualErail: PositionEngine.Answer? = null
    private var manualErailAt = 0L
    private var manualErailDetail: String? = null
    private var wimtCandidate: WimtCandidate.Result? = null
    private var wimtCheckedAt = 0L
    private var manualWimtAnswer: PositionEngine.Answer? = null
    private var manualWimtResult: `in`.opentrack.india.remote.WimtLiveProvider.Result? = null
    private var manualWimtIntermediate: `in`.opentrack.india.remote.WimtLiveProvider.WimtIntermediate? = null
    private var manualWimtAt = 0L
    private var manualWimtDetail: String? = null
    private var manualProbeJob: Job? = null
    private var manualProbeSource: String? = null
    private var sourceProbeOrder = 0L
    private val latestProbeOrder = mutableMapOf<String, Long>()
    private val latestManualProbeOrder = mutableMapOf<String, Long>()
    private var firstSourceSurveyStarted = false
    private val publicChecks = mutableMapOf<String, PublicPageChecks.Result>()
    /** Forward-only floor for this screen's selected origin run. Store the actual
     * report, not an index: the dated route can gain earlier halts while loading,
     * so progress must be recalculated against the CURRENT route on each reply. */
    private var maxOrgWebsiteReport: `in`.opentrack.india.v2.mirror.OrgWebsiteStatus.Report? = null
    /** Phone-measured wall time for the selected source, not a synthetic network speed claim. */
    private val manualProbeMs = mutableMapOf<String, Long>()

    /** Explicit selection is a fresh fetch. Only qualified source-specific data can populate
     * its table; on refusal that source shows the timetable and its own failure, never NTES
     * actuals mislabeled as the selected website's observations. */
    fun setActiveSource(name: String) {
        if (name == "NTES (official)" || name == TrackingSources.OFFICIAL || name == "last known") {
            // Switching back while a different manual source is in flight must not
            // let that older reply re-select itself over the traveller's tap.
            manualProbeJob?.cancel()
            manualProbeSource = null
            mirrorActiveSource = name
            val u = _ui.value ?: return
            _ui.value = buildUi(u.route, lastAnswer ?: u.answer, u.tried).copy(relayOk = u.relayOk)
            Diary.get()?.log("SOURCE", "active source set to $name")
            return
        }
        if (name == "erail") {
            probeSource(name) // every explicit selection is a fresh, timed attempt
            return
        }
        if (name == WimtCandidate.SOURCE || name == TrackingSources.WIMT) {
            probeSource(name) // recheck; a cached candidate is not a new source answer
            return
        }
        if (name in setOf(RunningStatusMirror.SOURCE, ConfirmTktMirror.SOURCE) || name in PublicPageChecks.names) {
            probeSource(name) // never mistake an earlier page for this tap's result
            return
        }
        _mirrorState.postValue("$name: no verified per-stop feed available to ask")
    }

    fun probeSource(name: String) = probeSource(name, select = true)

    private fun probeSource(name: String, select: Boolean,
                            allowMapRecheck: Boolean = false): Job? {
        if (select && manualProbeJob?.isActive == true) {
            if (manualProbeSource == name) {
                _mirrorState.postValue("Already checking $name; wait for its result")
                return manualProbeJob
            }
            // A previous manual check must never swallow a different selected
            // source. Cancel its publication, then ask the new source directly.
            Diary.get()?.log("SOURCE", "replacing unfinished $manualProbeSource check with $name")
            manualProbeJob?.cancel()
        }
        if (!select && !allowMapRecheck && name == PublicPageChecks.ORG &&
            manualProbeSource == name && manualProbeJob?.isActive == true) {
            // The selected website request will notice any newly arrived map and
            // recheck once it publishes. Do not supersede its visible tap result.
            return manualProbeJob
        }
        if (select) manualProbeSource = name
        val order = ++sourceProbeOrder
        latestProbeOrder[name] = order
        if (select) latestManualProbeOrder[name] = order
        Diary.get()?.log("SOURCE", "${if (select) "manual" else "first-load"} probe requested: $name")
        if (name == RunningStatusMirror.SOURCE || name == ConfirmTktMirror.SOURCE) {
            maybeMirror(force = true, preferred = if (select) name else null)
            return if (select) manualProbeJob else null
        } else if (name in PublicPageChecks.names) {
            // Manual checks always go to the selected public endpoint, even when NTES is healthy.
            // Their reply is displayed, NEVER promoted into Act or the train marker.
            val started = System.currentTimeMillis()
            val job = viewModelScope.launch {
                if (select) _mirrorState.value = "Checking $name…"
                val route = _ui.value?.route ?: return@launch
                // Snapshot the map alongside this request. If a full static route
                // arrives while the HTTP call is in flight, the first reply cannot
                // contain its intermediate points; recheck once after publishing it.
                val mapAtRequest = _ui.value?.staticRoute ?: geoRoute ?: confirmStaticRoute
                val pacing = if (name == PublicPageChecks.ORG) WebsiteRequestPacing(app) else null
                val paused = pacing?.paused(System.currentTimeMillis())
                if (paused != null) Diary.get()?.log("SOURCE", "website not asked: ${paused.detail}")
                val result = paused ?: withContext(Dispatchers.IO) {
                    if (name == PublicPageChecks.ORG) {
                        // The selected website now gets an actual browser-engine document
                        // navigation, not a Java GET disguised with a browser User-Agent.
                        // Keep the SAME source/parser and refusal/pacing qualification.
                        PublicPageChecks.fetch(name, trainNo, originEpochDay,
                            System.currentTimeMillis(), route.srcCode, route.dstCode,
                            route.stops.map { it.code.uppercase() }.toSet(), route, mapAtRequest) { url ->
                            WebsiteBrowserRequest.document(app, url)
                        }
                    } else {
                        PublicPageChecks.fetch(Http(app), name, trainNo, originEpochDay,
                            System.currentTimeMillis(), route.srcCode, route.dstCode,
                            route.stops.map { it.code.uppercase() }.toSet(), route, mapAtRequest)
                    }
                }
                if (paused == null) pacing?.observe(result, System.currentTimeMillis())
                if (_ui.value == null) return@launch
                if (latestProbeOrder[name] != order) {
                    Diary.get()?.log("SOURCE", "ignoring older $name reply after a newer probe started")
                    return@launch
                }
                val resolved = if (name == PublicPageChecks.ORG && result.orgReport != null) {
                    val previous = publicChecks[name]?.orgReport
                    val site = `in`.opentrack.india.v2.mirror.OrgWebsiteStatus
                    val floor = maxOrgWebsiteReport?.let { site.progress(it, route) }
                    val accepted = site.forwardOnly(previous, result.orgReport, floor, route,
                        System.currentTimeMillis())
                    if (accepted === result.orgReport) {
                        accepted?.let { report ->
                            site.progress(report, route)?.let { position ->
                                if (floor == null || position > floor) maxOrgWebsiteReport = report
                            }
                        }
                        result
                    } else result.copy(orgReport = accepted,
                        orgDistanceProbe = "newer page regressed; no distance candidate retained",
                        detail = result.detail + if (accepted == null)
                            " Newer page regressed behind a previously reached station; position unknown."
                        else " Newer page regressed; holding the previous reported station until its original clock expires.")
                } else result
                // A failed HTTP ask must not erase a previously valid observation. Its
                // page clock is NOT refreshed: keep the older event and surface the
                // failed request separately, so fallback selection cannot call it new.
                val previousReport = publicChecks[name]?.orgReport
                publicChecks[name] = if (name == PublicPageChecks.ORG &&
                    resolved.orgReport == null && !resolved.pageAnswered && previousReport != null)
                    resolved.copy(orgReport = previousReport,
                        detail = resolved.detail + " · previous website report retained, not refreshed")
                else resolved
                Diary.get()?.log("EVIDENCE") {
                    SourceEvidenceReceipt.publicPage(name, trainNo, originEpochDay,
                        publicChecks[name] ?: resolved)
                }
                if (paused == null) manualProbeMs[name] = System.currentTimeMillis() - started
                else manualProbeMs.remove(name)
                if (select) mirrorActiveSource = name
                else if (name == PublicPageChecks.ORG) {
                    val officialActuals = lastAnswer?.let { answer ->
                        answer.position.provenance == SourceKind.PROVIDER_NTES &&
                            answer.perStop.values.any { stop ->
                                stop.actualArrivalMin != null || stop.actualDepartureMin != null
                            }
                    } == true
                    val display = RefreshTargetPolicy.afterWebsiteSurvey(mirrorActiveSource,
                        resolved.orgReport != null && resolved.pageAnswered, officialActuals)
                    if (display != mirrorActiveSource) {
                        mirrorActiveSource = display
                        Diary.get()?.log("SOURCE", "website first-load check has no usable report; " +
                            "showing independently answered NTES instead; website remains manual Refresh preference")
                    }
                }
                _ui.value?.let { u -> _ui.value = buildUi(u.route, lastAnswer ?: u.answer, u.tried) }
                if (select) _mirrorState.value = resolved.detail
                Diary.get()?.log("SOURCE", "$name: ${resolved.detail}")
                if (name == PublicPageChecks.ORG && resolved.pageAnswered &&
                    (_ui.value?.staticRoute?.stops?.size ?: 0) > (mapAtRequest?.stops?.size ?: 0)) {
                    Diary.get()?.log("SOURCE", "static map arrived during website request; rechecking intermediate stops")
                    probeSource(name, select = false, allowMapRecheck = true)
                }
                resolved.orgReport?.let { website ->
                    val last = website.rows[website.anchorCode]
                    Diary.get()?.log("ORG") {
                        "probe=${if (select) "selected" else "background comparison"} source=$name " +
                            "train=$trainNo run=${RailTime.formatDate(originEpochDay)} " +
                            "(inferred, website undated); past-card=${website.anchorCode} " +
                            "Web arr=${last?.actualArrivalMin?.let(RailTime::formatRailMinute) ?: "—"} " +
                            "dep=${last?.actualDepartureMin?.let(RailTime::formatRailMinute) ?: "—"}; " +
                            "${website.rows.size} page rows; page-clock=" +
                            "${RailTime.formatClock(website.pageUpdatedAt)} IST is NOT event time; " +
                            "official-last=${lastAnswer?.anchorStopCode ?: "unknown"} " +
                            "(different source; no GPS or independently verified Act)"
                    }
                }
                // Locally expire a provider's response-clock claim after five minutes.
                // No automatic network poll; a static old claim must not linger as current.
                (resolved.stationClaim?.updateAt ?: resolved.orgReport?.pageUpdatedAt)?.let { publishedAt ->
                    viewModelScope.launch {
                        delay((publishedAt + 300_001L - System.currentTimeMillis()).coerceAtLeast(1_000L))
                        if (mirrorActiveSource == name && publicChecks[name] === resolved)
                            _ui.value?.let { u ->
                                _ui.value = buildUi(u.route, lastAnswer ?: u.answer, u.tried)
                            }
                    }
                }
            }
            if (select) manualProbeJob = job
            return job
        } else if (name == WimtCandidate.SOURCE || name == TrackingSources.WIMT) {
            val probeStarted = System.currentTimeMillis()
            val job = viewModelScope.launch {
                if (select) _mirrorState.postValue("Checking Where Is My Train…")
                val u = _ui.value ?: return@launch
                val route = u.staticRoute ?: u.route
                val repo = repo()
                val result = withContext(Dispatchers.IO) {
                    val staticMap = u.staticRoute
                    WimtLiveProvider.fetch(trainNo, route, originEpochDay) { code ->
                        staticMap?.stops?.firstOrNull { it.code.equals(code, true) }?.name
                            ?: repo?.queries?.station(code)?.name
                    }
                }
                if (latestProbeOrder[name] != order) return@launch
                val now = System.currentTimeMillis()
                manualWimtResult = result
                manualWimtIntermediate = result.intermediate
                val ans = result.snapshot?.let { snap ->
                    PositionEngine.locate(
                        PositionEngine.Request(route, snap, nowMinute(now), now)
                    )
                }
                manualWimtAnswer = ans
                manualWimtDetail = result.detail
                manualWimtAt = now
                wimtCheckedAt = now
                manualProbeMs[name] = now - probeStarted
                if (select) mirrorActiveSource = name
                if (select) _mirrorState.postValue("Where Is My Train: ${result.detail}")

                val wimtStops = result.snapshot?.perStop?.mapNotNull { (code, fields) ->
                    val stopOnRoute = route.stops.firstOrNull { it.code.equals(code, true) }
                    RunningStatusMirror.Stop(
                        code,
                        stopOnRoute?.arrivalMin,
                        stopOnRoute?.departureMin,
                        fields.actualArrivalMin,
                        fields.actualDepartureMin,
                    )
                }.orEmpty()
                mirrorStops = mirrorStops + (TrackingSources.WIMT to wimtStops)

                val latest = _ui.value ?: u
                _ui.value = buildUi(
                    latest.route,
                    if (select && ans != null) ans else (lastAnswer ?: latest.answer),
                    latest.tried,
                    listOf("Where Is My Train: ${result.detail}")
                ).copy(relayOk = latest.relayOk)
                Diary.get()?.log("SOURCE", "$name: ${result.detail}")
            }
            if (select) manualProbeJob = job
            return job
        } else if (name == "erail") {
            val probeStarted = System.currentTimeMillis()
            val job = viewModelScope.launch {
                if (select) _mirrorState.postValue("Checking erail…")
                val u = _ui.value ?: return@launch
                val live = runCatching { repo()?.probeErail(trainNo, u.route, originEpochDay) }.getOrNull()
                if (!isActive) return@launch // runCatching can swallow CancellationException
                if (latestProbeOrder[name] != order) return@launch
                manualErail = live?.answer?.takeIf { it.position.provenance.live &&
                    it.perStop.values.any { f -> f.actualArrivalMin != null || f.actualDepartureMin != null } }
                manualErailDetail = if (manualErail != null) "verified run stops from erail"
                    else "checked; no verified same-run stop minutes"
                if (manualErail != null) manualErailAt = System.currentTimeMillis()
                if (select) mirrorActiveSource = name
                manualProbeMs[name] = System.currentTimeMillis() - probeStarted
                if (select) _mirrorState.postValue("erail: $manualErailDetail")
                val latest = _ui.value ?: u
                _ui.value = buildUi(latest.route, if (select) manualErail ?: (lastAnswer ?: latest.answer)
                    else lastAnswer ?: latest.answer, latest.tried)
                    .copy(relayOk = latest.relayOk)
                Diary.get()?.log("SOURCE", "${if (select) "manual" else "first-load"} erail: $manualErailDetail")
            }
            if (select) manualProbeJob = job
            return job
        } else if (name == "NTES (official)" || name == TrackingSources.OFFICIAL) {
            if (select) mirrorActiveSource = name
            val job = viewModelScope.launch {
                if (select) _mirrorState.postValue("Checking NTES (official)…")
                refreshBody(lastAnswer?.position)
            }
            if (select) manualProbeJob = job
            return job
        } else if (select) _mirrorState.postValue("$name: no verified per-stop endpoint is configured")
        return null
    }

    /** First opening asks every configured independent status path. Each result updates its own
     * dropdown state; none switches the selected table or gates the fast official request. */
    private fun surveySourcesOnce() {
        // Battery mode asks only the selected source and necessary fallback. This survey
        // used to launch multiple unrelated HTTP probes on every first opening.
        if (`in`.opentrack.india.data.Prefs(app).batteryMode) return
        if (firstSourceSurveyStarted || _ui.value == null) return
        firstSourceSurveyStarted = true
        Diary.get()?.log("SOURCE", "first load: checking all independent sources without selecting them")
        // The mirror checks ConfirmTkt and runningstatus together, even when NTES succeeded.
        maybeMirror(force = true)
        // Do not spend a website request merely because an NTES screen was opened.
        // If this website is the chosen default, ask it once; otherwise only an
        // explicit selection/Refresh or the existing stale-NTES comparison may ask.
        if (mirrorActiveSource == TrackingSources.WIMT) {
            probeSource(TrackingSources.WIMT, select = false)
        }
        (PublicPageChecks.names.filter { it != PublicPageChecks.ORG ||
            RefreshTargetPolicy.surveyWebsite(mirrorActiveSource, publicChecks[it] != null) } +
            listOf(WimtCandidate.SOURCE, "erail")).forEach {
            probeSource(it, select = false)
        }
    }

    /**
     * The stale-while-revalidate store: the mirror's last known answer for ANY train, persisted as one
     * JSON object (org.json - the house way to build JSON). A track screen for a train seen in the last
     * six hours renders its actuals THE MOMENT it opens, then refreshes underneath; on a fluctuating
     * network that is the difference between an instant table and a spinner the journey outlives.
     */
    private suspend fun loadMirrorCache() {
        val repo = runCatching { repo() }.getOrNull() ?: return
        val raw = runCatching { org.json.JSONObject(repo.prefs.mirrorCache) }.getOrNull() ?: return
        val obj = raw.optJSONObject(trainNo) ?: return
        if (obj.optInt("validated") != 1 || obj.optLong("originDay") != originEpochDay) return
        val at = obj.optLong("at", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 6 * 3_600_000L) return
        val lines = LinkedHashMap<String, String>()
        val keys = obj.optJSONObject("lines")
        if (keys != null) for (k in keys.keys()) lines[k] = keys.optString(k)
        if (lines.isEmpty()) return
        cacheTrainNo = trainNo
        mirrorSourceLines = mapOf("last known" to lines)
        mirrorSourceStates = mapOf("last known" to ("last-known" to "last known · refreshed ${obj.optString("refreshed")}"))
        val cachedStops = mutableListOf<RunningStatusMirror.Stop>()
        val stopArr = obj.optJSONArray("stops")
        if (stopArr != null) {
            for (i in 0 until stopArr.length()) {
                val o = stopArr.optJSONObject(i) ?: continue
                cachedStops += RunningStatusMirror.Stop(
                    o.optString("c"),
                    o.optInt("sa", -1).takeIf { it >= 0 },
                    o.optInt("sd", -1).takeIf { it >= 0 },
                    o.optInt("aa", -1).takeIf { it >= 0 },
                    o.optInt("ad", -1).takeIf { it >= 0 },
                )
            }
        }
        if (cachedStops.isNotEmpty()) mirrorStops = mapOf("last known" to cachedStops)
        // A cache is selectable but cannot steal the user's saved default.
        if (mirrorActiveSource == null) mirrorActiveSource = "last known"
        mirrorAnchorCode = obj.optString("anchor").ifBlank { null }
        mirrorHeadline = obj.optString("headline").ifBlank { null }
        mirrorNext = obj.optString("next").ifBlank { null }
        mirrorBadge = obj.optString("badge").ifBlank { null }
        mirrorLate = obj.optInt("late", 0)
        delayDevice = obj.optInt("delayDevice", mirrorLate)
        delayDetail = obj.optString("delayDetail").ifBlank { null }
        mirrorSources = "last known · refreshed " + obj.optString("refreshed")
        mirrorNote = obj.optString("note").ifBlank { null }
        mirrorShiftAt = at
        mirrorRefreshedAt = at
        _mirrorState.postValue("Showing last known actuals (from ${obj.optString("refreshed")}) — refreshing…")
        Diary.get()?.log("MIRROR") {
            "cache applied for $trainNo: ${lines.size} stop line(s) from ${obj.optString("refreshed")}"
        }
    }

    private suspend fun saveMirrorCache(display: Map<String, String>) {
        val repo = runCatching { repo() }.getOrNull() ?: return
        val raw = runCatching { org.json.JSONObject(repo.prefs.mirrorCache) }.getOrNull() ?: return
        val refreshed = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(mirrorRefreshedAt))
        val obj = org.json.JSONObject()
            .put("at", mirrorRefreshedAt)
            .put("validated", 1)
            .put("originDay", originEpochDay)
            .put("refreshed", refreshed)
            .put("late", mirrorLate)
            .put("delayDevice", delayDevice)
        delayDetail?.let { obj.put("delayDetail", it) }
        mirrorAnchorCode?.let { obj.put("anchor", it) }
        mirrorHeadline?.let { obj.put("headline", it) }
        mirrorNext?.let { obj.put("next", it) }
        mirrorBadge?.let { obj.put("badge", it) }
        mirrorNote?.let { obj.put("note", it) }
        val lines = org.json.JSONObject()
        for ((k, v) in display) lines.put(k, v)
        obj.put("lines", lines)
        val stopsOut = org.json.JSONArray()
        for (st in mirrorStops[mirrorActiveSource].orEmpty()) {
            val so = org.json.JSONObject()
            so.put("c", st.stationCode)
            st.schArr?.let { so.put("sa", it) }
            st.schDep?.let { so.put("sd", it) }
            st.actArr?.let { so.put("aa", it) }
            st.actDep?.let { so.put("ad", it) }
            stopsOut.put(so)
        }
        if (stopsOut.length() > 0) obj.put("stops", stopsOut)
        raw.put(trainNo, obj)
        val trimmed = org.json.JSONObject()
        // Keep the freshest eight trains: a commuter's week is five, with margin.
        val keysWithAt = mutableListOf<Pair<Long, String>>()
        for (key in raw.keys()) {
            val o = raw.optJSONObject(key) ?: continue
            keysWithAt += o.optLong("at", 0L) to key
        }
        var kept = 0
        for (entry in keysWithAt.sortedByDescending { it.first }) {
            if (kept++ >= 8) continue
            trimmed.put(entry.second, raw.optJSONObject(entry.second) ?: continue)
        }
        repo.prefs.mirrorCache = trimmed.toString()
    }

    /** The track screen's live "what are the sources doing" line: fetching / answered / honest empty. */
    private val _mirrorState = MutableLiveData<String?>()
    val mirrorState: LiveData<String?> = _mirrorState

    /** The direct wire's 403 is a fact for minutes, not for every refresh: skip it while fresh. */
    private var directDeadUntil = 0L

    private var liveRefreshJob: Job? = null
    private var lastAnswer: PositionEngine.Answer? = null
    private var started = false
    /** Emitted only after a fresh live answer, never after a cached/timetable repaint. */
    private val _liveReceipt = MutableLiveData<Long>()
    val liveReceipt: LiveData<Long> = _liveReceipt

    val originDate: Long get() = originEpochDay
    val trainNumber: String get() = trainNo

    /** Seq bounds for "my segment" vs "whole route" in the stop list and the chart. */
    fun fromSeq(): Int = _ui.value?.boarding?.seq ?: 0
    fun toSeq(): Int = _ui.value?.alighting?.seq ?: Int.MAX_VALUE

    /** Resolved lazily: the first screen visit may be the moment the DB finishes extracting. */
    private suspend fun repo(): RailRepository? = app.repository()

    fun start() {
        if (started) return
        started = true
        refresh()
        fetchLiveIdentity()
    }

    /**
     * One identity request per screen open (2.5), off the critical path: the timetable renders first,
     * the live answer relabels it when (and only when) it arrives. Null means "no live identity reached
     * this device" and the timetable label stays exactly as it was - a failed lookup is never rendered
     * as a correction.
     */
    private fun fetchLiveIdentity() {
        viewModelScope.launch {
            val id = runCatching { identityFetch.identityOf(trainNo) }.getOrNull()
            if (id == null) {
                Diary.get()?.log("UI", "no erail identity reached the device for $trainNo; the timetable label stays")
                return@launch
            }
            liveIdentity = id
            Diary.get()?.log(
                "UI",
                "erail live identity for $trainNo: ${id.identity.fromCode}->${id.identity.toCode} (${id.identity.name})",
            )
            // Re-read our own last emission (the Activity holds its own copy; this class does not see it).
            _ui.value?.let { u -> _ui.value = buildUi(u.route, u.answer, u.tried).copy(reports = u.reports, relayOk = u.relayOk) }
        }
    }

    /** A failed website check can leave NTES displayed. Only a deliberate button press retries
     * the saved website preference; Auto stays on NTES until the traveller chooses the site again. */
    fun refreshTarget(auto: Boolean): String = RefreshTargetPolicy.target(mirrorActiveSource,
        `in`.opentrack.india.data.Prefs(app).defaultTrackingSource, auto)

    /** Initial open or explicit/opt-in screen-visible Refresh. Auto does not repeatedly probe a
     * website that refused the preceding manual request. */
    fun refresh(manual: Boolean = false, auto: Boolean = false) {
        // Three taps in one second on the owner's R53 phone launched three copies of the
        // same official POST. Keep one live chain in flight; the independent mirror is
        // already throttled separately. This prevents duplicate traffic on weak networks.
        if (liveRefreshJob?.isActive == true) {
            Diary.get()?.log("UI", "refresh already running; duplicate tick/tap ignored")
            return
        }
        lastFetchAt = System.currentTimeMillis()
        Diary.get()?.log("UI", when {
            auto -> "one-minute auto refresh on visible tracking screen"
            manual -> "refresh tapped by the user"
            else -> "first train load"
        })
        // refresh() is entered on Main (Activity/ticker). Deliver the busy edge
        // synchronously: postValue(true) can coalesce with a fast postValue(false),
        // so the screen never sees wasBusy and never issues an Auto receipt.
        _checking.value = true
        if (manual) {
            // The traveller pressed the button. A background experiment that is holding a socket to find out
            // why the last press failed is now the reason this press is slow, so it is told to stop; the
            // diagnostic resumes on the next refusal and has never been the point of the screen.
            FeedProbe.requestCancel()
        }
        val previous = lastAnswer?.position
        val selected = (if (manual) refreshTarget(auto) else mirrorActiveSource)?.takeIf {
            it in PublicPageChecks.names || it in setOf(TrackingSources.WIMT, WimtCandidate.SOURCE, "erail",
                ConfirmTktMirror.SOURCE, RunningStatusMirror.SOURCE) }
        // A user-selected source gets the FIRST request. NTES is only a last fallback,
        // never an automatic preflight that delays a 0.4-second website response.
        if (selected != null && (selected == TrackingSources.WIMT || manual || `in`.opentrack.india.data.Prefs(app).batteryMode)) {
            liveRefreshJob = viewModelScope.launch {
                try {
                    // A page probe needs the route. In battery mode do NOT silently ask NTES
                    // first merely to seed the view: paint the cached timetable instead.
                    if (_ui.value == null && !prepareBatteryRoute()) {
                        // No offline route covers the passenger span. Only the official
                        // route bootstrap can recover it; still ask the chosen source next.
                        refreshBody(previous)
                        if (_ui.value == null) return@launch
                    }
                    Diary.get()?.log("SOURCE", "refresh first: $selected; official only if no other usable source")
                    probeSource(selected, select = true)?.join()
                    val current = _ui.value ?: return@launch
                    val state = current.sourceStates[selected]?.first
                    val usable = state == "live" ||
                        (selected == TrackingSources.WIMT && manualWimtAnswer != null) ||
                        selected == PublicPageChecks.ORG && WebsiteReportDisplay.usable(
                            state, current.publicSourceAnswered[selected] == true,
                            current.orgWebsiteReport) ||
                        state == "candidate" && selected == WimtCandidate.SOURCE &&
                            current.wimtWebPoint != null
                    if (!usable) {
                        val fallback = bestAvailableSource(current, selected)
                        Diary.get()?.log("SOURCE", "refresh $selected failed ($state); fallback=${fallback ?: "official request"}")
                        mirrorActiveSource = fallback ?: "NTES (official)"
                        if (fallback == null || fallback == "NTES (official)") refreshBody(previous)
                        else _ui.value?.let { latest ->
                            _ui.value = buildUi(latest.route, lastAnswer ?: latest.answer, latest.tried)
                                .copy(relayOk = latest.relayOk)
                        }
                    }
                } finally {
                    _checking.value = false
                }
            }
            return
        }
        liveRefreshJob = viewModelScope.launch {
            try {
                val oldEvent = officialStopEvent()
                refreshBody(previous) // selected official source always goes FIRST
                if (manual && lastOfficialRequest?.kind == OfficialRequestReceipt.Kind.REQUEST_FAILED) {
                    probeSource(PublicPageChecks.ORG, select = false)?.join()
                    val u = _ui.value
                    val fallback = u?.let { bestAvailableSource(it, "NTES (official)") }
                    if (fallback != null && fallback != "NTES (official)") {
                        mirrorActiveSource = fallback
                        _ui.value?.let { latest ->
                            _ui.value = buildUi(latest.route, lastAnswer ?: latest.answer, latest.tried)
                                .copy(relayOk = latest.relayOk)
                        }
                    }
                }
                if (manual && !`in`.opentrack.india.data.Prefs(app).batteryMode &&
                    (mirrorActiveSource == null || mirrorActiveSource == "NTES (official)") &&
                    oldEvent != null && oldEvent == officialStopEvent() &&
                    (officialStopAgeMillis() ?: 0L) >= 300_000L) {
                    Diary.get()?.log("SOURCE", "official stop unchanged for 5+ min; checking website comparison, not switching source")
                    probeSource(PublicPageChecks.ORG, select = false)?.join()
                }
            } finally { _checking.value = false }
        }
    }

    /** Offline route scaffold for a selected-source first opening. No network request and no
     * promotion of saved actuals into today's verified position. */
    private suspend fun prepareBatteryRoute(): Boolean {
        val repo = repo() ?: return false
        val saved = repo.lastOfficial(trainNo, fromCode, toCode, originEpochDay)
        loadConfirmScheduleCache()
        val static = repo.savedSchedule(trainNo, fromCode, toCode, originEpochDay)
        val route = listOfNotNull(saved?.route, static,
            runCatching { repo.queries.route(trainNo) }.getOrNull(), confirmHaltRoute)
            .firstOrNull { JourneyRouteGate.covers(
                it.stops.map { stop -> stop.code }, fromCode, toCode) } ?: return false
        officialStaticKnown = route === saved?.route || route === static
        loadGeoCache(route, repo)
        val now = System.currentTimeMillis()
        val answer = saved?.answer ?: PositionEngine.locate(
            PositionEngine.Request(route, null, nowMinute(now), now, null))
        lastAnswer = answer
        _ui.value = buildUi(route, answer, emptyList(), saved?.notes ?: listOf(
            "Timetable shown while the selected source is asked."))
        return true
    }

    /** The event fingerprint ignores both HTTP arrival and the provider's page clock. */
    private fun officialStopEvent(): String? = lastAnswer?.let { answer ->
        val code = answer.anchorStopCode ?: return@let null
        val stop = answer.perStop[code] ?: return@let null
        if (stop.actualArrivalMin == null && stop.actualDepartureMin == null) null
        else "$code:${stop.actualArrivalMin}:${stop.actualDepartureMin}"
    }

    private fun officialStopAgeMillis(): Long? = lastAnswer?.let { answer ->
        val code = answer.anchorStopCode ?: return@let null
        val stop = answer.perStop[code] ?: return@let null
        val minute = stop.actualDepartureMin ?: stop.actualArrivalMin ?: return@let null
        (System.currentTimeMillis() - RailTime.railMinuteToEpochMillis(originEpochDay,
            minute)).takeIf { it >= 0L }
    }

    /** Prefer run-bound actuals; a selected website can separately provide its own
     * labelled past-card timeline when the official request genuinely fails.
     * A newer HTML page clock never outranks an older train event by itself. */
    private fun bestAvailableSource(ui: Ui, rejected: String): String? =
        listOf(TrackingSources.WIMT, "NTES (official)", RunningStatusMirror.SOURCE,
            ConfirmTktMirror.SOURCE, "erail").firstOrNull { name ->
            name != rejected && ui.sourceStates[name]?.first == "live" &&
                SourceEvidencePolicy.primaryActuals(name, runIdentified = true,
                    verifiedStopMinutes = when (name) {
                        TrackingSources.WIMT -> manualWimtAnswer != null
                        "NTES (official)" -> ui.answer.position.provenance == SourceKind.PROVIDER_NTES &&
                            ui.answer.perStop.values.any { it.actualArrivalMin != null || it.actualDepartureMin != null }
                        RunningStatusMirror.SOURCE, ConfirmTktMirror.SOURCE ->
                            freshShift() && mirrorStops[name].orEmpty().isNotEmpty()
                        "erail" -> manualErail != null &&
                            System.currentTimeMillis() - manualErailAt in 0L..PositionEngine.STALE_MS
                        else -> false
                    })
        } ?: PublicPageChecks.ORG.takeIf { name ->
            name != rejected && WebsiteReportDisplay.usable(ui.sourceStates[name]?.first,
                ui.publicSourceAnswered[name] == true, ui.orgWebsiteReport)
        }

    private suspend fun refreshBody(previous: TrainPosition?) {
        run {
            val repo = repo() ?: run {
                _error.value = "Timetable is not installed on this device yet."
                return
            }
            crowdOn = repo.prefs.crowdReports
            // The traveller does NOT stare at a spinner: the offline timetable paints FIRST - station,
            // distance and schedule are on screen before any network is waited on (the owner's round-47
            // complaint: the table must appear at once; actuals land on top when a witness answers).
            val paintT0 = System.currentTimeMillis()
            if (_ui.value == null) {
                val now0 = System.currentTimeMillis()
                val saved = repo.lastOfficial(trainNo, fromCode, toCode, originEpochDay)
                // Validated ConfirmTkt timetable from an earlier opening can paint its halts and
                // intermediate points BEFORE the first network request of this opening.
                loadConfirmScheduleCache()
                val savedStatic = repo.savedSchedule(trainNo, fromCode, toCode, originEpochDay)
                val cachedRoute = listOfNotNull(saved?.route, savedStatic,
                    runCatching { repo.queries.route(trainNo) }.getOrNull(), confirmHaltRoute)
                    .firstOrNull { JourneyRouteGate.covers(
                        it.stops.map { stop -> stop.code }, fromCode, toCode) }
                if (cachedRoute != null) {
                    officialStaticKnown = cachedRoute === saved?.route || cachedRoute === savedStatic
                    // Reopen today's validated full static route BEFORE asking any live source.
                    loadGeoCache(cachedRoute, repo)
                    val cached = saved?.answer ?: PositionEngine.locate(
                        PositionEngine.Request(cachedRoute, null, nowMinute(now0), now0, null),
                    )
                    lastAnswer = cached
                    _ui.value = buildUi(
                        cachedRoute, cached, emptyList(),
                        saved?.notes ?: listOf("Timetable shown while the live sources are asked — actuals land on top the moment a source answers."),
                    )
                    _error.value = null
                    Diary.get()?.log("UI") {
                        "instant offline paint for $trainNo (${cachedRoute.stops.size} halts) in " +
                            "${System.currentTimeMillis() - paintT0}ms - live chain continues underneath"
                    }
                }
            }
            // The witnesses start NOW, in parallel with the erail chain - not after it. Paste #27: on a
            // fluctuating network the chain held the screen for minutes while the mirror needed 14 s.
            // The 90 s throttle makes the later, post-buildUi calls no-ops.
            maybeCrowd()
            // Independent static route request: it never waits for (or delays) the official POST.
            // Its page clocks are not observed train actuals, even when the page says "running".
            maybeConfirmSchedule()
            // And the route grows to the journey's real span once erail's page names the halts the
            // device timetable lacks (throttled + day-cached; never blocks the paint above).
            // A 0.5 s official answer makes both of these slow page probes unnecessary;
            // defer them until after the official request actually fails.
            // Crucial: an erail list can contain numbers absent from the bundled (older) DB.
            // Previously repo.live() returned null BEFORE asking NTES in that case (20689).
            // Use the dated live-list identity only as an endpoint scaffold; the official STNS
            // must independently prove its train, day and passenger span before gaining any rows.
            val hasDeviceRoute = runCatching { repo.queries.route(trainNo) }.getOrNull()
                ?.let { JourneyRouteGate.covers(it.stops.map { stop -> stop.code },
                    fromCode, toCode) } == true
            val fallbackRoute = if (!hasDeviceRoute &&
                repo.savedSchedule(trainNo, fromCode, toCode, originEpochDay) == null) {
                confirmHaltRoute?.takeIf { JourneyRouteGate.covers(
                    it.stops.map { stop -> stop.code }, fromCode, toCode) } ?: (liveIdentity ?:
                    runCatching { identityFetch.identityOf(trainNo) }.getOrNull())
                    ?.takeIf { it.identity.number == trainNo }
                    ?.also { liveIdentity = it }
                    ?.let(::syntheticRoute)
            } else null
            if (fallbackRoute != null && _ui.value == null) {
                val at = System.currentTimeMillis()
                val initial = PositionEngine.locate(
                    PositionEngine.Request(fallbackRoute, null, nowMinute(at), at, null))
                lastAnswer = initial
                _ui.value = buildUi(fallbackRoute, initial, emptyList(),
                    listOf("Only endpoint times available while checking the dated official station table."))
            }
            val officialStarted = android.os.SystemClock.elapsedRealtime()
            val live = runCatching {
                repo.live(
                    trainNo = trainNo, fromCode = fromCode, toCode = toCode,
                    originEpochDay = originEpochDay,
                    previous = previous?.let { PositionEngine.TrainPositionInput(it) },
                    fallbackRoute = fallbackRoute,
                )
            }.getOrNull()
            lastOfficialMs = android.os.SystemClock.elapsedRealtime() - officialStarted
            lastOfficialRequest = if (live == null)
                OfficialRequestReceipt(OfficialRequestReceipt.Kind.REQUEST_FAILED,
                    "The request could not complete on this device.")
            else OfficialRequestReceipt.from(live.tried, live.notes)
            Diary.get()?.log("EVIDENCE") {
                val snapshot = live?.answer
                val anchor = snapshot?.anchorStopCode
                val fields = anchor?.let { snapshot?.perStop?.get(it) }
                SourceEvidenceReceipt.official(trainNo, originEpochDay, lastOfficialRequest!!,
                    snapshot?.perStop?.values?.count {
                        it.actualArrivalMin != null || it.actualDepartureMin != null
                    } ?: 0, anchor, fields?.actualDepartureMin ?: fields?.actualArrivalMin,
                    snapshot?.position?.feedStampMillis)
            }
            if (live == null) {
                // A transient failure cannot delete an already rendered valid report.
                _ui.value?.let { old ->
                    _ui.value = buildUi(old.route, lastAnswer ?: old.answer, old.tried)
                    _error.value = "Official request failed; keeping the previous report. " +
                        "Position since its event time is unknown."
                    return
                }
                // A failed lookup is not always a missing train: on a network that kills every live
                // ask, repo.live can fail TRANSIENTLY for a train whose full route sits on this very
                // device - and the owner's 16:47 paste reported exactly that: "some stations are
                // also missing after refresh". So before any endpoint-only view, re-read the offline
                // route; the synthetic two-stop view is ONLY for trains the offline copy truly lacks.
                val cachedRoute = listOfNotNull(
                    runCatching { repo.queries.route(trainNo) }.getOrNull(), confirmHaltRoute)
                    .firstOrNull { JourneyRouteGate.covers(
                        it.stops.map { stop -> stop.code }, fromCode, toCode) }
                if (cachedRoute != null) {
                    val now = System.currentTimeMillis()
                    val answer = PositionEngine.locate(
                        PositionEngine.Request(cachedRoute, null, nowMinute(now), now, previous),
                    )
                    lastAnswer = answer
                    _ui.value = buildUi(
                        cachedRoute, answer, emptyList(),
                        listOf("the live fetch failed this tick — the on-device timetable is shown, unchanged"),
                    )
                    _error.value = null
                    maybeCrowd()
                    surveySourcesOnce()
                    maybeRouteExtent()
                    return
                }
                // The train the list advertised may exist ONLY in the live source (the offline copy
                // lacks it - 20689 on the owner's device). A dead end here is the worst sentence in
                // the app: render what we DO know - identity and the two endpoint times the erail
                // tile carries - and name exactly what is missing. Never silence.
                val id = liveIdentity ?: runCatching { identityFetch.identityOf(trainNo) }.getOrNull()
                if (id == null) {
                    _error.value = "No timetable for $trainNo on this device, and the live source did not answer."
                    return
                }
                liveIdentity = id
                val route = syntheticRoute(id)
                val now = System.currentTimeMillis()
                val answer = PositionEngine.locate(
                    PositionEngine.Request(route, null, nowMinute(now), now, null),
                )
                lastAnswer = answer
                _ui.value = buildUi(route, answer, emptyList(), listOf(fallbackNote(id, fromCode, toCode)))
                _error.value = null
                maybeCrowd()
                surveySourcesOnce()
                maybeRouteExtent()
                return
            }
            lastAnswer = live.answer
            officialStaticKnown = officialStaticKnown || live.officialScheduleVerified ||
                live.answer.position.provenance == SourceKind.PROVIDER_NTES
            _ui.value = buildUi(live.route, live.answer, live.tried, live.notes)
            _error.value = null
            if (live.answer.position.provenance.live) _liveReceipt.value = System.currentTimeMillis()
            maybeCrowd()
            if (!`in`.opentrack.india.data.Prefs(app).batteryMode &&
                staticMapMatchesLiveIdentity(live.route)) maybeRailGeo(live.route)
            surveySourcesOnce()
            if (live.answer.position.provenance != SourceKind.PROVIDER_NTES) {
                // The first-load survey already asks independent status sources; later manual
                // refreshes retain the usual throttle when the official request fails.
                if (firstSourceSurveyStarted && lastMirrorPoll + CROWD_POLL_MIN_MILLIS < System.currentTimeMillis()) maybeMirror()
                if (!officialStaticKnown) maybeRouteExtent()
            } else {
                _mirrorState.postValue(if (`in`.opentrack.india.data.Prefs(app).batteryMode)
                    "Official NTES answered" else
                    "Official NTES answered; checking other sources in the background")
            }
        }
    }

    private suspend fun recomputeFromCache() {
        val repo = repo() ?: return
        val cached = runCatching { repo.queries.route(trainNo) }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val answer = PositionEngine.locate(
            PositionEngine.Request(
                route = cached, snapshot = null, nowRailMinute = nowMinute(now),
                nowMillis = now, previous = lastAnswer?.position,
            )
        )
        lastAnswer = answer
        _ui.value = buildUi(cached, answer, emptyList(), emptyList())
    }

    /** Reader poll discipline (adopted from the owner's researched spec, 60-120 s window, 90 s chosen):
        the relay is free but a poll per auto-tick hammers it for data that changes by the minute, not
        by the second. The user's own publish bypasses the throttle - they just acted; they read now. */
    private fun maybeCrowd(force: Boolean = false) {
        if (`in`.opentrack.india.data.Prefs(app).batteryMode) return

        if (!crowdOn) return
        val nowWall = System.currentTimeMillis()
        if (!force && nowWall - lastCrowdPoll < CROWD_POLL_MIN_MILLIS) return
        lastCrowdPoll = nowWall
        viewModelScope.launch {
            val codes = _ui.value?.route?.stops?.map { it.code } ?: return@launch
            val poll = withContext(Dispatchers.IO) { relay.pollRecentOrFailure(trainNo) }
            val merged = CrowdMerger.merge(poll.reports, System.currentTimeMillis())
            // Reader lines ONLY where a fresh actual exists: a 14-halt wall of "no traveller reports
            // yet" is noise, not honesty - the honest absence is one diary line and an empty column.
            val lines = CrowdMerger.lines(merged, merged.stops.map { it.stationCode }.distinct())
            _ui.postValue(_ui.value?.copy(reports = lines, relayOk = poll.relayOk))
            Diary.get()?.log("CROWD") {
                "read ${poll.reports.size} report(s) on ct-$trainNo; ${lines.size} stop(s) with fresh " +
                    "actuals, ${merged.droppedStale} dropped stale; relay " +
                    (if (poll.relayOk) "answered" else "unreachable")
            }
        }
    }

    /** Historical RailGeo was reported to carry passing-point CODES in 2019; the CI runner cannot
     * handshake with it today. Try once, on the phone, AFTER the fast official answer (never on the
     * live request's critical path). An unmatched/changed schema only creates a diagnostic, not rows. */
    /** Cache is keyed to the selected origin day; this is not the NTES observation cache. */
    private fun loadConfirmScheduleCache() {
        val prefs = app.getSharedPreferences("confirmtkt_static_routes", android.content.Context.MODE_PRIVATE)
        val key = "$trainNo:$originEpochDay"
        val now = System.currentTimeMillis()
        val halts = OfficialScheduleCache.decode(prefs.getString("$key:halts", null),
            trainNo, originEpochDay, fromCode, toCode, now)
        val all = OfficialScheduleCache.decode(prefs.getString("$key:all", null),
            trainNo, originEpochDay, fromCode, toCode, now)
        if (halts != null && all != null && all.stops.filter { it.isHalt }.map { it.code } ==
            halts.stops.map { it.code }) {
            confirmHaltRoute = halts
            confirmStaticRoute = all
        }
    }

    /** A number reused on different endpoints must not inherit the old route's passing stations. */
    private fun staticMapMatchesLiveIdentity(route: TrainRoute): Boolean =
        liveIdentity?.identity?.let { id ->
            id.fromCode.equals(route.srcCode, true) && id.toCode.equals(route.dstCode, true)
        } ?: true

    /** User-initiated static map retry. It is never used as a position source. */
    fun retryIntermediateMap(fromCode: String? = null) {
        val route = _ui.value?.route ?: return
        if (mapRetryJob?.isActive == true) {
            _mapRetryStatus.value = "Already checking independent station maps…"
            return
        }
        if (!staticMapMatchesLiveIdentity(route)) {
            _mapRetryStatus.value = "Live train endpoints disagree with this device's timetable. " +
                "Cannot attach passing stations to the wrong route. Scheduled halts remain."
            return
        }
        // An old cached map for a different route is not a reason to skip a
        // deliberate retry. The dated halt schedule remains untouched.
        if (_ui.value?.staticRoute == null) confirmStaticRoute = null
        confirmScheduleAttemptAt = 0L
        geoAttemptAt = 0L
        Diary.get()?.log("ROUTE", "user requested static intermediate map retry for $trainNo")
        _mapRetryStatus.value = "Checking independent station maps…"
        val confirm = maybeConfirmSchedule()
        val geo = maybeRailGeo(route)
        mapRetryJob = viewModelScope.launch {
            confirm?.join()
            geo?.join()
            val u = _ui.value
            val mapped = u?.let { RouteBetween.groupedPoints(it.staticRoute, it.route) }
            _mapRetryStatus.value = if (fromCode != null &&
                fromCode.uppercase() !in u?.staticMappedLegStarts.orEmpty())
                "No verified map for this leg yet. Other mapped legs remain available."
            else if (!mapped.isNullOrEmpty())
                "Intermediate map ready. Tap a leg to reveal its stations."
            else "No validated intermediate map answered; timed halts remain. Retry later."
            Diary.get()?.log("ROUTE") { "manual map retry: ${mapped?.values?.sumOf { it.size } ?: 0} independent intermediate points" }
        }
    }

    /** Fetch embedded, dated *static* Schedule, independently of the fast official call. No
     * visible running-status clock and no intermediate-passage time ever becomes an actual. */
    private fun maybeConfirmSchedule(): Job? {
        if (confirmStaticRoute != null) return null
        val now = System.currentTimeMillis()
        if (now - confirmScheduleAttemptAt < 90_000L) return null
        confirmScheduleAttemptAt = now
        return viewModelScope.launch {
            val day = NtesWire.dateForWire(originEpochDay)
            val result = withContext(Dispatchers.IO) {
                val body = ConfirmTktMirror.plainGet(ConfirmTktMirror.BASE + trainNo).first
                body?.let { ConfirmTktSchedule.parse(it, trainNo, day, fromCode, toCode) }
            } ?: run {
                Diary.get()?.log("ROUTE", "ConfirmTkt dated static schedule unavailable for $trainNo/$day")
                return@launch
            }
            confirmHaltRoute = result.halts
            confirmStaticRoute = result.allStations
            val at = System.currentTimeMillis()
            val key = "$trainNo:$originEpochDay"
            app.getSharedPreferences("confirmtkt_static_routes", android.content.Context.MODE_PRIVATE)
                .edit().putString("$key:halts", OfficialScheduleCache.encode(result.halts, originEpochDay, at))
                .putString("$key:all", OfficialScheduleCache.encode(result.allStations, originEpochDay, at))
                .apply()
            Diary.get()?.log("ROUTE", "ConfirmTkt $day: ${result.halts.stops.size} scheduled halts, " +
                "${result.allStations.stops.size - result.halts.stops.size} unconfirmed stopping points")
            val old = _ui.value
            if (old == null || (!officialStaticKnown &&
                    !old.answer.position.provenance.live &&
                    old.answer.perStop.values.none {
                        it.actualArrivalMin != null || it.actualDepartureMin != null
                    } && old.route.stops.size < result.halts.stops.size &&
                    old.route.stops.map { it.code.uppercase() }.let { existing ->
                        result.halts.stops.map { it.code.uppercase() }.filter { it in existing } == existing
                    })) {
                // Only replace an incomplete NON-official timetable. If an NTES run has
                // already landed, keep its position and its observation-based stop table.
                val answer = PositionEngine.locate(PositionEngine.Request(result.halts,
                    null, nowMinute(at), at, null))
                lastAnswer = answer
                _ui.value = buildUi(result.halts, answer, emptyList(), listOf(
                    "ConfirmTkt dated static schedule — no independently observed actuals from this page."))
                _error.value = null
            } else if (old != null) {
                // BuildUi checks every known halt's order before it can attach these points.
                val candidate = result.allStations.takeIf { full ->
                    old.route.stops.map { it.code.uppercase() }.let { required ->
                        full.stops.map { it.code.uppercase() }.filter { it in required } == required
                    }
                }
                if (candidate != null && staticMapMatchesLiveIdentity(old.route) &&
                    (old.staticRoute?.stops?.size ?: 0) < candidate.stops.size) {
                    _ui.value = old.copy(staticRoute = if (officialStaticKnown)
                        RouteBetween.withVerifiedHalts(candidate, old.route) ?: candidate else candidate,
                        staticRouteSource = "ConfirmTkt",
                        staticMappedLegStarts = old.route.stops.take(old.route.stops.size - 1)
                            .map { it.code.uppercase() }.toSet())
                    // An early website reply used halts only. Once a dated, matched
                    // map arrives, ask it once again for its missing intermediate card.
                    if (publicChecks[PublicPageChecks.ORG]?.pageAnswered == true)
                        probeSource(PublicPageChecks.ORG, select = false)
                }
            }
        }
    }

    private fun maybeRailGeo(official: TrainRoute): Job? {
        if (geoRoute != null && _ui.value?.staticRoute != null) return null
        val now = System.currentTimeMillis()
        if (now - geoAttemptAt < 3_600_000L) return null
        geoAttemptAt = now
        return viewModelScope.launch {
            val repo = repo() ?: return@launch
            if (loadGeoCache(official, repo)) {
                _ui.value?.let { u -> if (staticMapMatchesLiveIdentity(u.route)) {
                    _ui.value = u.copy(staticRoute = geoRoute,
                        staticRouteSource = "RailGeo",
                        staticMappedLegStarts = u.route.stops.take(u.route.stops.size - 1)
                            .map { it.code.uppercase() }.toSet())
                    if (u.staticRoute == null && publicChecks[PublicPageChecks.ORG]?.pageAnswered == true)
                        probeSource(PublicPageChecks.ORG, select = false)
                } }
                return@launch
            }
            val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.ENGLISH).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            }.format(java.util.Date(originEpochDay * 86_400_000L))
            val url = "https://enquiry.indianrail.gov.in/ntes/RailGeo?action=getTrainSchedule" +
                "&trainNo=$trainNo&trainStartDate=" + java.net.URLEncoder.encode(date, "UTF-8")
            val response = withContext(Dispatchers.IO) {
                Http(app).get(url, timeoutMillis = 3_500, retries = 0, budgetMillis = 3_500)
            }
            val body = (response as? Http.Result.Ok)?.body
            if (body == null) {
                Diary.get()?.log("ROUTE") { "RailGeo phone request: ${(response as Http.Result.Failed).message}" }
                return@launch
            }
            val grown = withContext(Dispatchers.IO) {
                RailGeoRoute.parse(body, official) { code -> repo.queries.station(code)?.name }
            }
            if (grown == null) {
                Diary.get()?.log("ROUTE") {
                    "RailGeo returned ${body.length}B but no validated full route for $trainNo; " +
                        "schema prefix=${body.take(350).replace('\n', ' ')}"
                }
                return@launch
            }
            geoRoute = grown
            app.getSharedPreferences("railgeo_routes", 0).edit()
                .putString("$trainNo:$originEpochDay", body)
                .putLong("$trainNo:$originEpochDay:at", System.currentTimeMillis()).apply()
            _ui.value?.let { u -> if (u.route.trainNo == trainNo &&
                staticMapMatchesLiveIdentity(u.route)) {
                _ui.value = u.copy(staticRoute = grown,
                    staticRouteSource = "RailGeo",
                    staticMappedLegStarts = u.route.stops.take(u.route.stops.size - 1)
                        .map { it.code.uppercase() }.toSet())
                if (u.staticRoute == null && publicChecks[PublicPageChecks.ORG]?.pageAnswered == true)
                    probeSource(PublicPageChecks.ORG, select = false)
            } }
            Diary.get()?.log("ROUTE") {
                "RailGeo validated ${grown.stops.size} ordered stations (${grown.stops.count { !it.isHalt }} " +
                    "extra route points with unknown stopping status, no assumed times); official schedule ${official.stops.size} halts"
            }
        }
    }

    private fun loadGeoCache(base: TrainRoute, repo: RailRepository): Boolean {
        val key = "$trainNo:$originEpochDay"
        val prefs = app.getSharedPreferences("railgeo_routes", 0)
        val savedAt = prefs.getLong("$key:at", 0L)
        if (savedAt <= 0 || System.currentTimeMillis() - savedAt !in 0L..86_400_000L) return false
        val body = prefs.getString(key, null) ?: return false
        val grown = RailGeoRoute.parse(body, base) { code -> repo.queries.station(code)?.name }
            ?: return false
        geoRoute = grown
        return true
    }

    /** One route-page attempt per 10 minutes; the answer itself is cached for a day in prefs. */
    private fun maybeRouteExtent(force: Boolean = false) {
        val nowWall = System.currentTimeMillis()
        if (!force && nowWall - extentAttemptAt < 600_000) return
        extentAttemptAt = nowWall
        viewModelScope.launch {
            val repo = repo() ?: return@launch
            if (extentRoute == null && !loadExtentCache()) {
                val got = withContext(Dispatchers.IO) { ErailRouteMirror.fetchOrWhy(trainNo) }
                if (got.halts.isEmpty()) {
                    Diary.get()?.log("ROUTE") { "full route not available: ${got.why}" }
                    return@launch
                }
                val base = _ui.value?.route
                    ?: runCatching { repo.queries.route(trainNo) }.getOrNull()
                    ?: return@launch
                val merged = mergeRouteExtents(base, got.halts)
                if (merged == null) {
                    Diary.get()?.log("ROUTE") {
                        "erail's halt list does not contain the device route in order - keeping the device table"
                    }
                    return@launch
                }
                extentRoute = merged
                extentAt = System.currentTimeMillis()
                saveExtentCache(got.halts)
            }
            val u = _ui.value ?: return@launch
            // A late, partial erail halt list must not overwrite a validated official schedule,
            // including a schedule-only response with no actual minutes yet.
            if (officialStaticKnown || u.answer.position.provenance == SourceKind.PROVIDER_NTES) return@launch
            val grown = extentRoute ?: return@launch
            _ui.postValue(buildUi(grown, u.answer, u.tried).copy(relayOk = u.relayOk))
            Diary.get()?.log("ROUTE") {
                "full route on screen: ${grown.stops.size} halts " +
                    "${grown.srcCode}->${grown.dstCode} (device timetable carries " +
                    "${u.route.stops.size}; the rest render an em-dash schedule until a timetable names their minutes)"
            }
        }
    }

    /** Day-fresh route cache, same SWR discipline (and JSON house style) as the mirror's. */
    private suspend fun loadExtentCache(): Boolean {
        if (extentRoute != null) return true
        val raw = runCatching {
            org.json.JSONObject(runCatching { repo()?.prefs?.erailRouteCache }.getOrNull() ?: "{}")
        }.getOrNull() ?: return false
        val obj = raw.optJSONObject(trainNo) ?: return false
        val at = obj.optLong("at", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 86_400_000L) return false
        val arr = obj.optJSONArray("halts") ?: return false
        val halts = mutableListOf<ErailRouteMirror.Halt>()
        for (i in 0 until arr.length()) {
            val h = arr.optJSONObject(i) ?: continue
            halts += ErailRouteMirror.Halt(h.optString("n"), h.optString("c"))
        }
        if (halts.size < 2) return false
        val base = runCatching { repo()?.queries?.route(trainNo) }.getOrNull() ?: return false
        val merged = mergeRouteExtents(base, halts) ?: return false
        extentRoute = merged
        extentAt = at
        return true
    }

    private suspend fun saveExtentCache(halts: List<ErailRouteMirror.Halt>) {
        val raw = runCatching {
            org.json.JSONObject(runCatching { repo()?.prefs?.erailRouteCache }.getOrNull() ?: "{}")
        }.getOrNull() ?: return
        val obj = org.json.JSONObject().put("at", extentAt)
        val arr = org.json.JSONArray()
        for (h in halts) arr.put(org.json.JSONObject().put("n", h.name).put("c", h.code))
        obj.put("halts", arr)
        raw.put(trainNo, obj)
        // Keep the freshest twelve trains' routes; the same trim discipline as the mirror cache.
        val trimmed = org.json.JSONObject()
        val keysWithAt = mutableListOf<Pair<Long, String>>()
        for (key in raw.keys()) {
            val o = raw.optJSONObject(key) ?: continue
            keysWithAt += o.optLong("at", 0L) to key
        }
        var kept = 0
        for (entry in keysWithAt.sortedByDescending { it.first }) {
            if (kept++ >= 12) continue
            trimmed.put(entry.second, raw.optJSONObject(entry.second) ?: continue)
        }
        runCatching {
            val r = repo() ?: return
            r.prefs.erailRouteCache = trimmed.toString()
        }
    }

    /**
     * The mirror witness: while the officials refuse this device, runningstatus.in server-renders the
     * actual arrival/departure of today's run for every halt it knows about - the exact minutes this
     * product exists for (the owner's priority, verbatim: "get actual arrival and departure time").
     * It runs only when NO live data reached the device (a live answer outranks a mirror), it is
     * throttled like the relay, and its lines carry its own name - a second witness is labelled, never
     * blended into the official columns. A traveller report wins its stop; the mirror fills the rest.
     */
    private fun maybeMirror(force: Boolean = false, preferred: String? = null) {
        if (!force && _ui.value?.answer?.position?.provenance?.live == true) return
        val nowWall = System.currentTimeMillis()
        if (!force && nowWall - lastMirrorPoll < CROWD_POLL_MIN_MILLIS) return
        lastMirrorPoll = nowWall
        val mirrorOrder = ++sourceProbeOrder
        val job = viewModelScope.launch {
            val route = _ui.value?.route ?: runCatching { repo()?.queries?.route(trainNo) }.getOrNull()
                ?: return@launch
            if (!freshShift()) loadMirrorCache()
            val names = route.stops.map { it.code to it.name }
            if (preferred != null) _mirrorState.postValue("Checking $preferred…")
            val ckDeferred = async {
                val direct = withContext(Dispatchers.IO) { ConfirmTktMirror.fetchOrWhy(trainNo, names) }
                if (direct.ok) direct else {
                    val page = withContext(Dispatchers.IO) {
                        BrowserFetch.pageText(ConfirmTktMirror.BASE + trainNo, 25_000,
                            settleMillis = 1_200, textCap = 24_000)
                    }
                    val text = (page as? Http.Result.Ok)?.body?.let(RunningStatusMirror::stripTags)
                    if (text == null) direct else {
                        val lines = ConfirmTktMirror.parse(text, names)
                        ConfirmTktMirror.Fetched(lines, lines.size, lines.isNotEmpty(),
                            ConfirmTktMirror.stopsOf(text, names), ConfirmTktMirror.pageDayOf(text))
                    }
                }
            }
            // A manual ConfirmTkt selection must ask ConfirmTkt, not spend its latency budget
            // waiting for a known-403 runningstatus page. NTES's own already-received observations
            // can cross-check overlapping cells; they do not make projected page rows actuals.
            val onlyConfirm = preferred == ConfirmTktMirror.SOURCE &&
                _ui.value?.answer?.position?.provenance == SourceKind.PROVIDER_NTES
            val direct = if (onlyConfirm)
                RunningStatusMirror.Fetched(null, "not needed for this manual ConfirmTkt probe")
            else if (System.currentTimeMillis() < directDeadUntil)
                RunningStatusMirror.Fetched(null, "recent 403; browser lane")
            else withContext(Dispatchers.IO) { RunningStatusMirror.fetchOrWhy(trainNo) }
            if (direct.why.startsWith("HTTP 403")) directDeadUntil = System.currentTimeMillis() + 600_000
            val primary = if (onlyConfirm) null else (direct.actuals ?: run {
                val page = withContext(Dispatchers.IO) {
                    BrowserFetch.pageText("https://runningstatus.in/status/$trainNo", 30_000,
                        settleMillis = 1_200, textCap = 24_000)
                }
                (page as? Http.Result.Ok)?.body?.let {
                    RunningStatusMirror.parse(RunningStatusMirror.stripTags(it))
                }
            })
            val rawCk = ckDeferred.await()
            // A slower mirror must never replace a verified official screen or its route.
            if (!force && _ui.value?.answer?.position?.provenance == SourceKind.PROVIDER_NTES) return@launch
            val expectedDay = java.text.SimpleDateFormat("dd-MMM", java.util.Locale.ENGLISH).apply {
                timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
            }.format(java.util.Date(originEpochDay * 86_400_000L))
            val ck = if (rawCk.pageDay?.equals(expectedDay, true) == true) rawCk else {
                Diary.get()?.log("MIRROR", "confirmtkt page date ${rawCk.pageDay ?: "missing"} " +
                    "does not prove requested run $expectedDay; refusing all minutes")
                rawCk.copy(lines = emptyMap(), stops = emptyList(), halts = 0, ok = false)
            }
            if (!isActive) return@launch // superseded manual probe cannot publish its witness
            if (preferred == null && listOf(RunningStatusMirror.SOURCE, ConfirmTktMirror.SOURCE)
                    .any { (latestManualProbeOrder[it] ?: 0L) > mirrorOrder }) return@launch
            val u = _ui.value ?: return@launch
            val nowRail = nowMinute(System.currentTimeMillis())
            val base = TrackModel.build(route, u.answer.position, u.answer.perStop, nowRail,
                route.stops.firstOrNull { it.code.equals(fromCode, true) }?.seq ?: route.stops.first().seq,
                route.stops.lastOrNull { it.code.equals(toCode, true) }?.seq ?: route.stops.last().seq,
                originEpochDay, null)
            if (primary != null && primary.runEpochDay != originEpochDay) {
                Diary.get()?.log("MIRROR", "runningstatus page run=${primary.runEpochDay} " +
                    "does not match requested run=$originEpochDay; refusing all minutes")
            }
            val primaryForRun = primary?.takeIf { it.runEpochDay == originEpochDay }
            val plausiblePrimary = validateStops(base, primaryForRun?.stops.orEmpty(), nowRail)
                .filter { st -> base.rows.any { row -> row.code.equals(st.stationCode, true) &&
                    ((row.schedArr != null && st.actArr != null) ||
                        (row.schedDep != null && st.actDep != null)) } }
            val disputed = conflictingCodes(plausiblePrimary, ck.stops, base, nowRail)
            val verifiedPrimary = plausiblePrimary.filterNot { it.stationCode.uppercase() in disputed }
            // ConfirmTkt's page mixes schedule, estimates and past runs. An answer is NOT evidence.
            // The runningstatus page often has no run date even when it returned 14 raw rows. Use
            // the dated official response as a SECOND possible witness, but only for overlapping
            // reported cells: this does not promote the other ConfirmTkt cells to observations.
            // The original answer (not the selected table) retains NTES cells on manual selection.
            val officialWitness = officialStopsForCrossCheck(lastAnswer ?: u.answer, base, nowRail)
            val witness = if (officialWitness.isNotEmpty()) officialWitness else verifiedPrimary
            val verifiedCk = crossCheckedStops(witness, ck.stops, base, nowRail)
            val ckWitnessLabel = if (officialWitness.isNotEmpty()) "NTES" else "dated runningstatus"
            Diary.get()?.log("MIRROR") {
                "ConfirmTkt ${ck.stops.size} raw, ${verifiedCk.size} overlapping halts corroborated by $ckWitnessLabel; " +
                    "official witness=${officialWitness.size}, runningstatus witness=${verifiedPrimary.size}"
            }
            mirrorCrossChecked = verifiedCk.isNotEmpty()
            val refreshed = clockAt(System.currentTimeMillis())
            mirrorSourceStates = linkedMapOf(
                RunningStatusMirror.SOURCE to (if (onlyConfirm)
                    mirrorSourceStates[RunningStatusMirror.SOURCE] ?: ("none" to "not checked in this probe")
                    else if (verifiedPrimary.isNotEmpty())
                    "live" to "${verifiedPrimary.size} verified halts" +
                        (if (disputed.isEmpty()) "" else " · ${disputed.size} disputed hidden") + " · $refreshed"
                    else "none" to "${primary?.stops?.size ?: 0} page rows; " +
                        "0 same-run actuals (${direct.why.take(45)})"),
                ConfirmTktMirror.SOURCE to (if (verifiedCk.isNotEmpty())
                    "live" to "${verifiedCk.size} cells overlap $ckWitnessLabel · $refreshed"
                    else if (ck.ok) "unverified" to "${ck.stops.size} page rows for " +
                        "${ck.pageDay ?: "unknown day"}; 0 independently corroborated actuals"
                    else "none" to "${rawCk.stops.size} page rows; " +
                        (if (rawCk.pageDay != expectedDay) "run day mismatched/missing"
                        else "no validated rows")),
            )
            mirrorSourceLines = linkedMapOf(
                RunningStatusMirror.SOURCE to primary?.let {
                    RunningStatusMirror.sentences(it.copy(stops = verifiedPrimary), route.stops.map { r -> r.code })
                }.orEmpty(),
                ConfirmTktMirror.SOURCE to ck.lines.filterKeys { code ->
                    verifiedCk.any { it.stationCode.equals(code, true) }
                },
            )
            mirrorStops = linkedMapOf(
                RunningStatusMirror.SOURCE to verifiedPrimary,
                ConfirmTktMirror.SOURCE to verifiedCk,
            )
            // Never silently switch away from the selected feed, except when the previous selection
            // was a deprecated cache entry. The dropdown and the table must describe the same witness.
            if (preferred != null) mirrorActiveSource = preferred
            else if (mirrorActiveSource == "last known") mirrorActiveSource = null
            // A background first-load check must never silently replace the official selection.
            mirrorRefreshedAt = System.currentTimeMillis()
            if (preferred != null) manualProbeMs[preferred] = mirrorRefreshedAt - nowWall
            mirrorShiftAt = mirrorRefreshedAt
            cacheTrainNo = trainNo
            val overlaid = overlayActuals(base, verifiedPrimary, nowRail)
            val receipt = deviceReceipt(overlaid)
            delayDevice = receipt?.first ?: 0
            delayDetail = receipt?.second
            // An extrapolated position and ETA must never arise from an uncorroborated page token.
            mirrorLate = if (verifiedPrimary.isNotEmpty()) delayDevice.coerceAtLeast(0) else 0
            mirrorNote = null
            mirrorHeadline = null
            mirrorNext = null
            mirrorBadge = null
            mirrorAnchorCode = null
            mirrorSources = "${verifiedPrimary.size} verified on runningstatus.in · " +
                "${verifiedCk.size} corroborated on confirmtkt · checked $refreshed"
            if (preferred != null) _mirrorState.postValue(
                if (mirrorStops[preferred].orEmpty().isNotEmpty())
                    "$preferred: verified for this run · $mirrorSources"
                else if (preferred == ConfirmTktMirror.SOURCE)
                    "ConfirmTkt returned ${ck.stops.size} page rows for ${ck.pageDay ?: "unknown day"}; " +
                        "0 independently corroborated actuals; timetable shown for this source."
                else "$preferred: ${primary?.stops?.size ?: 0} page rows, " +
                    "0 verified same-run actuals (${direct.why.take(45)}); timetable shown."
            )
            val latest = _ui.value ?: u
            _ui.value = buildUi(latest.route, lastAnswer ?: latest.answer, latest.tried)
                .copy(relayOk = latest.relayOk)
            if (verifiedPrimary.isNotEmpty()) saveMirrorCache(mirrorSourceLines[RunningStatusMirror.SOURCE].orEmpty())
            Diary.get()?.log("MIRROR") {
                "manual=${preferred ?: "no"}; direct=${direct.why.take(45)}; " +
                    "primary raw=${primary?.stops?.size ?: 0} verified=${verifiedPrimary.size} " +
                    "disputed hidden=${disputed.joinToString(",")}; " +
                    "confirmtkt raw=${ck.stops.size} corroborated=${verifiedCk.size}; " +
                    "unverified minutes never drive position or delay"
            }
        }
        if (force && preferred != null) manualProbeJob = job
    }

    /** The traveller's half: publish one observation, then re-read the topic so the report shows. */
    fun reportAt(stationCode: String, kind: StopReport.Kind) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val rep = StopReport(
                trainNo = trainNo, stationCode = stationCode.uppercase(), kind = kind,
                railMinute = RailTime.istMinuteOfDay(now), reportedAtMillis = now,
            )
            val w = withContext(Dispatchers.IO) { relay.publish(rep) }
            val msg = when (w) {
                is NtfyRelay.Wire.Ok ->
                    "published: ${rep.stationCode} $kind ${rep.railMinute / 60}:" + "%02d".format(rep.railMinute % 60) +
                        " — thank you, travellers on this train will see it"
                is NtfyRelay.Wire.Unreachable -> "could not publish (${w.why}) — nothing was sent"
            }
            _reportStatus.postValue(msg)
            Diary.get()?.log("CROWD") { msg }
            if (w is NtfyRelay.Wire.Ok) maybeCrowd(force = true)
        }
    }

    /**
     * The pipe's proof, with a guardrail the vocabulary forbids us to skip: the test report travels on
     * ct-test-<nonce>, NEVER on a real train's topic - a loopback that showed up as a traveller report
     * would be exactly the fabricated witness this project promised never to create.
     */
    fun loopbackTest() {
        viewModelScope.launch {
            val nonce = java.util.UUID.randomUUID().toString().takeLast(4)
            val now = System.currentTimeMillis()
            val rep = StopReport("test-$nonce", "TEST", StopReport.Kind.DEPARTED,
                RailTime.istMinuteOfDay(now), now)
            val pub = withContext(Dispatchers.IO) { relay.publish(rep) }
            if (pub !is NtfyRelay.Wire.Ok) {
                val why = (pub as? NtfyRelay.Wire.Unreachable)?.why ?: "unknown"
                _reportStatus.postValue("loopback FAILED at publish: $why")
                return@launch
            }
            // ntfy.sh serves from a load-balanced cluster: the publish lands on one node and the
            // read-back can hit another before the caches sync - a single 1.5 s read failed THREE
            // times on the owner's phone (paste #16) with relayOk=true. The retry window is the fix:
            // same topic, same test minute, a few patient reads - never a second publish.
            var back = withContext(Dispatchers.IO) { relay.pollRecentOrFailure("test-$nonce") }
            var attempt = 1
            while (attempt < 4 &&
                !(back.relayOk && back.reports.any { it.stationCode == "TEST" && it.railMinute == rep.railMinute })
            ) {
                delay(attempt * 2_500L)
                attempt++
                back = withContext(Dispatchers.IO) { relay.pollRecentOrFailure("test-$nonce") }
            }
            // Three verdicts, not one: PARSED (the pipe and the parser both work), IN-THE-BYTES (the
            // relay returned the message but our parser could not read it - parser guilty, and the raw
            // line is captured below), or NEVER-RETURNED (the relay accepted the publish and never
            // serves it back - relay guilty). Paste #19 made both failure shapes plausible.
            val id = (pub as NtfyRelay.Wire.Ok).body.substringAfter("\"id\":\"", "").substringBefore('"')
            val parsedMatch = back.reports.any { it.stationCode == "TEST" && it.railMinute == rep.railMinute }
            val rawHit = id.isNotEmpty() && back.rawBody.contains(id)
            val msg = when {
                parsedMatch ->
                    "loopback OK: published and read back on ct-test-$nonce — the report pipe works on this device"
                rawHit -> {
                    val rawLine = back.rawBody.lineSequence().firstOrNull { it.contains(id) }?.take(600) ?: ""
                    Diary.get()?.log("CROWD") { "loopback raw line: $rawLine" }
                    "loopback: the relay DID return the message ($id) but it did not parse - parser guilty, raw line captured in the diary"
                }
                else ->
                    "loopback FAILED: published $id but the poll never returned it after $attempt read(s) - relay-side"
            }
            _reportStatus.postValue(msg)
            Diary.get()?.log("CROWD") { msg }
        }
    }

    private fun clockAt(at: Long): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        .format(java.util.Date(at))

    private fun nowMinute(now: Long): Int =
        ((RailTime.istEpochDay(now) - originEpochDay) * RailTime.MINUTES_PER_DAY +
            RailTime.istMinuteOfDay(now)).toInt()

    private fun buildUi(
        route: TrainRoute, answer: PositionEngine.Answer, tried: List<String>,
        notes: List<String> = emptyList(),
        /** The mirror's actuals set this clock back by the train's measured lateness (0 = schedule). */
        nowRailOverride: Int? = null,
        /** When the mirror speaks, its sentence IS the position note - it outranks the refusal text. */
        positionNote: String? = null,
    ): Ui {
        val now = System.currentTimeMillis()
        if (manualErail != null && now - manualErailAt > PositionEngine.STALE_MS) {
            manualErail = null
            if (mirrorActiveSource == "erail") mirrorActiveSource = null
        }
        val nowRail = nowRailOverride ?: nowMinute(now)
        val erailSelected = mirrorActiveSource == "erail"
        val wimtSelected = mirrorActiveSource == WimtCandidate.SOURCE || mirrorActiveSource == TrackingSources.WIMT
        val officialUnavailable = mirrorActiveSource == "NTES (official)" &&
            answer.position.provenance == SourceKind.PROVIDER
        val erailAnswer = if (erailSelected) manualErail ?: answer.takeIf {
            it.position.provenance == SourceKind.PROVIDER
        } else null
        val wimtAns = if (wimtSelected) manualWimtAnswer else null
        val pos = (wimtAns ?: erailAnswer ?: answer).position
        // An explicit mirror selection is an independent witness, not an overlay on
        // NTES's 15 actuals. Start from the schedule and add ONLY that mirror's
        // validated cells; otherwise the dropdown would misattribute official minutes.
        val mirrorSelected = mirrorActiveSource in
            setOf(RunningStatusMirror.SOURCE, ConfirmTktMirror.SOURCE, "last known")
        val selectedMirrorForTable = freshShift() && mirrorSelected &&
            mirrorStops[mirrorActiveSource].orEmpty().isNotEmpty()
        val webPoint = wimtCandidate?.webPoint?.takeIf { p ->
            wimtSelected &&
                now - p.eventAtMillis in 0L..600_000L &&
                now - p.cacheAtMillis in 0L..120_000L &&
                route.stops.indexOfFirst { it.code.equals(p.followingHalt, true) } ==
                    route.stops.indexOfFirst { it.code.equals(p.precedingHalt, true) } + 1 &&
                route.stops.any { it.code.equals(p.precedingHalt, true) } &&
                RouteBetween.webCacheLeg(route, null, p.precedingHalt,
                    p.followingHalt, p.routeKm) != null
        }
        val webAnchor = webPoint?.let { p -> route.stops.firstOrNull {
            it.code.equals(p.precedingHalt, true) } }
        val publicSelected = mirrorActiveSource in PublicPageChecks.names
        val railClaim = publicChecks[PublicPageChecks.RAIL_YATRI]?.stationClaim?.takeIf { c ->
            mirrorActiveSource == PublicPageChecks.RAIL_YATRI &&
                now - c.updateAt in 0L..300_000L &&
                route.stops.any { it.code.equals(c.code, true) && it.schedKm >= 0 }
        }
        val claimStop = railClaim?.let { c -> route.stops.first { it.code.equals(c.code, true) } }
        val orgReport = publicChecks[PublicPageChecks.ORG]?.orgReport?.takeIf { report ->
            mirrorActiveSource == PublicPageChecks.ORG &&
                // A source outage does not delete an older validated site claim.
                // Keep it on this run for one day, but never redate it to the failed ask.
                now - report.pageUpdatedAt in 0L..86_400_000L &&
                (route.stops.any { it.code.equals(report.anchorCode, true) && it.schedKm >= 0 } ||
                report.intermediate?.let { point ->
                    RouteBetween.groupedPoints(_ui.value?.staticRoute ?: geoRoute ?: confirmStaticRoute,
                        route)[point.precedingHalt]?.any { it.code == point.code &&
                            it.schedKm == point.mapKm } == true
                } == true)
        }
        // The website may fall outside its five-minute *current* window while its
        // original past cards are still worth a source-labelled historical comparison.
        // Never use them as the selected NTES event/marker or redate the page clock.
        val oldWebsiteComparison = if (mirrorActiveSource == "NTES (official)")
            (publicChecks[PublicPageChecks.ORG]?.orgReport ?: maxOrgWebsiteReport)?.takeIf { report ->
                now - report.pageUpdatedAt in 0L..43_200_000L &&
                    route.stops.any { it.code.equals(report.anchorCode, true) }
            } else null
        val orgStop = orgReport?.let { report -> route.stops.firstOrNull {
            it.code.equals(report.intermediate?.precedingHalt ?: report.anchorCode, true) } }
        val tablePos = if (wimtSelected && wimtAns != null) wimtAns.position
        else if (orgReport != null && orgStop != null) pos.copy(
            provenance = SourceKind.PROVIDER_OVERRIDE,
            anchorSeq = orgStop.seq,
            nextSeq = route.stops.getOrNull(route.stops.indexOf(orgStop) + 1)?.seq ?: orgStop.seq,
            fraction = 0f, kmAlong = orgStop.schedKm.toFloat(),
            status = if (orgReport.departed || orgReport.intermediate != null)
                `in`.opentrack.india.model.StopStatus.DEPARTED else
                `in`.opentrack.india.model.StopStatus.ARRIVED,
            delayMinutes = null, etaNextMinute = null,
            ageSeconds = (now - RailTime.railMinuteToEpochMillis(originDate,
                orgReport.eventMinute)).coerceAtLeast(0L) / 1_000L,
            feedAgeSeconds = (now - RailTime.railMinuteToEpochMillis(originDate,
                orgReport.eventMinute)).coerceAtLeast(0L) / 1_000L,
            feedStampMillis = null)
        else if (mirrorSelected || publicSelected || (wimtSelected && wimtAns == null) ||
            (erailSelected && erailAnswer == null) || officialUnavailable) pos.copy(
            provenance = SourceKind.OFFLINE_SCHEDULE, anchorSeq = 0, nextSeq = 0,
            fraction = 0f, delayMinutes = null, etaNextMinute = null, feedStampMillis = null)
        else pos
        val model = TrackModel.build(
            route = route, pos = tablePos,
            perStop = if (wimtSelected && wimtAns != null) wimtAns.perStop
                else if (orgReport != null && orgStop != null) orgReport.rows
                else if (mirrorSelected || (wimtSelected && wimtAns == null) || publicSelected || officialUnavailable) emptyMap()
                else if (erailSelected) erailAnswer?.perStop.orEmpty()
                else answer.perStop,
            nowRailMinute = nowRail,
            boardSeq = route.stops.firstOrNull { it.code.equals(fromCode, true) }?.seq
                ?: route.stops.firstOrNull()?.seq ?: 1,
            alightSeq = route.stops.lastOrNull { it.code.equals(toCode, true) }?.seq
                ?: route.stops.lastOrNull()?.seq ?: route.lastSeq,
            runEpochDay = originEpochDay,
            positionOnly = true,
            // The source's own sentence leads. It is the only one that names a cause the traveller can act
            // on — no network, a timeout, a refusal, or the case this endpoint really is: it answered about
            // the train and published no arrival or departure minute at all. "No live report reached this
            // device" is the last resort, for when no source had anything more specific to say.
            note = (if (orgReport != null && publicChecks[PublicPageChecks.ORG]?.pageAnswered == false)
                    "Website request failed. Keeping the previous site report at its original event time; " +
                        "position since then unknown."
                else if (orgReport != null) "Past website card clocks shown; upcoming site estimates are not arrivals. Run day inferred from timetable; last mapped arrival is not current GPS."
                else if (publicSelected) "No confirmed times from this site; timetable shown."
                else if (mirrorSelected && !selectedMirrorForTable ||
                    erailSelected && erailAnswer == null || officialUnavailable)
                    "No confirmed times from this source; timetable shown."
                else if (wimtSelected && wimtAns != null)
                    wimtAns.note ?: "Where Is My Train live feed · updated just now"
                else if (wimtSelected && webPoint == null)
                    "Connecting to Where Is My Train… timetable shown."
                else if (webPoint != null) "Site distance claim, not phone location; other stops use the timetable."
                else null)
                ?: positionNote ?: notes.firstOrNull { it.isNotBlank() } ?: answer.note
                ?: if (pos.provenance.live) null
                else "No live report reached this device — every minute below is the timetable, " +
                    "not the train.",
        )
        // The label is where the founding bug lived (16228: the timetable said one route, the railway
        // another). When a live identity reached the device, the label says both readings - the overlay
        // is a sentence, never a silent swap, because the stop table below is still the timetable's.
        val labelled = model.copy(
            // The raw enum covers real overrides too. A public station claim must not
            // inherit its generic "LIVE · custom" wording in Info or copied diagnostics.
            provenanceLabel = when {
                claimStop != null -> "RailYatri · comparison only"
                orgStop != null -> "whereismytrain.org.in · website past-card report"
                webPoint != null -> "WIMT web · comparison only"
                else -> model.provenanceLabel
            },
            routeLabel = identityOverlay(route.srcCode, route.dstCode, liveIdentity?.identity)
                ?.let { "${model.routeLabel} · $it" }
                ?: model.routeLabel,
            progressLabel = when {
                orgReport?.intermediate != null ->
                    "Website's last past-card station ${orgReport.intermediate.name} · " +
                        "static map ${orgReport.intermediate.mapKm} km · current position unknown"
                orgStop != null -> "Website's last past-card halt · ${orgStop.schedKm} " +
                    "scheduled km · current position unknown"
                SourceEvidencePolicy.comparisonOnly(mirrorActiveSource) ->
                    "Position unknown · timetable only; compare website separately"
                else -> model.progressLabel
            },
        )
        // THE OWNER'S COLUMNS, FED: the active witness's structured minutes are written INTO the
        // rows - Act under Sch - whenever that witness answered, whether or not the erail chain
        // did. This is the gate paste #28 caught and #29 proved twice more: the mirror answered
        // while the screen kept saying "schedule only". Erail's refusal no longer buries a
        // witness that spoke.
        val selectedMirror = mirrorActiveSource in setOf(RunningStatusMirror.SOURCE,
            ConfirmTktMirror.SOURCE, TrackingSources.WIMT, "last known")
        val overlayStops = if ((freshShift() || mirrorActiveSource == TrackingSources.WIMT) && selectedMirror &&
            (pos.provenance != SourceKind.PROVIDER_NTES || selectedMirrorForTable || mirrorActiveSource == TrackingSources.WIMT))
            mirrorStops[mirrorActiveSource].orEmpty() else emptyList()
        val withActuals = when {
            orgReport != null -> WebsiteReportDisplay.rows(labelled, orgReport)
            overlayStops.isEmpty() -> labelled
            else -> overlayActuals(labelled, overlayStops, nowMinute(now), staticFuture = true)
        }
        // Refreshes must not wipe what the witnesses wrote (paste #24 wiped the reader lines, paste #26
        // wiped the actuals headline): everything the mirror said is re-applied from state while fresh.
        val live = freshShift()
        // No deviation to build a status word from is still not silence: one sentence from the
        // freshest reported halt keeps the top box in the mirror's voice.
        val verified = if (live && overlayStops.isNotEmpty()) mirrorVerdict(withActuals) else null
        val receipt = if (verified != null) deviceReceipt(withActuals) else null
        val ntesActualCount = answer.perStop.values.count {
            it.actualArrivalMin != null || it.actualDepartureMin != null
        }
        val sourceNames = linkedMapOf(
            "NTES (official)" to (if (answer.position.provenance == SourceKind.PROVIDER_NTES && ntesActualCount > 0)
                "live" to "$ntesActualCount stops with actual minutes"
                else if (answer.position.provenance == SourceKind.CACHED && ntesActualCount > 0)
                "last-known" to "$ntesActualCount saved stops; not live"
                else "none" to "no per-stop actuals on this device"),
            RunningStatusMirror.SOURCE to (mirrorSourceStates[RunningStatusMirror.SOURCE]
                ?: ("none" to "not answered yet")),
            TrackingSources.WIMT to (if (manualWimtAnswer != null) {
                val count = manualWimtAnswer?.perStop?.size ?: 0
                "live" to (manualWimtDetail ?: "$count stops reported live")
            } else if (manualWimtDetail != null) {
                "unverified" to manualWimtDetail!!
            } else {
                "none" to "select to check Where Is My Train"
            }),
            ConfirmTktMirror.SOURCE to (mirrorSourceStates[ConfirmTktMirror.SOURCE]
                ?: ("none" to "not answered yet")),
            WimtCandidate.SOURCE to (wimtCandidate?.let {
                val p = it.webPoint
                val at = route.stops.indexOfFirst { stop -> stop.code.equals(p?.precedingHalt, true) }
                val from = route.stops.getOrNull(at)
                val to = route.stops.getOrNull(at + 1)
                val usable = p != null && from != null && to != null &&
                    to.code.equals(p.followingHalt, true) &&
                    from.schedKm >= 0 && to.schedKm > from.schedKm &&
                    p.routeKm in from.schedKm.toDouble()..to.schedKm.toDouble() &&
                    now - p.eventAtMillis in 0L..600_000L &&
                    now - p.cacheAtMillis in 0L..120_000L
                (if (usable) "comparison" else "unverified") to
                    "${it.detail}; web point is not phone-app GPS or a verified stop Act"
            } ?: ("none" to "manual check available; not a verified source")),
            "erail" to (if (manualErail != null) "live" to (manualErailDetail ?: "verified manual result")
                else if (manualErailDetail != null) "unverified" to manualErailDetail!!
                else if (answer.position.provenance == SourceKind.PROVIDER && ntesActualCount > 0)
                "live" to "$ntesActualCount stops with actual minutes"
                else if (liveIdentity != null) "unverified" to "identity only; no actual halt times"
                else "none" to "no per-stop actuals"),
        )
        for (name in PublicPageChecks.names) {
            val result = publicChecks[name]
            sourceNames[name] = if (result == null)
                "none" to (if (name == PublicPageChecks.ORG)
                    "select to check; no unsolicited website request" else "checking on first load") else
                (if (name == PublicPageChecks.ORG && result.orgReport != null &&
                    (!result.pageAnswered || now - result.orgReport.pageUpdatedAt !in 0L..300_000L))
                    "last-known" else if (result.orgReport != null &&
                    now - result.orgReport.pageUpdatedAt in 0L..300_000L)
                    "comparison" else if (result.stationClaim != null &&
                    now - result.stationClaim.updateAt in 0L..300_000L)
                    "comparison" else "unverified") to (if (result.detail.startsWith("Website request paused"))
                    result.detail else result.detail + " · checked " +
                    RailTime.formatDate(RailTime.istEpochDay(result.checkedAt)) + " " +
                    RailTime.formatClock(result.checkedAt) + " IST" +
                    (if (now - result.checkedAt > 300_000L) " (old check; refresh to ask again)" else ""))
        }
        lastOfficialMs?.let { ms ->
            sourceNames["NTES (official)"]?.let { (state, detail) ->
                sourceNames["NTES (official)"] = state to "$detail · checked in ${ms} ms"
            }
        }
        for ((name, ms) in manualProbeMs) {
            val status = sourceNames[name] ?: continue
            sourceNames[name] = status.first to (status.second + " · checked in ${ms} ms")
        }
        val selectable = linkedMapOf<String, Map<String, String>>()
        if ("last known" in mirrorSourceLines) sourceNames["last known"] =
            "last-known" to "cached for this run; not a new observation"
        for (name in sourceNames.keys) selectable[name] = when {
            name == TrackingSources.WIMT -> manualWimtAnswer?.perStop?.mapValues {
                val dep = it.value.actualDepartureMin?.let { m -> "dep " + RailTime.formatRailMinuteBare(m) }
                val arr = it.value.actualArrivalMin?.let { m -> "arr " + RailTime.formatRailMinuteBare(m) }
                val pf = it.value.platform?.let { p -> "PF $p" }
                val delay = it.value.delayDepartureMin ?: it.value.delayArrivalMin
                val dStr = delay?.let { d -> if (d > 0) "+${d}m" else if (d < 0) "${d}m" else "on time" }
                listOfNotNull(arr, dep, dStr, pf).joinToString(" · ")
            }.orEmpty()
            name == WimtCandidate.SOURCE || name in PublicPageChecks.names -> emptyMap()
            else -> mirrorSourceLines[name].orEmpty()
        }
        val selected = mirrorActiveSource ?: if (answer.position.provenance == SourceKind.PROVIDER)
            "erail" else "NTES (official)"
        val lastReported = withActuals.rows.lastOrNull { it.source == TrackModel.MinuteSource.REPORTED }
        val nextRow = lastReported?.let { r -> withActuals.rows.getOrNull(withActuals.rows.indexOf(r) + 1) }
        val nextStatement = nextRow?.let { r ->
            "Next scheduled: ${r.name}" +
                ((r.schedArr ?: r.schedDep)?.let { " · ${RailTime.formatRailMinute(it)}" } ?: "") +
                " · arrival not confirmed"
        }
        val offline = historicalCorridorJson?.let { OfflineCorridorMap.parse(it, route) }
        val externalCandidate = listOfNotNull(geoRoute, confirmStaticRoute)
            .filter { candidate ->
                candidate.srcCode.equals(route.srcCode, true) &&
                    candidate.dstCode.equals(route.dstCode, true) &&
                    route.stops.map { it.code.uppercase() }.let { required ->
                        candidate.stops.map { it.code.uppercase() }.filter { it in required } == required
                    }
            }.maxByOrNull { it.stops.size }
        // A currently dated full map wins. The historical corridor is an offline
        // fallback checked against each consecutive pair of today's scheduled halts.
        val staticCandidate = if (staticMapMatchesLiveIdentity(route))
            externalCandidate ?: offline?.route else null
        val staticRoute = staticCandidate?.let { candidate ->
            if (officialStaticKnown) RouteBetween.withVerifiedHalts(candidate, route) ?: candidate
            else candidate
        }
        val alternateSiteHint = if (selected == "NTES (official)" &&
            answer.position.provenance == SourceKind.PROVIDER_NTES) {
            val page = publicChecks[PublicPageChecks.ORG]?.orgReport
                ?.takeIf { now - it.pageUpdatedAt in 0L..300_000L }
            val official = withActuals.rows.lastOrNull {
                it.source == TrackModel.MinuteSource.REPORTED &&
                    (it.showArr != null || it.showDep != null)
            }
            val siteIndex = page?.let { p -> route.stops.indexOfFirst {
                it.code.equals(p.intermediate?.precedingHalt ?: p.anchorCode, true)
            } } ?: -1
            val officialIndex = official?.let { r -> route.stops.indexOfFirst {
                it.code.equals(r.code, true)
            } } ?: -1
            if (page != null && SourceRecency.showComparison(SourceRecency.progressOrdinal(officialIndex, false),
                    SourceRecency.progressOrdinal(siteIndex, page.intermediate != null),
                    officialStopAgeMillis() ?: 0L, now - page.pageUpdatedAt,
                    now - RailTime.railMinuteToEpochMillis(originDate, page.eventMinute))) {
                val place = page.intermediate?.name ?: route.stops[siteIndex].name
                "Website reports ${RowText.of(place)} at " +
                    "${RailTime.formatRailMinute(page.eventMinute)} · unverified; tap to view its report."
            } else null
        } else null
        return Ui(
            // Keep the original official answer in state. The selected source's table-only
            // anchor must NEVER become the next refresh's official position or cross-check.
            route = route, answer = answer, model = withActuals,
            boarding = withActuals.rows.firstOrNull { it.isBoarding },
            alighting = withActuals.rows.firstOrNull { it.isAlighting },
            nowRailMinute = nowRail, tried = tried, loading = false,
            reports = selectable[mirrorActiveSource] ?: _ui.value?.reports ?: emptyMap(),
            relayOk = _ui.value?.relayOk ?: true,
            anchorCode = if (orgReport != null) orgReport.intermediate?.precedingHalt
                ?: orgReport.anchorCode
                else if (mirrorActiveSource == TrackingSources.WIMT && manualWimtAnswer != null)
                    manualWimtAnswer?.anchorStopCode ?: lastReported?.code
                else if (verified != null) lastReported?.code else null,
            actualsHeadline = when {
                mirrorActiveSource == TrackingSources.WIMT && manualWimtAnswer != null -> {
                    val inter = manualWimtIntermediate
                    val delay = (inter?.delayMin ?: manualWimtAnswer?.position?.delayMinutes) ?: 0
                    val delayStr = if (delay > 0) "+${delay}m late" else if (delay < 0) "${delay}m early" else "on time"
                    if (inter != null) {
                        "Last WIMT report: Near ${inter.name} · $delayStr"
                    } else {
                        val statusWord = if (manualWimtAnswer?.position?.status == StopStatus.DEPARTED) "Departed" else "Arrived at"
                        val anchor = manualWimtAnswer?.anchorStopName ?: manualWimtAnswer?.anchorStopCode ?: "last reported stop"
                        "Last WIMT report: $statusWord $anchor · $delayStr"
                    }
                }
                verified != null -> verified.first.let { if (selected == "last known")
                    "Last known at ${clockAt(mirrorRefreshedAt)} · $it" else it }
                else -> null
            },
            actualsNext = if (mirrorActiveSource == TrackingSources.WIMT && manualWimtAnswer != null) nextStatement
                else if (verified != null) nextStatement else null,
            actualsBadge = when {
                mirrorActiveSource == TrackingSources.WIMT && manualWimtAnswer != null -> "LIVE · Where Is My Train"
                verified != null -> if (selected == "last known") "CACHED · $selected" else "VERIFIED · $selected"
                else -> null
            },
            actualsLate = if (mirrorActiveSource == TrackingSources.WIMT && manualWimtAnswer != null)
                manualWimtIntermediate?.delayMin ?: manualWimtAnswer?.position?.delayMinutes
                else receipt?.first,
            sourcesLine = if (mirrorActiveSource == TrackingSources.WIMT && manualWimtAnswer != null)
                manualWimtDetail ?: "Where Is My Train live feed"
                else if (verified != null) mirrorSources else null,
            sourceLines = selectable,
            activeSource = selected,
            actualsSource = if (mirrorActiveSource == TrackingSources.WIMT && manualWimtAnswer != null)
                TrackingSources.WIMT
                else if (verified != null) selected else null,
            sourceStates = sourceNames,
            officialRequest = lastOfficialRequest,
            publicSourceAnswered = publicChecks.entries.associate { it.key to it.value.pageAnswered },
            selectedLoadReceiptMillis = when {
                selected in PublicPageChecks.names -> publicChecks[selected]
                    ?.takeIf { it.pageAnswered }?.checkedAt
                selected == "NTES (official)" &&
                    lastOfficialRequest?.kind == OfficialRequestReceipt.Kind.ANSWERED_WITH_ACTUALS ->
                    lastFetchAt.takeIf { it > 0 }
                selected == "last known" -> null
                verified != null -> mirrorRefreshedAt.takeIf { it > 0 }
                else -> null
            },
            selectedSourceUpdateMillis = when (selected) {
                TrackingSources.WIMT -> manualWimtAnswer?.position?.feedStampMillis
                "NTES (official)" -> if (lastOfficialRequest?.kind ==
                    OfficialRequestReceipt.Kind.ANSWERED_WITH_ACTUALS)
                    answer.position.feedStampMillis else null
                PublicPageChecks.ORG -> orgReport?.pageUpdatedAt
                PublicPageChecks.RAIL_YATRI -> railClaim?.updateAt
                WimtCandidate.SOURCE -> webPoint?.cacheAtMillis
                else -> null // mirror refresh clock is ours, not its publication clock
            },
            selectedSourceUpdateHasSeconds = when (selected) {
                TrackingSources.WIMT -> true
                PublicPageChecks.ORG -> orgReport?.pageClockHasSeconds == true
                WimtCandidate.SOURCE -> webPoint != null // ISO web cache stamp includes seconds
                else -> false // NTES parser does not retain the raw precision
            },
            lastUpdated = (mirrorRefreshedAt.takeIf { it > 0 } ?: lastFetchAt.takeIf { it > 0 })
                ?.let { (if (verified != null) "Verified " else "Last checked ") + clockAt(it) },
            delayDetail = receipt?.second,
            staticRoute = staticRoute,
            staticRouteSource = when (staticCandidate) {
                geoRoute -> if (staticCandidate == null) null else "RailGeo"
                confirmStaticRoute -> if (staticCandidate == null) null else "ConfirmTkt"
                offline?.route -> OfflineCorridorMap.SOURCE
                else -> null
            },
            staticMappedLegStarts = if (staticCandidate == null) emptySet()
                else if (externalCandidate != null) route.stops.take(route.stops.size - 1)
                .map { it.code.uppercase() }.toSet() else offline?.mappedLegStarts.orEmpty(),
            wimtWebPoint = webPoint,
            railYatriClaim = railClaim,
            orgWebsiteReport = orgReport,
            orgWebsiteComparisonReport = oldWebsiteComparison,
            orgWebsiteDistanceProbe = publicChecks[PublicPageChecks.ORG]?.orgDistanceProbe
                ?.takeIf { selected == PublicPageChecks.ORG },
            alternateSiteHint = alternateSiteHint,
            websiteGap = when (selected) {
                PublicPageChecks.ORG -> publicChecks[PublicPageChecks.ORG]?.orgQualificationNote
                    ?: PublicPageChecks.websiteGap(publicChecks[PublicPageChecks.ORG], route.dstCode)
                "NTES (official)" ->
                    PublicPageChecks.websiteGap(publicChecks[PublicPageChecks.ORG], route.dstCode)
                else -> null
            },
            wimtIntermediate = if (mirrorActiveSource == TrackingSources.WIMT) manualWimtIntermediate else null,
        )
    }

    /**
     * Which rows the list should carry, given the screen's "my segment / full route" toggle.
     *
     * A 950-halt passenger train costs a layout pass per bound row, and nobody scrolls 950 rows to find
     * themselves in one, so the window is bounded at [MAX_ROWS] and centred on the anchor when a position
     * exists (otherwise on the boarding stop). The caller prints what was hidden, which is the difference
     * between a bounded list and a truncated one.
     */
    fun windowFor(rows: List<TrackModel.Stop>, anchorSeq: Int, fullRoute: Boolean): IntRange {
        val base = if (fullRoute) 0 until rows.size else segmentRange(rows)
        if (base.last - base.first + 1 <= MAX_ROWS) return base
        val centre = rows.indexOfFirst { it.seq == anchorSeq }.takeIf { it >= 0 }
            ?: rows.indexOfFirst { it.isBoarding }.takeIf { it >= 0 }
            ?: (base.first + base.last) / 2
        val half = MAX_ROWS / 2
        val lo = (centre - half).coerceIn(0, (rows.size - MAX_ROWS).coerceAtLeast(0))
        return lo until (lo + MAX_ROWS)
    }

    private fun segmentRange(rows: List<TrackModel.Stop>): IntRange {
        val b = rows.indexOfFirst { it.isBoarding }
        val a = rows.indexOfLast { it.isAlighting }
        return if (b in 0..a) b..a else 0 until (rows.size - 1).coerceAtLeast(0)
    }

    companion object {
        /** Enough rows to read a real route without a stutter on a five-year-old phone. */
        const val MAX_ROWS = 140

        /** The researched spec's 60-120 s cache window, midpoint adopted for relay reads. */
        const val CROWD_POLL_MIN_MILLIS = 90_000L

        /**
         * What the live identity adds to the timetable's route label, as a pure function (the screen
         * computes nothing, 8.2). Null: no identity reached the device - the label stays. Match: the
         * confirmation is stated, not implied. Mismatch: BOTH readings in one line, and the table is
         * named as the timetable's - the label may be corrected but the times below it are not, not
         * until a real per-stop source exists (T20.1's paste, 17.1's ladder).
         */
        /**
         * The two-stop route a live-only train can honestly render: origin departure and terminus
         * arrival from the erail tile, the day wrap folded into the terminus stop's journeyDay.
         * Nothing in it claims per-stop knowledge the source never gave.
         */
        internal fun syntheticRoute(live: ErailLiveIdentity): TrainRoute = TrainRoute(
            trainNo = live.identity.number,
            trainName = live.identity.name,
            srcCode = live.identity.fromCode,
            srcName = live.identity.fromCode,
            dstCode = live.identity.toCode,
            dstName = live.identity.toCode,
            totalKm = 0,
            totalMin = null,
            runDays = null,
            returnNo = null,
            stops = listOf(
                StopRow(
                    seq = 1, code = live.identity.fromCode, name = live.identity.fromCode,
                    state = null, lat = null, lon = null,
                    arrivalMin = null, departureMin = live.depMinute,
                    journeyDay = 0, schedKm = 0, isHalt = true,
                ),
                StopRow(
                    seq = 2, code = live.identity.toCode, name = live.identity.toCode,
                    state = null, lat = null, lon = null,
                    arrivalMin = live.arrMinute, departureMin = null,
                    journeyDay = live.alightDayOffset, schedKm = 0, isHalt = true,
                ),
            ),
            segments = emptyList(),
        )

        private fun slip(actual: Int, scheduled: Int): Int =
            Math.floorMod(actual - scheduled + 720, 1440) - 720

        /** Refuse a clock-only token that is hours before its own displayed stop, ahead of now,
         * before the run began, or out of route order. Never wrap an implausible -237 min into a
         * seemingly valid delay, and never use the feed's separate schedule as the baseline. */
        internal fun validateStops(
            model: TrackModel.View, stops: List<RunningStatusMirror.Stop>, nowRail: Int,
        ): List<RunningStatusMirror.Stop> {
            val byCode = stops.associateBy { it.stationCode.uppercase() }
            val origin = model.rows.firstOrNull()?.schedDep ?: model.rows.firstOrNull()?.schedArr
            var lastMinute = origin?.minus(10) ?: Int.MIN_VALUE
            return model.rows.mapNotNull { row ->
                val st = byCode[row.code.uppercase()] ?: return@mapNotNull null
                fun accept(clock: Int?, sched: Int?): Int? {
                    if (clock == null || sched == null) return null
                    val shown = if (clock >= 1440) clock else RailTime.alignClock(clock, sched) ?: return null
                    val offset = shown - sched
                    return shown.takeIf { offset in -10..180 && shown <= nowRail + 2 &&
                        (origin == null || shown >= origin - 10) && shown >= lastMinute - 2 }
                }
                val arr = accept(st.actArr, row.schedArr)
                if (arr != null) lastMinute = arr
                val dep = accept(st.actDep, row.schedDep)
                if (dep != null) lastMinute = dep
                // Extensions without a timetable can carry a *labelled estimate*, not a confirmed Act.
                val extension = row.schedArr == null && row.schedDep == null
                st.copy(actArr = arr ?: if (extension) st.actArr else null,
                    actDep = dep ?: if (extension) st.actDep else null)
            }
        }

        /** Suppress a row when two plausible past timestamps explicitly disagree; neither witness
         * wins by ordering. Missing or implausible page times are NOT counter-evidence. */
        internal fun conflictingCodes(
            primary: List<RunningStatusMirror.Stop>, candidate: List<RunningStatusMirror.Stop>,
            model: TrackModel.View, nowRail: Int,
        ): Set<String> {
            val second = validateStops(model, candidate, nowRail)
                .associateBy { it.stationCode.uppercase() }
            return primary.filter { first ->
                val other = second[first.stationCode.uppercase()] ?: return@filter false
                (first.actArr != null && other.actArr != null &&
                    kotlin.math.abs(slip(first.actArr, other.actArr)) > 5) ||
                    (first.actDep != null && other.actDep != null &&
                        kotlin.math.abs(slip(first.actDep, other.actDep)) > 5)
            }.map { it.stationCode.uppercase() }.toSet()
        }

        /** Official same-run per-stop ACTUALS may corroborate overlapping page cells, never
         * unobserved page rows. Cached/offline/erail data cannot authenticate ConfirmTkt.
         * No clock is inferred from delay or schedule. validateStops gates future and order. */
        internal fun officialStopsForCrossCheck(
            answer: PositionEngine.Answer, model: TrackModel.View, nowRail: Int,
        ): List<RunningStatusMirror.Stop> {
            if (answer.position.provenance != SourceKind.PROVIDER_NTES) return emptyList()
            return validateStops(model, answer.perStop.map { (code, cell) ->
                RunningStatusMirror.Stop(code, null, null,
                    cell.actualArrivalMin, cell.actualDepartureMin)
            }, nowRail).filter { it.actArr != null || it.actDep != null }
        }

        /** The second page is not independent proof merely because it returned HTML. */
        internal fun crossCheckedStops(
            primary: List<RunningStatusMirror.Stop>, candidate: List<RunningStatusMirror.Stop>,
            model: TrackModel.View, nowRail: Int,
        ): List<RunningStatusMirror.Stop> {
            val clean = validateStops(model, candidate, nowRail)
            val byCode = primary.associateBy { it.stationCode.uppercase() }
            return clean.mapNotNull { st ->
                val first = byCode[st.stationCode.uppercase()] ?: return@mapNotNull null
                val arr = st.actArr?.takeIf { a -> first.actArr != null &&
                    kotlin.math.abs(slip(a, first.actArr)) <= 5 }
                val dep = st.actDep?.takeIf { d -> first.actDep != null &&
                    kotlin.math.abs(slip(d, first.actDep)) <= 5 }
                if (arr == null && dep == null) null else st.copy(actArr = arr, actDep = dep)
            }
        }

        private fun deviceReceipt(model: TrackModel.View): Pair<Int, String>? {
            val last = model.rows.lastOrNull { it.source == TrackModel.MinuteSource.REPORTED &&
                ((it.showDep != null && it.schedDep != null) ||
                    (it.showArr != null && it.schedArr != null)) } ?: return null
            val useDep = last.showDep != null && last.schedDep != null
            val shown = if (useDep) last.showDep!! else last.showArr!!
            val sched = if (useDep) last.schedDep!! else last.schedArr!!
            return slip(shown, sched) to
                "${last.code} ${RailTime.formatRailMinuteBare(sched)} → ${RailTime.formatRailMinuteBare(shown)}"
        }

        /** Only a VERIFIED stop can move an estimate. Propagate forward, never backwards from a
         * later halt; hide an ETA already behind the device clock instead of printing time travel. */
        internal fun overlayActuals(
            model: TrackModel.View, stops: List<RunningStatusMirror.Stop>, nowRail: Int,
            staticFuture: Boolean = false,
        ): TrackModel.View {
            val byCode = validateStops(model, stops, nowRail).associateBy { it.stationCode.uppercase() }
            // Project only the single next station after the final verified stop.
            val lastVerified = model.rows.indexOfLast { r -> byCode[r.code.uppercase()]?.let { st ->
                st.actArr != null || st.actDep != null } == true }
            val nextSeq = model.rows.getOrNull(lastVerified + 1)?.seq
            var carried: Int? = null
            val origin = model.rows.firstOrNull()?.schedDep ?: model.rows.firstOrNull()?.schedArr
            return model.copy(rows = model.rows.map { row ->
                val st = byCode[row.code.uppercase()]
                if (row.schedArr == null && row.schedDep == null) {
                    return@map if (st?.actArr != null || st?.actDep != null) row.copy(
                        showArr = st?.actArr, showDep = st?.actDep,
                        source = TrackModel.MinuteSource.PROJECTED) else row
                }
                val arr = st?.actArr?.takeIf { row.schedArr != null }
                val dep = st?.actDep?.takeIf { row.schedDep != null }
                if (arr != null || dep != null) {
                    val actual = if (dep != null && row.schedDep != null) dep to row.schedDep
                        else arr!! to row.schedArr!!
                    carried = slip(actual.first, actual.second)
                    return@map row.copy(showArr = arr, showDep = dep, delay = carried,
                        source = TrackModel.MinuteSource.REPORTED)
                }
                if (row.source == TrackModel.MinuteSource.REPORTED) return@map row
                // Position-only view: the remaining journey is the timetable, not a
                // delay-propagated forecast from an unverified page.
                if (staticFuture) return@map row.copy(
                    showArr = row.schedArr, showDep = row.schedDep,
                    source = TrackModel.MinuteSource.SCHEDULE, delay = null)
                val offset = carried?.takeIf { row.seq == nextSeq }
                val arrEst = if (offset == null) null else row.schedArr?.plus(offset)
                val depEst = if (offset == null) null else row.schedDep?.plus(offset)
                val pivot = arrEst ?: depEst
                if (pivot != null && pivot >= nowRail - 2 && (origin == null || pivot >= origin - 10))
                    row.copy(showArr = arrEst, showDep = depEst, delay = offset,
                        source = TrackModel.MinuteSource.PROJECTED)
                else row.copy(source = TrackModel.MinuteSource.SCHEDULE, delay = null)
            })
        }

        /**
         * The top box's sentence when the mirror answered but nothing deviated yet: the freshest
         * REPORTED halt, its minute, and its slip against the displayed timetable. Null when no row
         * carries an observed minute - the screen then says plainly that no halt is reported yet.
         */
        internal fun mirrorVerdict(model: TrackModel.View): Pair<String, Int>? {
            val last = model.rows.lastOrNull { it.source == TrackModel.MinuteSource.REPORTED &&
                (it.showDep != null || it.showArr != null) } ?: return null
            val dep = last.showDep != null && last.schedDep != null
            val actual = if (dep) last.showDep!! else last.showArr ?: return null
            val schedule = if (dep) last.schedDep!! else last.schedArr ?: return null
            val d = slip(actual, schedule)
            val word = when {
                d > 2 -> "$d min late"
                d < -2 -> "${-d} min early"
                else -> "on time"
            }
            return "Last verified: ${if (dep) "left" else "arrived at"} ${last.code} at " +
                RailTime.formatRailMinuteBare(actual) + " · $word" to d
        }

        /**
         * The device halts must appear in the page's list, IN ORDER, or the merge is refused - a
         * route is never guessed. Device rows keep their seqs (the position answer stays valid);
         * added rows take seqs outside the device band, no minutes, no km.
         */
        internal fun mergeRouteExtents(base: TrainRoute, halts: List<ErailRouteMirror.Halt>): TrainRoute? {
            val baseCodes = base.stops.map { it.code.uppercase() }
            val pageCodes = halts.map { it.code.uppercase() }
            var at = -1
            loop@ for (off in 0..pageCodes.size - baseCodes.size) {
                for (i in baseCodes.indices) {
                    if (pageCodes[off + i] != baseCodes[i]) continue@loop
                }
                at = off
                break
            }
            if (at < 0) return null
            val before = halts.subList(0, at)
            val after = halts.subList(at + baseCodes.size, halts.size)
            val pre = before.mapIndexed { i, h ->
                StopRow(
                    seq = i - before.size, code = h.code, name = h.name, state = null,
                    lat = null, lon = null, arrivalMin = null, departureMin = null,
                    journeyDay = 0, schedKm = -1, isHalt = true,
                )
            }
            val post = after.mapIndexed { i, h ->
                StopRow(
                    seq = base.stops.last().seq + 1 + i, code = h.code, name = h.name, state = null,
                    lat = null, lon = null, arrivalMin = null, departureMin = null,
                    journeyDay = 0, schedKm = -1, isHalt = true,
                )
            }
            val stops = pre + base.stops + post
            return base.copy(
                stops = stops,
                srcCode = stops.first().code, srcName = stops.first().name,
                dstCode = stops.last().code, dstName = stops.last().name,
            )
        }
        /** The sentence under the dead-end's replacement: what we know, and exactly what we do not. */
        internal fun fallbackNote(live: ErailLiveIdentity, searchedFrom: String, searchedTo: String): String {
            val base = "This train is not in the device's offline timetable — the two times are the live " +
                "source's origin→terminus times (via erail); per-stop halts are not available on this device."
            val pairMatches = live.identity.fromCode.equals(searchedFrom, true) &&
                live.identity.toCode.equals(searchedTo, true)
            return if (pairMatches) base
            else "the live source says this number runs ${live.identity.fromCode} → ${live.identity.toCode}, " +
                "not $searchedFrom → $searchedTo. " + base
        }

        internal fun identityOverlay(srcCode: String, dstCode: String, id: TrainIdentity?): String? = when {
            id == null -> null
            id.fromCode.equals(srcCode, ignoreCase = true) &&
                id.toCode.equals(dstCode, ignoreCase = true) ->
                "identity confirmed live (via erail)"
            else ->
                "the live source says this number runs ${id.fromCode} → ${id.toCode} (via erail); " +
                    "this device's timetable says $srcCode → $dstCode — the stop table below is the timetable's"
        }
    }
}
