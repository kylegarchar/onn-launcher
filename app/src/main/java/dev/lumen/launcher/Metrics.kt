package dev.lumen.launcher

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * All layout geometry, in pixels.
 *
 * Everything is derived from one unit, [u] = 1/960 of the screen width, so the home screen
 * has identical proportions whether the box renders its UI at 1080p or 4K and whatever
 * density it reports.
 */
@Immutable
class Metrics(
    val screenW: Float,
    val screenH: Float,
    val density: Float,
    val fontScale: Float,
) {
    val u: Float = screenW / 960f

    val side: Float = 52f * u
    val gap: Float = 18f * u
    val cardW: Float = (screenW - 2f * side - (COLS - 1) * gap) / COLS
    val cardH: Float = cardW * 9f / 16f
    val corner: Float = 9f * u

    /** Vertical distance between one grid row and the next. */
    val rowPitch: Float = cardH + 38f * u

    val dockTop: Float = 282f * u
    val glassPad: Float = 14f * u
    val gridTop: Float = dockTop + cardH + 54f * u

    /** Where the focused grid row comes to rest on screen. */
    val anchor: Float = 196f * u

    /** Icons are rendered at their focused size so the card you are looking at is pixel-exact. */
    val iconW: Int = (cardW * FOCUS_SCALE).roundToInt().coerceAtLeast(16)
    val iconH: Int = (cardH * FOCUS_SCALE).roundToInt().coerceAtLeast(9)

    fun px(units: Float): Float = units * u

    fun dp(units: Float): Dp = (units * u / density).dp

    fun toDp(pixels: Float): Dp = (pixels / density).dp

    fun sp(units: Float): TextUnit = (units * u / (density * fontScale)).sp

    fun slotX(col: Int): Float = side + col * (cardW + gap)

    fun gridY(row: Int): Float = gridTop + row * rowPitch

    companion object {
        const val COLS = 6
        const val MAX_DOCK = 6
        const val FOCUS_SCALE = 1.12f
    }
}
