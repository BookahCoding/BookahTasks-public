package dev.bookah.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.bookah.tasks.data.Completion
import dev.bookah.tasks.data.IcsPatcher
import dev.bookah.tasks.data.OutboxKind
import dev.bookah.tasks.data.OutboxState
import dev.bookah.tasks.data.Priority
import dev.bookah.tasks.data.Session
import dev.bookah.tasks.data.StoredObject
import dev.bookah.tasks.data.SyncReport
import dev.bookah.tasks.data.Task
import dev.bookah.tasks.data.TaskEdit
import dev.bookah.tasks.data.TaskList
import dev.bookah.tasks.data.TaskParser
import dev.bookah.tasks.data.TaskViews
import dev.bookah.tasks.data.TaskWhen
import dev.bookah.tasks.data.normalizeServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Timings shown in Settings (milliseconds): the screen opened to tasks shown, the last read of the saved copy, the last sync. */
data class Speed(val coldStartMs: Long? = null, val loadMs: Long? = null, val syncMs: Long? = null)

enum class Phase { Loading, SignedOut, SignedIn }

/** A message as a string resource (so it shows in English or Greek) with an optional number. */
data class UiText(val id: Int, val arg: Int? = null, val plural: Boolean = false) {
    @androidx.compose.runtime.Composable
    fun resolve(): String = when {
        plural && arg != null -> androidx.compose.ui.res.pluralStringResource(id, arg, arg)
        arg == null -> androidx.compose.ui.res.stringResource(id)
        else -> androidx.compose.ui.res.stringResource(id, arg)
    }
}

/** What the main area shows: a smart view, one list, or the tasks with one tag. */
sealed interface Screen {
    data object Today : Screen
    data object Schedule : Screen
    data object Inbox : Screen
    data object Upcoming : Screen
    data object Someday : Screen
    data object Logbook : Screen
    /** Phase 5: tasks waiting on somebody. */
    data object Waiting : Screen
    data class OneList(val listId: Long) : Screen
    data class Tag(val name: String) : Screen
}

data class UiState(
    val phase: Phase = Phase.Loading,
    val signingIn: Boolean = false,
    /** A plain-words message for the sign-in screen; never contains the password. */
    val signInError: UiText? = null,
    val syncing: Boolean = false,
    val lastReport: SyncReport? = null,
    val lastSyncMs: Long? = null,
    /** The "Check for updates" button of Settings (release builds only). */
    val updateBusy: Boolean = false,
    val updateResult: dev.bookah.tasks.data.UpdateResult? = null,
    val lists: List<TaskList> = emptyList(),
    /** The tasks of every shown list that holds tasks. */
    val tasks: List<Task> = emptyList(),
    val screen: Screen = Screen.Schedule,
    val openTask: Task? = null,
    val settingsOpen: Boolean = false,
    val signedInUser: String = "",
    val crashLog: String = "",
    /** Items on the phone that could not be read (0 normally). */
    val unreadable: Int = 0,
    val prefs: Prefs = Prefs(),
    val speed: Speed = Speed(),
    /** Addresses of tasks whose change has not reached Nextcloud yet (the "waiting to sync" mark). */
    val pending: Set<String> = emptySet(),
    val conflicts: List<ConflictInfo> = emptyList(),
    val failed: List<FailedInfo> = emptyList(),
    val undo: UndoBar? = null,
    /** A plain-words message for a change that could not be made (shown once, then dismissed). */
    val message: UiText? = null,
    val quickAdd: Boolean = false,
    val confirmSignOut: Boolean = false,
    /** The list screen shows drag handles and arrows instead of swipes and taps (manual order). */
    val reordering: Boolean = false,
    /** Counts finished reorder attempts (also refused ones), so the list never keeps showing a move that was not saved. */
    val orderAttempts: Int = 0,
    /** "href|uid" of a task whose reschedule sheet should open (a reminder notification was tapped). */
    val askReschedule: String? = null,
    /** Today's events of the shown event calendars (the day strip), worked out off the main thread. */
    val dayEvents: List<dev.bookah.tasks.data.DayEvent> = emptyList(),
    /** The search page is open (drawn over the current screen like Settings). */
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
    /** Text a share (or the tile) put into the quick-add sheet. */
    val prefill: QuickPrefill? = null,
    /** BookahCalendar is installed: a tap on the day strip opens it (and only then is the tap offered, also to a screen reader). */
    val calendarInstalled: Boolean = false,
) {
    /** The lists that hold tasks (event-only calendars are never shown). */
    val taskLists: List<TaskList> get() = lists.filter { it.kind.holdsTasks }

    /** The list that acts as the inbox: the one called "Tasks" once BookahTasks has made it; until then null (the inbox then shows every list). */
    val inboxListId: Long? get() = taskLists.firstOrNull { it.name.equals("Tasks", ignoreCase = true) }?.id

    /** Every tag in use on open tasks (not the special ones), for the drawer. */
    val tags: List<String> get() = tasks.filter { it.isOpen }.flatMap { it.tags }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
}

