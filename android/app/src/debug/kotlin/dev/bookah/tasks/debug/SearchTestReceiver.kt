package dev.bookah.tasks.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.bookah.tasks.BookahApp

/**
 * Debug builds only: types a search, because adb cannot type Greek. `\uXXXX` escapes are decoded:
 * `adb shell am broadcast -a dev.bookah.tasks.SEARCH_TEST -n dev.bookah.tasks/.debug.SearchTestReceiver --es q 'δεη'`
 */
class SearchTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val raw = intent.getStringExtra("q").orEmpty()
        val q = Regex("\\\\u([0-9a-fA-F]{4})").replace(raw) { it.groupValues[1].toInt(16).toChar().toString() }
        (context.applicationContext as BookahApp).graph.debugSearch.tryEmit(q)
    }
}
