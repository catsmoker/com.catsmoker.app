package com.catsmoker.app.features.gamingtools.tools.overlay

/**
 * Position math for the performance readout, split out of the service so it can be tested
 * off-device.
 *
 * The readout is a `Gravity.TOP or Gravity.START` window: its params are absolute pixels from the
 * screen's top-left corner, so the legal range on each axis is `0 .. display - view - margin`.
 * [bounds] answers that range and [Bounds.clamp] folds a stored or dragged position into it.
 *
 * A margin is kept on both axes so a dragged panel never ends up flush against the physical edge,
 * where a cutout or a rounded corner could clip it with no outline left to show it is still there.
 * A window larger than the panel leaves zero travel rather than a negative range, so a clamp can
 * never answer a value outside the display.
 */
internal object OverlayPosition {

    data class Bounds(val maxX: Int, val maxY: Int) {

        /** Folds a position into the display, answering it untouched when it already fits. */
        fun clamp(x: Int, y: Int): Pair<Int, Int> =
            x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    /**
     * The furthest the window may sit from the top-left corner of a
     * [displayWidth]x[displayHeight] panel.
     */
    fun bounds(
        displayWidth: Int,
        displayHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        marginPx: Int
    ): Bounds = Bounds(
        maxX = (displayWidth - viewWidth - marginPx).coerceAtLeast(0),
        maxY = (displayHeight - viewHeight - marginPx).coerceAtLeast(0)
    )
}
