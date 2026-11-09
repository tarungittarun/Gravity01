package `in`.opentrack.india.ui

import `in`.opentrack.india.remote.WimtLiveProvider
import `in`.opentrack.india.v2.mirror.PublicPageChecks

/** Preferences use the exact selector key, never the shortened display name. */
object TrackingSources {
    const val OFFICIAL = "NTES (official)"
    const val WIMT = WimtLiveProvider.SOURCE_NAME

    val options = listOf(OFFICIAL, WIMT)

    fun label(name: String): String = when (name) {
        OFFICIAL -> "NTES · official"
        WIMT -> "Where Is My Train · live"
        PublicPageChecks.ORG -> "Where Is My Train · live"
        else -> name
    }

    fun valid(name: String?): String = when (name) {
        in options -> name!!
        PublicPageChecks.ORG -> PublicPageChecks.ORG
        else -> OFFICIAL
    }
}
