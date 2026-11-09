package `in`.opentrack.india.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import `in`.opentrack.india.App
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.data.RailRepository
import `in`.opentrack.india.domain.TrainFinder
import `in`.opentrack.india.model.TrainHit
import `in`.opentrack.india.model.TrainSummary
import `in`.opentrack.india.v2.contract.Provenance
import `in`.opentrack.india.v2.contract.SourceKind
import `in`.opentrack.india.v2.domain.AssetRow
import `in`.opentrack.india.v2.domain.PairCache
import `in`.opentrack.india.v2.domain.PairMerge
import `in`.opentrack.india.v2.source.ErailPairParser
import `in`.opentrack.india.v2.source.ErailTransport
import `in`.opentrack.india.v2.source.PairAnswer
import `in`.opentrack.india.v2.source.PairQuery
import `in`.opentrack.india.v2.source.PairSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import `in`.opentrack.india.model.StationHit

/**
 * Home-screen state: pick a mode, pick a date, get the runs.
 *
 * Kept as a plain ViewModel with LiveData instead of a Flow/state-machine because there are exactly
 * two async operations here (prepare the database, run a search) and anything more elaborate would be
 * architecture theatre in a 5 MB app.
 */
class MainViewModel(private val app: App) : ViewModel() {

    enum class Mode { STATIONS, TRAIN }

    private val _phase = MutableLiveData<Phase>(Phase.Preparing(0, 0))
    val phase: LiveData<Phase> = _phase

    private val _results = MutableLiveData<TrainFinder.Report?>(null)
    val results: LiveData<TrainFinder.Report?> = _results

    private val _trainQuery = MutableLiveData<String>("")
    val trainQuery: LiveData<String> = _trainQuery

    /**
     * The v2 live-list state line, or null while nothing live has been said this search. Rendered as
     * the suffix of the results header - the 8.2 sentence the screen owes the passenger.
     */
    private val _liveNote = MutableLiveData<String?>(null)
    val liveNote: LiveData<String?> = _liveNote

    /** TTL cache for live pair listings (v2). A read from here is "cached", never "live". */
    private val pairCache = PairCache()

    // A slow pair response belongs only to the query that asked for it. Cancelling saves
    // the next request's bandwidth; the serial also guards callbacks that finish despite
    // cancellation (and the asynchronous LiveData delivery boundary).
    @Volatile private var searchSerial = 0L
    private var searchJob: Job? = null
    private var pairJob: Job? = null
    private var installedDataVersion: String? = null

    private fun invalidateSearch(): Long {
        searchJob?.cancel()
        pairJob?.cancel()
        return ++searchSerial
    }

    var mode = Mode.STATIONS
        private set

    /**
     * Selected instant. Defaults to *now* rather than midnight: "trains for the next 24 hours" is the
     * question a traveller asks, and a midnight default makes the -24h half of the window return
     * yesterday's trains first, which reads as a bug.
     */
    var targetEpochDay: Long = RailTime.todayEpochDay()
        private set
    var targetMinuteOfDay: Int = RailTime.istMinuteOfDay(System.currentTimeMillis())
        private set

    /**
     * The instant the search was run against, in minutes since the IST epoch. The result rows print
     * "in 40 min" beside the departure, and that has to be relative to the day the user picked — a
     * search for tomorrow must not label tomorrow's 06:00 as "gone 20 hours ago".
     */
    val targetEpochMinute: Long get() = targetEpochDay * RailTime.MINUTES_PER_DAY + targetMinuteOfDay

    sealed class Phase {
        data class Preparing(val done: Long, val total: Long) : Phase()
        data class Ready(val dataVersion: String) : Phase()
        data class Broken(val message: String) : Phase()
        object Searching : Phase()
    }

    fun prepare() {
        if (_phase.value is Phase.Ready || _phase.value is Phase.Searching) return
        viewModelScope.launch {
            val repo = ready { done, total -> _phase.postValue(Phase.Preparing(done, total)) }.await()
            val version = repo?.dataVersion()
            installedDataVersion = version
            _phase.postValue(
                if (version != null) Phase.Ready(version)
                else Phase.Broken(
                    "The built-in timetable could not be installed on this device. " +
                        "Reinstall the app, or import a timetable file from Settings."
                )
            )
        }
    }

