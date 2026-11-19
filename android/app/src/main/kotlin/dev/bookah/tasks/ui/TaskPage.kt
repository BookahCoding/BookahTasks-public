package dev.bookah.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.bookah.tasks.R
import dev.bookah.tasks.data.Priority
import dev.bookah.tasks.data.Task
import dev.bookah.tasks.data.TaskWhen
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The task page as the editor (design spec section 7). Title and notes save when you leave the field or close the page; the other rows open a
 * small picker. Phase 3: the repeat rule ([RepeatDialog]) and the list of reminders ([RemindersSection]) are edited here too.
 */
@Composable
fun TaskPage(task: Task, env: RowEnv, onClose: () -> Unit) {
    val words = env.words
    val a = env.actions
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val uri = LocalUriHandler.current

    var title by remember(task.href, task.uid) { mutableStateOf(task.title) }
    var notes by remember(task.href, task.uid) { mutableStateOf(task.notes.orEmpty()) }
    var deleted by remember(task.href, task.uid) { mutableStateOf(false) }
    // only fields the owner actually typed in are written: a newer title or notes that arrived from Nextcloud while the page was open is never overwritten by the old text
    var titleDirty by remember(task.href, task.uid) { mutableStateOf(false) }
    var notesDirty by remember(task.href, task.uid) { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(task.title) { if (!titleDirty) title = task.title }
    androidx.compose.runtime.LaunchedEffect(task.notes) { if (!notesDirty) notes = task.notes.orEmpty() }
    val latestTask by rememberUpdatedState(task)
    val latestTitle by rememberUpdatedState(title)
    val latestNotes by rememberUpdatedState(notes)
    val latestDeleted by rememberUpdatedState(deleted)
    val latestTitleDirty by rememberUpdatedState(titleDirty)
    val latestNotesDirty by rememberUpdatedState(notesDirty)
    fun commit() {
        if (latestDeleted) return
        val t = latestTask
        if (latestTitleDirty && latestTitle.isNotBlank() && latestTitle.trim() != t.title.trim()) a.setTitle(t, latestTitle.trim())
        if (latestNotesDirty && latestNotes.trim() != t.notes.orEmpty().trim()) a.setNotes(t, latestNotes.ifBlank { null })
        titleDirty = false; notesDirty = false
    }
    DisposableEffect(task.href, task.uid) { onDispose { commit() } }

    var dialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Dialog?>(null) }          // an open editor survives a rotation
    val editable = env.state.lists.firstOrNull { it.id == task.listId }?.editable == true

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 96.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Filled.Close, stringResource(R.string.nav_close)) }
                Spacer(Modifier.weight(1f))
                if (task.isOpen && env.state.lists.firstOrNull { it.id == task.listId }?.editable == true) TextButton({ commit(); env.onReschedule(task) }) { Text(stringResource(R.string.swipe_reschedule)) }
                TextButton({ commit(); a.toggle(task) }) { Text(stringResource(if (task.isDone) R.string.edit_reopen else R.string.edit_mark_done)) }
            }
            BasicTextField(
                title, { title = it.replace("\n", " "); titleDirty = true },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).onFocusChanged { if (!it.isFocused) commit() },
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface, textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner -> if (title.isEmpty()) Text(stringResource(R.string.edit_title_hint), style = MaterialTheme.typography.headlineMedium, color = muted); inner() },
            )
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                EditRow(stringResource(R.string.label_start), task.start?.let { words.whenText(it, env.today) }, muted = task.start == null) { dialog = Dialog.Start }
                EditRow(stringResource(R.string.label_deadline), task.due?.let { words.whenText(it, env.today) }, muted = task.due == null) { dialog = Dialog.Due }
                EditRow(stringResource(R.string.label_priority), words.priority(task.priority), muted = task.priority == Priority.None) { dialog = Dialog.Priority }
                EditRow(stringResource(R.string.label_tags), task.tags.takeIf { it.isNotEmpty() }?.joinToString("  ") { "#$it" }, muted = task.tags.isEmpty()) { dialog = Dialog.Tags }
                EditRow(stringResource(R.string.label_list), env.listName(task).orEmpty(), dot = env.color(task)) { dialog = Dialog.Move }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.edit_someday), Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium, color = muted)
                    Spacer(Modifier.weight(1f))
                    Switch(task.someday, { a.setSomeday(task, it) })
                }
                // Phase 5: waiting on somebody until a check-back date (not for a repeating task: its start would move the series)
                if (editable && task.isOpen && !task.isOccurrenceCopy && (!task.repeats || task.waiting)) {
                    val who = task.waitingFor?.let { stringResource(R.string.waiting_for_person, it) } ?: stringResource(R.string.mark_waiting)
                    val value = if (!task.waiting) null else task.start?.let { stringResource(R.string.waiting_until, who, words.day(it.date, env.today)) } ?: who
                    EditRow(stringResource(R.string.label_waiting), value ?: stringResource(R.string.waiting_none), muted = !task.waiting) { dialog = Dialog.Waiting }
                }
                // Phase 3: the repeat rule is edited here; a repeating task without dates says it cannot repeat yet
                val repeatText = task.repeat?.let { r ->
                    words.repeat(r, env.today) + if (task.start == null && task.due == null) "\n" + stringResource(R.string.repeat_no_date) else ""
                } ?: stringResource(R.string.repeat_none)
                // a finished copy of one occurrence is never edited (it shares its address with the series)
                EditRow(stringResource(R.string.label_repeat), repeatText, muted = task.repeat == null) { if (editable && !task.isOccurrenceCopy) dialog = Dialog.Repeat }
                task.completedAtMs?.let { ms ->
                    InfoRow(stringResource(R.string.label_finished), words.heading(Instant.ofEpochMilli(ms).atZone(env.zone).toLocalDate(), env.today))
                }
                task.url?.let { u ->
                    val web = u.startsWith("https://", true) || u.startsWith("http://", true)
                    InfoRow(stringResource(R.string.label_link), u, onClick = if (web) ({ uri.openUri(u) }) else null)
                }
            }
            RemindersSection(task, words, env.zone, editable && !task.isOccurrenceCopy, onSet = { a.setReminders(task, it) }, onDefaults = { a.useDefaultReminders(task) })
            SectionHeading(stringResource(R.string.label_notes))
            BasicTextField(
                notes, { notes = it; notesDirty = true },
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).onFocusChanged { if (!it.isFocused) commit() },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner -> if (notes.isEmpty()) Text(stringResource(R.string.edit_notes_hint), style = MaterialTheme.typography.bodyLarge, color = muted); inner() },
            )
            val kids = env.state.tasks.filter { it.parentUid == task.uid && !it.isOccurrenceCopy }.sortedWith(dev.bookah.tasks.data.TaskOrder.comparator)          // manual order, as in the list
            SectionHeading(stringResource(R.string.label_subtasks))
            kids.forEach { k ->
                TaskRow(k, env.color(k), null, words, env.today, env.zone, env.progress(k), pending = k.href in env.state.pending, onToggle = { a.toggle(k) }) { commit(); env.onOpen(k) }
            }
            var sub by remember(task.href, task.uid) { mutableStateOf("") }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    sub, { sub = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(stringResource(R.string.edit_add_subtask)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )
                TextButton({ a.addSubtask(task, sub); sub = "" }, Modifier.padding(start = 8.dp), enabled = sub.isNotBlank()) { Text(stringResource(R.string.add_button)) }
            }
            OutlinedButton(
                { deleted = true; a.delete(task) }, Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
            ) { Text(stringResource(R.string.edit_delete), color = MaterialTheme.colorScheme.error) }
        }
    }

    when (dialog) {
        Dialog.Start -> WhenDialog(task.start, { a.setDates(task, it, task.due) }, { a.setDates(task, null, task.due) }) { dialog = null }
        Dialog.Due -> WhenDialog(task.due, { a.setDates(task, task.start, it) }, { a.setDates(task, task.start, null) }) { dialog = null }
        Dialog.Priority -> PriorityDialog(task.priority, words, { a.setPriority(task, it) }) { dialog = null }
        Dialog.Tags -> TagsDialog(task.tags, { a.setTags(task, it) }) { dialog = null }
        Dialog.Move -> MoveDialog(env, task, { commit(); a.move(task, it) }) { dialog = null }
        Dialog.Repeat -> RepeatDialog(task, words, env.today, env.zone, { a.setRepeat(task, it) }) { dialog = null }
        Dialog.Waiting -> WaitingDialog(task, env.today, words, onSave = { p, d -> commit(); a.setWaiting(task, p, d) }, onStop = { commit(); a.endWaiting(task) }) { dialog = null }
        null -> {}
    }
}

