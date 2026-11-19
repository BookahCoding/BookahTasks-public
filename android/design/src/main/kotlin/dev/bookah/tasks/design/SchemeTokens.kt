package dev.bookah.tasks.design

import androidx.compose.ui.graphics.Color

/**
 * The colours the app works out from a family's tokens (docs/design-spec.md 3.2), kept here in one place so the contrast test checks exactly
 * what the screens draw. Every value is opaque.
 */
data class DerivedColors(
    /** Selected chips and the selected drawer item: the secondary accent at 20% over the sidebar. Text on it: [ThemeColors.text]. */
    val chipSelected: Color,
    /** The "done" swipe background: the main accent at 20% over the page. Text on it: [ThemeColors.text]. */
    val accentTint: Color,
    /** Quick add: the background of recognised words (secondary at 25% over the sheet, which is the sidebar colour). Text on it: text. */
    val hitTint: Color,
    /** The Undo bar: the other appearance's raised surface, its text and its accent (the Undo button). */
    val inverseSurface: Color,
    val inverseText: Color,
    val inverseAccent: Color,
)

object SchemeTokens {
    fun of(family: ThemeFamily, dark: Boolean): DerivedColors {
        val t = if (dark) family.dark else family.light
        val other = if (dark) family.light else family.dark
        return DerivedColors(
            chipSelected = Contrast.over(t.secondary.copy(alpha = 0.2f), t.sidebar),
            accentTint = Contrast.over(t.accent.copy(alpha = 0.2f), t.bg),
            hitTint = Contrast.over(t.secondary.copy(alpha = 0.25f), t.sidebar),
            inverseSurface = other.surface3,
            inverseText = other.text,
            inverseAccent = other.accent,
        )
    }
}
