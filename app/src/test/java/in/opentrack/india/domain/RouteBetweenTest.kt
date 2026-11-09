package `in`.opentrack.india.domain

import `in`.opentrack.india.model.SourceKind
import `in`.opentrack.india.model.StopRow
import `in`.opentrack.india.model.StopStatus
import `in`.opentrack.india.model.TrainPosition
import `in`.opentrack.india.model.TrainRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteBetweenTest {
    private val route = TrainRoute(
        trainNo = "20652", trainName = "Test", srcCode = "AAA", srcName = "AAA",
        dstCode = "DDD", dstName = "DDD", totalKm = 40, totalMin = null,
        runDays = 127, returnNo = null,
        stops = listOf(
            StopRow(1, "AAA", "Start", null, null, null, null, 600, 0, 0, true),
            StopRow(2, "BBB", "Passing", null, null, null, null, null, 0, 10, false),
            StopRow(3, "CCC", "Halt", null, null, null, 700, 705, 0, 20, true),
            StopRow(4, "DDD", "End", null, null, null, 800, null, 0, 40, true),
        ), segments = emptyList(),
    )

    @Test fun officialScheduleUpgradesOnlyTheHaltsItActuallyConfirms() {
        val official = route.copy(stops = route.stops.map { if (it.code == "BBB")
            it.copy(isHalt = true, arrivalMin = 645, departureMin = 646) else it })
        val reconciled = RouteBetween.withVerifiedHalts(route, official)!!
        assertTrue(reconciled.stops[1].isHalt)
        assertEquals(645, reconciled.stops[1].arrivalMin)
        assertEquals(listOf("AAA", "BBB", "CCC", "DDD"), reconciled.stops.map { it.code })
        assertEquals(null, RouteBetween.withVerifiedHalts(route, official.copy(trainNo = "20689")))
    }

    @Test fun tapOnHaltRevealsOnlyNextLegAndLeavesPointUntimed() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        val groups = RouteBetween.groupedPoints(route, halts)
        assertEquals(listOf("BBB"), groups["AAA"]?.map { it.code })
        assertTrue(groups["CCC"].isNullOrEmpty())
        assertEquals(null, groups["AAA"]?.first()?.arrivalMin)
        assertEquals(emptyMap<String, List<StopRow>>(),
            RouteBetween.groupedPoints(route.copy(trainNo = "99999"), halts))
    }

    @Test fun aPointWithImpossibleMapKmKeepsItsNameButLosesItsNumber() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        val bad = route.copy(stops = route.stops.map { if (it.code == "BBB")
            it.copy(schedKm = 29) else it })
        assertEquals(-1, RouteBetween.groupedPoints(bad, halts)["AAA"]!!.single().schedKm)
    }

    @Test fun trainIconIsAProjectionNotAReportedIntermediateArrival() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        fun position(source: SourceKind, age: Long = 120) = TrainPosition(
            anchorSeq = 1, nextSeq = 2, fraction = .5f, kmAlong = 10f,
            status = StopStatus.DEPARTED, etaNextMinute = null, delayMinutes = 0,
            remainingMin = null, provenance = source, confidence = TrainPosition.Confidence.LOW,
            ageSeconds = age)
        val projection = RouteBetween.projectLeg(halts, route, position(SourceKind.PROVIDER_NTES))!!
        assertEquals("Passing", projection.nearestPoint)
        assertEquals(10, projection.estimateKm)
        assertEquals("AAA", projection.fromCode)
        assertEquals("CCC", projection.toCode)
        assertEquals(.5f, projection.points.single().fraction, .001f)
        assertEquals(null, RouteBetween.projectLeg(halts, route,
            position(SourceKind.OFFLINE_SCHEDULE)))
        assertEquals(null, RouteBetween.projectLeg(halts, route,
            position(SourceKind.PROVIDER_NTES, 601)))
    }

    @Test fun webCacheMarkerUsesOnlyAnInBracketKmAndCannotCreateAnArrival() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        val leg = RouteBetween.webCacheLeg(halts, route, "AAA", "CCC", 10.0)!!
        assertEquals(RouteBetween.MarkerKind.WEB_CACHE_CANDIDATE, leg.markerKind)
        assertEquals(.5f, leg.fraction, .001f)
        assertEquals("BBB", leg.points.single().code)
        assertEquals(null, RouteBetween.webCacheLeg(halts, route, "AAA", "DDD", 10.0))
        assertEquals(null, RouteBetween.webCacheLeg(halts, route, "AAA", "CCC", 21.0))
        assertEquals(null, RouteBetween.webCacheLeg(halts, route, "AAA", "CCC", Double.NaN))
        assertEquals(null, halts.stops[1].actualArrivalMin)
    }

    @Test fun departureCueIsOnlyAQuarterOfTheFirstMappedLegAndNeverAReportedKm() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        val cue = RouteBetween.departureCueLeg(halts, route, "AAA")!!
        assertEquals(RouteBetween.MarkerKind.REPORTED_DEPARTURE_CUE, cue.markerKind)
        assertEquals("Passing", cue.nearestPoint)
        assertEquals(.125f, cue.fraction, .001f) // first mapped point is halfway
        assertEquals(0, cue.estimateKm) // never advertise 1/4 as measured km
        assertEquals(.25f, RouteBetween.departureCueLeg(halts, null, "CCC")!!.fraction, .001f)
        assertEquals(null, RouteBetween.departureCueLeg(halts, null, "DDD"))
        assertEquals(RouteBetween.MarkerKind.SITE_DEPARTURE_SUGGESTION,
            RouteBetween.departureCueLeg(halts, route, "AAA", true)!!.markerKind)
    }

    @Test fun officialStopMarkerUsesDatedHaltEvenWithoutExpandedMap() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        val marker = RouteBetween.reportedHaltLeg(halts, null, "AAA")!!
        assertEquals(RouteBetween.MarkerKind.REPORTED_HALT, marker.markerKind)
        assertEquals(0f, marker.fraction, .001f)
        assertEquals("CCC", marker.toCode)
        assertTrue(marker.points.isEmpty())
        assertEquals("BBB", RouteBetween.reportedHaltLeg(halts, route, "AAA")!!
            .points.single().code)
        assertEquals(null, RouteBetween.reportedHaltLeg(halts, route, "DDD"))
        assertTrue(RouteBetween.reportedHaltLeg(halts,
            route.copy(trainNo = "wrong"), "AAA")!!.points.isEmpty()) // discard wrong map, retain dated halt
    }

    @Test fun providerStationClaimIsPinnedButNeverCreatesEventOrDistanceProgress() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        val claim = RouteBetween.stationClaimLeg(halts, route, "AAA")!!
        assertEquals(RouteBetween.MarkerKind.STATION_CLAIM, claim.markerKind)
        assertEquals(0f, claim.fraction, .001f)
        assertEquals("BBB", claim.points.single().code)
        assertEquals(null, RouteBetween.stationClaimLeg(halts, route, "BBB"))
        assertTrue(RouteBetween.stationClaimLeg(halts, null, "AAA")!!.points.isEmpty())
    }

    @Test fun websiteHaltMarkerStaysAtZeroWithoutInventingBetweenStopDistance() {
        val halts = route.copy(stops = route.stops.filter { it.isHalt }
            .mapIndexed { i, s -> s.copy(seq = i + 1) })
        val leg = RouteBetween.siteLastStopLeg(halts, route, "AAA")!!
        assertEquals(RouteBetween.MarkerKind.SITE_LAST_STOP, leg.markerKind)
        assertEquals(0f, leg.fraction, .001f)
        assertEquals("AAA", leg.fromCode)
        assertEquals("CCC", leg.toCode)
        assertEquals(.5f, leg.points.single().fraction, .001f) // static map, not train progress
        assertEquals(null, RouteBetween.siteLastStopLeg(halts, route, "DDD"))
        assertTrue(RouteBetween.siteLastStopLeg(halts,
            route.copy(trainNo = "another train"), "AAA")!!.points.isEmpty()) // no foreign ticks
        val withoutMap = RouteBetween.siteLastStopLeg(halts, null, "AAA")!!
        assertEquals(RouteBetween.MarkerKind.SITE_LAST_STOP, withoutMap.markerKind)
        assertEquals(0f, withoutMap.fraction, .001f)
        assertTrue(withoutMap.points.isEmpty())
        assertEquals(null, RouteBetween.siteLastStopLeg(halts, route, "BBB"))
    }

    @Test fun arbitraryPairUsesTrainOrderWithoutReadingPosition() {
        assertEquals(listOf("AAA", "BBB", "CCC"),
            RouteBetween.inclusive(route, "CCC", "AAA").map { it.code })
        assertEquals(listOf("BBB", "CCC", "DDD"),
            RouteBetween.inclusive(route, "BBB", "DDD").map { it.code })
        assertTrue(RouteBetween.inclusive(route, "X", "AAA").isEmpty())
        val passing = RouteBetween.inclusive(route, "AAA", "CCC")[1]
        assertTrue(!passing.isHalt && passing.arrivalMin == null && passing.departureMin == null)
    }
}
