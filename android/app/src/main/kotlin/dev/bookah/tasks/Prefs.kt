package dev.bookah.tasks

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.bookah.tasks.design.Appearance
import dev.bookah.tasks.design.ThemeCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Choices that are not secrets: look and the evening hour. (Secrets live in the Keystore vault.) */
data class Prefs(
    val themeId: String = ThemeCatalog.DEFAULT_ID,
    val appearance: Appearance = Appearance.System,
    /** "This evening" starts at this hour (plan: default 18:00). */
    val eveningHour: Int = 18,
    /** The seasonal pictures in Today, the menu and empty pages (on by default; decoration only). */
    val artwork: Boolean = true,
    /** The address of the list new tasks go to when none is on screen (null = the inbox, the list called "Tasks"). */
    val defaultListHref: String? = null,
    /** Phase 5: today's calendar events above the tasks on Today and Schedule (on by default). */
    val showDayStrip: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("prefs")

class PrefsStore(private val context: Context) {
    private val theme = stringPreferencesKey("theme")
    private val appearance = stringPreferencesKey("appearance")
    private val evening = intPreferencesKey("evening_hour")
    private val artwork = androidx.datastore.preferences.core.booleanPreferencesKey("artwork")
    private val defaultList = stringPreferencesKey("default_list")

    val flow: Flow<Prefs> = context.dataStore.data.map { p ->
        Prefs(
            themeId = ThemeCatalog.get(p[theme] ?: ThemeCatalog.DEFAULT_ID).id,
            appearance = runCatching { Appearance.valueOf(p[appearance] ?: "") }.getOrDefault(Appearance.System),
            eveningHour = (p[evening] ?: 18).coerceIn(12, 23),
            artwork = p[artwork] ?: true,
            defaultListHref = p[defaultList],
            showDayStrip = p[dayStrip] ?: true,
        )
    }
    private val dayStrip get() = androidx.datastore.preferences.core.booleanPreferencesKey("day_strip")
    suspend fun setDayStrip(on: Boolean) = context.dataStore.edit { it[dayStrip] = on }

    suspend fun setTheme(id: String) = context.dataStore.edit { it[theme] = ThemeCatalog.get(id).id }
    suspend fun setAppearance(a: Appearance) = context.dataStore.edit { it[appearance] = a.name }
    suspend fun setEveningHour(h: Int) = context.dataStore.edit { it[evening] = h.coerceIn(12, 23) }
    suspend fun setArtwork(on: Boolean) = context.dataStore.edit { it[artwork] = on }
    suspend fun setDefaultListHref(href: String?) = context.dataStore.edit { if (href == null) it.remove(defaultList) else it[defaultList] = href }
}
