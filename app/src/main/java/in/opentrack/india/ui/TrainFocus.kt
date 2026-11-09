package `in`.opentrack.india.ui

/** scrollToPositionWithOffset takes a row TOP, while a train can be deep inside
 * an expanded leg. Make the train centre, not that row top, 32% down the viewport. */
object TrainFocus {
    fun rowOffset(viewportPx: Int, iconYWithinRowPx: Float): Int =
        (viewportPx * .32f - iconYWithinRowPx).toInt()
}
