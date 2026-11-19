package dev.bookah.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bookah.tasks.R
import dev.bookah.tasks.Screen
import dev.bookah.tasks.UiState
import dev.bookah.tasks.Words
import dev.bookah.tasks.data.DayBucket
import dev.bookah.tasks.data.Task
import dev.bookah.tasks.data.TaskNode
import dev.bookah.tasks.data.TaskViews
import java.time.LocalDate
import java.time.ZoneId

/** What every row needs: the colours of the lists, the words, today's date. Built once per screen. */
class RowEnv(
    val state: UiState, val words: Words, val today: LocalDate, val zone: ZoneId, val onOpen: (Task) -> Unit,
    val actions: dev.bookah.tasks.TaskActions, val onReschedule: (Task) -> Unit = {}, val dayKnown: Boolean = false,
    /** The overdue tamer's "move all" (the tasks shown in the confirmation and the target). */
    val onMoveAll: (List<Task>, dev.bookah.tasks.data.RescheduleTarget) -> Unit = { _, _ -> },
    /** Today's calendar events for the day strip (empty when switched off), and what a tap on one does. */
    val dayEvents: List<dev.bookah.tasks.data.DayEvent> = emptyList(),
    val onEvent: (dev.bookah.tasks.data.DayEvent) -> Unit = {},
) {
    /** The same, for a screen whose heading already names the day: rows then show only the time. */
    fun underDayHeading() = RowEnv(state, words, today, zone, onOpen, actions, onReschedule, dayKnown = true, onMoveAll, dayEvents, onEvent)
    private val byId = state.lists.associateBy { it.id }
    private val kids = state.tasks.filter { it.parentUid != null }.groupBy { it.parentUid!! }
    fun color(t: Task) = listColorOf(byId[t.listId]?.colorHex ?: "#245A86")
    fun listName(t: Task): String? = byId[t.listId]?.name
    fun progress(t: Task): Pair<Int, Int>? = kids[t.uid]?.takeIf { it.isNotEmpty() }?.let { c -> c.count { it.isDone } to c.size }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TaskLine(env: RowEnv, t: Task, showList: Boolean, indent: Int = 0, moves: List<Pair<String, () -> Unit>> = emptyList(), moveButton: Boolean = false) {
    // an open, real task can be swiped: right = done, left = reschedule (the row always snaps back; the action is its own)
    val writable = env.state.lists.firstOrNull { it.id == t.listId }?.editable == true          // no swipes on a read-only list
    val canSwipe = t.isOpen && !t.isOccurrenceCopy && writable
    val cur by androidx.compose.runtime.rememberUpdatedState(t)                                  // the swipe state outlives a recomposition: always act on the task as it is now
    val row: @Composable () -> Unit = {
        TaskRow(
            t, env.color(t), if (showList) env.listName(t) else null, env.words, env.today, env.zone, env.progress(t), indent, env.dayKnown,
            pending = t.href in env.state.pending, onToggle = if (t.isOccurrenceCopy) null else ({ env.actions.toggle(t) }),
            onReschedule = if (canSwipe) ({ env.onReschedule(t) }) else null, moreActions = moves,
            trailing = if (moveButton && canSwipe) ({
                androidx.compose.material3.IconButton({ env.onReschedule(t) }) { Icon(androidx.compose.material.icons.Icons.Filled.DateRange, stringResource(R.string.swipe_reschedule)) }
            }) else null,
        ) { env.onOpen(t) }
    }
    if (!canSwipe) { row(); return }
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.4f },                                                       // a short flick while scrolling must not tick a task
        confirmValueChange = { v ->
            when (v) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> env.actions.toggle(cur)
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> env.onReschedule(cur)
                else -> {}
            }
            false
        },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state,
        backgroundContent = {
            val dir = state.dismissDirection
            val done = dir == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            val move = dir == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().then(
                    when {
                        done -> Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                        move -> Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                        else -> Modifier
                    },
                ).padding(horizontal = 24.dp),
                contentAlignment = if (done) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (done) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(androidx.compose.material.icons.Icons.Filled.Check, null); Text(stringResource(R.string.edit_mark_done), Modifier.padding(start = 8.dp))
                }
                if (move) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.swipe_reschedule), Modifier.padding(end = 8.dp)); Icon(androidx.compose.material.icons.Icons.Filled.DateRange, null)
                }
            }
        },
    ) { androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) { row() } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.rows(env: RowEnv, tasks: List<Task>, showList: Boolean) {
    items(tasks.size, key = { tasks[it].href + "|" + tasks[it].uid + "|" + tasks[it].isOccurrenceCopy + "|" + it }) { TaskLine(env, tasks[it], showList) }
}

