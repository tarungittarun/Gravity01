package `in`.opentrack.india.ui

import `in`.opentrack.india.R
import `in`.opentrack.india.domain.TrackModel

/** Green is reserved for an explicitly early report. An on-time or unknown
 * delay is neutral; a positive measured delay is red, even if only one minute. */
object DelayPresentation {
    fun color(tone: TrackModel.Tone, actualMinutesLate: Int? = null): Int = when {
        actualMinutesLate != null && actualMinutesLate < 0 -> R.color.early
        actualMinutesLate != null && actualMinutesLate > 0 -> R.color.late
        actualMinutesLate == 0 -> R.color.delay_neutral
        tone == TrackModel.Tone.LIVE_EARLY -> R.color.early
        tone == TrackModel.Tone.LIVE_LATE || tone == TrackModel.Tone.PROBLEM -> R.color.late
        tone == TrackModel.Tone.SCHEDULE || tone == TrackModel.Tone.NONE -> R.color.warn
        else -> R.color.delay_neutral
    }

    fun mirrorLabel(actualMinutesLate: Int?, default: String): String = when {
        actualMinutesLate == null -> default
        actualMinutesLate > 0 -> "$actualMinutesLate min late"
        actualMinutesLate < 0 -> "${-actualMinutesLate} min early"
        else -> "on time"
    }
}
