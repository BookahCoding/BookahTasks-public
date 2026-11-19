package dev.bookah.tasks.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import dev.bookah.tasks.NewTask
import dev.bookah.tasks.data.Hit
import dev.bookah.tasks.data.HitKind
import dev.bookah.tasks.data.Parsed
import dev.bookah.tasks.data.Priority
import dev.bookah.tasks.data.QuickParser
import dev.bookah.tasks.data.TaskList
import dev.bookah.tasks.data.TaskWhen
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import dev.bookah.tasks.ConflictInfo
import dev.bookah.tasks.R
import dev.bookah.tasks.UndoBar

/**
 * The quick-add sheet. Type a line such as "Call mom tomorrow at 6pm !1 #family @home" and the words that mean something are tinted and
 * their chips light up (nothing is removed from the text while typing; the title is cleaned when the task is saved). Tap a lit chip to switch
 * that part off (its words then stay in the title). Or just tap the chips: When (Today, Tomorrow, Next week, any date, a time), an
 * optional Deadline, priority and list. The keyboard's Done key adds the task and keeps the sheet open for the next one (date, priority and
 * list stay, and a line confirms what was added); the Add button adds and closes.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    lists: List<TaskList>, defaultListId: Long?, today: LocalDate, eveningHour: Int, onAdd: (NewTask, Boolean) -> Unit, onDismiss: () -> Unit,
    /** A share's text (Phase 5): the title fills the field; the link and the notes go with the first task added. */
    prefill: dev.bookah.tasks.QuickPrefill? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable { mutableStateOf(prefill?.title.orEmpty()) }
    var sharedUrl by rememberSaveable { mutableStateOf(prefill?.url) }
    var sharedNotes by rememberSaveable { mutableStateOf(prefill?.notes) }
    var manualStart by remember { mutableStateOf<TaskWhen?>(null) }
    var manualDue by remember { mutableStateOf<TaskWhen?>(null) }
    var manualPriority by remember { mutableStateOf<Priority?>(null) }
    var manualList by remember { mutableStateOf<Long?>(null) }
    var dismissed by remember { mutableStateOf(emptySet<HitKind>()) }
    var showDue by remember { mutableStateOf(false) }
    var chooseList by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val words = remember(res) { dev.bookah.tasks.Words(res) }

    // the parser runs on every keystroke: whatever happens inside it, typing must never crash the app (plain text is the safe answer)
    val parsed = remember(text, today, eveningHour, lists) {
        try { QuickParser.parse(text, today, eveningHour, lists.map { it.name }) }
        catch (_: Exception) { Parsed(text, null, null, null, emptyList(), null, emptyList()) }
    }
    val present = parsed.hits.map { it.kind }.toSet()
    val off = dismissed intersect present                                    // a switched-off kind stays off only while its words are still there
    LaunchedEffect(present) { dismissed = dismissed intersect present }       // and is forgotten once its words are gone, so they can be recognised again
    // typed words win while they are lit; a tapped chip (or a value carried over from the last add) applies when the words are absent or switched off
    fun lit(k: HitKind) = k in present && k !in dismissed
    // "every monday" typed: the task repeats, and without a typed or chosen date it starts on the first chosen day (a repeat needs a date)
    val repeat = parsed.repeat?.takeIf { lit(HitKind.Repeat) }
    fun effStart(): TaskWhen? = if (lit(HitKind.Start)) parsed.start else manualStart ?: repeat?.let { TaskWhen(dev.bookah.tasks.data.RepeatRules.firstDate(it, today), null) }
    fun effDue(): TaskWhen? =
        if (lit(HitKind.Due)) parsed.due?.let { d -> if (parsed.dueDateExplicit || d.time == null) d else TaskWhen(effStart()?.date ?: today, d.time) } else manualDue
    val priority = if (lit(HitKind.Priority)) parsed.priority ?: Priority.None else manualPriority ?: Priority.None
    val parsedList = parsed.listName?.takeIf { lit(HitKind.List) }?.let { n -> lists.firstOrNull { it.name == n } }
    val chosenList = parsedList ?: lists.firstOrNull { it.id == manualList } ?: lists.firstOrNull { it.id == defaultListId } ?: lists.firstOrNull()
    val tags = parsed.tags.takeIf { HitKind.Tag !in off }.orEmpty()
    val tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)
    val litHits = parsed.hits.filter { it.kind !in off }

    fun whenText(w: TaskWhen?) = w?.let { DateTimeFormatter.ofPattern(if (it.time != null) "EEE d MMM, HH:mm" else "EEE d MMM").format(if (it.time != null) it.date.atTime(it.time) else it.date.atStartOfDay()) }

    fun submit(keepOpen: Boolean) {
        val title = parsed.title(off)
        if (text.isBlank() || title.isBlank()) return
        val s = effStart(); val d = effDue()
        onAdd(NewTask(title, s, d, priority, chosenList?.id, tags, repeat, url = sharedUrl, notes = sharedNotes), keepOpen)
        sharedUrl = null; sharedNotes = null                                  // the shared link belongs to the first task only
        added = listOfNotNull(title, whenText(s) ?: whenText(d)).joinToString(" · ")
        // what was chosen stays for the next task
        manualStart = s; manualDue = d; manualPriority = priority; manualList = chosenList?.id
        text = ""; dismissed = emptySet()                                   // cleared at once, so a second tap on Add cannot add the task again
        if (keepOpen) { try { focus.requestFocus() } catch (_: IllegalStateException) {} }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                text, { text = it.replace("\n", " ") }, Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text(stringResource(R.string.add_title_hint)) }, singleLine = true,
                visualTransformation = HitTint(litHits, tint),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit(keepOpen = true) }),
            )
            sharedUrl?.let { u ->
                Text(stringResource(R.string.qa_link, u), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            added?.let { line ->
                Text(stringResource(R.string.qa_added, line), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                LaunchedEffect(line) { kotlinx.coroutines.delay(3500); added = null }
            }
            WhenRow(stringResource(R.string.qa_when), effStart(), today) { v ->
                manualStart = v
                if (HitKind.Start in present) dismissed = dismissed + HitKind.Start          // the typed words go back into the title
            }
            if (showDue || effDue() != null) {
                WhenRow(stringResource(R.string.qa_deadline), effDue(), today) { v ->
                    manualDue = v
                    if (HitKind.Due in present) dismissed = dismissed + HitKind.Due
                }
            }
            // wraps onto a second line (large text) instead of hiding a chip off the edge
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!(showDue || effDue() != null)) AssistChip({ showDue = true }, { Text(stringResource(R.string.qa_add_deadline)) })
                // one chip: tap to go through none, high, medium, low
                val order = listOf(Priority.None, Priority.High, Priority.Medium, Priority.Low)
                FilterChip(
                    priority != Priority.None,
                    { val next = order[(order.indexOf(priority) + 1) % order.size]; manualPriority = next; if (HitKind.Priority in present) dismissed = dismissed + HitKind.Priority },
                    { Text(when (priority) { Priority.High -> stringResource(R.string.priority_high); Priority.Medium -> stringResource(R.string.priority_medium); Priority.Low -> stringResource(R.string.priority_low); Priority.None -> stringResource(R.string.label_priority) }) },
                )
                if (chosenList != null) AssistChip({ if (lists.size > 1) chooseList = true }, { Text(chosenList.name, maxLines = 1) })
                if (tags.isNotEmpty()) FilterChip(true, { dismissed = dismissed + HitKind.Tag }, { Text(tags.joinToString(" ") { "#$it" }) })
                if (repeat != null) FilterChip(true, { dismissed = dismissed + HitKind.Repeat }, { Text(stringResource(R.string.repeat_chip, words.repeat(repeat, today)), maxLines = 1) })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Button({ submit(keepOpen = false) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.add_button)) }
            }
        }
    }
    if (chooseList) AlertDialog(
        onDismissRequest = { chooseList = false },
        title = { Text(stringResource(R.string.settings_default_list)) },
        text = {
            Column {
                lists.forEach { l ->
                    Row(Modifier.fillMaxWidth().clickable { manualList = l.id; if (HitKind.List in present) dismissed = dismissed + HitKind.List; chooseList = false }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text(l.name) }
                }
            }
        },
        confirmButton = { TextButton({ chooseList = false }) { Text(stringResource(R.string.edit_cancel)) } },
    )
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: IllegalStateException) {} }
}

