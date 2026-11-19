package dev.bookah.tasks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bookah.tasks.R
import dev.bookah.tasks.Words
import dev.bookah.tasks.data.ReminderChoice
import dev.bookah.tasks.data.ReminderSet
import dev.bookah.tasks.data.ReminderSource
import dev.bookah.tasks.data.RepeatEnd
import dev.bookah.tasks.data.RepeatFreq
import dev.bookah.tasks.data.RepeatRules
import dev.bookah.tasks.data.RepeatSpec
import dev.bookah.tasks.data.Task
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The repeat editor (docs/phase-3-design.md section 1): none, daily, weekly on chosen days, monthly (same day, or the nth weekday), yearly;
 * every N; ends never, on a date, or after N times; and the second kind, "repeat after I complete it". A live line says the rule in words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RepeatDialog(task: Task, words: Words, today: LocalDate, zone: ZoneId, onSave: (RepeatSpec?) -> Unit, onDismiss: () -> Unit) {
    val stored = task.repeat?.let { RepeatRules.parse(it.raw, it.fromCompletion, zone) }
    val custom = task.repeat != null && stored == null
    // saveable: a rotation or a trip to another app keeps what the owner was choosing (plain values only: enum names, numbers, lists)
    var freqName by rememberSaveable { mutableStateOf(stored?.freq?.name) }
    val freq = freqName?.let { RepeatFreq.valueOf(it) }
    fun setFreq(v: RepeatFreq?) { freqName = v?.name }
    var every by rememberSaveable { mutableStateOf(stored?.interval ?: 1) }
    var dayNums by rememberSaveable { mutableStateOf(stored?.weekdays?.map { it.value }?.sorted() ?: emptyList()) }
    val days: Set<DayOfWeek> = dayNums.map { DayOfWeek.of(it) }.toSet()
    fun setDays(v: Set<DayOfWeek>) { dayNums = v.map { it.value }.sorted() }
    var useNth by rememberSaveable { mutableStateOf(stored?.monthlyNth != null) }
    val storedEnd = stored?.end ?: RepeatEnd.Never
    var endKind by rememberSaveable { mutableStateOf(when (storedEnd) { RepeatEnd.Never -> 0; is RepeatEnd.Until -> 1; is RepeatEnd.Count -> 2 }) }
    var untilDay by rememberSaveable { mutableStateOf((storedEnd as? RepeatEnd.Until)?.day?.toEpochDay() ?: 0L) }
    var countN by rememberSaveable { mutableStateOf((storedEnd as? RepeatEnd.Count)?.times ?: 10) }
    val end: RepeatEnd = when (endKind) { 1 -> RepeatEnd.Until(LocalDate.ofEpochDay(untilDay)); 2 -> RepeatEnd.Count(countN); else -> RepeatEnd.Never }
    fun setEnd(v: RepeatEnd) { when (v) { RepeatEnd.Never -> endKind = 0; is RepeatEnd.Until -> { endKind = 1; untilDay = v.day.toEpochDay() }; is RepeatEnd.Count -> { endKind = 2; countN = v.times } } }
    var fromDone by rememberSaveable { mutableStateOf(stored?.fromCompletion ?: false) }
    var pickUntil by rememberSaveable { mutableStateOf(false) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val dated = task.start ?: task.due
    fun spec(): RepeatSpec? {
        val f = freq ?: return null
        val draft = RepeatSpec(f, every, if (f == RepeatFreq.Weekly) days else emptySet(), end = end, fromCompletion = fromDone)
        val base = dated?.date ?: RepeatRules.firstDate(draft, today)
        return if (f == RepeatFreq.Monthly && useNth) draft.copy(monthlyNth = RepeatRules.nthOf(base), monthlyWeekday = base.dayOfWeek) else draft
    }
    val s = spec()
    val firstDay = dated?.date ?: s?.let { RepeatRules.firstDate(it, today) } ?: today
    val ok = (s == null || s.problem(firstDay) == null) && (touched || !custom)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.label_repeat)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (custom) Text(stringResource(R.string.repeat_custom_kept), style = MaterialTheme.typography.bodySmall, color = muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to R.string.repeat_none, RepeatFreq.Daily to R.string.repeat_daily, RepeatFreq.Weekly to R.string.repeat_weekly,
                        RepeatFreq.Monthly to R.string.repeat_monthly, RepeatFreq.Yearly to R.string.repeat_yearly).forEach { (f, label) ->
                        // a custom rule from another app selects no chip until the owner picks one (Save stays off until then)
                        FilterChip(freq == f && (touched || !custom), {
                            setFreq(f); touched = true
                            if (f == RepeatFreq.Weekly && days.isEmpty()) setDays(setOf((dated?.date ?: today).dayOfWeek))
                        }, { Text(stringResource(label)) })
                    }
                }
                if (freq != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.repeat_every_label), Modifier.width(72.dp), color = muted)
                        Stepper(every, 1..99, stringResource(R.string.repeat_every_less), stringResource(R.string.repeat_every_more)) { every = it }
                    }
                    if (freq == RepeatFreq.Weekly) {
                        Text(stringResource(R.string.repeat_on_days), color = muted)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DayOfWeek.entries.forEach { d ->
                                FilterChip(d in days, { setDays(if (d in days) days - d else days + d) }, { Text(words.weekdayShort(d)) })
                            }
                        }
                    }
                    if (freq == RepeatFreq.Monthly) {
                        val base = dated?.date ?: today
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!useNth, { useNth = false }, { Text(stringResource(R.string.repeat_month_day, base.dayOfMonth)) })
                            FilterChip(useNth, { useNth = true }, { Text(words.nth(RepeatRules.nthOf(base), base.dayOfWeek) + " " + base.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())) })
                        }
                        // the 29th to the 31st: some months have no such day (skipped by the fixed schedule; the second kind takes the month's last day)
                        if (!useNth && RepeatRules.monthlyDaySkips(base)) Text(stringResource(R.string.repeat_month_skip_hint, base.dayOfMonth), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Text(stringResource(R.string.repeat_ends), color = muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(end == RepeatEnd.Never, { setEnd(RepeatEnd.Never) }, { Text(stringResource(R.string.repeat_end_never)) })
                        FilterChip(end is RepeatEnd.Until, { pickUntil = true }, {
                            Text((end as? RepeatEnd.Until)?.let { words.day(it.day, today) } ?: stringResource(R.string.repeat_end_on))
                        })
                        FilterChip(end is RepeatEnd.Count, { if (end !is RepeatEnd.Count) setEnd(RepeatEnd.Count(10)) }, { Text(stringResource(R.string.repeat_end_after)) })
                    }
                    (end as? RepeatEnd.Count)?.let { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Stepper(c.times, 1..999, stringResource(R.string.repeat_count_less), stringResource(R.string.repeat_count_more)) { setEnd(RepeatEnd.Count(it)) }
                            Text(pluralStringResource(R.plurals.repeat_count_n, c.times, c.times), Modifier.padding(start = 8.dp), color = muted)
                        }
                    }
                    // one control for screen readers: the whole row is the switch
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = fromDone, role = Role.Switch, onValueChange = { fromDone = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.repeat_from_done_switch), Modifier.weight(1f))
                        Switch(fromDone, onCheckedChange = null)
                    }
                    if (fromDone) Text(stringResource(R.string.repeat_from_done_hint), style = MaterialTheme.typography.bodySmall, color = muted)
                    s?.let { Text(words.repeat(it, today), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
                    if (dated == null) Text(stringResource(R.string.repeat_needs_date, words.day(firstDay, today)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton({ onSave(s); onDismiss() }, enabled = ok) { Text(stringResource(R.string.repeat_save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
    if (pickUntil) QuickDateDialog((end as? RepeatEnd.Until)?.day ?: firstDay.plusMonths(1), { setEnd(RepeatEnd.Until(it)); pickUntil = false }) { pickUntil = false }
}

@Composable
private fun Stepper(value: Int, range: IntRange, lessLabel: String, moreLabel: String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // spoken names: "Every: fewer" / "Every: more" instead of a bare minus and plus sign
        OutlinedIconButton({ if (value > range.first) onChange(value - 1) }, Modifier.semantics { contentDescription = lessLabel }, enabled = value > range.first) { Text("−", Modifier.clearAndSetSemantics {}) }
        Text(value.toString(), Modifier.width(44.dp).padding(horizontal = 4.dp), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        OutlinedIconButton({ if (value < range.last) onChange(value + 1) }, Modifier.semantics { contentDescription = moreLabel }, enabled = value < range.last) { Text("+", Modifier.clearAndSetSemantics {}) }
    }
}

/**
 * The task page's reminders (docs/phase-3-design.md section 3): the effective list. While the defaults apply they are shown as such; any
 * change turns them into the task's own list. Alarms from other apps are listed read-only. [editable] false (a read-only list) shows only.
 */
@Composable
fun RemindersSection(task: Task, words: Words, zone: ZoneId, editable: Boolean, onSet: (List<ReminderChoice>) -> Unit, onDefaults: () -> Unit) {
    val list = ReminderSet.of(task, zone)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var adding by remember { mutableStateOf(false) }
    SectionHeading(stringResource(R.string.label_reminders))
    Column(Modifier.padding(horizontal = 20.dp)) {
        if (list.usesDefaults && list.own.isNotEmpty()) Text(stringResource(R.string.reminders_defaults_note), style = MaterialTheme.typography.bodySmall, color = muted)
        if (list.items.isEmpty()) Text(stringResource(R.string.reminders_none), style = MaterialTheme.typography.bodyLarge, color = muted, modifier = Modifier.padding(vertical = 8.dp))
        list.items.forEach { item ->
            val label = item.choice?.let { words.reminderChoice(it, zone) } ?: item.foreign?.let { words.reminder(it, task, zone) }.orEmpty()
            val tag = when (item.source) { ReminderSource.Default -> stringResource(R.string.reminder_default); ReminderSource.Foreign -> stringResource(R.string.reminder_foreign); ReminderSource.Ours -> null }
            val second = listOfNotNull(item.atMs?.let { words.moment(it, zone) } ?: stringResource(R.string.reminder_gone), tag).joinToString(" · ")
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(second, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                val c = item.choice
                if (editable && c != null) {
                    val desc = stringResource(R.string.reminder_remove, label)
                    IconButton({ onSet(list.own - c) }, Modifier.semantics { contentDescription = desc }) { Icon(Icons.Filled.Close, null) }
                }
            }
        }
        if (task.start == null && task.due == null) Text(stringResource(R.string.reminders_need_date), style = MaterialTheme.typography.bodySmall, color = muted)
        if (editable) Row {
            TextButton({ adding = true }) { Text(stringResource(R.string.reminders_add)) }
            Spacer(Modifier.weight(1f))
            if (!list.usesDefaults) TextButton(onDefaults) { Text(stringResource(R.string.reminders_use_defaults)) }
        }
    }
    if (adding) AddReminderDialog(task, list.own, words, zone, { onSet(list.own + it); adding = false }) { adding = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReminderDialog(task: Task, have: List<ReminderChoice>, words: Words, zone: ZoneId, onPick: (ReminderChoice) -> Unit, onDismiss: () -> Unit) {
    var pickDay by remember { mutableStateOf<LocalDate?>(null) }
    var askDay by remember { mutableStateOf(false) }
    val offered = ReminderSet.MENU.filter { c -> (if (c.toDue) task.due else task.start) != null && c !in have }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminders_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                offered.forEach { c ->
                    Text(words.reminderChoice(c, zone), Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
                Text(stringResource(R.string.reminder_set_time), Modifier.fillMaxWidth().clickable { askDay = true }.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
    if (askDay) QuickDateDialog(task.start?.date ?: task.due?.date ?: LocalDate.now(zone), { pickDay = it; askDay = false }) { askDay = false }
    pickDay?.let { d ->
        val ts = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickDay = null },
            title = { Text(stringResource(R.string.edit_pick_time)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(ts) } },
            confirmButton = { TextButton({ onPick(ReminderChoice.At(d.atTime(LocalTime.of(ts.hour, ts.minute)).atZone(zone).toInstant().toEpochMilli())); pickDay = null }) { Text(stringResource(R.string.edit_ok)) } },
            dismissButton = { TextButton({ pickDay = null }) { Text(stringResource(R.string.edit_cancel)) } },
        )
    }
}