    fun setMode(mode: Mode) {
        invalidateSearch()
        this.mode = mode
        _results.value = null
        if (_phase.value is Phase.Searching) installedDataVersion?.let {
            _phase.value = Phase.Ready(it)
        }
    }

    fun setDate(epochDay: Long, minuteOfDay: Int) {
        invalidateSearch()
        targetEpochDay = epochDay
        targetMinuteOfDay = minuteOfDay
        if (_phase.value is Phase.Searching) installedDataVersion?.let {
            _phase.value = Phase.Ready(it)
        }
    }

    fun setTrainQuery(text: String) {
        _trainQuery.value = text
    }

    fun search(fromText: String, toText: String) {
        if (_phase.value !is Phase.Ready && _phase.value !is Phase.Searching) return
        val serial = invalidateSearch()
        val requestedMode = mode
        val requestedDay = targetEpochDay
        val requestedMinute = targetMinuteOfDay
        _phase.value = Phase.Searching
        _liveNote.value = null // a new search starts with no live claim at all
        searchJob = viewModelScope.launch {
            val repo = app.repository() ?: run {
                if (PairSearchGate.current(serial, searchSerial))
                    _phase.value = Phase.Broken("Timetable is not installed yet.")
                return@launch
            }
            val report = when (requestedMode) {
                Mode.STATIONS -> repo.search(fromText, toText, requestedDay, requestedMinute)
                Mode.TRAIN -> trainReport(repo, toText)
            }
            if (!PairSearchGate.current(serial, searchSerial)) return@launch
            _results.value = report
            val version = repo.dataVersion()
            installedDataVersion = version
            _phase.value = Phase.Ready(version)
            // Do not fetch a live pair for an impossible trip. TrainFinder already
            // refused it with zero rows; an unrelated pair reply must never replace
            // that refusal with train rows. Ask with RESOLVED codes, not fuzzy input.
            if (requestedMode == Mode.STATIONS && PairSearchGate.eligible(report)) {
                pairJob = fetchLivePair(report.from!!.code, report.to!!.code, report, serial)
            }
        }
    }

    /**
     * One live erail pair attempt, merged over the asset rows, every row labelled.
     *
     * This lives in this v1 file under the legacy freeze only because the results screen it upgrades
     * is v1's (the strangler replaces screens one at a time): merge, cache and typed answers are all
     * v2 (`in.opentrack.india.v2.*`), and this is the seam that feeds them to the existing adapter.
     * Cache first (a fresh cached listing renders instantly, honestly aged by the note), then at most
     * ONE network attempt, typed failure, no retries.
     */
    private fun fetchLivePair(fromCode: String, toCode: String, assetReport: TrainFinder.Report,
                              serial: Long): Job = viewModelScope.launch(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = pairCache.get(fromCode, toCode, now)
        if (cached != null) {
            applyLive(assetReport, PairAnswer.Listing(cached.listing), now, serial, fromCache = true)
            return@launch
        }
        val source = PairSource("erail", ErailTransport(), ErailPairParser())
        val answer = try {
            source.search(PairQuery(fromCode.uppercase(), toCode.uppercase(), budgetMs = 12_000))
        } catch (e: CancellationException) {
            throw e // do not turn an obsolete request into a displayed fallback
        } catch (e: Exception) {
            // 5.4: no silent catch - the failure becomes the sentence the passenger reads.
            PairAnswer.CouldNotLook("unexpected ${e.javaClass.simpleName}")
        }
        if (!PairSearchGate.current(serial, searchSerial)) return@launch
        if (answer is PairAnswer.Listing) {
            pairCache.put(answer.listing, System.currentTimeMillis())
        }
        applyLive(assetReport, answer, System.currentTimeMillis(), serial)
    }

