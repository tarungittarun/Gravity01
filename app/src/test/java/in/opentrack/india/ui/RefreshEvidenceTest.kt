package `in`.opentrack.india.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshEvidenceTest {
    @Test fun aRevisedMinuteAtHassanIsNotAnotherStop() {
        assertEquals(RefreshEvidence.Change.REVISED_AT_SAME_STOP,
            RefreshEvidence.compare("HAS:727", "HAS:730"))
        assertEquals(RefreshEvidence.Change.UNCHANGED,
            RefreshEvidence.compare("BGPA:702", "BGPA:702"))
        assertEquals(RefreshEvidence.Change.NEW_STOP,
            RefreshEvidence.compare("BGPA:702", "HAS:727"))
        assertEquals(RefreshEvidence.Change.UNCHANGED,
            RefreshEvidence.compare("BGPA:702", null))
    }
}