/** Day headings with time-only rows for the near days; "Week of ..." headings with dated rows for later weeks. */
private fun androidx.compose.foundation.lazy.LazyListScope.buckets(env: RowEnv, buckets: List<DayBucket>) {
    val under = env.underDayHeading()
    for (b in buckets) {
        item(key = "h|${b.weekOf}|${b.date}") { SectionHeading(if (b.weekOf) env.words.weekOf(b.date, env.today) else env.words.heading(b.date, env.today)) }
        rows(if (b.weekOf) env else under, b.tasks, showList = true)
    }
}

/** Screen-reader moves on a list's rows: the two action names and what to say after a move ("Moved to position N of M"). */
private class MoveWords(val up: String, val down: String, val announce: (position: Int, of: Int) -> Unit)

/** A list's tasks with subtasks nested, in manual order. On a writable list each row also offers "Move up" / "Move down" to a screen reader. */
private fun androidx.compose.foundation.lazy.LazyListScope.tree(env: RowEnv, nodes: List<TaskNode>, depth: Int = 0, words: MoveWords? = null) {
    val writable = nodes.firstOrNull()?.let { n -> env.state.lists.firstOrNull { it.id == n.task.listId }?.editable == true } == true
    val group = nodes.map { it.task }
    fun moveTo(t: Task, to: Int) {
        val (after, before) = dev.bookah.tasks.data.TaskOrder.neighbours(group, t.href, to)
        env.actions.reorder(t, after, before)
        words?.announce?.invoke(to + 1, group.size)
    }
    nodes.forEachIndexed { i, n ->
        val moves = if (words == null || !writable) emptyList() else listOfNotNull(
            if (i > 0) words.up to { moveTo(n.task, i - 1) } else null,
            if (i < nodes.size - 1) words.down to { moveTo(n.task, i + 1) } else null,
        )
        item(key = "t|" + n.task.href + "|" + n.task.uid + "|" + depth) { TaskLine(env, n.task, showList = false, indent = (depth * 32).coerceAtMost(96), moves = moves) }
        tree(env, n.children, depth + 1, words)
    }
}

/** The title of the current screen, for the top bar. */
@Composable
fun screenTitle(state: UiState): String = when (val s = state.screen) {
    Screen.Today -> stringResource(R.string.nav_today)
    Screen.Schedule -> stringResource(R.string.nav_schedule)
    Screen.Inbox -> stringResource(R.string.nav_inbox)
    Screen.Upcoming -> stringResource(R.string.nav_upcoming)
    Screen.Someday -> stringResource(R.string.nav_someday)
    Screen.Logbook -> stringResource(R.string.nav_logbook)
    Screen.Waiting -> stringResource(R.string.nav_waiting)
    is Screen.OneList -> state.lists.firstOrNull { it.id == s.listId }?.name.orEmpty()
    is Screen.Tag -> "#" + s.name
}

