package `in`.opentrack.india.remote

import android.content.Context
import `in`.opentrack.india.core.BrowserFetch
import `in`.opentrack.india.core.Diary
import `in`.opentrack.india.core.Http
import `in`.opentrack.india.core.RailTime
import `in`.opentrack.india.model.LiveSnapshot
import `in`.opentrack.india.model.LiveStopFields
import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainRoute
import java.io.File

/**
 * The live-data port: everything the app knows about a train *right now* arrives through here.
 *
 * This interface is the direct answer to failure mode C1 in docs/ARCHITECTURE.md — earlier attempts
 * bound the domain to one endpoint, so an upstream policy change ended the product. The domain only
 * ever sees [LiveSnapshot]; implementations are replaceable at runtime and two of the four need no
 * network at all, so "the server went away" costs freshness, never capability.
 */
interface LiveProvider {
    val kind: SourceKind
    /** Blocking; called on an IO dispatcher. Must return null rather than throw. */
    fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): LiveSnapshot?

    /**
     * A sentence for the traveller explaining an empty [fetch], in the language of what actually
     * happened: "the network is off", "the request timed out", "the server answered 503", "the answer
     * did not parse", "the answer carries no running times". Null means "nothing specific to say", and
     * the chain then falls back to the generic offline sentence. This exists because "no live data" and
     * "live data is unavailable for a reason you could act on" are different screens, and every other
     * train app in this space shows the first while meaning the second.
     */
    fun failureNote(): String? = null
}

/**
 * The sentence a transport failure deserves. File-level rather than one per provider because what the
 * traveller needs — "your device is offline", "the server refused us", "the reply was not data" — is
 * the same whoever made the request, and two copies of it drift the moment one gets reworded.
 */
/**
 * One request, sent by whichever client this device will actually let through.
 *
 * The order is not a preference. A phone that hung up on `HttpURLConnection`, then got a 2xx from the same URL
 * sent from inside a page of the site's own a second later, has told us which client it permits - and on that
 * phone, asking again with the refused client is known-futile, which is what the last five rounds of logs
 * looked like. So: the JDK client whenever it is not demonstrably blocked, the site's own origin on devices
 * where the probe proved otherwise.
 *
 * `browserWorks` is latched only on a real 2xx body, never on a document the engine rendered. That is not
 * pedantry: the previous version of this latch was set by a "159-byte answer" which turned out to be Android's
 * XML parser complaining about JSON, and once latched it routed every refresh of the app through a transport
 * that had nothing to give back. A diagnostic that changes what the traveller sees is a bug, so the latch is
 * strict and the answer is read as bytes from `fetch()`, not as a page.
 */
private fun ask(
    http: Http,
    url: String,
    body: String?,
    headers: Map<String, String>,
    timeoutMillis: Int = Http.DEFAULT_TIMEOUT,
    budgetMillis: Int = 0,
): Http.Result {
    // A 2xx browser answer on erail/ixigo is not proof that the NTES POST works in that
    // engine. Keep host-specific transports isolated; never warm enquiry with another host's page.
    if (!FeedProbe.browserWorks || BrowserFetch.hostOf(url) != "enquiry.indianrail.gov.in") {
        return if (body == null) http.get(url, timeoutMillis, 1, headers, budgetMillis = budgetMillis)
        else http.post(url, body, headers, timeoutMillis = timeoutMillis, retries = 1,
            budgetMillis = budgetMillis)
    }
    Diary.get()?.log("TRANSPORT") { "asking from the site's own page, which this device accepted" }
    // The page is part of the finding, not a detail of it: if this device's answer came from a request riding
    // /ntes/, a request riding / will not repeat it.
    val page = if (FeedProbe.browserPage.isBlank()) BrowserFetch.originPage(url) else FeedProbe.browserPage
    return BrowserFetch.apiCall(url, body, page, headers, BrowserFetch.defaultTimeout)
}

/**
 * The one question worth asking before a socket is: did this network manage to open port 443 at all when the
 * app last looked? If it did not, every request shape this app can produce fails the same way, and saying so
 * costs the traveller nothing instead of thirty seconds of retry ladder.
 *
 * Null - "we do not know" - is the answer whenever the diagnostic collector has never been switched on, since
 * that probe is part of it. Not knowing must never become a licence to skip a real request, so the callers
 * treat anything other than a fresh "unreachable" as permission to proceed.
 */
private fun portDead(host: String): String? =
    if (Diary.get()?.portUnreachable(host) == true) {
        "This network could not open a connection to " + host + " on port 443 when the app last tried, so no " +
            "request was sent. The times shown are the published timetable."
    } else null

