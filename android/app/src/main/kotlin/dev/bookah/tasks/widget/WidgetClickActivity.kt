package dev.bookah.tasks.widget

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import dev.bookah.tasks.MainActivity

/**
 * Invisible: a widget list has one tap template, so every tap in the list lands here and is sent on (open a task, open the app, or hand the
 * tick to [WidgetActionReceiver]). It shows nothing and closes at once.
 */
class WidgetClickActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            when (intent.getStringExtra("op")) {
                "open" -> {
                    val link = Uri.Builder().scheme("bookahtasks").authority("task")
                        .appendQueryParameter("uid", intent.getStringExtra("uid").orEmpty())
                        .appendQueryParameter("list", intent.getStringExtra("list").orEmpty()).build()
                    startActivity(Intent(Intent.ACTION_VIEW, link).setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                "open_app" -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                "tick", "reopen" -> sendBroadcast(Intent(this, WidgetActionReceiver::class.java).putExtras(intent))
            }
        } finally {
            finish()
        }
    }
}
