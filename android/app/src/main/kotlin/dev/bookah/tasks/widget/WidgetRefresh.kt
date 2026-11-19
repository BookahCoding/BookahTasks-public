package dev.bookah.tasks.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

object WidgetRefresh {
    const val ACTION = "dev.bookah.tasks.action.WIDGET_REFRESH"

    /** One redraw at [atMs] (inexact, no special permission). The same [code] replaces an earlier alarm of that kind. */
    fun scheduleAt(context: Context, atMs: Long, code: Int) {
        val app = context.applicationContext
        val pi = PendingIntent.getBroadcast(app, code, Intent(ACTION).setClass(app, TodayWidgetProvider::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        runCatching { (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setAndAllowWhileIdle(AlarmManager.RTC, atMs, pi) }
    }

    /** The first minute after the next local midnight: "today" changes then (the DATE_CHANGED broadcast is not delivered to manifest receivers). */
    fun scheduleMidnight(context: Context) {
        val zone = java.time.ZoneId.systemDefault()
        scheduleAt(context, java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 60_000L, 11)
    }

    /** Asks every Today widget to redraw. Cheap and safe to call often; does nothing when no widget is placed. */
    fun request(context: Context) {
        val app = context.applicationContext
        val ids = AppWidgetManager.getInstance(app).getAppWidgetIds(ComponentName(app, TodayWidgetProvider::class.java))
        if (ids.isEmpty()) return
        app.sendBroadcast(Intent(ACTION).setClass(app, TodayWidgetProvider::class.java))
    }
}