private fun transportFailureSentence(failure: Http.Result.Failed): String =
    when (failure.kind) {
        Http.Result.Kind.OFFLINE -> "This device has no network route to the live feed."
        Http.Result.Kind.TIMEOUT -> "The live feed did not answer within the time limit."
        Http.Result.Kind.HTTP -> "The live feed refused the request" +
            (if (failure.message.isNotBlank()) " (" + failure.message + ")" else "") + "."
        Http.Result.Kind.EMPTY -> "The live feed answered with an empty body."
        Http.Result.Kind.PARSE -> "The live feed answered with something unreadable."
    }

/**
 * Primary source: the public enquiry service, keyed by train number + journey date, live flavour.
 * No API key, no account, and no proxy of ours in the middle — which is also why we parse the flat
 * `~`-delimited enquiry format instead of the HTML page (see [ErailWire]).
 */
class ErailProvider(
    private val http: Http,
    private val baseUrl: String = DEFAULT_BASE,
    private val columnHint: String? = null,
    private val store: SnapshotStore? = null,
) : LiveProvider {

    override val kind = SourceKind.PROVIDER

    @Volatile
    private var note: String? = null

    override fun failureNote(): String? = note

    override fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): LiveSnapshot? {
        note = null
        portDead("erail.in")?.let { note = it; return null }
        val url = buildString {
            append(baseUrl.trimEnd('/'))
            append("/rail/getTrains.aspx?TrainNo=").append(trainNo)
            append("&DataSource=1&Language=0&Cache=true&Flag=1")
            append("&Date=").append(RailTime.formatIsoDate(journeyEpochDay))
        }
        // Four seconds for the alternate source. It is the second opinion, not the answer: making the
        // traveller wait through its whole ladder to be told "also refused" is how this app read as frozen.
        val r = when (val got = ask(http, url, null, emptyMap(), Http.DEFAULT_TIMEOUT, 4_000)) {
            is Http.Result.Ok -> got
            // Every one of these used to be `as? Ok ?: return null`, which collapsed five distinguishable
            // failures into one blank screen. The device state that causes each is different — aeroplane
            // mode, a slow tunnel, a server refusing us, a page that changed shape — so the sentence the
            // traveller gets has to be different too, or the only advice we can give is "try again".
            is Http.Result.Failed -> {
                note = transportFailureSentence(got)
                return null
            }
        }
        val parsed = runCatching { ErailWire.parse(r.body, columnHint) }.getOrNull()
        if (parsed == null) {
            note = "The live feed answered, but its reply is not in the format this build understands."
            return null
        }
        if (parsed.isEmpty) {
            note = "The live feed has nothing at all about train $trainNo."
            return null
        }
        // The honest headline for the endpoint's real limitation: it publishes the timetable, the run
        // window and berth availability, and *not* per-stop running times. A snapshot is still built from
        // whatever it did report, but the screen is told why the live column stays dashed.
        if (!parsed.hasLiveColumns) {
            // Copied to a local first: `feedStampMillis` is a property of another file, so a null test on
            // it does not license passing the value to a non-null parameter, and `!!` in a string template
            // is how a copy that runs on every refresh ends up crashing on the one that does not.
            val stamp = parsed.feedStampMillis
            note = if (stamp != null) {
                "The live feed answered (its data is stamped ${RailTime.formatClock(stamp)} IST) " +
                    "but published no arrival or departure minute for this run, so every minute below is the timetable."
            } else {
                "The live feed published no arrival or departure minute for this run, so every minute below " +
                    "is the timetable."
            }
        }
        // Delay-only/schedule-only replies are not observed arrival/departure minutes.
        if (parsed.stops.none { it.actArr != null || it.actDep != null }) return null
        val snap = ErailAdapter.toSnapshot(parsed, trainNo, journeyEpochDay, r.fromCache, r.ageMillis)
            ?: return null
        store?.save(trainNo, snap)
        return snap
    }


    companion object {
        const val DEFAULT_BASE = "https://erail.in"
    }
}

/**
 * Primary live source: the railway's own running-status feed, which carries actual times per halt.
 *
 * This is the provider whose absence made the whole product look like a timetable. It is tried before
 * [ErailProvider] and *after* the user's override, and it is deliberately not the only source: its payload
 * is AES-framed with a key that is fixed inside the official app (see [NtesWire]), so an upstream rotation
 * is a real possibility, and the price of that risk is falling back to a labelled timetable — never a
 * blank screen and never a schedule wearing a "live" badge.
 */
