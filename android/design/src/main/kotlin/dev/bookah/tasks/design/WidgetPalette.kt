package dev.bookah.tasks.design

import androidx.compose.ui.graphics.toArgb

/** The few colours the widget draws, as ARGB ints (RemoteViews takes ints, not Compose colours). All opaque. */
data class WidgetColors(val panel: Int, val text: Int, val muted: Int, val accent: Int, val onAccent: Int, val danger: Int)

object WidgetPalette {
    fun of(family: ThemeFamily, dark: Boolean): WidgetColors {
        val t = if (dark) family.dark else family.light
        return WidgetColors(
            panel = t.bg.toArgb(), text = t.text.toArgb(), muted = t.textMuted.toArgb(),
            accent = t.accent.toArgb(), onAccent = t.onAccent.toArgb(), danger = t.danger.toArgb(),
        )
    }
}
