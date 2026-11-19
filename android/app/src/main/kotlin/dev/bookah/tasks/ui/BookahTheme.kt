package dev.bookah.tasks.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.bookah.tasks.design.Appearance
import dev.bookah.tasks.design.ThemeCatalog
import dev.bookah.tasks.design.ThemeColors

/** The theme tokens screens read directly (tiles, hairlines) beside the Material scheme. */
val LocalThemeColors = staticCompositionLocalOf { ThemeCatalog.get(ThemeCatalog.DEFAULT_ID).dark }

/** Maps one Hikyaku family onto Material 3 following docs/design-spec.md section 3.2. Washi & Ink and System appearance until Settings exists. */
@Composable
fun BookahTheme(
    familyId: String = ThemeCatalog.DEFAULT_ID, appearance: Appearance = Appearance.System,
    /** The picture to show in Today, the menu and empty pages (a drawable id), or null for no artwork. */
    artwork: Int? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (appearance) { Appearance.Dark -> true; Appearance.Light -> false; Appearance.System -> isSystemInDarkTheme() }
    val family = ThemeCatalog.get(familyId)
    val t: ThemeColors = if (dark) family.dark else family.light
    // every derived colour comes from the design module, where ThemeContrastTest checks the text drawn on it
    val d = dev.bookah.tasks.design.SchemeTokens.of(family, dark)
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        background = t.bg, onBackground = t.text,
        surface = t.surface, onSurface = t.text, onSurfaceVariant = t.textMuted,
        surfaceContainer = t.raised, surfaceContainerHigh = t.surface3, surfaceContainerLow = t.sidebar,
        // the roles Material would otherwise fill with its own purple: the lifted reorder row, switch tracks, dialogs' deepest layer
        surfaceVariant = t.surface3, surfaceContainerHighest = t.surface3, surfaceContainerLowest = t.bg,
        primary = t.accent, onPrimary = t.onAccent,
        // the "done" swipe background
        primaryContainer = d.accentTint, onPrimaryContainer = t.text,
        secondary = t.secondary, onSecondary = t.onSecondary,
        // selected drawer item and chips: a 20% tint of the secondary accent (docs/design-spec.md 3.2)
        secondaryContainer = d.chipSelected, onSecondaryContainer = t.text,
        // the Undo bar
        inverseSurface = d.inverseSurface, inverseOnSurface = d.inverseText, inversePrimary = d.inverseAccent,
        // the edge of a text field or an outlined button must be seen (3:1 for controls): the muted ink, not the faint hairline
        outline = t.textMuted, outlineVariant = t.border,
        error = t.danger,
    )
    // system bar icons follow the theme: light icons on a dark page, dark icons on a light page
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
    CompositionLocalProvider(LocalThemeColors provides t, LocalArtwork provides artwork) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