class NtesProvider(
    private val http: Http,
    private val baseUrl: String = NtesWire.BASE_URL,
    private val store: SnapshotStore? = null,
    private val selectedFrom: String? = null,
    private val selectedTo: String? = null,
) : LiveProvider {

    override val kind = SourceKind.PROVIDER_NTES

    @Volatile
    private var note: String? = null

    /** Same dated response can provide a safe schedule even without a confirmed actual. */
    var verifiedSchedule: TrainRoute? = null
        private set

    override fun failureNote(): String? = note

    override fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): LiveSnapshot? {
        note = null
        verifiedSchedule = null
        portDead(BrowserFetch.hostOf(baseUrl))?.let { note = it; return null }
        // If this device already found a request shape the feed answers, use it. Adoption is per-process and
        // per-network rather than baked into the build, because the difference between an answer and a closed
        // socket here belongs to the carrier and the server, not to the APK - and a rebuild should never be the
        // price of a header.
        val adopted = FeedProbe.adoptedFor(trainNo, journeyEpochDay)
        val plain = NtesWire.plainFor(NtesWire.SUB_LIVE, trainNo, journeyEpochDay)
        val headers = mapOf(
            "Content-Type" to "application/json",
            "charset" to "utf-8",
            // The feed answers the client signature it expects; with anything else some deployments of it
            // reset the connection, which the traveller would otherwise experience as "no live data".
            "User-Agent" to NtesWire.USER_AGENT,
        )
        val first = if (adopted != null) {
            FeedProbe.execute(http, adopted, trainNo, journeyEpochDay)
        } else {
            // Bounded ladder; a separate, session-warmed mobile JSON form follows a refusal.
            ask(http, baseUrl, NtesWire.envelope(plain), headers, TIMEOUT, 6_500)
        }
        // kilotrain documents this second official request shape but its CI did not prove a
        // per-stop response. It is a fallback, not a new "working source" in the UI.
        val mobile = if (first is Http.Result.Failed && baseUrl == NtesWire.BASE_URL)
            NtesMobileFallback.fetch(trainNo, journeyEpochDay) else null
        val got = if (mobile is Http.Result.Ok) mobile else first
        val body = when (got) {
            is Http.Result.Ok -> got
            is Http.Result.Failed -> {
                note = transportFailureSentence(got) +
                    (if (adopted != null) " (asked with the shape this device adopted: " + adopted.name + ")" else "") +
                    (if (FeedProbe.browserWorks) " (asked from the site's own page, the way its app does)" else "")
                // Refused is the moment the experiment matters: the device is the only place that can tell a
                // wrong request from a blocked network, so ask it instead of guessing from a sandbox.
                Diary.get()?.log("NTES", "refused: " + got.kind + " " + got.message +
                    (if (adopted == null) " (shipped shape)" else " (adopted shape " + adopted.name + ")"))
                if (got.kind == Http.Result.Kind.HTTP || got.kind == Http.Result.Kind.TIMEOUT) {
                    FeedProbe.maybeStart(http, trainNo, journeyEpochDay)
                }
                return null
            }
        }
        if (body.fromCache) {
            // A GET request shape adopted on this device can inherit Http's offline
            // fallback cache. That is not a new official answer, even if the old
            // envelope still parses perfectly. SnapshotStore may keep its old Act
            // rows, but this request must be recorded as FAILED, never answered.
            note = "The live feed did not answer; an older HTTP body was returned from cache " +
                "(${body.ageMillis / 60_000} min old) and ignored."
            Diary.get()?.log("NTES", "request failed; cached HTTP body ignored; original age=" +
                (body.ageMillis / 1000) + "s")
            return null
        }
        val doc = NtesWire.unwrapJson(body.body)
        // Log the *decrypted* structure, not 1,500 characters of ciphertext. On the owner's R53
        // phone the official envelope succeeded in 0.4 s, but the old log concealed the fields
        // that would explain why a 34,957-byte answer was refused.
        Diary.get()?.log("NTES") {
            "reply via " + (if (mobile is Http.Result.Ok) "warmed JSON" else "official envelope") +
                " ${body.body.length}B; decoded=" +
                (doc?.let { NtesWire.shapeOf(it, 3) } ?: "unopenable")
        }
        if (doc == null) {
            // The one failure that is ours rather than upstream's, so it has to be distinguishable: an
            // envelope we could not open means the framing changed, not that the train is not running.
            note = "The live feed answered, but this build could not open its envelope (its protocol may have changed)."
            Diary.get()?.log("NTES", "envelope unopenable: body=" + body.body.length + "B, framing changed?")
            return null
        }
        val stns = doc.has("STNS")
        val rawParsed = runCatching { NtesWire.parse(doc.toString()) }.getOrNull()
        val officialRoute = if (stns && rawParsed != null) NtesRoute.fromStatus(rawParsed, route) else null
        val scheduleMatched = stns && officialRoute != null &&
            NtesMobileFallback.matchesScheduleRun(doc, trainNo, journeyEpochDay,
                selectedFrom ?: route.srcCode, selectedTo ?: route.dstCode)
        if (scheduleMatched) {
            verifiedSchedule = officialRoute
            Diary.get()?.log("ROUTE") {
                "NTES dated static timetable for $trainNo: ${officialRoute!!.stops.size} scheduled halts " +
                    "(${route.stops.size} on device); live actuals gated separately"
            }
        }
        // Never align an unproven STNS table, and never turn a schedule cell into an actual.
        if (stns && !scheduleMatched) {
            note = "NTES supplied stops, but their train, day or ordered schedule could not be verified."
            Diary.get()?.log("NTES") {
                "STNS schedule refused; train=" + doc.optString("TrainNo").take(8) +
                    " run-field=" + doc.optString("StartDate").take(24) +
                    " update=" + doc.optString("LUPDT").take(40) +
                    " parsed-rows=${rawParsed?.rows?.size ?: 0}"
            }
            return null
        }
        if (stns && !NtesMobileFallback.matchesRun(doc, trainNo, journeyEpochDay,
                selectedFrom ?: route.srcCode, selectedTo ?: route.dstCode)) {
            note = "NTES supplied a dated static timetable, but no verified current-run actual minutes."
            Diary.get()?.log("NTES", "STNS actuals refused; keeping verified static timetable only")
            return null
        }
        val parsed = if (stns && rawParsed != null && officialRoute != null) {
            val stamped = rawParsed.copy(feedStampMillis =
                NtesMobileFallback.updateMillis(doc, journeyEpochDay) ?: rawParsed.feedStampMillis)
            NtesMobileFallback.trustedRows(stamped, officialRoute, journeyEpochDay, System.currentTimeMillis())
        } else rawParsed
        if (parsed == null) {
            val keys = runCatching { doc.keys().asSequence().take(8).joinToString(", ") }.getOrNull()
            Diary.get()?.log("NTES", "reply was not a status table; top-level keys: " + (keys ?: "-") +
                " shape=" + runCatching { NtesWire.shapeOf(doc) }.getOrDefault("-"))
            note = "The live feed answered, but its reply was not a running-status table" +
                (if (keys.isNullOrBlank()) "." else " (it sent: $keys).")
            return null
        }
        if (parsed.isEmpty) {
            // The dangerous case, because it is indistinguishable from the truth. "No running data" is what a
            // wrong key name looks like from the outside, so the shape is recorded even though the parse
            // "succeeded" - if this line ever shows a populated array, the parser is the bug, not the feed.
            note = "The live feed has no running data for train $trainNo on this date" +
                (parsed.message?.let { " ($it)" } ?: ".")
            Diary.get()?.log("NTES", "parsed empty; shape=" + runCatching { NtesWire.shapeOf(doc) }.getOrDefault("-"))
            return null
        }
        if (!parsed.hasReportedTimes) {
            note = "NTES answered but supplied no observed arrival or departure minute for this run. " +
                "A winner in the request ladder is not a confirmed stop; checking the next source."
            Diary.get()?.log("NTES", "timetable/delay-only answer rejected: no actual per-stop minute")
            return null
        }
        val snap = NtesAdapter.toSnapshot(parsed, trainNo, journeyEpochDay) ?: run {
            Diary.get()?.log("NTES", "rows parsed (" + parsed.rows.size + ") but no station matched the timetable")
            return null
        }
        Diary.get()?.log(
            "NTES",
            "accepted: " + snap.perStop.size + " timetable stops, " +
                snap.perStop.values.count { it.actualArrivalMin != null ||
                    it.actualDepartureMin != null } + " with reported minutes; live cells " +
                (if (parsed.hasLiveColumns) "present" else "none yet") +
                "; last timed stop=" + (snap.atCode ?: "unknown") +
                " event minute=" + (snap.eventMinute?.toString() ?: "unknown") +
                " delay at that stop=" + (snap.delayMinutes?.toString() ?: "unknown") +
                " feed update (NOT movement)=" + (parsed.feedStampMillis?.toString() ?: "unknown"),
        )
        // Said out loud so a screenshot explains itself: "live, via a shape learned on this device" is a
        // different support conversation from "live, via the shipped request".
        // `adopted.name` is only reachable in the else branch, where the null check above has already excluded
        // the case - `adopted` is nullable, so hoisting that read out here would not compile.
        // Small, station-scoped evidence for the gaps the traveller can see. The
        // encrypted body itself is never logged; even an answered NTES row may
        // contain only an ETA or an echoed timetable, not an observed Act clock.
        if (stns && officialRoute != null && rawParsed != null) Diary.get()?.log("NTES_GAPS") {
            val limit = rawParsed.rows.indexOfFirst { it.code.equals(snap.atCode, true) }
            val rawRows = doc.optJSONArray("STNS")
            if (limit < 0) "no accepted anchor on STNS route"
            else (0..limit).filter { i ->
                val accepted = parsed.rows[i]
                val scheduled = officialRoute.stops[i]
                (scheduled.arrivalMin != null && accepted.actArr == null ||
                 scheduled.departureMin != null && accepted.actDep == null)
            }.takeLast(10).joinToString(" ; ") { i ->
                val row = rawRows?.optJSONObject(i)
                val code = rawParsed.rows[i].code.orEmpty()
                val accepted = parsed.rows[i]
                fun field(key: String) = row?.optString(key).orEmpty().take(26).ifBlank { "—" }
                "$code accepted=${accepted.actArr ?: "—"}/${accepted.actDep ?: "—"} " +
                    "STA=${field("STA")} STD=${field("STD")} " +
                    "ETA=${field("ETA")} ETD=${field("ETD")} " +
                    "DARR=${field("DARR")} DDEP=${field("DDEP")} " +
                    "ATA=${field("ATA")} ATD=${field("ATD")}"
            }
        }
        val routed = snap.copy(reportedRoute = officialRoute)
        val shaped = if (adopted == null || routed.rawNote.isNullOrBlank()) routed
        else routed.copy(rawNote = (routed.rawNote ?: "live") + " · via " + adopted.name)
        // The cache codec stores stops but not the authoritative route. Replaying these
        // observations against an unrelated device route would misidentify the train.
        val withHistory = if (officialRoute == null) shaped else OfficialRunCache.retainAccepted(
            shaped, store?.loadOfficial(trainNo, journeyEpochDay,
                officialRoute.srcCode, officialRoute.dstCode))
        if (officialRoute == null) store?.save(trainNo, withHistory)
        else store?.saveOfficial(trainNo, withHistory)
        if (withHistory.savedOfficialClocks.isNotEmpty()) Diary.get()?.log("NTES") {
            "retained earlier accepted official cells for same run/route: " +
                withHistory.savedOfficialClocks.sorted().joinToString(",") +
                "; latest event remains ${withHistory.atCode}:${withHistory.eventMinute}"
        }
        return withHistory
    }

    companion object {
        /** Slower than the timetable source on purpose: the envelope costs a round trip and a decrypt. */
        const val TIMEOUT = 15_000
    }
}

