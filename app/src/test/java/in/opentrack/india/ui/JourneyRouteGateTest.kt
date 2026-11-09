package `in`.opentrack.india.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyRouteGateTest {
    @Test fun aReusedNumberCannotPaintAnUnrelatedDeviceRun() {
        assertFalse(JourneyRouteGate.covers(listOf("MYS", "CPT", "MAS"), "TLGP", "MYS"))
        assertFalse(JourneyRouteGate.covers(listOf("TLGP", "MYS", "MAS"), "MYS", "TLGP"))
        assertTrue(JourneyRouteGate.covers(listOf("TLGP", "SPV", "MYS"), "TLGP", "MYS"))
        assertTrue(JourneyRouteGate.covers(listOf("TLGP", "SPV", "MYS"), "SPV", "MYS"))
        assertTrue(JourneyRouteGate.covers(listOf("TLGP", "SPV", "MYS"), "", "MYS"))
    }
}
