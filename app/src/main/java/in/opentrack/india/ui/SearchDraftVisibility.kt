package `in`.opentrack.india.ui

/** No old result row while an edited query has not been submitted, even if the keyboard closes. */
object SearchDraftVisibility {
    fun showResults(draft: Boolean, keyboardOpen: Boolean, hasResults: Boolean): Boolean =
        !draft && (!keyboardOpen || hasResults)
}
