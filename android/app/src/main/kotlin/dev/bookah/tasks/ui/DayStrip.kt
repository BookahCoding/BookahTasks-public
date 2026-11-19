package dev.bookah.tasks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bookah.tasks.R
import dev.bookah.tasks.data.DayEvent
import java.time.format.DateTimeFormatter

/** Whether the day strip has anything to show (the switch is on and today has events). */
fun RowEnv.hasStrip(): Boolean = state.prefs.showDayStrip && dayEvents.isNotEmpty()

/**
 * The day strip (plan 4.2.3): today's events from the owner's calendars as small pills, all-day first, read only. Each pill has the
 * calendar's colour, the time and the title; a tap opens BookahCalendar when it is installed, otherwise nothing happens.
 */
@Composable
fun DayStrip(env: RowEnv, modifier: Modifier = Modifier) {
    if (!env.hasStrip()) return
    val byId = env.state.lists.associateBy { it.id }
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.strip_heading), Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tap = if (env.state.calendarInstalled) env.onEvent else null          // a tap is only offered when BookahCalendar can open
            env.dayEvents.forEach { e -> EventPill(e, listColorOf(byId[e.listId]?.colorHex ?: "#245A86"), tap) }
        }
    }
}

@Composable
private fun EventPill(e: DayEvent, color: androidx.compose.ui.graphics.Color, onTap: ((DayEvent) -> Unit)?) {
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    val time = when {
        e.wholeDay -> stringResource(R.string.strip_all_day)
        e.startsBefore -> stringResource(R.string.strip_until, e.end?.let { hm.format(it) } ?: "")
        e.endsAfter -> stringResource(R.string.strip_from, e.start?.let { hm.format(it) } ?: "")
        e.end != null && e.end != e.start -> stringResource(R.string.strip_range, hm.format(e.start), hm.format(e.end))
        else -> e.start?.let { hm.format(it) }.orEmpty()
    }
    val title = e.title.ifBlank { stringResource(R.string.untitled_task) }
    val open = stringResource(R.string.strip_open)
    Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = (if (onTap != null) Modifier.clickable { onTap(e) } else Modifier).semantics(mergeDescendants = true) {
            contentDescription = "$title, $time"
            if (onTap != null) onClick(label = open) { onTap(e); true }
        },
    ) {
        Row(Modifier.padding(end = 12.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(5.dp).fillMaxHeight().background(color)) {}
            Column(Modifier.padding(start = 8.dp).widthIn(max = 200.dp)) {
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(title, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
