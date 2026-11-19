package dev.bookah.tasks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bookah.tasks.R
import dev.bookah.tasks.Words
import dev.bookah.tasks.data.Priority
import dev.bookah.tasks.data.Task
import dev.bookah.tasks.data.TaskViews
import dev.bookah.tasks.design.ChipColor
import java.time.LocalDate
import java.time.ZoneId

fun listColorOf(hex: String): Color = runCatching { ChipColor.parse(hex) }.getOrDefault(Color(0xFF245A86))

/** A round tick box: outlined in the list colour, filled with it (and a check in black or white by contrast) when the task is done. Phase 1: it only shows the state. */
@Composable
fun TickBox(done: Boolean, color: Color, modifier: Modifier = Modifier) {
    val ink = ChipColor.forCalendar(color).text
    Box(modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(24.dp)) {
            if (done) drawCircle(color) else drawCircle(color, style = Stroke(width = 2.dp.toPx()))
        }
        if (done) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = ink)
    }
}

/** A small flag drawn in code (no icon font needed), used with the priority word. */
@Composable
fun FlagMark(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(12.dp)) {
        val w = size.width; val h = size.height
        drawLine(color, Offset(w * 0.18f, h * 0.05f), Offset(w * 0.18f, h * 0.98f), strokeWidth = 1.6.dp.toPx())
        val p = Path().apply { moveTo(w * 0.18f, h * 0.08f); lineTo(w * 0.92f, h * 0.30f); lineTo(w * 0.18f, h * 0.55f); close() }
        drawPath(p, color)
    }
}

@Composable
fun TopBar(title: String, onMenu: () -> Unit, syncing: Boolean, offline: Boolean, action: (@Composable () -> Unit)? = null) {
    // at least 56 dp, taller when the system text is very large (the title may take two lines instead of being cut)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onMenu) { Icon(Icons.Filled.Menu, stringResource(R.string.nav_open_menu)) }
        Text(
            title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        action?.invoke()
        if (syncing) CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp).semantics { contentDescription = "" }, strokeWidth = 2.dp)
        else if (offline) Icon(Icons.Filled.Warning, stringResource(R.string.sync_offline), Modifier.padding(end = 16.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A group heading in a list ("Tomorrow", "This evening"): a screen reader can jump between them. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Text drawn at most [max] times its normal size, for the few places with a fixed shape (BookahCalendar's approach): everything else
 * follows the system text size in full.
 */
@Composable
fun CappedFontScale(max: Float, content: @Composable () -> Unit) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, minOf(d.fontScale, max)), content = content,
    )
}

/** The drawable of the season's picture, or null when the owner switched the artwork off in Settings. */
val LocalArtwork = androidx.compose.runtime.staticCompositionLocalOf<Int?> { null }

/** A rounded picture of the season. Decoration only: it says nothing to a screen reader and shows nothing at all when the artwork is off. */
@Composable
fun ArtBanner(height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val art = LocalArtwork.current ?: return
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(art), contentDescription = null,
        modifier = modifier.fillMaxWidth().height(height).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
    )
}

