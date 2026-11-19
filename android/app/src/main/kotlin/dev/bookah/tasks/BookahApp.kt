package dev.bookah.tasks

import android.app.Application
import android.content.Context
import dev.bookah.tasks.data.CalDavApi
import dev.bookah.tasks.data.CalDavClient
import dev.bookah.tasks.data.Http
import dev.bookah.tasks.data.KeystoreVault
import dev.bookah.tasks.data.Session
import dev.bookah.tasks.data.SessionStore
import dev.bookah.tasks.data.SqliteTaskStore
import dev.bookah.tasks.data.SyncEngine
import dev.bookah.tasks.data.VaultSessionStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The few long-lived objects of the app, created once. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val store = SqliteTaskStore(context)
    val vault = KeystoreVault(context)
    val sessions: SessionStore = VaultSessionStore(vault)
    val http = Http.newClient()

    /** How the app talks to the server. The default is the real client; only tests would swap it. */
    @Volatile var apiFactory: (Session) -> CalDavApi = { CalDavClient(http, it) }
    val sync = SyncEngine(store)
    /** Sign-in: tasks only, so it never waits for years of events (the first normal refresh brings them). */
    val syncTasksOnly = SyncEngine(store, events = false)

    /** Every change the owner makes goes through this: it applies the change on the phone at once and queues it for Nextcloud. */
    val outbox = dev.bookah.tasks.data.Outbox(store)

    /**
     * Makes the phone's alarms match the tasks on the phone: a notification when a task starts and one an hour before its deadline
     * (see [dev.bookah.tasks.data.ReminderPlanner]). Called after every change, every sync, a restart of the phone and when an alarm fires.
     */
    private val reminderLock = Mutex()
    suspend fun rescheduleReminders() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { reminderLock.withLock {
        val zone = java.time.ZoneId.systemDefault()
        val shown = store.lists().filter { it.visible && it.kind.holdsTasks }.map { it.id }.toSet()
        val tasks = dev.bookah.tasks.data.TaskParser.parseAll(store.allObjects().filter { it.listId in shown }, zone)
        ReminderScheduler(appContext).reschedule(dev.bookah.tasks.data.ReminderPlanner.plan(tasks, System.currentTimeMillis(), zone))
    } }

    /** A background push or refresh finished: an open screen reads the saved copy again. */
    val changed = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A search typed by the debug-only SearchTestReceiver (a release build has no sender). */
    val debugSearch = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** One sync at a time, whether it comes from the screen or from the background worker. */
    val syncLock = Mutex()

    /**
     * One change at a time ("read the text, work out the change, queue it"), whether it comes from a screen or from a notification button,
     * so two changes to the same task can never read the same old text and erase each other.
     */
    val writeLock = Mutex()
    val prefs = PrefsStore(context)

    /** Remembers the time of a successful sync (screen or background job), so Settings and the sync-on-return rule know the truth. */
    suspend fun noteSync(report: dev.bookah.tasks.data.SyncReport) {
        if (dev.bookah.tasks.data.SyncPolicy.succeeded(report)) prefs.setLastSync(System.currentTimeMillis())
    }
}

class BookahApp : Application() {
    companion object {
        /** When the main screen was last created (set in MainActivity), for the cold-start figure in Settings. */
        @Volatile var screenCreatedAt: Long = android.os.SystemClock.elapsedRealtime()
    }

    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        RefreshWorker.schedule(this)      // KEEP: an existing schedule is left alone; with nobody signed in a run does nothing
    }
}
