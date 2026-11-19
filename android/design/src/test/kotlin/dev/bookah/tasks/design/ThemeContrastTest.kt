package dev.bookah.tasks.design

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every text colour the app draws stays readable (WCAG AA, 4.5:1) on every ground it is drawn on, in every family, light and dark
 * (Phase 6 accessibility). The pairs follow the screens: rows and headings on the page, the day-strip pills and dialogs on surface3, sheets
 * and the drawer on the sidebar, selected chips, the quick-add tint, the swipe backgrounds and the Undo bar.
 */
class ThemeContrastTest {
    private val bad = mutableListOf<String>()

    private fun check(what: String, fg: Color, bg: Color, min: Double = 4.5) {
        val r = Contrast.ratio(fg, bg)
        if (r < min) bad += "$what %.2f".format(r)
    }

    @Test fun familiesHaveUniqueIdsAndTheDefaultExists() {
        val ids = ThemeCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ThemeCatalog.DEFAULT_ID in ids)
        assertEquals(11, ids.size)
    }

    @Test fun everyTextColourReadsOnEverySurface() {
        for (f in ThemeCatalog.all) for ((mode, t) in listOf("light" to f.light, "dark" to f.dark)) {
            val grounds = listOf("bg" to t.bg, "surface" to t.surface, "sidebar" to t.sidebar, "raised" to t.raised, "surface3" to t.surface3, "well" to t.well)
            for ((gName, g) in grounds) {
                // body text, secondary text and the muted line (dates, list names, pill times, hints) everywhere
                check("${f.id} $mode text/$gName", t.text, g)
                check("${f.id} $mode text2/$gName", t.text2, g)
                check("${f.id} $mode muted/$gName", t.textMuted, g)
                // the accent is text too: links, Settings group titles, the repeat summary, text buttons in dialogs (surface3) and sheets (sidebar)
                check("${f.id} $mode accent/$gName", t.accent, g)
                // the danger colour is the overdue date and header, warnings in dialogs, "Delete task"
                check("${f.id} $mode danger/$gName", t.danger, g)
            }
            check("${f.id} $mode onAccent/accent", t.onAccent, t.accent)              // filled buttons, the "+" button
            check("${f.id} $mode onSecondary/secondary", t.onSecondary, t.secondary)
        }
        assertTrue(bad.joinToString(" | "), bad.isEmpty())
    }

    @Test fun derivedGroundsKeepTheirTextReadable() {
        for (f in ThemeCatalog.all) for (dark in listOf(false, true)) {
            val t = if (dark) f.dark else f.light
            val d = SchemeTokens.of(f, dark)
            val mode = if (dark) "dark" else "light"
            // a selected chip and the selected drawer item draw their label and count in the text colour (onSecondaryContainer), never muted
            check("${f.id} $mode text/chipSelected", t.text, d.chipSelected)
            check("${f.id} $mode text/accentTint", t.text, d.accentTint)
            check("${f.id} $mode text/hitTint", t.text, d.hitTint)
            check("${f.id} $mode undoText/inverse", d.inverseText, d.inverseSurface)
            check("${f.id} $mode undoButton/inverse", d.inverseAccent, d.inverseSurface)
            // the tick box check mark on a list colour is chosen by ChipColor; the theme accent is a list colour too
            val chip = ChipColor.forCalendar(t.accent)
            check("${f.id} $mode chipInk/accent", chip.text, chip.fill)
        }
        assertTrue(bad.joinToString(" | "), bad.isEmpty())
    }

    @Test fun derivedColoursAreOpaque() {
        for (f in ThemeCatalog.all) for (dark in listOf(false, true)) {
            val d = SchemeTokens.of(f, dark)
            for (c in listOf(d.chipSelected, d.accentTint, d.hitTint, d.inverseSurface, d.inverseText, d.inverseAccent)) assertEquals(1f, c.alpha, 0.001f)
        }
    }

    /** A list colour from Nextcloud is shown as it is; the text on it (the tick box check, a checkbox) is black or white and always reads. */
    @Test fun textOnAnyListColourReads() {
        for (hex in listOf("#0082C9", "#FFEB3B", "#FFFFFF", "#000000", "#E9322D", "#808080", "#7A7A7A", "#C98879", "#31CC7C", "#245A86")) {
            val c = ChipColor.forCalendar(ChipColor.parse(hex))
            check("list $hex", c.text, c.fill)
        }
        assertTrue(bad.joinToString(" | "), bad.isEmpty())
    }
}