@Composable
fun MainList(env: RowEnv) {
    val state = env.state
    when (val s = state.screen) {
        Screen.Today -> TodayScreen(env)
        Screen.Schedule -> {
            val view = TaskViews.schedule(state.tasks, env.today)
            var overdueOpen by rememberSaveable { mutableStateOf(false) }
            if (view.isEmpty && !env.hasStrip()) EmptyState(emptyText(state, R.string.empty_schedule)) else LazyColumn(Modifier.fillMaxSize()) {
                // today's calendar events at the top (the schedule starts with today)
                if (env.hasStrip()) item(key = "strip") { DayStrip(env, Modifier.padding(top = 4.dp, bottom = 8.dp)) }
                if (view.isEmpty) item(key = "empty") {
                    Text(emptyText(state, R.string.empty_schedule), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // folded like in Today (count and chevron), so a pile of overdue tasks never buries the day
                overdueGroup(env, view.overdue, overdueOpen) { overdueOpen = !overdueOpen }
                if (view.noDate.isNotEmpty()) {
                    item(key = "h|nodate") { SectionHeading(stringResource(R.string.schedule_no_date)) }
                    rows(env, view.noDate, showList = true)
                }
                buckets(env, TaskViews.bucketDays(view.days, env.today))
            }
        }
        Screen.Inbox -> {
            val tasks = TaskViews.inbox(state.tasks, state.inboxListId)
            if (tasks.isEmpty()) EmptyState(emptyText(state, R.string.empty_inbox)) else LazyColumn(Modifier.fillMaxSize()) { rows(env, tasks, showList = true) }
        }
        Screen.Upcoming -> {
            val groups = TaskViews.upcoming(state.tasks, env.today)
            if (groups.isEmpty()) EmptyState(emptyText(state, R.string.empty_upcoming)) else LazyColumn(Modifier.fillMaxSize()) {
                buckets(env, TaskViews.bucketDays(groups, env.today))
            }
        }
        Screen.Someday -> {
            val tasks = TaskViews.someday(state.tasks)
            if (tasks.isEmpty()) EmptyState(emptyText(state, R.string.empty_someday)) else LazyColumn(Modifier.fillMaxSize()) { rows(env, tasks, showList = true) }
        }
        Screen.Waiting -> {
            val tasks = TaskViews.waiting(state.tasks)
            if (tasks.isEmpty()) EmptyState(emptyText(state, R.string.empty_waiting)) else LazyColumn(Modifier.fillMaxSize()) { rows(env, tasks, showList = true) }
        }
        Screen.Logbook -> {
            val days = TaskViews.logbook(state.tasks, env.zone)
            val earlier = stringResource(R.string.log_earlier)
            if (days.isEmpty()) EmptyState(emptyText(state, R.string.empty_logbook)) else LazyColumn(Modifier.fillMaxSize()) {
                for (d in days) {
                    item(key = "h|${d.date}") { SectionHeading(d.date?.let { env.words.heading(it, env.today) } ?: earlier) }
                    rows(env, d.tasks, showList = true)
                }
            }
        }
        is Screen.OneList -> {
            val nodes = TaskViews.listTree(state.tasks, s.listId)
            when {
                nodes.isEmpty() -> EmptyState(stringResource(R.string.empty_list))
                state.reordering -> ReorderList(env, nodes)
                else -> {
                    val view = androidx.compose.ui.platform.LocalView.current
                    val res = androidx.compose.ui.platform.LocalContext.current.resources
                    val words = MoveWords(stringResource(R.string.order_move_up), stringResource(R.string.order_move_down)) { pos, of ->
                        @Suppress("DEPRECATION") view.announceForAccessibility(res.getString(R.string.order_moved, pos, of))
                    }
                    LazyColumn(Modifier.fillMaxSize()) { tree(env, nodes, words = words) }
                }
            }
        }
        is Screen.Tag -> {
            val tasks = state.tasks.filter { t -> t.isOpen && !t.someday && t.tags.any { it.equals(s.name, true) } }.sortedWith(compareBy({ it.priority.rank }, { it.sortOrder }))
            if (tasks.isEmpty()) EmptyState(stringResource(R.string.empty_tag)) else LazyColumn(Modifier.fillMaxSize()) { rows(env, tasks, showList = true) }
        }
    }
}

/** With no task list at all the first thing to say is why, not "nothing for today". */
@Composable
private fun emptyText(state: UiState, normal: Int): String = if (state.taskLists.isEmpty()) stringResource(R.string.no_lists) else stringResource(normal)

@Composable
private fun TodayScreen(env: RowEnv) {
    val state = env.state
    val view = TaskViews.today(state.tasks, env.today, state.prefs.eveningHour)
    val noTasks = view.overdue.isEmpty() && view.today.isEmpty() && view.evening.isEmpty()
    // an empty day still shows its calendar events (an owner with few tasks still sees the day)
    if (noTasks && !env.hasStrip()) {
        EmptyState(emptyText(state, R.string.empty_today)); return
    }
    var overdueOpen by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "art") { ArtBanner(104.dp, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }          // scrolls away with the list
        item(key = "date") {
            Text(env.words.fullDate(env.today), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "strip") { DayStrip(env, Modifier.padding(bottom = 8.dp)) }
        if (noTasks) item(key = "empty") {
            Text(emptyText(state, R.string.empty_today), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        overdueGroup(env, view.overdue, overdueOpen) { overdueOpen = !overdueOpen }
        rows(env, view.today, showList = true)
        if (view.evening.isNotEmpty()) {
            item(key = "evening-head") { SectionHeading(stringResource(R.string.today_evening)) }
            rows(env, view.evening, showList = true)
        }
    }
}

/** The folded "Overdue - 3" line with its chevron (red count), shared by Today and Schedule. */
@Composable
private fun OverdueHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.overdue_group, count), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
    }
}

/** The targets of the overdue tamer, in the order of the buttons (plan 4.2.2). */
private val tamerTargets = listOf(
    dev.bookah.tasks.data.RescheduleTarget.Today to R.string.word_today,
    dev.bookah.tasks.data.RescheduleTarget.Tomorrow to R.string.word_tomorrow,
    dev.bookah.tasks.data.RescheduleTarget.Weekend to R.string.resched_weekend,
    dev.bookah.tasks.data.RescheduleTarget.NextWeek to R.string.qa_next_week,
    dev.bookah.tasks.data.RescheduleTarget.Someday to R.string.edit_someday,
)

/**
 * The folded Overdue group of Today and Schedule. Opened, it shows "Move all to" buttons (Today, Tomorrow, Weekend, Next week, Someday;
 * each asks first) and the overdue tasks, each with a small Reschedule button that opens the one-task sheet.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.overdueGroup(env: RowEnv, overdue: List<Task>, open: Boolean, onToggle: () -> Unit) {
    if (overdue.isEmpty()) return
    item(key = "h|overdue") { OverdueHeader(overdue.size, open, onToggle) }
    if (!open) return
    val writable = env.state.lists.filter { it.editable }.map { it.id }.toSet()
    // repeating tasks are moved one by one (ruling): the buttons appear when at least one other task can move
    if (overdue.any { it.listId in writable && !it.repeats }) item(key = "overdue-all") { MoveAllRow(env, overdue) }
    items(overdue.size, key = { "o|" + overdue[it].href + "|" + overdue[it].uid }) { TaskLine(env, overdue[it], showList = true, moveButton = overdue[it].listId in writable) }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MoveAllRow(env: RowEnv, overdue: List<Task>) {
    var asking by rememberSaveable { mutableStateOf(-1) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
        Text(stringResource(R.string.overdue_move_all), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            tamerTargets.forEachIndexed { i, (_, label) ->
                androidx.compose.material3.AssistChip({ asking = i }, { Text(stringResource(label)) })
            }
        }
    }
    if (asking in tamerTargets.indices) {
        val (target, label) = tamerTargets[asking]
        val writable = env.state.lists.filter { it.editable }.map { it.id }.toSet()
        val readOnly = overdue.count { it.listId !in writable }
        val repeating = overdue.count { it.listId in writable && it.repeats }
        val movable = overdue.size - readOnly - repeating
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { asking = -1 },
            title = { Text(androidx.compose.ui.res.pluralStringResource(R.plurals.overdue_confirm_title, movable, movable)) },
            text = {
                Column {
                    Text(stringResource(R.string.overdue_confirm_to, stringResource(label)))
                    if (readOnly > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.overdue_confirm_readonly, readOnly, readOnly), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (repeating > 0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.overdue_confirm_repeating, repeating, repeating), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { androidx.compose.material3.TextButton({ env.onMoveAll(overdue, target); asking = -1 }) { Text(stringResource(R.string.overdue_confirm_yes)) } },
            dismissButton = { androidx.compose.material3.TextButton({ asking = -1 }) { Text(stringResource(R.string.edit_cancel)) } },
        )
    }
}
