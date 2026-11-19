package dev.bookah.tasks

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import dev.bookah.tasks.ui.AppDrawer
import dev.bookah.tasks.ui.BookahTheme
import dev.bookah.tasks.ui.ConflictDialog
import dev.bookah.tasks.ui.MessageDialog
import dev.bookah.tasks.ui.QuickAddSheet
import dev.bookah.tasks.ui.RescheduleSheet
import dev.bookah.tasks.ui.SignOutDialog
import dev.bookah.tasks.ui.UndoBarView
import dev.bookah.tasks.ui.MainList
import dev.bookah.tasks.ui.RowEnv
import dev.bookah.tasks.ui.SettingsScreen
import dev.bookah.tasks.ui.SearchPage
import androidx.compose.material.icons.filled.Search
import dev.bookah.tasks.ui.SignInScreen
import dev.bookah.tasks.ui.TaskPage
import dev.bookah.tasks.ui.TopBar
import dev.bookah.tasks.ui.screenTitle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : ComponentActivity() {
    /** The same ViewModel the screens use (one per activity), reachable from [onNewIntent]. */
    private val model: AppViewModel by lazy { ViewModelProvider(this, AppViewModel.Factory((application as BookahApp).graph))[AppViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BookahApp.screenCreatedAt = android.os.SystemClock.elapsedRealtime()
        enableEdgeToEdge()
        // a link opens its task once: not again when the screen is rebuilt (rotation) or the app is reopened from Recents
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            val model = this.model
            val state by model.state.collectAsStateWithLifecycle()
            BookahTheme(state.prefs.themeId, state.prefs.appearance, artwork = if (state.prefs.artwork) Art.forMonth(LocalDate.now().monthValue) else null) {
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.onResume() }
                val drawer = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                // Android 13 and later ask once before notifications may show (the reminders need them)
                val askNotifications = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                androidx.compose.runtime.LaunchedEffect(state.phase) {
                    if (state.phase == Phase.SignedIn && android.os.Build.VERSION.SDK_INT >= 33) {
                        val sp = getSharedPreferences("reminders", MODE_PRIVATE)
                        if (!sp.getBoolean("asked", false)) { sp.edit().putBoolean("asked", true).apply(); askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                    }
                }
                // Back closes the topmost thing: the drawer, then the task page or Settings; otherwise the system leaves the app.
                BackHandler(enabled = drawer.isOpen || state.openTask != null || state.searchOpen || state.settingsOpen || state.reordering || state.screen != Screen.Schedule) {
                    if (drawer.isOpen) scope.launch { drawer.close() } else model.back()
                }
                val context = LocalContext.current
                val words = remember(context.resources) { Words(context.resources) }
                // a Surface sets the default text and icon colour for everything inside (otherwise black text on a dark page)
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    when (state.phase) {
                        Phase.Loading -> {}
                        Phase.SignedOut -> SignInScreen(state.signingIn, state.signInError, model::signIn)
                        Phase.SignedIn -> {
                            var rescheduling by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
                            androidx.compose.runtime.LaunchedEffect(state.askReschedule) { state.askReschedule?.let { rescheduling = it; model.rescheduleAsked() } }
                            val env = RowEnv(state, words, LocalDate.now(), ZoneId.systemDefault(), model::openTask, model, onReschedule = { rescheduling = it.href + "|" + it.uid },
                                onMoveAll = model::moveAllOverdue, dayEvents = state.dayEvents, onEvent = { model.openCalendar() })
                            Box(Modifier.fillMaxSize()) {
                                ModalNavigationDrawer(
                                    drawerContent = {
                                        AppDrawer(
                                            state,
                                            onShow = { model.show(it); scope.launch { drawer.close() } },
                                            onToggleList = model::setListVisible,
                                            onRefresh = { model.refresh(); scope.launch { drawer.close() } },
                                            onSettings = { model.openSettings(true); scope.launch { drawer.close() } },
                                            onSearch = { model.openSearch(true); scope.launch { drawer.close() } },
                                        )
                                    },
                                    drawerState = drawer,
                                    gesturesEnabled = drawer.isOpen,          // a swipe on a task row is for that row; the menu button opens the drawer
                                ) {
                                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                                        // a writable list with something to order gets a Reorder / Done button
                                        val oneList = (state.screen as? Screen.OneList)?.let { s -> state.taskLists.firstOrNull { it.id == s.listId } }
                                        val canOrder = oneList?.editable == true && state.tasks.count { it.listId == oneList.id && it.isOpen && !it.someday } >= 2
                                        TopBar(
                                            screenTitle(state), onMenu = { scope.launch { drawer.open() } }, syncing = state.syncing, offline = state.lastReport?.offline == true,
                                            action = {
                                                if (canOrder || state.reordering) androidx.compose.material3.TextButton({ model.setReordering(!state.reordering) }) {
                                                    Text(stringResource(if (state.reordering) R.string.order_done else R.string.order_reorder))
                                                }
                                                if (!state.reordering) androidx.compose.material3.IconButton({ model.openSearch(true) }) {
                                                    Icon(androidx.compose.material.icons.Icons.Filled.Search, stringResource(R.string.search_open))
                                                }
                                            },
                                        )
                                        SyncLine(state)
                                        MainList(env)
                                    }
                                }
                                // the search page sits under a task opened from its results, so closing that task comes back to the results
                                if (state.searchOpen) SearchPage(env, state.searchQuery, model::setSearchQuery, onClose = { model.openSearch(false) })
                                // keyed by task: when a link opens another task over this one, the old page saves its own typing to its own task
                                state.openTask?.let { t -> androidx.compose.runtime.key(t.href, t.uid) { TaskPage(t, env, onClose = model::back) } }
                                if (state.settingsOpen) SettingsScreen(
                                    state, onBack = model::back, onSignOut = model::signOut, onTheme = model::setTheme, onAppearance = model::setAppearance,
                                    onEveningHour = model::setEveningHour, onSync = model::refresh, onClearCrash = model::clearCrashLog,
                                    onArtwork = model::setArtwork, onDefaultList = model::setDefaultList,
                                    onRetry = model::retryFailed, onDiscard = model::discardFailed,
                                    onDayStrip = model::setDayStrip, onShowEvents = model::setShowEvents, onCheckUpdates = model::checkUpdates,
                                )
                                // not on top of the open menu, nor over the arrows of the reorder mode
                                val showFab = state.openTask == null && !state.settingsOpen && !state.searchOpen && !state.reordering && drawer.targetValue == DrawerValue.Closed
                                if (showFab) FloatingActionButton(
                                    onClick = { model.openQuickAdd(true) }, modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(16.dp),
                                ) { Icon(Icons.Filled.Add, stringResource(R.string.add_task)) }
                                rescheduling?.let { key ->
                                    val task = state.tasks.firstOrNull { !it.isOccurrenceCopy && it.href + "|" + it.uid == key }
                                    if (task == null) rescheduling = null else RescheduleSheet(task, env.today, onPick = { model.reschedule(task, it); rescheduling = null }, onDismiss = { rescheduling = null })
                                }
                                state.undo?.let { bar ->
                                    UndoBarView(
                                        bar, model::undo,
                                        Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 16.dp, end = if (showFab) 88.dp else 16.dp, bottom = 16.dp),
                                    )
                                }
                                // A task page opened by a link (BookahCalendar) goes over an open quick-add sheet without closing it: the sheet
                                // waits and comes back when the page closes, with its typed text kept the same way it survives a rotation.
                                val quickAddSaved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                                // a new share gets a fresh sheet (its own saved state), so its text replaces what was typed before
                                val sheetKey = "quick-add-" + (state.prefill?.token ?: 0L)
                                val lastKey = androidx.compose.runtime.remember { java.util.concurrent.atomic.AtomicReference(sheetKey) }
                                androidx.compose.runtime.LaunchedEffect(state.quickAdd, sheetKey) {
                                    if (lastKey.get() != sheetKey) { quickAddSaved.removeState(lastKey.get()); lastKey.set(sheetKey) }
                                    if (!state.quickAdd) quickAddSaved.removeState(sheetKey)
                                }
                                if (state.quickAdd && state.openTask == null) quickAddSaved.SaveableStateProvider(sheetKey) {
                                    androidx.compose.runtime.key(sheetKey) {
                                        QuickAddSheet(
                                            state.taskLists.filter { it.editable && it.visible }, model.addTarget()?.id, env.today, state.prefs.eveningHour,
                                            onAdd = { n, keepOpen -> model.add(n); if (!keepOpen) model.openQuickAdd(false) }, onDismiss = { model.openQuickAdd(false) },
                                            prefill = state.prefill,
                                        )
                                    }
                                }
                                state.conflicts.firstOrNull()?.let { ConflictDialog(it, model::keepMine, model::keepServers, model::keepBoth) }
                                if (state.confirmSignOut) SignOutDialog(model::signOutNow, model::cancelSignOut)
                                state.message?.let { MessageDialog(it.resolve(), model::dismissMessage) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** The app was already running (singleTask): a link from another app arrives here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /**
     * bookahtasks://task?uid=...&list=... from BookahCalendar (see [dev.bookah.tasks.data.TaskLinks]). Anything else, or junk, is ignored
     * quietly. The link is never logged or shown: the list address contains the user name.
     */
    private fun handleLink(i: Intent?) {
        if (i == null) return
        if (i.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (i.action == ACTION_QUICK_ADD) {
            // from our ShareActivity (cleaned shared text) or the quick-settings tile (no text); the text is cleaned again here because
            // MainActivity is exported and any app could send this action
            fun extra(name: String) = runCatching { i.getStringExtra(name) }.getOrNull()
            val shared = if (extra(EXTRA_SHARED_TITLE) == null && extra(EXTRA_SHARED_URL) == null) null
                else dev.bookah.tasks.data.SharedText.of(extra(EXTRA_SHARED_TITLE), extra(EXTRA_SHARED_URL), extra(EXTRA_SHARED_NOTES))
            setIntent(Intent(this, MainActivity::class.java))          // handled: a rotation or Recents never opens the sheet again
            model.openShared(shared)
            return
        }
        if (i.action == ACTION_OPEN_TASK) {
            // a reminder notification was tapped (our own immutable PendingIntent): open that task with its reschedule sheet
            val href = i.getStringExtra("href"); val uid = i.getStringExtra("uid")
            setIntent(Intent(this, MainActivity::class.java))
            if (!href.isNullOrEmpty() && !uid.isNullOrEmpty()) model.openFromReminder(href, uid)
            return
        }
        if (i.action != Intent.ACTION_VIEW) return
        val link = dev.bookah.tasks.data.TaskLinks.parse(i.dataString)
        setIntent(Intent(this, MainActivity::class.java))          // handled: a later look at the intent finds nothing to open
        if (link != null) model.openLink(link)
    }
}

/** One quiet line under the title when the last sync did not finish: the saved copy is still shown. */
@androidx.compose.runtime.Composable
private fun SyncLine(state: UiState) {
    val r = state.lastReport ?: return
    val text = when {
        r.offline -> stringResource(R.string.sync_offline)
        r.errorCode == 401 -> stringResource(R.string.sync_wrong_password)
        r.error != null -> stringResource(R.string.sync_error, r.errorCode)
        r.listErrors > 0 -> stringResource(R.string.sync_list_errors)
        else -> return
    }
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