    /** Merge the live answer into the asset report and post it, rows labelled, state line attached. */
    private suspend fun applyLive(assetReport: TrainFinder.Report, answer: PairAnswer, nowMillis: Long,
                                  serial: Long, fromCache: Boolean = false) {
        if (!PairSearchGate.current(serial, searchSerial)) return
        val assetRows = assetReport.hits.map { h ->
            AssetRow(
                h.trainNo, h.trainName, h.fromCode, h.toCode,
                h.boardMinute, h.alightMinute,
                Provenance(SourceKind.OFFLINE_ASSET, "refdb", 0L),
            )
        }
        val merged0 = PairMerge.merge(assetRows, answer, targetEpochMinute)
        // One train shown twice at the same minute (16579 beside 20689 at 09:15, the owner's
        // screenshots of 2026-10-12) is a renumber the offline copy never learned. Ask the live
        // source about collision candidates - at most two asks, only on the fresh path, only when an
        // offline-only row departs at a live-only row's exact minute - and merge on the source's OWN
        // name answer, never a similarity guess (the decision lives in PairMerge.dedupeRenumbered).
        var appliedRenumbers: List<PairMerge.Renumber> = emptyList()
        val merged = if (answer is PairAnswer.Listing && !fromCache) {
            val offlineOnly = merged0.rows.filter { !it.confirmedByLive && !it.liveOnly }
            val liveOnly = merged0.rows.filter { it.liveOnly }
            val evidence = ArrayList<PairMerge.Renumber>(1)
            for (a in offlineOnly) {
                if (evidence.size >= 2) break
                val board = a.boardMin ?: continue
                val twin = liveOnly.firstOrNull {
                    it.boardMin == board && it.identity.number != a.identity.number
                } ?: continue
                val id = runCatching { erailIdentityFetch(a.identity.number) }.getOrNull() ?: continue
                if (id.identity.name.equals(twin.identity.name, true) &&
                    id.identity.fromCode.equals(twin.identity.fromCode, true) &&
                    id.identity.toCode.equals(twin.identity.toCode, true)
                ) {
                    evidence.add(PairMerge.Renumber(a.identity.number, twin.identity.number, twin.identity.name))
                }
            }
            val (rows, ap) = PairMerge.dedupeRenumbered(merged0.rows, evidence)
            appliedRenumbers = ap
            merged0.copy(rows = rows)
        } else {
            merged0
        }
        val (hits, hidden) = PairMerge.toTrainHits(merged.rows, targetEpochMinute) { board ->
            pickOrigin(targetEpochMinute, board)
        }
        // A dated pair listing can correct times/identity, not the weekly operating
        // calendar. Preserve the offline mask for a matching train number; do not
        // infer seven running days from one dated live appearance.
        val hitsWithDays = TrainResultsDisplay.withAssetDays(hits, assetReport.hits)
        var line = merged.stateLine
        if (hidden > 0) line += " · $hidden train(s) listed without a readable time"
        if (appliedRenumbers.isNotEmpty()) line += " · " + PairMerge.renumberNote(appliedRenumbers)
        if (fromCache) {
            // A cache read is never live (7.5): the sentence says so, with the age.
            val fetchedAt = (answer as? PairAnswer.Listing)?.listing?.provenance?.fetchedAtMillis
                ?: nowMillis
            val ageMin = ((nowMillis - fetchedAt) / 60_000L).coerceAtLeast(0L)
            line += if (ageMin <= 0L) " · served from cache" else " · cached, fetched $ageMin min ago"
        }
        withContext(Dispatchers.Main.immediate) {
            // postValue can race a new query even after a worker checked its serial.
            // Publish on Main only after checking again, atomically with the UI state.
            if (PairSearchGate.current(serial, searchSerial)) {
                _results.value = assetReport.copy(hits = hitsWithDays)
                _liveNote.value = line
            }
        }
    }

    /**
     * Train-number/name mode: resolve the train from the offline timetable, then attach its own
     * origin→destination leg so the row is as informative as a station-pair result. The user gets the
     * same tap-through to live tracking either way.
     */
    private suspend fun trainReport(repo: RailRepository, query: String): TrainFinder.Report {
        val summaries = repo.queries.findTrains(query)
        val hits = ArrayList<TrainHit>(summaries.size)
        for (sm in summaries) {
            val route = repo.queries.route(sm.no) ?: continue
            val first = route.stops.firstOrNull()
            val last = route.stops.lastOrNull()
            if (first == null || last == null) continue
            val board = first.departureMin ?: first.arrivalMin ?: 0
            val alight = last.arrivalMin ?: last.departureMin ?: board
            // "for -24..+24 h" still applies in number mode: pick the run nearest the target instant.
            val target = targetEpochDay * RailTime.MINUTES_PER_DAY + targetMinuteOfDay
            val origin = pickOrigin(target, board)
            if (!RailTime.runsOn(sm.runDays, RailTime.istWeekdayOfEpochDay(origin))) continue
            hits += TrainHit(
                trainNo = sm.no, trainName = sm.name, trainType = sm.type,
                originDateEpochDay = origin, boardMinute = board, alightMinute = alight,
                fromCode = route.srcCode, toCode = route.dstCode,
                fromSeq = first.seq, toSeq = last.seq,
                distanceKm = (last.schedKm - first.schedKm).coerceAtLeast(0),
                runDays = sm.runDays,
            )
        }
        return TrainFinder.Report(
            from = null, to = null, hits = hits,
            reason = if (hits.isEmpty()) TrainFinder.Reason.NO_DIRECT_TRAINS else TrainFinder.Reason.OK,
        )
    }

