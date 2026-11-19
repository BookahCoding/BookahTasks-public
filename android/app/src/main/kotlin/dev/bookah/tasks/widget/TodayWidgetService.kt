package dev.bookah.tasks.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.bookah.tasks.CrashLog
import dev.bookah.tasks.R
import dev.bookah.tasks.data.WidgetRow
import dev.bookah.tasks.widget.WidgetRender.tint
import kotlinx.coroutines.runBlocking
import java.time.format.DateTimeFormatter

/** Gives the widget's list its rows (read off the main thread by the system). */
class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        private var rows: List<WidgetRow> = emptyList()
        private var failed = false
        private var look: WidgetRender.Look? = null
        private val hm = DateTimeFormatter.ofPattern("HH:mm")
        private val FALLBACK = dev.bookah.tasks.design.WidgetPalette.of(dev.bookah.tasks.design.ThemeCatalog.get(dev.bookah.tasks.design.ThemeCatalog.DEFAULT_ID), false)
            .let { WidgetRender.Look(it, it, 18) }

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            try {
                runBlocking {
                    val l = WidgetRender.look(context)
                    look = l
                    rows = WidgetRender.rowsNow(context, l.eveningHour)
                    failed = false
                }
            } catch (e: Exception) {
                CrashLog.record(context, e)
                rows = emptyList(); failed = true
            }
        }

        override fun getCount(): Int = if (failed) 1 else rows.size
        override fun getViewTypeCount(): Int = 3
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = false
        override fun getLoadingView(): RemoteViews? = null

        private fun open(op: String) = Intent().putExtra("op", op)

        override fun getViewAt(position: Int): RemoteViews {
            if (failed || look == null) return note(context.getString(R.string.widget_error), look ?: FALLBACK, { it.muted }, open("open_app"))
            val c = look!!
            return when (val r = rows.getOrNull(position)) {
                null -> note("", c, { it.muted }, null)
                is WidgetRow.OverdueFold -> note(context.getString(R.string.widget_overdue, r.count), c, { it.danger }, open("open_app"))
                is WidgetRow.More -> note(context.getString(R.string.widget_more, r.count), c, { it.muted }, open("open_app"))
                is WidgetRow.NextDate -> note(context.getString(R.string.widget_next_date, r.title), c, { it.muted }, null)
                is WidgetRow.Heading -> RemoteViews(context.packageName, R.layout.widget_row_heading).apply {
                    setTextViewText(R.id.heading, context.getString(if (r.evening) R.string.today_evening else R.string.nav_today))
                    tint(R.id.heading, "setTextColor", c) { it.muted }
                }
                is WidgetRow.Item -> item(r, c)
            }
        }

        private fun note(text: String, c: WidgetRender.Look, pick: (dev.bookah.tasks.design.WidgetColors) -> Int, click: Intent?) = RemoteViews(context.packageName, R.layout.widget_row_note).apply {
            setTextViewText(R.id.note, text)
            tint(R.id.note, "setTextColor", c, pick)
            if (click != null) setOnClickFillInIntent(R.id.note, click)
        }

        private fun item(r: WidgetRow.Item, c: WidgetRender.Look): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_row_item)
            val title = r.title.ifBlank { context.getString(R.string.untitled_task) }
            rv.setTextViewText(R.id.title, title)
            rv.tint(R.id.title, "setTextColor", c) { if (r.done) it.muted else it.text }
            rv.setInt(R.id.title, "setPaintFlags", if (r.done) Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG else Paint.ANTI_ALIAS_FLAG)
            val meta = listOfNotNull(
                r.time?.let { hm.format(it) }, r.listName.ifBlank { null }, if (r.repeats) context.getString(R.string.mark_repeats) else null,
            ).joinToString(" · ")
            rv.setTextViewText(R.id.meta, meta)
            rv.setViewVisibility(R.id.meta, if (meta.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            rv.tint(R.id.meta, "setTextColor", c) { it.muted }
            val ring = runCatching { Color.parseColor(r.colorHex) }.getOrDefault(c.light.text)
            rv.setImageViewResource(R.id.tick_ring, if (r.done) R.drawable.widget_dot else R.drawable.widget_ring)
            rv.setInt(R.id.tick_ring, "setColorFilter", ring)
            if (r.canTick) {
                rv.setContentDescription(R.id.tick, context.getString(if (r.done) R.string.widget_reopen else R.string.widget_mark_done, title))
                rv.setOnClickFillInIntent(R.id.tick, open(if (r.done) "reopen" else "tick").putExtra("href", r.href).putExtra("stamp", r.stamp))
            }
            rv.setOnClickFillInIntent(R.id.row_text, open("open").putExtra("uid", r.uid).putExtra("list", r.listHref))
            return rv
        }
    }
}