/** Maps the feed's rows onto a domain snapshot. Separate object so the mapping is testable without HTTP. */
object NtesAdapter {

    fun toSnapshot(parsed: NtesWire.Parsed, trainNo: String, journeyEpochDay: Long): LiveSnapshot? {
        val rows = parsed.rows
        if (rows.isEmpty()) return null
        val perStop = LinkedHashMap<String, LiveStopFields>()
        var anchorIdx = -1
        var anchorEvent = StopStatus.DEPARTED
        for ((i, s) in rows.withIndex()) {
            val code = s.code ?: continue
            val departed = s.departed == true || s.actDep != null
            // A delay or future ETA alone cannot prove arrival at this halt.
            val arrived = s.actArr != null
            if (departed || arrived || s.cancelled) anchorIdx = i
            perStop[code] = LiveStopFields(
                actualArrivalMin = s.actArr,
                actualDepartureMin = s.actDep,
                delayArrivalMin = s.delayArr,
                delayDepartureMin = s.delayDep,
                platform = s.platform,
                cancelled = s.cancelled,
                departed = when {
                    s.departed != null -> s.departed
                    s.actDep != null -> true
                    s.actArr != null -> false
                    else -> null
                },
            )
            // Future timetable rows must not overwrite the last *reported* event. On the
            // owner's 16227 response SMET had an arrival but no departure minute: the old
            // unconditional else silently changed ARRIVED into DEPARTED at the next row.
            if (anchorIdx == i && (departed || arrived || s.cancelled)) anchorEvent = when {
                s.cancelled -> StopStatus.CANCELLED_HERE
                arrived && !departed -> StopStatus.ARRIVED
                else -> StopStatus.DEPARTED
            }
        }
        // Not one row in the answer named a station we can key on, so there is nothing for the domain to
        // anchor to and no cell for the stop table to read. Returning null here keeps that guarantee at the
        // seam that produces it: ProviderChain would also drop this snapshot, but an adapter that hands back
        // an empty "live" result is one refactor away from a screen that says LIVE over a table of dashes.
        if (perStop.values.none { it.actualArrivalMin != null || it.actualDepartureMin != null }) return null
        val anchor = anchorIdx.takeIf { it >= 0 }?.let { rows[it] }
        val next = anchorIdx.takeIf { it >= 0 }?.let { rows.getOrNull(it + 1) }
        val anchorCode = anchor?.code
        // A status header may name a later timetable stop without a reported event.
        // Do not pair that station with an older halt's arrival/departure minute.
        val feedCode = anchorCode
        // No halt with an event and no station named in the header: the feed knows nothing about where
        // this train is, which is a different screen from "it is standing at a halt".
        val positionKnown = anchor != null || anchorCode != null
        return LiveSnapshot(
            trainNo = trainNo,
            journeyEpochDay = journeyEpochDay,
            atCode = feedCode,
            atSeq = 0,
            atName = anchor?.name ?: parsed.currentName,
            event = if (positionKnown) anchorEvent else StopStatus.UNKNOWN,
            eventMinute = anchor?.actDep ?: anchor?.actArr,
            scheduledMinuteAtEvent = anchor?.schedDep ?: anchor?.schedArr,
            nextCode = parsed.nextCode?.takeIf { it.isNotBlank() } ?: next?.code,
            expectedNextMinute = next?.actArr,
            // An older stop's +18 is not this stop's delay. If its own delay is
            // absent, let the event/schedule comparison decide or show unknown.
            delayMinutes = anchor?.let { it.delayDep ?: it.delayArr },
            platform = anchor?.platform,
            perStop = perStop,
            fetchedAtMillis = System.currentTimeMillis(),
            sourceKind = SourceKind.PROVIDER_NTES,
            feedStampMillis = parsed.feedStampMillis,
            rawNote = buildString {
                parsed.message?.let { append(it.take(200)) }
                parsed.trainName?.let {
                    if (isNotEmpty()) append(" \u00b7 ")
                    append(it.take(60))
                }
            }.ifEmpty { null },
        )
    }
}