class AppViewModel(private val graph: AppGraph) : ViewModel(), TaskActions {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private var undoAction: (suspend () -> Unit)? = null
    private var undoJob: Job? = null

    private var session: Session? = null
    private val zone: ZoneId = ZoneId.systemDefault()
    private var loadedVersion = -1L

    /** A task another app asked to show (see [openLink]); kept until the saved copy is read, and on a cold start until the first sync has answered. */
    private var pendingLink: dev.bookah.tasks.data.TaskLink? = null
    private var firstSyncDone = false

    /** Sync touches SQLite and the network: never on the main thread. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    init {
        viewModelScope.launch {
            graph.prefs.flow.collect { p ->
                val stripChanged = _state.value.prefs.showDayStrip != p.showDayStrip
                _state.update { it.copy(prefs = p) }
                if (stripChanged && _state.value.phase == Phase.SignedIn) scheduleEvents()
            }
        }
        // a background push or refresh changed the saved copy: read it again so the waiting marks and the lists stay true
        viewModelScope.launch { graph.changed.collect { reload() } }
        // debug builds only send this (a test receiver types a search, e.g. Greek that adb cannot type)
        viewModelScope.launch { graph.debugSearch.collect { q -> _state.update { it.copy(searchOpen = true, searchQuery = q, openTask = null, settingsOpen = false) } } }
        viewModelScope.launch {
            session = graph.sessions.load()
            val s = session
            if (s == null) {
                pendingLink = null          // signed out: a link is ignored
                _state.update { it.copy(phase = Phase.SignedOut) }
            } else {
                _state.update { it.copy(phase = Phase.SignedIn, signedInUser = s.user) }
                reload()          // the saved copy first: tasks show before any network call
                refresh()
            }
        }
    }

    fun setTheme(id: String) { viewModelScope.launch { graph.prefs.setTheme(id) } }
    fun setAppearance(a: dev.bookah.tasks.design.Appearance) { viewModelScope.launch { graph.prefs.setAppearance(a) } }
    fun setEveningHour(h: Int) { viewModelScope.launch { graph.prefs.setEveningHour(h) } }
    fun setArtwork(on: Boolean) { viewModelScope.launch { graph.prefs.setArtwork(on) } }

    fun signIn(serverInput: String, user: String, password: String) {
        val server = normalizeServerAddress(serverInput)
        if (server == null) {
            _state.update { it.copy(signInError = UiText(R.string.err_address_https)) }
            return
        }
        if (user.isBlank() || password.isEmpty()) {
            _state.update { it.copy(signInError = UiText(R.string.err_need_both)) }
            return
        }
        val candidate = Session(server, user.trim(), password)
        _state.update { it.copy(signingIn = true, signInError = null) }
        viewModelScope.launch {
            // tasks only: the event calendars (possibly years of events) come with the first normal refresh, after sign-in
            val report = syncNow(candidate, tasksOnly = true)
            // nothing is saved unless the server answered: a wrong password or no network keeps the user on this screen
            if (report.error != null) {
                val why = when {
                    report.offline -> R.string.err_unreachable
                    report.errorCode == SYNC_CRASHED -> R.string.err_sign_in_failed
                    else -> R.string.err_not_accepted
                }
                _state.update { it.copy(signingIn = false, signInError = UiText(why)) }
                return@launch
            }
            graph.sessions.save(candidate)
            session = candidate
            firstSyncDone = true
            graph.noteSync(report)
            RefreshWorker.schedule(graph.appContext)
            _state.update { it.copy(signingIn = false, phase = Phase.SignedIn, signedInUser = candidate.user, lastReport = report, lastSyncMs = System.currentTimeMillis()) }
            reload()
            refresh()          // now the event calendars too, in the background
        }
    }

    /**
     * One sync, off the main thread, one at a time. Whatever goes wrong inside it (an odd server answer, a storage error) becomes a report
     * with an error, never a crash of the screen ([SYNC_CRASHED]).
     */
    private suspend fun syncNow(s: Session, tasksOnly: Boolean = false): SyncReport = try {
        io { graph.syncLock.withLock { (if (tasksOnly) graph.syncTasksOnly else graph.sync).sync(graph.apiFactory(s)) } }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        SyncReport(_state.value.lists.size, 0, 0, 0, offline = false, error = e.javaClass.simpleName, errorCode = SYNC_CRASHED)
    }

    fun refresh() {
        val s = session ?: return
        if (_state.value.syncing) return
        _state.update { it.copy(syncing = true) }
        viewModelScope.launch {
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val report = syncNow(s)
            graph.noteSync(report)
            val took = android.os.SystemClock.elapsedRealtime() - startedAt
            _state.update { it.copy(speed = it.speed.copy(syncMs = took), syncing = false, lastReport = report, lastSyncMs = if (report.error == null) System.currentTimeMillis() else it.lastSyncMs) }
            firstSyncDone = true
            reload()
        }
    }

