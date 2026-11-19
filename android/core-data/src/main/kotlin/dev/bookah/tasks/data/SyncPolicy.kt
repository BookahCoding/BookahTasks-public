package dev.bookah.tasks.data

/** When the app should sync by itself, as plain rules (tested without a screen or a clock). */
object SyncPolicy {
    /** Coming back to the app: sync when the last successful sync is older than this. */
    const val RESUME_STALE_MS = 5 * 60_000L

    /** The widget's own periodic update asks for a sync when the last successful one is older than this (a second path next to the background job). */
    const val WIDGET_STALE_MS = 15 * 60_000L

    /** Never synced, a saved time in the future (the clock was set back), or older than [thresholdMs]. */
    fun isStale(lastSyncMs: Long?, nowMs: Long, thresholdMs: Long): Boolean =
        lastSyncMs == null || lastSyncMs > nowMs || nowMs - lastSyncMs > thresholdMs

    /** Only a run that reached the server and met no error counts as "synced". */
    fun succeeded(report: SyncReport): Boolean = report.error == null && !report.offline
}
