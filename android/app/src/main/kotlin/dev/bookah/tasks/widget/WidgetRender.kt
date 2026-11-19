package dev.bookah.tasks.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import dev.bookah.tasks.BookahApp
import dev.bookah.tasks.MainActivity
import dev.bookah.tasks.R
import dev.bookah.tasks.data.TaskParser
import dev.bookah.tasks.data.WidgetList
import dev.bookah.tasks.data.WidgetRow
import dev.bookah.tasks.data.WidgetRows
import dev.bookah.tasks.design.Appearance
import dev.bookah.tasks.design.ThemeCatalog
import dev.bookah.tasks.design.WidgetColors
import dev.bookah.tasks.widget.WidgetRender.tint
import dev.bookah.tasks.design.WidgetPalette
import dev.bookah.tasks.quickAddIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Builds what the widget shows: the frame (colours, header, taps) and the rows. Reads only the phone's saved copy. */
object WidgetRender {
    /** Both colour sets: the system picks the one for the current light or dark mode by itself (API 31), so a change of mode needs no redraw. */
    data class Look(val light: WidgetColors, val dark: WidgetColors, val eveningHour: Int)

    /** Sets a colour that differs between light and dark mode (the same colour twice when the owner pinned Light or Dark). */
    fun RemoteViews.tint(id: Int, method: String, look: Look, pick: (WidgetColors) -> Int) = setColorInt(id, method, pick(look.light), pick(look.dark))

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    suspend fun look(context: Context): Look {
        val graph = (context.applicationContext as BookahApp).graph
        val prefs = graph.prefs.flow.first()
        val family = ThemeCatalog.get(prefs.themeId)
        val light = WidgetPalette.of(family, false)
        val dark = WidgetPalette.of(family, true)
        return when (prefs.appearance) {
            Appearance.Light -> Look(light, light, prefs.eveningHour)
            Appearance.Dark -> Look(dark, dark, prefs.eveningHour)
            Appearance.System -> Look(light, dark, prefs.eveningHour)
        }
    }

    suspend fun rowsNow(context: Context, eveningHour: Int): List<WidgetRow> = withContext(Dispatchers.IO) {
        val graph = (context.applicationContext as BookahApp).graph
        val zone = ZoneId.systemDefault()
        val shown = graph.store.lists().filter { it.visible && it.kind.holdsTasks }
        val ids = shown.map { it.id }.toSet()
        val tasks = TaskParser.parseAll(graph.store.allObjects().filter { it.listId in ids }, zone)
        val lists = shown.associate { it.id to WidgetList(it.href, it.name, it.colorHex, it.editable) }
        val now = System.currentTimeMillis()
        WidgetRows.build(tasks, lists, LocalDate.now(zone), now, eveningHour, WidgetRecent.titles(now))
    }

    /** The widget's frame: theme colours, header, the list's adapter and the tap template. */
    suspend fun frame(context: Context): RemoteViews = withContext(Dispatchers.IO) {
        val graph = (context.applicationContext as BookahApp).graph
        val look = look(context)
        val rv = RemoteViews(context.packageName, R.layout.widget_today)
        // texts set here, not left to the layout, so they follow the app's language (an owner may pick Greek for the app only)
        rv.setTextViewText(R.id.w_title, context.getString(R.string.widget_name))
        rv.setContentDescription(R.id.w_add, context.getString(R.string.widget_new_task))
        rv.tint(R.id.w_panel, "setColorFilter", look) { it.panel }
        rv.tint(R.id.w_title, "setTextColor", look) { it.accent }
        rv.tint(R.id.w_count, "setTextColor", look) { it.muted }
        rv.tint(R.id.w_empty, "setTextColor", look) { it.muted }
        rv.tint(R.id.w_add_dot, "setColorFilter", look) { it.accent }
        rv.tint(R.id.w_add_plus, "setTextColor", look) { it.onAccent }

        val launch = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)
        rv.setOnClickPendingIntent(R.id.w_title, launch)
        rv.setOnClickPendingIntent(R.id.w_add, PendingIntent.getActivity(context, 2, quickAddIntent(context), FLAGS))

        if (graph.sessions.load() == null) {
            rv.setViewVisibility(R.id.w_list, View.GONE)
            rv.setViewVisibility(R.id.w_empty, View.VISIBLE)
            rv.setTextViewText(R.id.w_empty, context.getString(R.string.widget_signed_out))
            rv.setOnClickPendingIntent(R.id.w_empty, launch)
            rv.setTextViewText(R.id.w_count, "")
        } else {
            rv.setViewVisibility(R.id.w_list, View.VISIBLE)
            rv.setTextViewText(R.id.w_empty, context.getString(R.string.empty_today))
            rv.setRemoteAdapter(R.id.w_list, Intent(context, TodayWidgetService::class.java))
            rv.setEmptyView(R.id.w_list, R.id.w_empty)
            rv.setPendingIntentTemplate(
                R.id.w_list,
                PendingIntent.getActivity(context, 1, Intent(context, WidgetClickActivity::class.java), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            val open = runCatching { rowsNow(context, look.eveningHour).count { it is WidgetRow.Item && !it.done } }.getOrDefault(0)
            rv.setTextViewText(R.id.w_count, if (open > 0) open.toString() else "")
        }
        rv
    }
}
