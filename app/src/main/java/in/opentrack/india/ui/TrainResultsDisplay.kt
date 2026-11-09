package `in`.opentrack.india.ui

import android.text.SpannableString
import android.text.Spanned
import android.text.style.UnderlineSpan
import `in`.opentrack.india.model.TrainHit

object TrainResultsDisplay {
    enum class Sort { SUGGESTED, DEPARTURE, ARRIVAL, DURATION }

    fun sorted(hits: List<TrainHit>, sort: Sort): List<TrainHit> = when (sort) {
        Sort.SUGGESTED -> hits
        Sort.DEPARTURE -> hits.sortedWith(compareBy<TrainHit> { it.boardEpochMinute }
            .thenBy { it.trainNo })
        Sort.ARRIVAL -> hits.sortedWith(compareBy<TrainHit> { it.alightEpochMinute }
            .thenBy { it.trainNo })
        Sort.DURATION -> hits.sortedWith(compareBy<TrainHit> { it.durationMin }
            .thenBy { it.boardEpochMinute }.thenBy { it.trainNo })
    }

    /** An erail listing can revise identity and times, not a weekly calendar. */
    fun withAssetDays(hits: List<TrainHit>, asset: List<TrainHit>): List<TrainHit> {
        val days = asset.associate { it.trainNo to it.runDays }
        return hits.map { h ->
            val known = days[h.trainNo]
            if (h.runDays == null && known != null) h.copy(runDays = known) else h
        }
    }

    /** Timetable mask: Sunday is bit 0; Monday bit 6 through Saturday bit 1. */
    fun active(mask: Int, sundayFirstIndex: Int): Boolean {
        require(sundayFirstIndex in 0..6)
        val bit = if (sundayFirstIndex == 0) 0 else 7 - sundayFirstIndex
        return mask and (1 shl bit) != 0
    }

    /** Always show S M T W T F S, with ONLY the operating days underlined. */
    fun frequency(mask: Int?): CharSequence {
        if (mask == null) return "Runs · listed for selected date; weekly days unverified"
        val letters = "S M T W T F S"
        val value = SpannableString("Runs · $letters")
        val offset = value.length - letters.length
        for (i in 0..6) if (active(mask, i))
            value.setSpan(UnderlineSpan(), offset + i * 2, offset + i * 2 + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return value
    }
}
