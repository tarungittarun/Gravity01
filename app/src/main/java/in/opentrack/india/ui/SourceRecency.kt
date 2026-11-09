package `in`.opentrack.india.ui

/** A site update is not a passage event. Only a newly past-marked stop farther
 * along the same dated official route earns a comparison hint, never a location
 * change or an automatic promotion to railway-confirmed actuals. */
object SourceRecency {
    /** A passing station is after its preceding halt, before the next one. */
    fun progressOrdinal(haltIndex: Int, intermediate: Boolean): Int =
        if (haltIndex < 0) -1 else haltIndex * 2 + if (intermediate) 1 else 0

    fun showComparison(officialIndex: Int, siteIndex: Int, officialAgeMs: Long,
                       pageAgeMs: Long, siteEventAgeMs: Long): Boolean =
        officialIndex >= 0 && siteIndex > officialIndex && officialAgeMs >= 300_000L &&
            pageAgeMs in 0L..300_000L && siteEventAgeMs in 0L..300_000L

    /** Agreement, not proof of present position: a dated railway stop and a
     * recent undated website page named the SAME stop at nearly the same minute. */
    fun agreementMinutes(officialCode: String?, officialMinute: Int?,
                         siteCode: String?, siteMinute: Int?, pageAgeMs: Long,
                         siteEventAgeMs: Long): Int? {
        if (officialCode == null || siteCode == null ||
            !officialCode.equals(siteCode, true) || officialMinute == null || siteMinute == null ||
            pageAgeMs !in 0L..300_000L || siteEventAgeMs !in 0L..600_000L) return null
        return kotlin.math.abs(officialMinute - siteMinute).takeIf { it <= 2 }
    }

    /** Compare passage/event clocks, never either source's page/feed update stamp. */
    fun officialIsNewer(officialEventAgeMs: Long?, siteEventAgeMs: Long?): Boolean =
        officialEventAgeMs != null && siteEventAgeMs != null &&
            officialEventAgeMs >= 0L && siteEventAgeMs >= 0L &&
            officialEventAgeMs + 120_000L < siteEventAgeMs
}
