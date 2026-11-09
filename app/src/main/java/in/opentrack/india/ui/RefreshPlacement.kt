package `in`.opentrack.india.ui

/** Refresh is an overlay, not a row in the scrolling source card. Coordinates are
 * relative to the content viewport (excluding status/navigation bars). The button
 * may be moved only within the lower half; fractions survive orientation changes. */
object RefreshPlacement {
    data class Point(val x: Float, val y: Float)

    fun at(width: Int, height: Int, buttonWidth: Int, buttonHeight: Int, inset: Int,
           centerX: Float = 0.875f, centerY: Float = 0.8f): Point {
        val left = inset.toFloat()
        val right = (width - buttonWidth - inset).coerceAtLeast(inset).toFloat()
        val top = (height / 2f).coerceAtMost((height - buttonHeight).coerceAtLeast(0).toFloat())
        val bottom = (height - buttonHeight - inset).toFloat().coerceAtLeast(top)
        return Point((width * centerX - buttonWidth / 2f).coerceIn(left, right),
            (height * centerY - buttonHeight / 2f).coerceIn(top, bottom))
    }

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Let the owner move the control, but never park it over a visible clock.
     * The reserved bottom dock is always a last-resort position. */
    fun settle(width: Int, height: Int, buttonWidth: Int, buttonHeight: Int,
               inset: Int, x: Float, y: Float, clockBoxes: List<Box>): Point {
        val wanted = drag(width, height, buttonWidth, buttonHeight, inset, x, y)
        fun free(p: Point): Boolean = clockBoxes.none { box ->
            p.x < box.right + inset && p.x + buttonWidth > box.left - inset &&
                p.y < box.bottom + inset && p.y + buttonHeight > box.top - inset
        }
        if (free(wanted)) return wanted
        val step = (minOf(buttonWidth, buttonHeight) / 2).coerceAtLeast(12)
        val maxX = (width - buttonWidth - inset).coerceAtLeast(inset)
        val maxY = (height - buttonHeight - inset).coerceAtLeast(height / 2)
        var best: Point? = null
        var distance = Float.MAX_VALUE
        for (cy in height / 2..maxY step step) for (cx in inset..maxX step step) {
            val candidate = drag(width, height, buttonWidth, buttonHeight, inset,
                cx.toFloat(), cy.toFloat())
            val d = (candidate.x - wanted.x) * (candidate.x - wanted.x) +
                (candidate.y - wanted.y) * (candidate.y - wanted.y)
            if (d < distance && free(candidate)) { best = candidate; distance = d }
        }
        return best ?: drag(width, height, buttonWidth, buttonHeight, inset,
            (width - buttonWidth - inset).toFloat(), (height - buttonHeight - inset).toFloat())
    }

    fun drag(width: Int, height: Int, buttonWidth: Int, buttonHeight: Int, inset: Int,
             x: Float, y: Float): Point = at(width, height, buttonWidth, buttonHeight, inset,
        (x + buttonWidth / 2f) / width.coerceAtLeast(1),
        (y + buttonHeight / 2f) / height.coerceAtLeast(1))
}