/** Turns the parsed wire rows into a domain snapshot. Separate object so it is testable alone. */
object ErailAdapter {

    fun toSnapshot(
        parsed: ErailWire.Parsed, trainNo: String, journeyEpochDay: Long,
        fromCache: Boolean, ageMillis: Long,
    ): LiveSnapshot? {
        val rows = parsed.stops
        if (rows.isEmpty()) return null
        val perStop = LinkedHashMap<String, LiveStopFields>()
        var anchorIdx = -1
        var anchorEvent = StopStatus.DEPARTED
        for ((i, s) in rows.withIndex()) {
            val code = s.code ?: continue
            val cancelled = s.statusWord?.contains("cancel") == true
            val departed = s.statusWord?.contains("depart") == true ||
                s.statusWord?.contains("pass") == true || s.actDep != null
            val arrived = s.statusWord?.contains("arriv") == true ||
                (s.actArr != null && s.actDep == null)
            if (departed || arrived || cancelled) anchorIdx = i
            perStop[code] = LiveStopFields(
                actualArrivalMin = s.actArr, actualDepartureMin = s.actDep,
                delayArrivalMin = s.delayArr, delayDepartureMin = s.delayDep,
                platform = s.platform, cancelled = cancelled,
                departed = when { departed -> true; s.actArr != null -> false; else -> null },
            )
            anchorEvent = when {
                cancelled -> StopStatus.CANCELLED_HERE
                arrived && !departed -> StopStatus.ARRIVED
                else -> StopStatus.DEPARTED
            }
        }
        val anchor = anchorIdx.takeIf { it >= 0 }?.let { rows[it] }
        val next = anchorIdx.takeIf { it >= 0 }?.let { rows.getOrNull(it + 1) }
        return LiveSnapshot(
            trainNo = trainNo,
            journeyEpochDay = journeyEpochDay,
            atCode = anchor?.code ?: parsed.header?.let { null },
            atSeq = 0,
            atName = anchor?.name ?: parsed.header?.positionLine,
            event = if (anchor == null) StopStatus.UNKNOWN else anchorEvent,
            eventMinute = anchor?.actDep ?: anchor?.actArr,
            scheduledMinuteAtEvent = anchor?.schedDep ?: anchor?.schedArr,
            nextCode = next?.code,
            expectedNextMinute = next?.actArr ?: next?.schedArr,
            delayMinutes = anchor?.let { it.delayDep ?: it.delayArr },
            platform = anchor?.platform,
            perStop = perStop,
            fetchedAtMillis = System.currentTimeMillis() - ageMillis,
            sourceKind = if (fromCache) SourceKind.CACHED else SourceKind.PROVIDER,
            feedStampMillis = parsed.feedStampMillis,
            rawNote = buildString {
                parsed.header?.positionLine?.let { append(it.take(240)) }
                if (parsed.unclassifiedRows > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${parsed.unclassifiedRows} row(s) not understood")
                }
            }.ifEmpty { null },
        )
    }
}