    /**
     * Another app (BookahCalendar) asked to show one task. Only a screen opens; nothing is changed. Signed out: ignored. The task is looked up
     * once the saved copy is read; if it is not there and the first sync since the app started is still running, that sync may bring it,
     * so the answer waits for it. Not found: a short message and the current screen stays.
     * A link only opens a screen: a task page that was open saves the owner's own typing as it always does on leaving; an open quick-add
     * sheet is not closed, it waits under the task page with its text kept and comes back when the page is closed (MainActivity).
     */
    fun openLink(link: dev.bookah.tasks.data.TaskLink) {
        if (_state.value.phase == Phase.SignedOut) return
        pendingLink = link
        if (loadedVersion >= 0) resolveLink()
    }

    private fun resolveLink() {
        val link = pendingLink ?: return
        val st = _state.value
        if (st.phase != Phase.SignedIn) { if (st.phase == Phase.SignedOut) pendingLink = null; return }
        val t = dev.bookah.tasks.data.TaskLinks.find(st.tasks, st.taskLists, link)
        if (t != null) {
            pendingLink = null
            _state.update { it.copy(openTask = t, settingsOpen = false) }          // Settings is drawn above the task page and holds no typing
            return
        }
        if (!firstSyncDone) return          // the reload after the first sync asks again
        pendingLink = null
        android.widget.Toast.makeText(graph.appContext, R.string.msg_task_not_found, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Coming back to the app: pick up what a background refresh changed meanwhile. */
    fun onResume() {
        _state.update { it.copy(calendarInstalled = calendarInstalled()) }          // BookahCalendar may have been installed or removed meanwhile
        if (_state.value.phase == Phase.SignedIn) viewModelScope.launch {
            reload()
            // back in the app: sync now when the last successful sync is old (the background job may have been paused by the phone)
            val last = graph.prefs.flow.first().lastSyncMs
            if (dev.bookah.tasks.data.SyncPolicy.isStale(last, System.currentTimeMillis(), dev.bookah.tasks.data.SyncPolicy.RESUME_STALE_MS)) refresh()
        }
    }

    /** Asks GitHub (the releases repository this build was made with) whether a newer version exists. Only on the button; does nothing in builds without one. */
    fun checkUpdates() {
        val repo = BuildConfig.RELEASES_REPO
        if (repo.isBlank() || _state.value.updateBusy) return
        _state.update { it.copy(updateBusy = true, updateResult = null) }
        viewModelScope.launch {
            val r = dev.bookah.tasks.data.UpdateChecker(graph.http).check(repo, BuildConfig.VERSION_NAME)
            _state.update { it.copy(updateBusy = false, updateResult = r) }
        }
    }

    /** Reads the saved copy and parses the tasks of the shown lists (off the main thread). Skipped when nothing changed. */
    private suspend fun reload() {
        val version = graph.store.version()
        val crash = io { CrashLog.read(graph.appContext) }
        scheduleEvents()          // on its own: the tasks never wait for the calendar events
        if (version == loadedVersion && _state.value.lists.isNotEmpty()) { _state.update { it.copy(crashLog = crash) }; resolveLink(); return }
        dev.bookah.tasks.widget.WidgetRefresh.request(graph.appContext)          // the saved copy changed (a sync, a sign-in, a hidden list): the home-screen widget reads it again
        val loadStart = android.os.SystemClock.elapsedRealtime()
        val queue = withContext(Dispatchers.Default) { graph.store.outboxAll() }
        fun titleOf(ics: String?): String? = ics?.let { TaskParser.parse(StoredObject("/x.ics", 0, "", it), zone).firstOrNull { t -> !t.isOccurrenceCopy }?.title }
        val pending = queue.filter { it.state == OutboxState.Pending }.map { it.href }.toSet()
        val conflicts = queue.filter { it.state == OutboxState.Conflict }.map { ConflictInfo(it.id, titleOf(it.ics ?: it.serverIcs).orEmpty(), titleOf(it.serverIcs), it.kind == OutboxKind.Delete) }
        val failed = queue.filter { it.state == OutboxState.Failed }.map { FailedInfo(it.id, titleOf(it.ics).orEmpty(), it.lastCode, it.kind) }
        val (lists, tasks) = withContext(Dispatchers.Default) {
            val lists = graph.store.lists()
            val shown = lists.filter { it.visible && it.kind.holdsTasks }.map { it.id }.toSet()
            TaskParser.failures.set(0)
            val tasks = TaskParser.parseAll(graph.store.allObjects().filter { it.listId in shown }, zone)
            lists to tasks
        }
        loadedVersion = version
        val now = android.os.SystemClock.elapsedRealtime()
        _state.update { st ->
            val open = st.openTask?.let { o -> tasks.firstOrNull { it.href == o.href && it.uid == o.uid } }
            val screen = (st.screen as? Screen.OneList)?.takeIf { s -> lists.none { it.id == s.listId } }?.let { Screen.Schedule } ?: st.screen
            st.copy(lists = lists, tasks = tasks, openTask = open, screen = screen, reordering = st.reordering && screen is Screen.OneList,
                crashLog = crash, unreadable = TaskParser.failures.get(),
                pending = pending, conflicts = conflicts, failed = failed,
                speed = st.speed.copy(loadMs = now - loadStart, coldStartMs = st.speed.coldStartMs ?: if (tasks.isNotEmpty()) now - BookahApp.screenCreatedAt else null))
        }
        graph.rescheduleReminders()          // alarms follow every change the owner makes or a sync brings
        resolveLink()
    }

    private var loadedEvents: Any = Unit

    /**
     * The day strip (docs/phase-5-design.md section 2): today's events of the event calendars the owner shows, read again only when the
     * events changed or the date did (so it never shows yesterday after midnight). Never throws: an odd calendar shows nothing.
     */
    private var eventsJob: Job? = null
    /** Day spans of the events kept among the task objects of a "both" list, by address and ETag (worked out once per version). */
    private val bothSpans = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Long>>()

    /** The strip is worked out in its own job (a newer request replaces an older one), so a task screen never waits for it. */
    private fun scheduleEvents() {
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch { reloadEvents() }
    }

    private suspend fun reloadEvents() {
        if (!_state.value.prefs.showDayStrip) {          // switched off: nothing is read or parsed at all
            loadedEvents = Unit
            if (_state.value.dayEvents.isNotEmpty()) _state.update { it.copy(dayEvents = emptyList()) }
            return
        }
        val day = java.time.LocalDate.now(zone)
        // the events of a list that holds tasks and events live among the task objects: then a change there counts too
        val both = withContext(Dispatchers.Default) { graph.store.lists().any { it.kind == dev.bookah.tasks.data.ListKind.Both && it.showEvents } }
        val key = Triple(graph.store.eventsVersion(), day, if (both) graph.store.version() else 0L)
        if (loadedEvents == key) return
        val owner = session?.user
        val events = withContext(Dispatchers.Default) {
            runCatching {
                val lists = graph.store.lists().filter { it.holdsEvents && it.showEvents }
                val eventOnly = lists.filter { it.kind == dev.bookah.tasks.data.ListKind.Events }.map { it.id }.toSet()
                val bothLists = lists.filter { it.kind == dev.bookah.tasks.data.ListKind.Both }
                val d = day.toEpochDay()
                // only events whose span can touch today are parsed (for "both" lists the span is kept per ETag)
                val fromBoth = bothLists.flatMap { l -> graph.store.objects(l.id) }.filter { o ->
                    o.ics.contains("BEGIN:VEVENT") && bothSpans.getOrPut(o.href + "|" + o.etag) { dev.bookah.tasks.data.EventSpan.of(o.ics) }.let { it.second >= d - 1 && it.first <= d + 1 }
                }
                val sources = graph.store.eventsTouching(d - 1, d + 1).filter { it.listId in eventOnly } + fromBoth
                dev.bookah.tasks.data.DayEvents.forDay(sources, day, zone, owner)
            }.getOrDefault(emptyList())
        }
        loadedEvents = key
        _state.update { it.copy(dayEvents = events) }
    }

    private fun calendarInstalled(): Boolean = runCatching { graph.appContext.packageManager.getLaunchIntentForPackage(CALENDAR_PACKAGE) != null }.getOrDefault(false)

    fun setShowEvents(listId: Long, show: Boolean) {
        viewModelScope.launch { io { graph.store.setShowEvents(listId, show) }; _state.update { it.copy(lists = graph.store.lists()) }; scheduleEvents() }
    }

    fun setDayStrip(on: Boolean) { viewModelScope.launch { graph.prefs.setDayStrip(on) } }

    /** A tap on an event in the strip: BookahCalendar opens if it is installed; otherwise nothing happens (the strip is read only). */
    fun openCalendar() {
        val ctx = graph.appContext
        val launch = runCatching { ctx.packageManager.getLaunchIntentForPackage(CALENDAR_PACKAGE) }.getOrNull() ?: return
        runCatching { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun setListVisible(listId: Long, visible: Boolean) {
        graph.store.setVisible(listId, visible)
        viewModelScope.launch { reload() }
    }

    fun show(screen: Screen) = _state.update { it.copy(screen = screen, openTask = null, settingsOpen = false, reordering = false, searchOpen = false, searchQuery = "") }
    /** A finished copy of a repeating task shares its address with the live task, so it is never opened for editing. */
    fun openTask(t: Task?) { if (t?.isOccurrenceCopy != true) _state.update { it.copy(openTask = t) } }
    fun openSettings(open: Boolean) = _state.update { it.copy(settingsOpen = open) }

    /** The search page opens over the current screen (a new search starts empty); Back closes it again. */
    fun openSearch(open: Boolean) = _state.update { it.copy(searchOpen = open, searchQuery = if (open) it.searchQuery.takeIf { _ -> it.searchOpen }.orEmpty() else "", settingsOpen = false) }
    fun setSearchQuery(q: String) = _state.update { it.copy(searchQuery = q) }

    /** Back closes the topmost thing: the task page, then the search page, then Settings. */
    fun back() = _state.update {
        if (it.openTask != null) it.copy(openTask = null) else if (it.searchOpen) it.copy(searchOpen = false, searchQuery = "") else if (it.settingsOpen) it.copy(settingsOpen = false)
        else if (it.reordering) it.copy(reordering = false)
        else if (it.screen != Screen.Schedule) it.copy(screen = Screen.Schedule) else it
    }

    fun clearCrashLog() { CrashLog.clear(graph.appContext); _state.update { it.copy(crashLog = "") } }

    /** Signing out forgets the phone's copy, including changes not yet sent, so with changes waiting the owner is asked first. */
    fun signOut() {
        if (graph.outbox.count() > 0) _state.update { it.copy(confirmSignOut = true) } else signOutNow()
    }

    fun cancelSignOut() = _state.update { it.copy(confirmSignOut = false) }

    fun signOutNow() {
        _state.update { it.copy(confirmSignOut = false) }
        viewModelScope.launch {
            io { graph.sessions.clear(); graph.store.clearAll() }
            dev.bookah.tasks.widget.WidgetRefresh.request(graph.appContext)          // the widget must not keep showing the signed-out account's tasks
            RefreshWorker.cancel(graph.appContext)
            ReminderScheduler(graph.appContext).reschedule(emptyList())          // no alarms for a task list that is no longer here
            session = null
            loadedVersion = -1L
            loadedEvents = Unit
            pendingLink = null              // a link that was waiting is dropped, and the next sign-in starts fresh
            firstSyncDone = false
            _state.update { UiState(phase = Phase.SignedOut, prefs = it.prefs) }
        }
    }

    // ---- changes: each one is applied on the phone at once, queued for Nextcloud, and the screen reads the saved copy again ----

    /** A change the outbox refuses (a task in conflict, a name already taken) becomes a plain message, never a crash. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {          // whatever goes wrong inside a change (a task in conflict, odd data from another app) is a message, not a crash
            _state.update { it.copy(message = UiText(R.string.msg_cant_change)) }
        }
    }

    /** One change at a time, off the main thread: two quick actions on the same task (type a title, tap Done in the app or in a notification) can never read the same old text and erase each other. */
    private suspend fun <T> write(block: suspend () -> T): T = graph.writeLock.withLock { withContext(Dispatchers.IO) { block() } }

    /** Reads the saved copy again and asks for a push a few seconds later (longer than the undo window, so a quick undo never reaches the server). */
    private suspend fun afterChange() {
        reload()
        PushWorker.enqueue(graph.appContext, delaySeconds = UNDO_SECONDS + 2L)
        dev.bookah.tasks.widget.WidgetRefresh.request(graph.appContext)          // the home-screen widget shows the change at once
    }

    private fun icsOf(t: Task): String? = graph.store.objectByHref(t.href)?.ics

    /** True when the stored text at [href] is exactly [text]. */
    private fun icsOf(text: String, href: String): Boolean = graph.store.objectByHref(href)?.ics == text

    private fun showUndo(message: UiText, restore: suspend () -> Unit) {
        undoAction = restore
        val token = System.nanoTime()
        _state.update { it.copy(undo = UndoBar(message, token)) }
        undoJob?.cancel()
        undoJob = viewModelScope.launch {
            delay(UNDO_SECONDS * 1000L)
            _state.update { s -> if (s.undo?.token == token) s.copy(undo = null) else s }
        }
    }

    fun undo() {
        val action = undoAction ?: return
        undoAction = null
        undoJob?.cancel()
        _state.update { it.copy(undo = null) }
        viewModelScope.launch { guarded { write { action() } }; afterChange() }
    }

    fun dismissUndo() = _state.update { it.copy(undo = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun openQuickAdd(open: Boolean) = _state.update { it.copy(quickAdd = open, prefill = if (open) it.prefill else null) }

    /**
     * A share from another app or the quick-settings tile (docs/phase-5-design.md section 3): the quick-add sheet opens, filled with the
     * shared text when there is some. On a cold start it waits until the session is read; signed out, the text is dropped and a short
     * message says so. A page or Settings that is open closes first (the sheet is drawn under them).
     */
    fun openShared(shared: dev.bookah.tasks.data.SharedTask?) {
        viewModelScope.launch {
            if (_state.first { it.phase != Phase.Loading }.phase != Phase.SignedIn) {
                if (shared != null && shared.title.isNotBlank()) android.widget.Toast.makeText(graph.appContext, R.string.msg_share_signed_out, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val fill = shared?.takeIf { it.title.isNotBlank() || it.url != null }?.let { QuickPrefill(it.title, it.url, it.notes, System.nanoTime()) }
            _state.update { it.copy(quickAdd = true, prefill = fill, openTask = null, settingsOpen = false, searchOpen = false, reordering = false) }
        }
    }

    private fun edit(t: Task, f: (String) -> String) {
        viewModelScope.launch {
            guarded { write { val ics = icsOf(t) ?: return@write; graph.outbox.edit(t.href, IcsPatcher.touch(f(ics), Instant.now())) } }
            afterChange()
        }
    }

    override fun toggle(t: Task) {
        viewModelScope.launch {
            var message: UiText? = null
            val old = LinkedHashMap<String, String>()          // address -> text before, for the undo
            val written = LinkedHashMap<String, String>()      // address -> text after the tick: an undo only touches what is still exactly that
            guarded {
                write {
                    val now = Instant.now()
                    val ics = icsOf(t) ?: return@write
                    // the row on screen may be out of date (a second tap on a repeating task would advance it twice): act only on what was drawn
                    val shown = TaskParser.parse(StoredObject(t.href, t.listId, t.etag, ics), zone).firstOrNull { !it.isOccurrenceCopy }
                    if (shown == null || shown.isOpen != t.isOpen || shown.start != t.start || shown.due != t.due) return@write
                    old[t.href] = ics
                    if (t.isOpen) {
                        val c = TaskEdit.complete(ics, now, zone)
                        if (c is Completion.Unchanged) {          // the next date could not be worked out: nothing is written, the series is not ended
                            old.clear(); _state.update { it.copy(message = UiText(R.string.msg_repeat_unknown)) }; return@write
                        }
                        graph.outbox.edit(t.href, c.ics); written[t.href] = c.ics
                        message = UiText(if (c is Completion.Advanced) R.string.undo_next_date else R.string.undo_done)
                        if (c is Completion.Done) for (k in TaskViews.descendants(_state.value.tasks, t.uid, openOnly = true)) {
                            val kics = icsOf(k) ?: continue
                            val kc = TaskEdit.complete(kics, now, zone)
                            if (kc is Completion.Unchanged) continue
                            old[k.href] = kics
                            graph.outbox.edit(k.href, kc.ics); written[k.href] = kc.ics
                        }
                    } else {
                        val reopened = TaskEdit.reopen(ics, now)
                        graph.outbox.edit(t.href, reopened); written[t.href] = reopened
                        message = UiText(R.string.undo_reopened)
                    }
                }
            }
            message?.let { m ->
                showUndo(m) {
                    // restore only items still exactly as the tick left them, so a newer edit or a pulled change is never erased by an Undo
                    old.forEach { (href, text) -> if (icsOf(written.getValue(href), href)) graph.outbox.edit(href, IcsPatcher.touch(text, Instant.now())) }
                }
            }
            afterChange()
        }
    }

    override fun reschedule(t: Task, target: dev.bookah.tasks.data.RescheduleTarget) {
        viewModelScope.launch {
            var old: String? = null
            var written: String? = null
            guarded {
                write {
                    val ics = icsOf(t) ?: return@write
                    // plan from the text on the phone now, not from the copy that was on screen when the swipe began
                    // already parked: nothing to change; Someday only adds the tag, the dates are not rewritten
                    var out = dev.bookah.tasks.data.Reschedule.applyTo(ics, target, java.time.LocalDate.now(), _state.value.prefs.eveningHour, zone) ?: return@write
                    out = IcsPatcher.touch(out, Instant.now())
                    graph.outbox.edit(t.href, out)
                    old = ics; written = out
                }
            }
            val before = old; val after = written
            if (before != null && after != null) showUndo(UiText(R.string.undo_rescheduled)) {
                if (icsOf(after, t.href)) graph.outbox.edit(t.href, IcsPatcher.touch(before, Instant.now()))
            }
            afterChange()
        }
    }

    /**
     * The overdue tamer's "move all" (docs/phase-5-design.md section 1): the tasks the owner saw in the confirmation, planned again from
     * their text as it is now under the write lock ([dev.bookah.tasks.data.OverdueTamer]), written each on its own. One Undo puts back every
     * task that is still exactly as the move left it.
     */
    fun moveAllOverdue(shown: List<Task>, target: dev.bookah.tasks.data.RescheduleTarget) {
        viewModelScope.launch {
            var plan: dev.bookah.tasks.data.MovePlan? = null
            var done: dev.bookah.tasks.data.Outbox.EachResult? = null
            guarded {
                write {
                    // plan and write under the store lock (the pull takes it too), and each write only if the text is still the one planned from
                    synchronized(graph.store) {
                        val p = dev.bookah.tasks.data.OverdueTamer.plan(
                            shown, { graph.store.objectByHref(it)?.ics }, graph.store.lists(), dev.bookah.tasks.data.OverdueTamer.blocked(graph.store.outboxAll()),
                            target, java.time.LocalDate.now(zone), _state.value.prefs.eveningHour, zone, Instant.now(),
                        )
                        plan = p
                        done = graph.outbox.editEach(p.writes, expected = p.old)
                    }
                }
            }
            val p = plan; val d = done
            if (p != null && d != null) {
                // read-only lists and repeating tasks were already named in the question; a dialog for them would only cover the Undo bar
                val left = p.stale + p.blocked + d.failed.size
                if (left > 0) _state.update { it.copy(message = UiText(R.plurals.msg_moved_skipped, left, plural = true)) }
                if (d.written.isNotEmpty()) {
                    val written = d.written.associateWith { p.writes.getValue(it) }
                    showUndo(UiText(R.plurals.undo_moved_n, d.written.size, plural = true)) {
                        // only tasks still exactly as the move left them, checked and written under one lock
                        synchronized(graph.store) {
                            val back = written.filter { (href, text) -> icsOf(text, href) }.mapValues { (href, _) -> IcsPatcher.touch(p.old.getValue(href), Instant.now()) }
                            graph.outbox.editEach(back, expected = written)
                        }
                    }
                }
            }
            afterChange()
        }
    }

    /** A change with an Undo bar: only a task still exactly as the change left it is put back. */
    private fun editWithUndo(t: Task, message: UiText, f: (String) -> String) {
        viewModelScope.launch {
            var old: String? = null
            var written: String? = null
            guarded {
                write {
                    val ics = icsOf(t) ?: return@write
                    val out = IcsPatcher.touch(f(ics), Instant.now())
                    graph.outbox.edit(t.href, out)
                    old = ics; written = out
                }
            }
            val before = old; val after = written
            if (before != null && after != null) showUndo(message) { if (icsOf(after, t.href)) graph.outbox.edit(t.href, IcsPatcher.touch(before, Instant.now())) }
            afterChange()
        }
    }

    override fun setWaiting(t: Task, person: String?, checkBack: java.time.LocalDate) {
        if (t.repeats) { _state.update { it.copy(message = UiText(R.string.waiting_repeating)) }; return }
        editWithUndo(t, UiText(R.string.undo_waiting)) { TaskEdit.setWaiting(it, person, checkBack, zone) }
    }

    override fun endWaiting(t: Task) = editWithUndo(t, UiText(R.string.undo_waiting_end)) { TaskEdit.endWaiting(it) }

    override fun setTitle(t: Task, title: String) { if (title.isNotBlank()) edit(t) { TaskEdit.setTitle(it, title) } }
    override fun setNotes(t: Task, notes: String?) = edit(t) { TaskEdit.setNotes(it, notes) }
    override fun setDates(t: Task, start: TaskWhen?, due: TaskWhen?) {
        // a repeating task keeps at least one date (the core refuses too): say why instead of the general message
        if (t.repeats && start == null && due == null) { _state.update { it.copy(message = UiText(R.string.msg_repeat_needs_date)) }; return }
        edit(t) { TaskEdit.setDates(it, start, due, zone) }
    }
    override fun setPriority(t: Task, p: Priority) = edit(t) { TaskEdit.setPriority(it, p) }
    override fun setTags(t: Task, tags: List<String>) = edit(t) { TaskEdit.setTags(it, tags) }
    override fun setSomeday(t: Task, on: Boolean) = edit(t) { TaskEdit.setSomeday(it, on) }
    override fun setRepeat(t: Task, spec: dev.bookah.tasks.data.RepeatSpec?) = edit(t) { TaskEdit.setRepeat(it, spec, zone, java.time.LocalDate.now(zone)) }
    override fun setReminders(t: Task, list: List<dev.bookah.tasks.data.ReminderChoice>) = edit(t) { TaskEdit.setReminders(it, list, zone) }
    override fun useDefaultReminders(t: Task) = edit(t) { TaskEdit.useDefaultReminders(it) }

    /** A reminder notification was tapped: that task opens with the reschedule sheet over it (This evening lives there: a notification has room for three buttons only). */
    fun openFromReminder(href: String, uid: String) {
        viewModelScope.launch {
            // on a cold start the session is still being read
            if (_state.first { it.phase != Phase.Loading }.phase != Phase.SignedIn) return@launch
            reload()
            val t = _state.value.tasks.firstOrNull { it.href == href && it.uid == uid && !it.isOccurrenceCopy }
            if (t == null) { android.widget.Toast.makeText(graph.appContext, R.string.msg_task_not_found, android.widget.Toast.LENGTH_SHORT).show(); return@launch }
            // a read-only list cannot be rescheduled: then only the page opens (as on the page itself, where Reschedule is hidden)
            val writable = _state.value.lists.firstOrNull { it.id == t.listId }?.editable == true
            _state.update { it.copy(openTask = t, settingsOpen = false, askReschedule = if (t.isOpen && writable) t.href + "|" + t.uid else null) }
        }
    }

    fun rescheduleAsked() = _state.update { it.copy(askReschedule = null) }

    /** The list a new task goes to: the one asked for, else the list on screen, else the default list from Settings, else the inbox, else the first writable list. */
    private fun targetList(preferred: Long?): TaskList? {
        val st = _state.value
        val writable = st.taskLists.filter { it.editable && it.visible }
        return preferred?.let { id -> writable.firstOrNull { it.id == id } }
            ?: (st.screen as? Screen.OneList)?.let { s -> writable.firstOrNull { it.id == s.listId } }
            ?: st.prefs.defaultListHref?.let { h -> writable.firstOrNull { it.href == h } }
            ?: writable.firstOrNull { it.id == st.inboxListId }
            ?: writable.firstOrNull()
    }

    /** The list a new task would go to now (shown in the quick-add sheet). */
    fun addTarget(): TaskList? = targetList(null)

    override fun add(task: NewTask) {
        if (task.title.isBlank()) return
        val list = targetList(task.listId)
        if (list == null) { _state.update { it.copy(message = UiText(R.string.msg_no_list)) }; return }
        viewModelScope.launch {
            guarded {
                write {
                    val uid = UUID.randomUUID().toString()
                    var ics = IcsPatcher.newTask(uid, task.title.trim(), Instant.now())
                    if (task.start != null || task.due != null) ics = TaskEdit.setDates(ics, task.start, task.due, zone)
                    if (task.priority != Priority.None) ics = TaskEdit.setPriority(ics, task.priority)
                    if (task.tags.isNotEmpty()) ics = TaskEdit.setTags(ics, task.tags)
                    task.repeat?.let { ics = TaskEdit.setRepeat(ics, it, zone, java.time.LocalDate.now(zone)) }          // gives an undated task its first date
                    task.url?.let { ics = TaskEdit.setUrl(ics, it) }
                    task.notes?.let { ics = TaskEdit.setNotes(ics, it) }
                    graph.outbox.create(list, uid, ics)
                }
            }
            afterChange()
        }
    }

    override fun addSubtask(parent: Task, title: String) {
        if (title.isBlank()) return
        val list = _state.value.taskLists.firstOrNull { it.id == parent.listId } ?: return
        viewModelScope.launch {
            guarded { write { val uid = UUID.randomUUID().toString(); graph.outbox.create(list, uid, TaskEdit.newSubtask(parent.uid, uid, title.trim(), Instant.now())) } }
            afterChange()
        }
    }

    override fun delete(t: Task) {
        viewModelScope.launch {
            val saved = ArrayList<Triple<String, Long, String>>()          // address, list, text: the task first, then what is under it
            guarded {
                write {
                    for (x in listOf(t) + TaskViews.descendants(_state.value.tasks, t.uid, openOnly = false)) icsOf(x)?.let { saved.add(Triple(x.href, x.listId, it)) }
                    for ((href, _, _) in saved) graph.outbox.delete(href)
                }
            }
            _state.update { it.copy(openTask = null) }
            if (saved.isNotEmpty()) showUndo(UiText(R.string.undo_deleted)) { saved.forEach { (href, listId, text) -> graph.outbox.recreate(listId, href, text) } }
            afterChange()
        }
    }

    override fun reorder(t: Task, after: String?, before: String?) {
        val list = _state.value.taskLists.firstOrNull { it.id == t.listId && it.editable } ?: return          // a read-only list keeps its order
        viewModelScope.launch {
            // planned from the saved copy under the push lock, all or nothing (Outbox.reorder): a task with a conflict, a refused change
            // or a delete waiting stops the whole move and the owner gets the usual message
            guarded { write { graph.outbox.reorder(list.id, t.href, after, before, zone, Instant.now()) } }
            afterChange()
            // written, refused or nothing to do: the screen drops the order it showed early and shows the saved one
            _state.update { it.copy(orderAttempts = it.orderAttempts + 1) }
        }
    }

    fun setReordering(on: Boolean) = _state.update { it.copy(reordering = on && it.screen is Screen.OneList) }

    override fun move(t: Task, listId: Long) {
        val to = _state.value.taskLists.firstOrNull { it.id == listId && it.editable } ?: return
        viewModelScope.launch {
            val moved = ArrayList<Pair<String, Long>>()                    // new address, the list it came from
            guarded {
                write {
                    for (x in listOf(t) + TaskViews.descendants(_state.value.tasks, t.uid, openOnly = false)) moved.add(graph.outbox.move(x.href, to) to x.listId)
                }
            }
            if (moved.isNotEmpty()) showUndo(UiText(R.string.undo_moved)) {
                for ((href, from) in moved) _state.value.lists.firstOrNull { it.id == from }?.let { graph.outbox.move(href, it) }
            }
            afterChange()
        }
    }

    // ---- conflicts and refused changes ----
    private fun firstConflictId(): Long? = _state.value.conflicts.firstOrNull()?.id

    fun keepMine() { firstConflictId()?.let { id -> viewModelScope.launch { guarded { write { graph.outbox.keepMine(id) } }; afterChange() } } }
    fun keepServers() { firstConflictId()?.let { id -> viewModelScope.launch { guarded { write { graph.outbox.keepServer(id) } }; afterChange() } } }
    fun keepBoth() { firstConflictId()?.let { id -> viewModelScope.launch { guarded { write { graph.outbox.keepBoth(id, UUID.randomUUID().toString()) } }; afterChange() } } }
    fun retryFailed(id: Long) { viewModelScope.launch { guarded { write { graph.outbox.retry(id) } }; afterChange() } }
    fun discardFailed(id: Long) { viewModelScope.launch { guarded { write { graph.outbox.discard(id) } }; afterChange() } }
    fun setDefaultList(href: String?) { viewModelScope.launch { graph.prefs.setDefaultListHref(href) } }

    companion object {
        const val UNDO_SECONDS = 6
        const val CALENDAR_PACKAGE = "dev.bookah.calendar"
        /** The error code of a sync that threw instead of answering (shown as "Sync did not finish"). */
        const val SYNC_CRASHED = -1
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
    }
}
