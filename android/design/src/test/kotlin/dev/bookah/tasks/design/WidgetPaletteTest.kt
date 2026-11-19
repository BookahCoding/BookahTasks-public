package dev.bookah.tasks.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPaletteTest {
    private fun c(i: Int) = Color(i)

    @Test fun everyFamilyReadsOnItsWidgetPanelInLightAndDark() {
        val bad = mutableListOf<String>()
        for (f in ThemeCatalog.all) for (dark in listOf(false, true)) {
            val w = WidgetPalette.of(f, dark)
            fun check(what: String, fg: Int, bg: Int) { val r = Contrast.ratio(c(fg), c(bg)); if (r < 4.5) bad += "${f.id} dark=$dark $what %.2f".format(r) }
            check("text/panel", w.text, w.panel)
            check("muted/panel", w.muted, w.panel)
            check("accent/panel", w.accent, w.panel)        // the header title
            check("danger/panel", w.danger, w.panel)        // the overdue row
            check("onAccent/accent", w.onAccent, w.accent)  // the + on its round button
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun thePanelIsTheThemesPageColour() {
        val f = ThemeCatalog.get("washi-ink")
        assertTrue(WidgetPalette.of(f, true).panel == f.dark.bg.toArgb())
    }
}
