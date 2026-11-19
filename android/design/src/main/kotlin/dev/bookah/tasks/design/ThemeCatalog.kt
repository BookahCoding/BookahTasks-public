package dev.bookah.tasks.design

import androidx.compose.ui.graphics.Color

/**
 * Colour tokens of one theme in one appearance. Values come from Hikyaku for Windows (`ThemeCatalog.cs`, MIT, see docs/CREDITS.md):
 * neutral off-white or black major surfaces plus two accents. The Hokusai families are interpretive colours, not measured artwork.
 */
data class ThemeColors(
    val bg: Color, val surface: Color, val sidebar: Color, val raised: Color, val surface3: Color, val well: Color,
    val text: Color, val text2: Color, val textMuted: Color,
    val accent: Color, val onAccent: Color, val secondary: Color, val onSecondary: Color,
    val border: Color, val danger: Color, val success: Color, val warning: Color,
    val markVermilion: Color, val markPaper: Color,
) {
    /** The six surfaces text must stay readable on. */
    val surfaces: List<Color> get() = listOf(bg, surface, sidebar, raised, surface3, well)
}

data class ThemeFamily(val id: String, val name: String, val inspiration: String, val light: ThemeColors, val dark: ThemeColors)

enum class Appearance { Light, Dark, System }

/** "RRGGBB" (opaque) or "AARRGGBB" like the WPF original. */
private fun c(hex: String): Color {
    val v = hex.toLong(16)
    return if (hex.length == 8) Color(v.toInt()) else Color((0xFF000000L or v).toInt())
}

private const val L_BG = "F7F7F5"; private const val L_SURFACE = "FAFAF8"; private const val L_SIDEBAR = "F0F0EE"
private const val L_RAISED = "F3F3F1"; private const val L_S3 = "EDEDEB"; private const val L_WELL = "F0F0EE"
private const val D_BG = "000000"; private const val D_SURFACE = "000000"; private const val D_SIDEBAR = "080808"
private const val D_RAISED = "171717"; private const val D_S3 = "202020"; private const val D_WELL = "0C0C0C"

/** Light palette: the neutral light surfaces plus this family's ink and accents. */
private fun light(
    text: String, text2: String, muted: String, accent: String, onAccent: String, border: String, danger: String, success: String,
    warning: String, mv: String, mp: String, secondary: String, onSecondary: String,
) = ThemeColors(
    c(L_BG), c(L_SURFACE), c(L_SIDEBAR), c(L_RAISED), c(L_S3), c(L_WELL), c(text), c(text2), c(muted), c(accent), c(onAccent), c(secondary), c(onSecondary),
    c(border), c(danger), c(success), c(warning), c(mv), c(mp),
)

/** Dark palette: the neutral black surfaces plus this family's ink and accents. */
private fun dark(
    text: String, text2: String, muted: String, accent: String, onAccent: String, border: String, danger: String, success: String,
    warning: String, mv: String, mp: String, secondary: String, onSecondary: String,
) = ThemeColors(
    c(D_BG), c(D_SURFACE), c(D_SIDEBAR), c(D_RAISED), c(D_S3), c(D_WELL), c(text), c(text2), c(muted), c(accent), c(onAccent), c(secondary), c(onSecondary),
    c(border), c(danger), c(success), c(warning), c(mv), c(mp),
)

object ThemeCatalog {
    const val DEFAULT_ID = "washi-ink"

