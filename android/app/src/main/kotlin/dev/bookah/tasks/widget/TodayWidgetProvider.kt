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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The Today widget: draws the frame; the rows come from [TodayWidgetService]. */
class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        redraw(context)
        askForSyncIfStale(context)
    }

    /** The system's own periodic update is a second path next to the background job: if no sync succeeded for a while, ask for one. */
    private fun askForSyncIfStale(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val graph = (context.applicationContext as dev.bookah.tasks.BookahApp).graph
                if (graph.sessions.load() != null &&
                    dev.bookah.tasks.data.SyncPolicy.isStale(graph.prefs.flow.first().lastSyncMs, System.currentTimeMillis(), dev.bookah.tasks.data.SyncPolicy.WIDGET_STALE_MS)
                ) dev.bookah.tasks.RefreshWorker.runOnce(context)
            } catch (e: Exception) {
                CrashLog.record(context, e)
            } finally {
                pending.finish()
            }
        }
    }

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
