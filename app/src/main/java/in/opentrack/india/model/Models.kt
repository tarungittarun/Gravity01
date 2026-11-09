package `in`.opentrack.india.model

import `in`.opentrack.india.core.RailTime

/**
 * Domain models. Deliberately plain data classes (no Retrofit, no Room, no kotlinx-serialization):
 * these are the contract between the SQLite layer, the live providers and the UI, and every one of
 * them can be constructed and asserted in a plain JVM unit test.
 *
 * Time fields are *rail minutes* — minutes since the midnight of the run's origin date (see
 * [RailTime]). That keeps comparisons on long-distance runs (which cross midnight up to twice)
 * correct without a timezone or calendar round-trip.
 */

data class StationHit(
    val code: String,
    val name: String,
    val state: String?,
    val zone: String?,
    val lat: Double?,
    val lon: Double?,
    /** number of trains that halt here — used as a popularity tiebreaker */
    val rank: Int,
)

/**
 * One row of the "trains between two stations" result: a *specific run* of a train, not the train
 * in the abstract, because a daily train boards you at 06:00 today and a different 06:00 tomorrow
 * and the live view needs to know which one it is showing.
 */
data class TrainHit(
    val trainNo: String,
    val trainName: String,
    val trainType: String?,
    val originDateEpochDay: Long,
    /** rail minutes at the boarding station (null arrival => it is the source) */
    val boardMinute: Int,
    val alightMinute: Int,
    val fromCode: String,
    val toCode: String,
    val fromSeq: Int,
    val toSeq: Int,
    val distanceKm: Int,
    val runDays: Int?,
    /**
     * v2 provenance suffix for this row, or null for a pure v1 row: "via erail" when a live list
     * carries this train, "timetable" when the time on the row is the baked-in copy. Rendered in the
     * meta line; the default keeps every existing constructor call site untouched (v2 wiring, not a
     * v1 rewrite).
     */
    val sourceTag: String? = null,
) {
    val durationMin: Int get() = alightMinute - boardMinute

    /** minutes between "now-ish" target instant and this boarding; negative = already left. */
    val boardEpochMinute: Long get() = originDateEpochDay * 1440L + boardMinute
    val alightEpochMinute: Long get() = originDateEpochDay * 1440L + alightMinute

    fun boardDayOffset(): Int = RailTime.dayOffsetOf(boardMinute)
    fun alightDayOffset(): Int = RailTime.dayOffsetOf(alightMinute)

    /** e.g. "17:00 → 08:32 (+1d)" */
    fun prettyTimes(): String =
        RailTime.formatRailMinute(boardMinute) + " → " + RailTime.formatRailMinute(alightMinute)
}

data class StopRow(
    val seq: Int,
    val code: String,
    val name: String,
    val state: String?,
    val lat: Double?,
    val lon: Double?,
    val arrivalMin: Int?,
    val departureMin: Int?,
    val journeyDay: Int,
    val schedKm: Int,
    val isHalt: Boolean,
    /** live overlay, null until a provider confirms this stop */
    val actualArrivalMin: Int? = null,
    val actualDepartureMin: Int? = null,
    val delayArrivalMin: Int? = null,
    val delayDepartureMin: Int? = null,
    val platform: String? = null,
    val status: StopStatus = StopStatus.UNKNOWN,
) {
    val isPast: Boolean get() = status == StopStatus.DEPARTED
    val isCurrent: Boolean get() = status == StopStatus.ARRIVED || status == StopStatus.CANCELLED_HERE
}

enum class StopStatus { UNKNOWN, UPCOMING, APPROACHING, ARRIVED, DEPARTED, CANCELLED_HERE, SKIPPED }

/** A leg between two consecutive stops; the position engine integrates along these. */
data class SegmentRow(
    val seq: Int,
    val fromCode: String,
    val toCode: String,
    val legKm: Int,
    val legMin: Int?,
)

data class TrainRoute(
    val trainNo: String,
    val trainName: String,
    val srcCode: String,
    val srcName: String,
    val dstCode: String,
    val dstName: String,
    val totalKm: Int,
    val totalMin: Int?,
    val runDays: Int?,
    val returnNo: String?,
    val stops: List<StopRow>,
    val segments: List<SegmentRow>,
) {
    val lastSeq: Int get() = stops.lastOrNull()?.seq ?: 0

    fun stopAtSeq(seq: Int): StopRow? = stops.firstOrNull { it.seq == seq }
    fun segmentAfter(seq: Int): SegmentRow? = segments.firstOrNull { it.seq == seq }

    /** Cumulative distance of a fractional position inside the leg that starts at [seq]. */
    fun kmAt(seq: Int, fraction: Float): Float {
        val stop = stopAtSeq(seq) ?: return 0f
        val seg = segmentAfter(seq) ?: return stop.schedKm.toFloat()
        return stop.schedKm + seg.legKm * fraction.coerceIn(0f, 1f)
    }
}

