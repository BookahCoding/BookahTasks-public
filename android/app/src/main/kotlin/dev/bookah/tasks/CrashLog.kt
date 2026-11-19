package dev.bookah.tasks

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A crash log that stays on the phone (nothing is uploaded). It keeps the exception class names and the stack frames,
 * never the messages: a message can carry a server name or an address.
 */
object CrashLog {
    private const val MAX_BYTES = 24_000

    fun install(context: Context) {
        val file = file(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { append(file, describe(error, thread.name)) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun file(context: Context) = File(context.filesDir, "crash.log")

    /** Records a caught error (class names and frames only, like a crash) so a failure the widget survived is still visible in Settings. */
    fun record(context: Context, error: Throwable) {
        runCatching { append(file(context), describe(error, Thread.currentThread().name)) }
    }

    fun read(context: Context): String = runCatching { file(context).takeIf { it.exists() }?.readText().orEmpty() }.getOrDefault("")

    fun clear(context: Context) { runCatching { file(context).delete() } }

    internal fun describe(error: Throwable, threadName: String, now: Date = Date()): String {
        val sb = StringBuilder()
        sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)).append("  thread ").append(threadName).append('\n')
        var e: Throwable? = error
        var depth = 0
        while (e != null && depth < 5) {
            sb.append(if (depth == 0) "" else "caused by ").append(e.javaClass.name).append('\n')
            e.stackTrace.take(12).forEach { sb.append("    at ").append(it.className).append('.').append(it.methodName).append(':').append(it.lineNumber).append('\n') }
            e = e.cause
            depth++
        }
        return sb.append('\n').toString()
    }

    private fun append(file: File, text: String) {
        val old = if (file.exists()) file.readText() else ""
        val all = text + old
        file.writeText(if (all.length > MAX_BYTES) all.take(MAX_BYTES) else all)    // newest first, bounded
    }
}
