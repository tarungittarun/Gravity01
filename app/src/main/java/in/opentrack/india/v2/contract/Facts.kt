package `in`.opentrack.india.v2.contract

/**
 * What a train *is*, as opposed to what our baked-in copy believes it is.
 *
 * The bug that started v2 was exactly this: `16228` in the offline asset was "Shimoga-Bangalore Express,
 * SMET→SBC" while the railway was running `16228` as "Talguppa–Mysuru Express, TLGP→MYS". A number is not an
 * identity, and a renumbering is invisible to a client that has nothing to compare against - so identity is
 * its own value type here, with a source, and disagreement is a query rather than an outage report.
 */
data class TrainIdentity(
    val number: String,
    val name: String,
    val fromCode: String,
    val toCode: String,
    val provenance: Provenance,
) {
    /** Same number, different route: the shape of "the app showed me the wrong train". */
    fun disagreesWith(other: TrainIdentity): Boolean =
        other.number == number && (other.fromCode != fromCode || other.toCode != toCode)

    /**
     * The sentence to show when our copy and a live answer disagree. Null when they agree.
     *
     * Kept as data rather than swallowed, because silence is what made v1's wrong times look like the
     * passenger's mistake. A corrected row and a contradiction are different user experiences and only one of
     * them can be produced by the app alone.
     */
    fun conflictWith(ours: TrainIdentity?): String? {
        if (ours == null || !ours.disagreesWith(this)) return null
        return "train $number: this device says ${ours.fromCode}→${ours.toCode}, " +
            "${provenance.source} says $fromCode→$toCode"
    }
}

/**
 * One stop on one run, scheduled and actual side by side.
 *
 * Minutes are counted from midnight of the run's first day, and [dayOffset] carries the `+1` that makes a
 * 07:00-next-morning arrival real. Both forms are accepted by the delay arithmetic because live feeds are not
 * consistent about which they send, and an off-by-1440 delay would be the next bug of this family.
 */
data class RouteStop(
    val stationCode: String,
    val seq: Int,
    val schedArrMin: Int? = null,
    val schedDepMin: Int? = null,
    val actualArrMin: Int? = null,
    val actualDepMin: Int? = null,
    val platform: String? = null,
    val dayOffset: Int = 0,
    val atKm: Int? = null,
    val provenance: Provenance,
) {
    val hasActuals: Boolean get() = actualArrMin != null || actualDepMin != null

    fun arrivalDelayMin(): Int? = diff(actualArrMin, schedArrMin)

    fun departureDelayMin(): Int? = diff(actualDepMin, schedDepMin)

    /**
     * Delay, with the midnight wrap handled and no invented zero.
     *
     * Due 23:58, seen 00:02: that is four minutes late, not fourteen hours early, and it is not zero either -
     * a one-sided pair is unknown, and unknown renders as an em dash. A feed that omits a platform must not
     * produce "Platform 1" any more than a train we do not have must produce a plausible number.
     */
    private fun diff(actual: Int?, scheduled: Int?): Int? {
        if (actual == null || scheduled == null) return null
        val d = actual - scheduled
        return when {
            d > 12 * 60 -> d - 1440
            d < -12 * 60 -> d + 1440
            else -> d
        }
    }
}

/**
 * The answer to "what runs between these two stations today" - the question v1 could only ask of its own
 * snapshot, and therefore could get wrong without any signal at all.
 *
 * Listing is a first-class type so that an empty list from a live source (a real event: a pair with nothing
 * running in the window) is distinguishable from a source that never answered, which is the difference between
 * "no trains" and "I could not look". v1 conflated those, and the user read the second as the first.
 */
data class PairListing(
    val fromCode: String,
    val toCode: String,
    val trains: List<TrainAtStation>,
    val provenance: Provenance,
    /** Set only when the source said something we could not read; it is shown, never used to pad the list. */
    val note: String? = null,
) {
    data class TrainAtStation(
        val identity: TrainIdentity,
        val boardMin: Int? = null,
        val alightMin: Int? = null,
        val boardDayOffset: Int = 0,
        val alightDayOffset: Int = 0,
    )

    val isEmpty: Boolean get() = trains.isEmpty()

    fun numbers(): List<String> = trains.map { it.identity.number }

    /** Rows a passenger can still board, nearest first - the rule v1 got wrong by reusing one comparator. */
    fun sortedForBoarding(nowMinuteOfDay: Int): List<TrainAtStation> =
        trains.sortedWith(
            compareByDescending<TrainAtStation> { (it.boardMin ?: 0) >= nowMinuteOfDay }
                .thenBy { kotlin.math.abs((it.boardMin ?: 0) - nowMinuteOfDay) }
                .thenBy { it.identity.number }
        )
}
