package dev.bookah.tasks

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import dev.bookah.tasks.data.SharedText

/** MainActivity opens the quick-add sheet (from a share or the tile), filled with these extras when they are there. */
const val ACTION_QUICK_ADD = "dev.bookah.tasks.action.QUICK_ADD"
const val EXTRA_SHARED_TITLE = "dev.bookah.tasks.extra.TITLE"
const val EXTRA_SHARED_URL = "dev.bookah.tasks.extra.URL"
const val EXTRA_SHARED_NOTES = "dev.bookah.tasks.extra.NOTES"

/** The intent that opens BookahTasks with the quick-add sheet (explicit: only our own MainActivity). */
fun quickAddIntent(context: android.content.Context): Intent =
    Intent(context, MainActivity::class.java).setAction(ACTION_QUICK_ADD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * "Share to BookahTasks" (plan 4.2.5): receives ACTION_SEND text/plain from any app, splits it into a title, a link and notes
 * ([SharedText], no network), hands it to MainActivity and closes. It shows nothing itself and keeps nothing.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val i = intent
            if (i?.action == Intent.ACTION_SEND) {
                // getCharSequenceExtra: an app that shares styled text sends a CharSequence, for which getStringExtra gives null
                val text = runCatching { i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { cut(it) } }.getOrNull()
                val subject = runCatching { i.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.let { cut(it) } }.getOrNull()
                val s = SharedText.parse(text, subject)
                startActivity(
                    quickAddIntent(this).putExtra(EXTRA_SHARED_TITLE, s.title).putExtra(EXTRA_SHARED_URL, s.url).putExtra(EXTRA_SHARED_NOTES, s.notes),
                )
            }
        } catch (_: Exception) {
            // odd input from another app: nothing to add, never a crash
        }
        finish()
    }

    /** Only the start of a huge text is looked at (a CharSequence is cut before it is turned into a String). */
    private fun cut(cs: CharSequence): String = cs.subSequence(0, minOf(cs.length, SharedText.MAX_INPUT)).toString()
}

/**
 * The quick-settings tile "New task": opens BookahTasks with the quick-add sheet. On a locked phone it asks to unlock first. Android 14 and
 * later need a PendingIntent here (the Intent version throws there); Android 12 and 13 take the Intent.
 */
class NewTaskTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.let { it.label = getString(R.string.tile_label); it.state = android.service.quicksettings.Tile.STATE_INACTIVE; it.updateTile() }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { open() } else open()
    }

    private fun open() {
        val intent = quickAddIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
