package dev.bookah.tasks

import androidx.annotation.DrawableRes

/**
 * The seasonal pictures: one for each month, shown as a banner in Today and the menu and above the text of empty pages (never behind
 * task text). They are decoration only: switching them off in Settings changes nothing else.
 *
 * To use your own picture, put it in app/src/main/res/drawable-nodpi (lowercase name, WebP or JPEG about 1200 px wide, wider than tall)
 * and replace the id of its month in [byMonth]. tools/make-art.py made the bundled ones.
 */
object Art {
    @DrawableRes
    private val byMonth = intArrayOf(
        R.drawable.art_01_fuji_snow_village, R.drawable.art_02_plum_in_snow, R.drawable.art_03_hina_dolls_blossom,
        R.drawable.art_04_cherry_river, R.drawable.art_05_koi_and_iris, R.drawable.art_06_hydrangea_rain,
        R.drawable.art_07_tanabata_night, R.drawable.art_08_fireworks_night, R.drawable.art_09_harvest_moon,
        R.drawable.art_10_chrysanthemum_mist, R.drawable.art_11_maple_temple, R.drawable.art_12_snow_lantern_night,
    )

    /** [month] is 1 to 12. */
    @DrawableRes
    fun forMonth(month: Int): Int = byMonth[(month - 1).coerceIn(0, 11)]
}
