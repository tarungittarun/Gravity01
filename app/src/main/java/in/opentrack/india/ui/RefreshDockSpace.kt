package `in`.opentrack.india.ui

import android.view.View

/** Keep the clock-free parking strip only until the traveller positions Refresh.
 * Afterwards it is unused black space: return its 60dp to the route viewport
 * without moving the button (its coordinates belong to the root overlay). */
object RefreshDockSpace {
    fun release(dock: View) {
        if (dock.layoutParams.height != 0) {
            dock.layoutParams = dock.layoutParams.apply { height = 0 }
        }
    }
}