/**
 * User-configured endpoint, tried first when set. The escape hatch that keeps the app alive if the
 * public feed ever starts requiring a key: the user repoints one setting, no APK update, and no new
 * server for us to operate. Accepts [SnapshotCodec]'s format (which most IR-derived REST APIs can be
 * mapped onto) as well as a nested `data.currentLocation` shape.
 */
class OverrideProvider(
    private val http: Http,
    private val template: String?,
    private val store: SnapshotStore? = null,
) : LiveProvider {

    override val kind = SourceKind.PROVIDER_OVERRIDE

    @Volatile
    private var note: String? = null

    override fun failureNote(): String? = note

    override fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): LiveSnapshot? {
        note = null
        val t = template?.trim().orEmpty()
        // Two distinct states this used to share one: "you never configured an endpoint" (nothing to
        // explain, the built-in source is in charge) and "your endpoint is set but is not a URL" (a typo
        // in Settings, which the traveller can fix). Silence was the more confusing of the two answers.
        if (t.isEmpty()) return null
        if (!t.startsWith("http")) {
            note = "The custom live endpoint in Settings is not a URL starting with http, so it was not tried."
            return null
        }
        val url = t.replace("{trainNo}", trainNo)
            .replace("{date}", RailTime.formatIsoDate(journeyEpochDay))
            .replace("{dateCompact}", RailTime.formatCompactDate(journeyEpochDay))
            .replace("{srcCode}", route.srcCode)
            .replace("{dstCode}", route.dstCode)
        val r = when (val got = ask(http, url, null, emptyMap())) {
            is Http.Result.Ok -> got
            is Http.Result.Failed -> {
                note = "Your endpoint: " + transportFailureSentence(got)
                return null
            }
        }
        val snap = runCatching { SnapshotCodec.decode(r.body, fallbackTrainNo = trainNo) }.getOrNull()
            ?.copy(fetchedAtMillis = System.currentTimeMillis() - r.ageMillis,
                sourceKind = if (r.fromCache) SourceKind.CACHED else SourceKind.PROVIDER_OVERRIDE)
            ?: run {
                note = "Your endpoint answered, but the reply was not the JSON shape this app reads " +
                    "(see the snapshot format in the README)."
                return null
            }
        // A hand-built endpoint can answer with the wrong train (a cached proxy, a copy-pasted URL, a
        // template that ignored {trainNo}). Showing another train's position is the single worst
        // failure this screen can have, so an identity mismatch is treated as "no answer" and left at
        // that — the header then reads OFFLINE/CACHED, which is true of the *requested* train.
        if (snap.trainNo.isNotEmpty() && trainNo.isNotEmpty() && snap.trainNo != trainNo) {
            note = "Your endpoint answered with train ${snap.trainNo} while this screen asked for $trainNo, " +
                "so its answer was refused rather than shown."
            return null
        }
        if (snap.sourceKind == SourceKind.PROVIDER_OVERRIDE) store?.save(trainNo, snap)
        return snap
    }
}

