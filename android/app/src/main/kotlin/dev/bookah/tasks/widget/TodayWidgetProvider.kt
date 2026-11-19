package dev.bookah.tasks.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dev.bookah.tasks.CrashLog
import dev.bookah.tasks.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The Today widget: draws the frame; the rows come from [TodayWidgetService]. */
class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) = redraw(context)

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetRefresh.ACTION) redraw(context)
    }

    private fun redraw(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
                if (ids.isNotEmpty()) {
                    mgr.updateAppWidget(ids, WidgetRender.frame(context))
                    mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
                    WidgetRefresh.scheduleMidnight(context)
                }
            } catch (e: Exception) {
                CrashLog.record(context, e)
            } finally {
                pending.finish()
            }
        }
    }
}
