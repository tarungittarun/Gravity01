package `in`.opentrack.india.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchDraftVisibilityTest {
    @Test fun oldResultsStayHiddenWhenEditOpensOrKeyboardCloses() {
        assertTrue(SearchDraftVisibility.showResults(false, false, true))
        assertTrue(SearchDraftVisibility.showResults(false, true, true))
        assertFalse(SearchDraftVisibility.showResults(true, false, true))
        assertFalse(SearchDraftVisibility.showResults(true, true, true))
        assertFalse(SearchDraftVisibility.showResults(true, false, false))
        assertFalse(SearchDraftVisibility.showResults(false, true, false))
    }
}