/**
 * Last good snapshot on disk. This is what "offline-first" means for live data: after a refresh in
 * the morning, the 2 a.m. status page still shows where the train was last *confirmed*, labelled as
 * such, rather than an error.
 */
class CacheProvider(private val store: SnapshotStore) : LiveProvider {
    override val kind = SourceKind.CACHED

    @Volatile
    private var note: String? = null

    override fun failureNote(): String? = note

    override fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): LiveSnapshot? {
        note = null
        val snap = store.load(trainNo)
        if (snap == null) {
            // Distinguish "this device has never been online with this train" from "the live feed is down
            // and there is no rescue": the first calls for a walk out of the tunnel, the second for a
            // retry later, and the file layer knows which one it is.
            note = "Nothing this device has stored about $trainNo is new enough to show — a snapshot older " +
                "than 24 hours is refused rather than presented as live."
            return null
        }
        if (snap.perStop.isEmpty() && snap.atCode == null) {
            note = "The snapshot on this device is too incomplete to show."
            return null
        }
        return snap
    }
}

/** Per-train snapshot file, named by train number so a "recent" list can rehydrate without an index. */
class SnapshotStore(context: Context) {

    private val dir = File(context.filesDir, "live").apply { mkdirs() }

    fun file(trainNo: String): File = File(dir, trainNo.filter { it.isDigit() || it.isLetter() } + ".json")

