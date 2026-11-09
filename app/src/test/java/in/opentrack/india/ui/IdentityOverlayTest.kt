package `in`.opentrack.india.ui

import `in`.opentrack.india.v2.contract.Provenance
import `in`.opentrack.india.v2.contract.SourceKind
import `in`.opentrack.india.v2.contract.TrainIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The details screen's live-identity overlay, as pure functions (the Activity computes nothing, 8.2):
 * when an erail identity reached the device, the label must say what the live source says the number
 * runs - and when it matches the device's timetable, say THAT too, because a confirmation is also a
 * fact. These cases are the three shapes the screen can meet, decided here so no screen arithmetic
 * can invent a fourth.
 */
class IdentityOverlayTest {

    private fun live(from: String, to: String, dep: Int?, arr: Int?, wrap: Int) = ErailLiveIdentity(
        id(from, to), dep, arr, wrap,
    )

    @Test
    fun the_live_only_train_gets_a_two_stop_route_not_a_dead_end() {
        val route = LiveViewModel.syntheticRoute(live("YPR", "SMET", 555, 845, 0))
        assertEquals(2, route.stops.size)
        assertEquals("YPR", route.stops[0].code)
        assertEquals(555, route.stops[0].departureMin)
        assertEquals("SMET", route.stops[1].code)
        assertEquals("the terminus arrival is a clock minute", 845, route.stops[1].arrivalMin)
        assertEquals(0, route.stops[1].journeyDay)
        assertEquals("the route carries the live identity's own name", "TLGP-MYS KUVEMPU EXPRESS", route.trainName)
    }

    @Test
    fun the_day_wrap_folds_into_the_terminus_stop_s_journey_day() {
        val route = LiveViewModel.syntheticRoute(live("TLGP", "MYS", 1255, 500, 1))
        assertEquals(1, route.stops[1].journeyDay)
        assertEquals("arrival stays a clock minute; the day is the offset", 500, route.stops[1].arrivalMin)
    }

    @Test
    fun the_fallback_note_names_what_is_missing_and_any_pair_mismatch() {
        val note = LiveViewModel.fallbackNote(live("YPR", "SMET", 555, 845, 0), "YPR", "SMET")
        assertTrue(note.contains("not in the device's offline timetable"))
        assertTrue(note.contains("per-stop halts are not available"))
        val mismatch = LiveViewModel.fallbackNote(live("TLGP", "MYS", 330, 930, 0), "SMET", "MYS")
        assertTrue("got: $mismatch", mismatch.contains("TLGP → MYS"))
        assertTrue("got: $mismatch", mismatch.contains("not SMET → MYS"))
    }

    private fun id(from: String, to: String) = TrainIdentity(
        number = "16221",
        name = "TLGP-MYS KUVEMPU EXPRESS",
        fromCode = from,
        toCode = to,
        provenance = Provenance(SourceKind.LIVE_PAIR, "erail", 1_000L),
    )

    @Test
    fun no_live_identity_leaves_the_timetable_label_alone() {
        assertNull(LiveViewModel.identityOverlay("SMET", "MYS", null))
    }

    @Test
    fun matching_identity_is_stated_as_a_confirmation_not_silence() {
        val overlay = LiveViewModel.identityOverlay("TLGP", "MYS", id("tlgp", "MYS"))
        assertNotNull(overlay)
        assertTrue("got: $overlay", overlay!!.contains("confirmed"))
        assertTrue("got: $overlay", overlay.contains("erail"))
    }

    @Test
    fun mismatching_identity_carries_both_readings_and_names_the_table_as_the_timetable_s() {
        val overlay = LiveViewModel.identityOverlay("SMET", "MYS", id("TLGP", "MYS"))
        assertNotNull(overlay)
        assertTrue("got: $overlay", overlay!!.contains("TLGP → MYS"))
        assertTrue("got: $overlay", overlay.contains("SMET"))
        assertTrue("got: $overlay", overlay.contains("timetable"))
    }
}
