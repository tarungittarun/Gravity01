package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebsiteGapSummaryUiTest {
    @Test fun compactGapDoesNotRedateOrInventArrival() {
        val full = "Page updated 04:50:08 AM; website has no YPR arrival row. " +
            "Last displayed: TK (comparison only); position unknown. This is not the phone app."
        assertEquals("No YPR site arrival · last listed TK (comparison). Position unknown.",
            WebsiteGapSummaryUi.concise(full))
        assertEquals("other failure", WebsiteGapSummaryUi.concise("other failure"))
        assertNull(SummaryNoteUi.visible("No confirmed times from this site; timetable shown.",
            websiteGapShown = true))
        assertEquals("No confirmed times from this site; timetable shown.",
            SummaryNoteUi.visible("No confirmed times from this site; timetable shown."))
        assertEquals("Connectivity failed · previous report unchanged",
            SummaryNoteUi.visible("Connectivity failed · previous report unchanged", true))
    }
}
