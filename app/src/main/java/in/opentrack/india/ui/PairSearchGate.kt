package `in`.opentrack.india.ui

import `in`.opentrack.india.domain.TrainFinder

/** A website list cannot override an invalid offline query or a newer search. */
internal object PairSearchGate {
    fun eligible(report: TrainFinder.Report): Boolean {
        val from = report.from?.code ?: return false
        val to = report.to?.code ?: return false
        return report.reason != TrainFinder.Reason.SAME_STATION &&
            report.reason != TrainFinder.Reason.STATION_NOT_FOUND &&
            !from.equals(to, ignoreCase = true)
    }

    fun current(requestSerial: Long, activeSerial: Long): Boolean = requestSerial == activeSerial
}