/** Where a provider thinks the train is right now. */
data class LiveSnapshot(
    val trainNo: String,
    val journeyEpochDay: Long?,
    /** last confirmed station, IR code, plus where in the DB route it maps */
    val atCode: String?,
    val atSeq: Int,
    val atName: String?,
    val event: StopStatus,
    /** rail-minute of the last confirmed event on the *train's* axis (already day-corrected) */
    val eventMinute: Int?,
    val scheduledMinuteAtEvent: Int?,
    /** expected arrival at the next stop, if the provider supplied one */
    val nextCode: String?,
    val expectedNextMinute: Int?,
    val delayMinutes: Int?,
    val platform: String?,
    val perStop: Map<String, LiveStopFields>,
    val fetchedAtMillis: Long,
    val sourceKind: SourceKind,
    val rawNote: String? = null,
    /**
     * The instant the *provider* says this data was generated, from the timestamp its own payload
     * carries. It is not the same as [fetchedAtMillis]: our HTTP response can arrive in half a second
     * and still contain a feed the upstream generated six hours ago, and "updated just now" on such a
     * screen is the exact lie this product exists to avoid. Null when the source stamps nothing, which
     * means the app can only claim the age of its own fetch — and the header says that instead.
     */
    val feedStampMillis: Long? = null,
    /** Validated NTES schedule for this run; ephemeral, never silently persisted as offline data. */
    val reportedRoute: TrainRoute? = null,
    /** Per-cell NTES clocks retained from an earlier accepted reply for this exact run/route.
     * These are history, NEVER a new position, receipt or event-age reset. */
    val savedOfficialClocks: Set<String> = emptySet(),
) {
    companion object {
        fun empty(trainNo: String, kind: SourceKind) = LiveSnapshot(
            trainNo = trainNo, journeyEpochDay = null, atCode = null, atSeq = 0, atName = null,
            event = StopStatus.UNKNOWN, eventMinute = null, scheduledMinuteAtEvent = null,
            nextCode = null, expectedNextMinute = null, delayMinutes = null, platform = null,
            perStop = emptyMap(), fetchedAtMillis = System.currentTimeMillis(), sourceKind = kind,
        )
    }
}

data class LiveStopFields(
    val actualArrivalMin: Int? = null,
    val actualDepartureMin: Int? = null,
    val delayArrivalMin: Int? = null,
    val delayDepartureMin: Int? = null,
    val platform: String? = null,
    val cancelled: Boolean = false,
    val departed: Boolean? = null,
)

/**
 * How much of this screen's content is actually live. Shown to the user verbatim: a tracker that
 * silently presents stale data as live is worse than one that admits it is offline.
 */
enum class SourceKind(val label: String, val live: Boolean) {
    PROVIDER("LIVE", true),
    /** The railway's own running-status feed, per stop: the difference between a tracker and a timetable. */
    PROVIDER_NTES("LIVE · running status", true),
    PROVIDER_OVERRIDE("LIVE · custom", true),
    SNAPSHOT_FILE("LIVE · snapshot", true),
    CACHED("CACHED · last known", false),
    DERIVED_GPS("DERIVED · GPS", false),
    OFFLINE_SCHEDULE("OFFLINE · schedule only", false),
    NONE("NO DATA", false),
}

/** Final, render-ready position. Produced by [in.opentrack.india.domain.PositionEngine]. */
data class TrainPosition(
    /** index into route.stops of the last confirmed/approached stop */
    val anchorSeq: Int,
    val nextSeq: Int,
    /** 0..1 along the anchor→next segment */
    val fraction: Float,
    val kmAlong: Float,
    val status: StopStatus,
    val etaNextMinute: Int?,
    val delayMinutes: Int?,
    /** minutes still to run to `toCode`, null if not derivable */
    val remainingMin: Int?,
    /** where `anchorSeq` sits in [TrainRoute.stops]; 0 when unknown */
    val provenance: SourceKind,
    val confidence: Confidence,
    /** seconds since the data was fetched; the UI must surface it, per the honesty rule */
    val ageSeconds: Long,
    /**
     * seconds since the *feed itself* says it was generated, when the source stamps that. Kept beside
     * [ageSeconds] rather than replacing it because they answer two different questions — "how old is
     * the newest thing we touched" and "how old is the fact" — and a traveller reading a live screen is
     * asking the second one.
     */
    val feedAgeSeconds: Long? = null,
    /**
     * The provider's generation instant itself, kept next to the derived age so a screen can print the
     * clock time a traveller can compare against their own watch ("stamped 07:56 IST"), not only the
     * relative one. Null when the source stamps nothing.
     */
    val feedStampMillis: Long? = null,
) {
    enum class Confidence { HIGH, MEDIUM, LOW }
}

/**
 * A (train, boarding stop, alighting stop) triple as stored in the reference DB, before any
 * date/window reasoning. Minutes are rail minutes (see [in.opentrack.india.core.RailTime]), so
 * `alightMinute` may legitimately exceed 1440 for a run that boards on day 1 and lands on day 2.
 *
 * Kept as a separate type from [TrainHit] on purpose: this is raw schedule data that a test can
 * build by hand, while [TrainHit] is a decision ("this run, on this date") produced by
 * [in.opentrack.india.domain.TrainFinder].
 */
data class PairStop(
    val trainNo: String,
    val trainName: String,
    val trainType: String?,
    val runDays: Int?,
    val srcCode: String,
    val dstCode: String,
    val fromSeq: Int,
    val boardMinute: Int,
    val toSeq: Int,
    val alightMinute: Int,
    val fromKm: Int,
    val toKm: Int,
)

/** Train identity + headline schedule facts, for the "train number / name" search mode. */
data class TrainSummary(
    val no: String,
    val name: String,
    val src: String,
    val dst: String,
    val type: String?,
    val totalKm: Int,
    val totalMin: Int?,
    val runDays: Int?,
    val halts: Int,
    val returnNo: String?,
)
