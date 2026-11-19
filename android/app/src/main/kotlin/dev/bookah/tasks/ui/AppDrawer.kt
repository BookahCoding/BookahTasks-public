package dev.bookah.tasks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bookah.tasks.R
import dev.bookah.tasks.Screen
import dev.bookah.tasks.UiState
import dev.bookah.tasks.data.TaskViews
import java.time.LocalDate

/** The drawer (design spec 2.1): smart views with counts, the lists with their colours and a show/hide box, the tags, then Settings. */
@Composable
fun AppDrawer(state: UiState, onShow: (Screen) -> Unit, onToggleList: (Long, Boolean) -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit, onSearch: () -> Unit = {}) {
    val counts = TaskViews.counts(state.tasks, LocalDate.now(), state.inboxListId, state.prefs.eveningHour)
    val openByList = state.tasks.filter { it.isOpen && !it.someday }.groupingBy { it.listId }.eachCount()
    val showHide = stringResource(R.string.nav_show_hide)
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(stringResource(R.string.app_name), Modifier.padding(horizontal = 28.dp, vertical = 16.dp), style = MaterialTheme.typography.titleLarge)
            ArtBanner(72.dp, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
            Smart(Icons.AutoMirrored.Filled.List, R.string.nav_schedule, null, state.screen == Screen.Schedule) { onShow(Screen.Schedule) }
            Smart(Icons.Filled.Star, R.string.nav_today, counts.today + counts.overdue, state.screen == Screen.Today) { onShow(Screen.Today) }
            Smart(Icons.Filled.Email, R.string.nav_inbox, counts.inbox, state.screen == Screen.Inbox) { onShow(Screen.Inbox) }
            Smart(Icons.Filled.DateRange, R.string.nav_upcoming, counts.upcoming, state.screen == Screen.Upcoming) { onShow(Screen.Upcoming) }
            Smart(Icons.Filled.FavoriteBorder, R.string.nav_someday, counts.someday, state.screen == Screen.Someday) { onShow(Screen.Someday) }
            Smart(Icons.Filled.Person, R.string.nav_waiting, TaskViews.waiting(state.tasks).size, state.screen == Screen.Waiting) { onShow(Screen.Waiting) }
            Smart(Icons.Filled.Check, R.string.nav_logbook, null, state.screen == Screen.Logbook) { onShow(Screen.Logbook) }
            Smart(Icons.Filled.Search, R.string.search_open, null, false, onSearch)

            if (state.taskLists.isNotEmpty()) {
                Text(stringResource(R.string.nav_lists), Modifier.padding(start = 28.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.taskLists.forEach { l ->
                    val color = listColorOf(l.colorHex)
                    val selected = state.screen == Screen.OneList(l.id)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            l.visible, { onToggleList(l.id, it) },
                            Modifier.semantics { contentDescription = l.name + ", " + showHide },
                            colors = CheckboxDefaults.colors(checkedColor = color, checkmarkColor = dev.bookah.tasks.design.ChipColor.forCalendar(color).text),
                        )
                        NavigationDrawerItem(
                            label = { Text(l.name, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                            selected = selected, onClick = { onShow(Screen.OneList(l.id)) },
                            badge = { Text((openByList[l.id] ?: 0).toString()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (state.tags.isNotEmpty()) {
                Text(stringResource(R.string.nav_tags), Modifier.padding(start = 28.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.tags.forEach { tag ->
                    NavigationDrawerItem(label = { Text("#$tag", maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, selected = state.screen == Screen.Tag(tag), onClick = { onShow(Screen.Tag(tag)) }, modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.nav_refresh)) }, selected = false, onClick = onRefresh,
                icon = { Icon(Icons.Filled.Refresh, null) }, modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.nav_settings)) }, selected = false, onClick = onSettings,
                icon = { Icon(Icons.Filled.Settings, null) }, modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

@Composable
private fun Smart(icon: ImageVector, label: Int, count: Int?, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(stringResource(label)) }, selected = selected, onClick = onClick, icon = { Icon(icon, null) },
        badge = if (count != null && count > 0) ({ Text(count.toString()) }) else null,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}