    val all: List<ThemeFamily> = listOf(
        ThemeFamily("washi-ink", "Washi & Ink", "warm paper, sumi ink and indigo night",
            light("2B2822", "403B31", "6A5F50", "A63F2D", "FFFFFF", "140A0A1E", "8A1C45", "3F6A4B", "855608", "A63F2D", "F4EDD9", "245A86", "FFFFFF"),
            dark("F4EDD9", "DCD3BC", "A8A08C", "E5927E", "0A0A0C", "33F4EDD9", "F4739B", "7DBE8A", "E3B458", "A63F2D", "F4EDD9", "73B6E3", "080808")),
        ThemeFamily("great-wave", "Great Wave", "Prussian blue, pale sky and cream foam",
            light("0F2438", "243B52", "4A657A", "1B4F72", "FFFFFF", "1A0F2438", "8B1E3F", "2F6B4F", "80600C", "C45C3A", "F5FAFC", "9B4D25", "FFFFFF"),
            dark("E8F0F4", "C5D6E4", "7F9BB0", "5BA4D9", "07131F", "33E8F0F4", "F080A0", "6FBF95", "E0C05A", "E08A6A", "E8F0F4", "E9AA72", "080808")),
        ThemeFamily("red-fuji", "Red Fuji", "rust peak, cream mist and deep blue sky",
            light("2A1810", "4A3024", "7A5A48", "A84828", "FFFFFF", "182A1810", "8A2030", "3E6848", "8C6410", "A84828", "FFF8F0", "285A83", "FFFFFF"),
            dark("F7EFE6", "E0D0C0", "A89080", "E09070", "120E1C", "33F7EFE6", "F07088", "78B888", "E0B858", "C85838", "F7EFE6", "83B8E6", "080808")),
        ThemeFamily("pine-mist", "Pine & Mist", "moss green, slate mist and paper",
            light("1A2420", "2E3C36", "5A6A62", "3F6B4E", "FFFFFF", "181A2420", "8A2A3A", "2F6848", "7A6810", "8B4A32", "F7FAF5", "79447F", "FFFFFF"),
            dark("EEF2EC", "D0D8D0", "889488", "7AAA88", "0C1410", "33EEF2EC", "E08090", "80C098", "D0C060", "C07050", "EEF2EC", "CB9FDF", "080808")),
        ThemeFamily("chrysanthemum", "Chrysanthemums", "golden blooms, leaf green and pale paper",
            light("26201A", "3E3528", "6B5C42", "8C5A0B", "FFFFFF", "1426201A", "8E2A1E", "3D6A3A", "7E5A06", "B4462A", "FCF8EE", "326341", "FFFFFF"),
            dark("F4ECD8", "DCD0B4", "A9997A", "E0B04A", "14130E", "33F4ECD8", "F08A6E", "8CC08A", "E8C060", "D46A44", "F4ECD8", "8EC7A4", "080808")),
        ThemeFamily("peony", "Peonies", "rose peonies, deep leaves and blush paper",
            light("2A1D22", "43303A", "765A64", "9C2E58", "FFFFFF", "142A1D22", "8E1F2E", "2F6A4A", "84600E", "B23A48", "FDF8F7", "246A59", "FFFFFF"),
            dark("F7EEF1", "E2D0D7", "B097A2", "E58AAE", "160F14", "33F7EEF1", "F4848F", "7FC09A", "E2BC62", "E07082", "F7EEF1", "7BD0BB", "080808")),
        ThemeFamily("kirifuri", "Kirifuri Falls", "foam white, deep waterfall teal and moss",
            light("06211E", "143632", "2A4E48", "084F48", "FFFFFF", "1A06211E", "7A1C2C", "0E5A34", "6A4A06", "B8432A", "E7F6F2", "8B412C", "FFFFFF"),
            dark("F3FFFC", "D4F3EC", "A8DDD2", "D8FFF6", "0C3C38", "33F3FFFC", "FFC8D0", "9EE8C0", "FFE08A", "F09A78", "F3FFFC", "F4AA7D", "080808")),
        ThemeFamily("thunderstorm", "Thunderstorm", "clear sky, storm indigo and lightning",
            light("10182C", "1E3050", "2C4068", "1C2748", "FFFFFF", "1A10182C", "7A1428", "0C5834", "5E4406", "8E3A28", "E6EEFC", "76530A", "FFFFFF"),
            dark("F7F9FF", "D5DBF0", "B4BCDC", "FFF3C4", "070A14", "33F7F9FF", "FFB4BE", "A4E8C4", "FFE08A", "E89878", "F7F9FF", "83BAFF", "080808")),
        ThemeFamily("irises", "Irises", "iris violet, lavender paper and aubergine ink",
            light("24152F", "382547", "51405E", "5D347F", "FFFFFF", "2024152F", "7C203C", "245133", "624506", "A64632", "F5EFFA", "38613E", "FFFFFF"),
            dark("F6EFFB", "E5D6EF", "C4ACD4", "C0A0F0", "21142C", "40F6EFFB", "FFAFC3", "A5D9B0", "EFD58D", "EA9A7F", "F6EFFB", "99D2A8", "080808")),
        ThemeFamily("shibuya-night", "Shibuya Night", "magenta signs, cyan light and violet night",
            light("1B1233", "2C2250", "5A4E7C", "A9136A", "FFFFFF", "201B1233", "B3202B", "1F6B55", "855608", "A9136A", "FCF9FF", "00677B", "FFFFFF"),
            dark("F3EEFF", "DAD2F0", "B1A6D1", "FF7CC0", "0A0A0C", "33F3EEFF", "FF6B6B", "3EE0B0", "FFC857", "FF7CC0", "F3EEFF", "22E3FF", "0A0A0C")),
        ThemeFamily("akihabara", "Akihabara", "green neon, violet signs and black-blue streets",
            light("0E1A24", "1E2D3A", "48596A", "06703A", "FFFFFF", "200E1A24", "B3202B", "0B6B3A", "855608", "06703A", "F8FAFC", "5A2DD0", "FFFFFF"),
            dark("E9F4F1", "CFE0DC", "9FB4B0", "2BE37A", "0A0A0C", "33E9F4F1", "FF6B7A", "2BE37A", "FFC857", "2BE37A", "E9F4F1", "A080FF", "0A0A0C")),
    )

    fun get(id: String): ThemeFamily = all.firstOrNull { it.id == id } ?: all.first()
}