@Composable
fun EmptyState(text: String) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ArtBanner(170.dp, Modifier.padding(bottom = 24.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One task row (design spec 2.1): tick box, title, one muted line with what matters. Overdue dates are the only red text. The whole row is
 * read aloud as one sentence. [indent] nests a subtask under its parent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    task: Task, listColor: Color, listName: String?, words: Words, today: LocalDate, zone: ZoneId,
    subtasks: Pair<Int, Int>?, indentDp: Int = 0, dayKnown: Boolean = false, pending: Boolean = false, onToggle: (() -> Unit)? = null, onReschedule: (() -> Unit)? = null,
    /** More screen-reader actions (label, action), such as "Move up" in a list. */
    moreActions: List<Pair<String, () -> Unit>> = emptyList(),
    /** A small button at the end of the row (the overdue group's Reschedule button). */
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val overdue = TaskViews.isOverdue(task, today)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val danger = MaterialTheme.colorScheme.error
    val title = task.title.ifBlank { stringResource(R.string.untitled_task) }
    val dateText = (task.due ?: task.start)?.let { if (dayKnown) words.time(it) else words.whenText(it, today) }
    val priority = words.priority(task.priority)
    val remindersText = when (task.reminders.size) { 0 -> null; 1 -> stringResource(R.string.mark_reminder_one); else -> stringResource(R.string.mark_reminders, task.reminders.size) }
    val repeatsText = if (task.repeats) stringResource(R.string.mark_repeats) else null
    val linkText = if (task.url != null) stringResource(R.string.mark_link) else null
    val waitingWho = task.waitingFor?.let { stringResource(R.string.waiting_for_person, it) } ?: stringResource(R.string.mark_waiting)
    // the check-back date is the start; it is named when the row's own date is the deadline
    val checkBack = task.start
    val waitingText = if (!task.waiting) null
        else if (checkBack != null && task.due != null && task.isOpen) stringResource(R.string.waiting_until, waitingWho, words.day(checkBack.date, today))
        else waitingWho
    val subText = subtasks?.let { stringResource(R.string.subtasks_progress, it.first, it.second) }
    val doneWord = stringResource(if (task.isDone) R.string.state_done else R.string.state_open)
    val spoken = listOfNotNull(title, doneWord, dateText, listName, priority, waitingText, repeatsText, remindersText, task.tags.takeIf { it.isNotEmpty() }?.joinToString(", "), subText).joinToString(", ")

    val pendingText = if (pending) stringResource(R.string.mark_pending) else null
    val tickLabel = stringResource(if (task.isDone) R.string.edit_reopen else R.string.edit_mark_done)
    val rescheduleLabel = stringResource(R.string.swipe_reschedule)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (4 + indentDp).dp, end = 16.dp)
            .semantics(mergeDescendants = false) {
                contentDescription = spoken
                // the swipe gestures have a screen-reader route too
                val acts = listOfNotNull(
                    onToggle?.let { f -> androidx.compose.ui.semantics.CustomAccessibilityAction(tickLabel) { f(); true } },
                    onReschedule?.let { f -> androidx.compose.ui.semantics.CustomAccessibilityAction(rescheduleLabel) { f(); true } },
                ) + moreActions.map { (label, f) -> androidx.compose.ui.semantics.CustomAccessibilityAction(label) { f(); true } }
                if (acts.isNotEmpty()) customActions = acts
            },
        verticalAlignment = Alignment.Top,
    ) {
        // the tick box is its own 48 dp target; the rest of the row opens the task
        Box(
            Modifier.size(48.dp).then(
                if (onToggle != null) Modifier.androidx_toggleable(task.isDone, title, tickLabel, onToggle) else Modifier,
            ),
            contentAlignment = Alignment.Center,
        ) { TickBox(task.isDone, listColor, Modifier.clearAndSetSemantics {}) }
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f).padding(top = 11.dp, bottom = 11.dp)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                color = if (task.isDone) muted else MaterialTheme.colorScheme.onSurface,
            )
            val hasMeta = listOfNotNull(dateText, listName, priority, repeatsText, remindersText, linkText, waitingText, subText, pendingText).isNotEmpty() || task.tags.isNotEmpty()
            if (hasMeta) FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                val small = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                if (dateText != null) Text(dateText, style = small, color = if (overdue) danger else muted)
                if (listName != null) Text(listName, style = small, color = muted)
                task.tags.forEach { Text("#$it", style = small, color = muted) }
                if (priority != null) Row(verticalAlignment = Alignment.CenterVertically) { FlagMark(muted); Spacer(Modifier.width(3.dp)); Text(priority, style = small, color = muted) }
                if (repeatsText != null) Text(repeatsText, style = small, color = muted)
                if (remindersText != null) Text(remindersText, style = small, color = muted)
                if (linkText != null) Text(linkText, style = small, color = muted)
                if (waitingText != null) Text(waitingText, style = small, color = muted)
                if (subText != null) Text(subText, style = small, color = muted)
                if (pendingText != null) Text(pendingText, style = small, color = muted)
            }
        }
        if (trailing != null) Box(Modifier.padding(top = 4.dp)) { trailing() }
    }
}

/** A row that is one switch control: the whole row toggles, a screen reader hears the label with the Switch role and its state. */
@Composable
fun SwitchRow(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
        androidx.compose.material3.Switch(checked, onCheckedChange = null)
    }
}

/** The tick box as a checkbox for a screen reader: it has the task's name, the checkbox role and its checked state, and its own 48 dp target. */
private fun Modifier.androidx_toggleable(checked: Boolean, name: String, label: String, onToggle: () -> Unit): Modifier =
    this.toggleable(value = checked, role = androidx.compose.ui.semantics.Role.Checkbox, onValueChange = { onToggle() })
        .semantics { contentDescription = name; stateDescription = label }