/** Tints the recognised words in the text field without changing the text. */
private class HitTint(private val hits: List<Hit>, private val color: androidx.compose.ui.graphics.Color) : androidx.compose.ui.text.input.VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): androidx.compose.ui.text.input.TransformedText {
        val styled = androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            for (h in hits) if (h.from < text.length) addStyle(androidx.compose.ui.text.SpanStyle(background = color), h.from, h.to.coerceAtMost(text.length))
        }
        return androidx.compose.ui.text.input.TransformedText(styled, androidx.compose.ui.text.input.OffsetMapping.Identity)
    }
}

/** One date-and-time line of chips on a single scrolling row: Today, Tomorrow, Next week, any date, and a time. Tapping the chosen day again clears it. */
@Composable
private fun WhenRow(label: String, value: TaskWhen?, today: LocalDate, onChange: (TaskWhen?) -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val tomorrow = today.plusDays(1)
    val nextMonday = today.plusDays((8 - today.dayOfWeek.value).toLong())          // the coming Monday
    // on a Sunday the coming Monday is tomorrow: one chip is enough
    val presets = listOfNotNull(today to R.string.word_today, tomorrow to R.string.word_tomorrow, (nextMonday to R.string.qa_next_week).takeIf { nextMonday != tomorrow })
    fun setDate(d: LocalDate?) = onChange(if (d == null) null else TaskWhen(d, value?.time))
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // the time chip sits right after the first days so it is always in view
            presets.take(2).forEach { (d, name) -> FilterChip(value?.date == d, { setDate(if (value?.date == d) null else d) }, { Text(stringResource(name)) }) }
            FilterChip(value?.time != null, { pickTime = true }, { Text(value?.time?.let { DateTimeFormatter.ofPattern("HH:mm").format(it) } ?: stringResource(R.string.qa_time)) })
            presets.drop(2).forEach { (d, name) -> FilterChip(value?.date == d, { setDate(if (value?.date == d) null else d) }, { Text(stringResource(name)) }) }
            val custom = value?.date?.takeIf { d -> presets.none { it.first == d } }
            FilterChip(custom != null, { pickDate = true }, { Text(custom?.let { DateTimeFormatter.ofPattern("EEE d MMM").format(it) } ?: stringResource(R.string.qa_pick)) })
        }
    }
    if (pickDate) QuickDateDialog(value?.date ?: today, { setDate(it); pickDate = false }) { pickDate = false }
    if (pickTime) QuickTimeDialog(
        value?.time,
        { t ->
            // a time picked with no date is for today, or for tomorrow when that time has already gone by
            val d = value?.date ?: if (t.isBefore(LocalTime.now())) tomorrow else today
            onChange(TaskWhen(d, t)); pickTime = false
        },
        { onChange(value?.copy(time = null)); pickTime = false },
    ) { pickTime = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickDateDialog(initial: LocalDate, onSet: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val start = if (initial.year in 1901..2099) initial else LocalDate.now()
    val ds = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton({ ds.selectedDateMillis?.let { onSet(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } }, enabled = ds.selectedDateMillis != null) { Text(stringResource(R.string.edit_set)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    ) { DatePicker(ds) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickTimeDialog(initial: LocalTime?, onSet: (LocalTime) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val ts = rememberTimePickerState(initialHour = initial?.hour ?: 9, initialMinute = initial?.minute ?: 0, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_pick_time)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(ts) } },
        confirmButton = { TextButton({ onSet(LocalTime.of(ts.hour, ts.minute)) }) { Text(stringResource(R.string.edit_ok)) } },
        dismissButton = { TextButton(onClear) { Text(stringResource(R.string.edit_no_time)) } },
    )
}

/** The swipe-left sheet (also reachable from the task page and by screen-reader actions): where should this task go? */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescheduleSheet(task: dev.bookah.tasks.data.Task, today: LocalDate, onPick: (dev.bookah.tasks.data.RescheduleTarget) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pickDate by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(task.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, modifier = Modifier.padding(bottom = 8.dp))
            listOf(
                dev.bookah.tasks.data.RescheduleTarget.Today to R.string.word_today, dev.bookah.tasks.data.RescheduleTarget.Evening to R.string.resched_evening,
                dev.bookah.tasks.data.RescheduleTarget.Tomorrow to R.string.word_tomorrow, dev.bookah.tasks.data.RescheduleTarget.Weekend to R.string.resched_weekend,
                dev.bookah.tasks.data.RescheduleTarget.NextWeek to R.string.qa_next_week,
                dev.bookah.tasks.data.RescheduleTarget.Someday to R.string.edit_someday,
            ).forEach { (target, label) ->
                Text(stringResource(label), Modifier.fillMaxWidth().clickable { onPick(target) }.padding(vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
            }
            Text(stringResource(R.string.qa_pick), Modifier.fillMaxWidth().clickable { pickDate = true }.padding(vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (pickDate) QuickDateDialog(today, { onPick(dev.bookah.tasks.data.RescheduleTarget.On(it)) }) { pickDate = false }
}

/** The bar after a tick, delete or move: one tap undoes it. It goes away by itself after a few seconds. */
@Composable
fun UndoBarView(bar: UndoBar, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(bar.message.resolve(), Modifier.weight(1f).padding(vertical = 14.dp))
            TextButton(onUndo) { Text(stringResource(R.string.undo_button), color = MaterialTheme.colorScheme.inversePrimary) }
        }
    }
}

/** A change met a changed server copy: both versions are named and the owner chooses. Nothing is overwritten until then. */
@Composable
fun ConflictDialog(info: ConflictInfo, onMine: () -> Unit, onServer: () -> Unit, onBoth: () -> Unit) {
    val mine = if (info.isDelete) stringResource(R.string.conflict_mine_delete) else info.mine
    val theirs = info.server ?: stringResource(R.string.conflict_gone)
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.conflict_title)) },
        text = { Text(stringResource(R.string.conflict_text, mine, theirs)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onMine) { Text(stringResource(R.string.conflict_keep_mine)) }
                TextButton(onServer) { Text(stringResource(R.string.conflict_keep_server)) }
                if (!info.isDelete && info.server != null) TextButton(onBoth) { Text(stringResource(R.string.conflict_keep_both)) }
            }
        },
    )
}

@Composable
fun SignOutDialog(onSignOut: () -> Unit, onStay: () -> Unit) {
    AlertDialog(
        onDismissRequest = onStay,
        title = { Text(stringResource(R.string.signout_title)) },
        text = { Text(stringResource(R.string.signout_text)) },
        confirmButton = { TextButton(onStay) { Text(stringResource(R.string.signout_wait)) } },
        dismissButton = { TextButton(onSignOut) { Text(stringResource(R.string.signout_anyway)) } },
    )
}

@Composable
fun MessageDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, text = { Text(text) }, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.edit_ok)) } })
}