    /** Origin date whose boarding time is closest to the target inside the ±24 h window. */
    private fun pickOrigin(targetEpochMinute: Long, boardMinute: Int): Long {
        val base = targetEpochDay - Math.floorDiv(boardMinute.toLong(), RailTime.MINUTES_PER_DAY.toLong())
        var best = base
        var bestDelta = Long.MAX_VALUE
        for (delta in -1L..1L) {
            val origin = base + delta
            val boardEpoch = origin * RailTime.MINUTES_PER_DAY + boardMinute
            val d = kotlin.math.abs(boardEpoch - targetEpochMinute)
            if (d < bestDelta) { bestDelta = d; best = origin }
        }
        return best
    }

    fun remember(hit: TrainHit) {
        viewModelScope.launch {
            app.repository()?.prefs?.pushRecent(hit.trainNo, hit.fromCode, hit.toCode, hit.originDateEpochDay)
        }
    }

    /**
     * One readiness job for the whole ViewModel, shared by `prepare()` and both suggest paths.
     *
     * This exists because `App.repository()` answers `null` while the 14 MB timetable is still being
     * extracted on a first run — and the old suggest code did `?: return@launch`, which is a silent refusal to
     * answer. Type three letters during those seconds on a fresh install and the type-ahead looked broken and
     * said nothing about why, which is exactly what it did. Awaiting one shared job instead means a query
     * typed mid-installation queues and lands as soon as the database opens.
     */
    private var readiness: Deferred<RailRepository?>? = null

    private fun ready(onProgress: (Long, Long) -> Unit = { _, _ -> }): Deferred<RailRepository?> {
        readiness?.let { return it }
        synchronized(this) {
            readiness?.let { return it }
            val job = viewModelScope.async(Dispatchers.IO) { app.prepare(onProgress) }
            readiness = job
            return job
        }
    }

    /** Let the next keystroke try again: a failed extraction should not be remembered forever. */
    private fun forgetReadiness() = synchronized(this) {
        if (readiness?.isCompleted == true) readiness = null
    }

    /**
     * The dropdown sources, with the failure reported rather than swallowed: `onMiss` is what lets the box
     * say "timetable still installing" or "lookup failed: SQLiteException" in the place the user is looking.
     */
    fun suggestStations(text: String, onResult: (List<StationHit>) -> Unit, onMiss: (String) -> Unit = {}) {
        lookup("station", text, onResult, onMiss) { it.resolveStations(text) }
    }

    fun suggestTrains(text: String, onResult: (List<TrainSummary>) -> Unit, onMiss: (String) -> Unit = {}) {
        lookup("train", text, onResult, onMiss) { it.findTrains(text) }
    }

    private fun <T> lookup(
        what: String, text: String, onResult: (List<T>) -> Unit, onMiss: (String) -> Unit,
        block: suspend (RailRepository) -> List<T>,
    ) {
        viewModelScope.launch {
            val repo = runCatching { ready().await() }.getOrNull()
            if (repo == null) {
                forgetReadiness()
                onMiss(
                    if (text.isBlank()) "Type at least three characters."
                    else "The built-in timetable is still being installed on this device — " +
                        "matches for \"$text\" will appear as soon as it is ready."
                )
                return@launch
            }
            runCatching { block(repo) }
                .onSuccess { rows ->
                    if (rows.isEmpty()) onMiss("Nothing in the built-in timetable matches \"$text\".")
                    else onResult(rows)
                }
                .onFailure { onMiss("$what lookup failed: ${it.javaClass.simpleName}") }
        }
    }
}