private enum class Dialog { Start, Due, Priority, Tags, Move, Repeat, Waiting }

/**
 * Waiting for (plan 4.2.7): who (optional) and when to check back (quick dates or any date). Saving puts the task on waiting: it leaves
 * Today until that date. A deadline before the date moves up to it (said first). "Stop waiting" ends it.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WaitingDialog(task: Task, today: LocalDate, words: dev.bookah.tasks.Words, onSave: (String?, LocalDate) -> Unit, onStop: () -> Unit, onDismiss: () -> Unit) {
    var person by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(task.waitingFor.orEmpty()) }
    var day by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf((task.start?.date?.takeIf { task.waiting && it > today } ?: today.plusDays(3)).toEpochDay()) }
    var pick by remember { mutableStateOf(false) }
    val chosen = LocalDate.ofEpochDay(day)
    val quick = listOf(
        today.plusDays(1) to R.string.word_tomorrow, today.plusDays(3) to R.string.waiting_in_3,
        today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)) to R.string.qa_next_week, today.plusDays(14) to R.string.waiting_in_14,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.waiting_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    person, { person = it.replace("\n", " ").take(100) }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(R.string.waiting_person_hint)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                )
                Text(stringResource(R.string.waiting_check_back), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.forEach { (d, label) -> androidx.compose.material3.FilterChip(chosen == d, { day = d.toEpochDay() }, { Text(stringResource(label)) }) }
                    val custom = quick.none { it.first == chosen }
                    androidx.compose.material3.FilterChip(custom, { pick = true }, { Text(if (custom) words.day(chosen, today) else stringResource(R.string.qa_pick)) })
                }
                if (dev.bookah.tasks.data.TaskViews.deadlineMoves(task, chosen)) Text(stringResource(R.string.waiting_deadline_moves), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton({ onSave(person.trim().ifBlank { null }, chosen); onDismiss() }) { Text(stringResource(R.string.repeat_save)) } },
        dismissButton = {
            Row {
                if (task.waiting) TextButton({ onStop(); onDismiss() }) { Text(stringResource(R.string.waiting_stop)) }
                TextButton(onDismiss) { Text(stringResource(R.string.edit_cancel)) }
            }
        },
    )
    if (pick) QuickDateDialog(chosen, { day = it.toEpochDay(); pick = false }) { pick = false }
}

@Composable
private fun EditRow(label: String, value: String?, dot: Color? = null, muted: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (dot != null) Row(Modifier.padding(top = 6.dp, end = 8.dp).size(10.dp).background(dot, CircleShape)) {}
        Text(
            value ?: stringResource(R.string.edit_none), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, Modifier.width(112.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

/** Two steps: the day, then the time (or "No time"). The device's own time zone is used; the server gets UTC. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhenDialog(initial: TaskWhen?, onSet: (TaskWhen) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf<LocalDate?>(null) }
    val picked = date
    if (picked == null) {
        // the picker only accepts years 1900 to 2100: an odd date written by another app must not crash it
        val start = (initial?.date ?: LocalDate.now()).let { if (it.year in 1901..2099) it else LocalDate.now() }
        val ds = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton({ ds.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } }, enabled = ds.selectedDateMillis != null) {
                    Text(stringResource(R.string.edit_set))
                }
            },
            dismissButton = {
                Row {
                    if (initial != null) TextButton({ onClear(); onDismiss() }) { Text(stringResource(R.string.edit_clear)) }
                    TextButton(onDismiss) { Text(stringResource(R.string.edit_cancel)) }
                }
            },
        ) { DatePicker(ds) }
    } else {
        val ts = rememberTimePickerState(initialHour = initial?.time?.hour ?: 9, initialMinute = initial?.time?.minute ?: 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.edit_pick_time)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(ts) } },
            confirmButton = { TextButton({ onSet(TaskWhen(picked, LocalTime.of(ts.hour, ts.minute))); onDismiss() }) { Text(stringResource(R.string.edit_ok)) } },
            dismissButton = { TextButton({ onSet(TaskWhen(picked, null)); onDismiss() }) { Text(stringResource(R.string.edit_no_time)) } },
        )
    }
}

@Composable
private fun PriorityDialog(current: Priority, words: dev.bookah.tasks.Words, onPick: (Priority) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_priority_dialog)) },
        text = {
            Column {
                listOf(Priority.None, Priority.Low, Priority.Medium, Priority.High).forEach { p ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(p); onDismiss() }.heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(current == p, { onPick(p); onDismiss() })
                        Text(words.priority(p) ?: stringResource(R.string.label_priority_none), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}

@Composable
private fun TagsDialog(current: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current.joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_tags_dialog)) },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true) },
        confirmButton = {
            TextButton({ onSave(text.split(',').map { it.trim().removePrefix("#").trim() }.filter { it.isNotEmpty() }); onDismiss() }) { Text(stringResource(R.string.edit_ok)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}

@Composable
private fun MoveDialog(env: RowEnv, task: Task, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val targets = env.state.taskLists.filter { it.editable && it.id != task.listId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_list_dialog)) },
        text = {
            Column {
                targets.forEach { l ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(l.id); onDismiss() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.padding(end = 12.dp).size(12.dp).background(listColorOf(l.colorHex), CircleShape)) {}
                        Text(l.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}
