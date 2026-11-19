package dev.bookah.tasks.design

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/** WCAG 2 contrast maths and the rule for text on a calendar-coloured chip. */
object Contrast {
    private fun lin(v: Float): Double { val c = v.toDouble(); return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4) }
    fun luminance(c: Color): Double = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)

    /** The contrast ratio of two opaque colours, from 1.0 to 21.0. */
    fun ratio(a: Color, b: Color): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    fun over(top: Color, bottom: Color): Color {
        val a = top.alpha
        return Color(top.red * a + bottom.red * (1 - a), top.green * a + bottom.green * (1 - a), top.blue * a + bottom.blue * (1 - a), 1f)
    }
}

/** Text colour and (only if needed) a shown fill for an event chip. The calendar's own colour is never changed in the data. */
data class ChipColors(val fill: Color, val text: Color)

object ChipColor {
    private val WHITE = Color.White
    private val BLACK = Color.Black
    const val MIN = 4.5

    /** Black or white text, whichever contrasts more with [calendar]; if neither reaches 4.5:1 the shown fill moves toward black or white in 5% steps until one does. */
    fun forCalendar(calendar: Color): ChipColors {
        var fill = calendar
        for (step in 0..20) {
            val w = Contrast.ratio(WHITE, fill); val b = Contrast.ratio(BLACK, fill)
            if (maxOf(w, b) >= MIN) return ChipColors(fill, if (w >= b) WHITE else BLACK)
            // nudge toward whichever extreme is already closer
            val target = if (w >= b) BLACK else WHITE
            fill = Color(fill.red + (target.red - fill.red) * 0.05f, fill.green + (target.green - fill.green) * 0.05f, fill.blue + (target.blue - fill.blue) * 0.05f, 1f)
        }
        return ChipColors(fill, if (Contrast.ratio(WHITE, fill) >= Contrast.ratio(BLACK, fill)) WHITE else BLACK)
    }

    fun parse(hex: String): Color {
        val s = hex.removePrefix("#")
        return Color((0xFF000000L or s.take(6).toLong(16)).toInt())
    }
}