    fun save(trainNo: String, snapshot: LiveSnapshot) {
        runCatching {
            val f = file(trainNo)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(SnapshotCodec.encode(snapshot))
            if (f.exists()) f.delete()
            if (!tmp.renameTo(f)) {
                // Some private-storage mounts refuse a rename; a copy is the fallback, and the temp
                // file is then removed, or it accumulates one per refresh for every train watched.
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** Persist the paired official route and its observations, atomically per train AND run.
     * Unlike the generic cache, a stale official answer can never be aligned to another route. */
    fun saveOfficial(trainNo: String, snapshot: LiveSnapshot) {
        val day = snapshot.journeyEpochDay ?: return
        runCatching {
            val f = File(dir, "${trainNo.filter(Char::isDigit)}-$day.official.json")
            val tmp = File(dir, f.name + ".tmp")
            tmp.writeText(OfficialRunCache.encode(snapshot))
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun loadOfficial(trainNo: String, day: Long, from: String, to: String): LiveSnapshot? =
        runCatching {
            val f = File(dir, "${trainNo.filter(Char::isDigit)}-$day.official.json")
            if (!f.isFile) null else OfficialRunCache.decode(f.readText(), trainNo, day, from, to,
                System.currentTimeMillis())
        }.getOrNull()

    fun load(trainNo: String): LiveSnapshot? = runCatching {
        val f = file(trainNo)
        if (!f.isFile) return null
        // Age-capped: a 3-day-old "live position" is misinformation, not a fallback.
        if (System.currentTimeMillis() - f.lastModified() > MAX_AGE) return null
        SnapshotCodec.decode(f.readText())
    }.getOrNull()

    fun clear() = runCatching { dir.listFiles()?.forEach { it.delete() } }

    companion object {
        const val MAX_AGE = 24 * 60 * 60_000L
    }
}

/**
 * Runs the chain in priority order and reports which source answered, because the UI must show it
 * (rule C5: a degraded mode is a visible state, never a silent one).
 */
class ProviderChain(private val providers: List<LiveProvider>) {

    data class Outcome(
        val snapshot: LiveSnapshot?,
        val winner: SourceKind,
        val tried: List<String>,
        /**
         * Why each source that came back empty did so, in its own words. Surfaced even when a later source
         * rescued the screen, because "showing the last thing we fetched at 06:12, because the live feed
         * timed out" is a more useful sentence than either half of it alone.
         */
        val notes: List<String> = emptyList(),
        /** Static-only dated NTES schedule, even if no observed minute passed the actuals gate. */
        val scheduleRoute: TrainRoute? = null,
    )

    fun fetch(trainNo: String, route: TrainRoute, journeyEpochDay: Long): Outcome {
        val tried = ArrayList<String>(providers.size)
        val notes = ArrayList<String>(providers.size)
        var scheduleRoute: TrainRoute? = null
        for (p in providers) {
            var thrown: Throwable? = null
            val snap = try {
                p.fetch(trainNo, route, journeyEpochDay)
            } catch (e: Throwable) {
                thrown = e
                null
            }
            if (p is NtesProvider) scheduleRoute = p.verifiedSchedule
            tried += p.kind.name + (if (snap == null) "=miss" else "=hit")
            if (snap == null) {
                // A provider is allowed to be silent about why it declined; a provider that *threw* must
                // not be, because that is the one case where the app itself may be at fault, and after
                // R8 this sentence is often the only trace of it a user will ever be able to show us.
                val why = p.failureNote() ?: thrown?.let {
                    "this source did not answer properly (${it.javaClass.simpleName})."
                }
                if (why != null && why.isNotBlank()) notes += "${p.kind.label}: $why"
            }
            if (snap != null) return Outcome(snap, snap.sourceKind, tried, notes, scheduleRoute)
        }
        return Outcome(null, SourceKind.NONE, tried, notes, scheduleRoute)
    }

    companion object {
        fun build(http: Http, store: SnapshotStore, overrideUrl: String?, columnHint: String?,
            fromCode: String? = null, toCode: String? = null) =
            ProviderChain(
                listOf(
                    OverrideProvider(http, overrideUrl, store),
                    NtesProvider(http, NtesWire.BASE_URL, store, fromCode, toCode),
                    ErailProvider(http, ErailProvider.DEFAULT_BASE, columnHint, store),
                    CacheProvider(store),
                )
            )
    }
}
