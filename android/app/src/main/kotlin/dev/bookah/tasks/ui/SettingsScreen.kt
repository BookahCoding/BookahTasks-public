package dev.bookah.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bookah.tasks.BuildConfig
import dev.bookah.tasks.R
import dev.bookah.tasks.UiState
import dev.bookah.tasks.design.Appearance
import dev.bookah.tasks.design.ThemeCatalog
import java.text.DateFormat
import java.util.Date

/** Settings (design spec 2.4), Phase 1: account, evening hour, theme gallery, appearance, sync state, about and the on-phone crash log. */
@Composable
fun SettingsScreen(
    state: UiState, onBack: () -> Unit, onSignOut: () -> Unit, onTheme: (String) -> Unit, onAppearance: (Appearance) -> Unit,
    onEveningHour: (Int) -> Unit, onSync: () -> Unit, onClearCrash: () -> Unit, onArtwork: (Boolean) -> Unit,
    onDefaultList: (String?) -> Unit, onRetry: (Long) -> Unit, onDiscard: (Long) -> Unit,
    onDayStrip: (Boolean) -> Unit = {}, onShowEvents: (Long, Boolean) -> Unit = { _, _ -> },
) {
    var chooseList by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (chooseList) DefaultListDialog(state, onPick = { onDefaultList(it); chooseList = false }, onDismiss = { chooseList = false })
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Filled.ArrowBack, stringResource(R.string.nav_back)) }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Group(stringResource(R.string.settings_account))
                Text(stringResource(R.string.settings_signed_in, state.signedInUser))
                OutlinedButton(onSignOut) { Text(stringResource(R.string.settings_sign_out)) }

                Group(stringResource(R.string.settings_general))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_evening), Modifier.weight(1f))
                    TextButton({ onEveningHour(state.prefs.eveningHour - 1) }, enabled = state.prefs.eveningHour > 12) { Text("−") }
                    Text(stringResource(R.string.settings_hour_format, state.prefs.eveningHour), style = MaterialTheme.typography.titleMedium)
                    TextButton({ onEveningHour(state.prefs.eveningHour + 1) }, enabled = state.prefs.eveningHour < 23) { Text("+") }
                }

                Row(Modifier.fillMaxWidth().clickable { chooseList = true }.heightIn(min = 48.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_default_list), Modifier.weight(1f))
                    val chosen = state.prefs.defaultListHref?.let { h -> state.taskLists.firstOrNull { it.href == h }?.name }
                    Text(chosen ?: stringResource(R.string.settings_default_inbox), color = MaterialTheme.colorScheme.primary)
                }

                Group(stringResource(R.string.settings_reminders))
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val allowed = dev.bookah.tasks.ReminderNotifier.notificationsAllowed(ctx)
                Text(stringResource(if (allowed) R.string.settings_notif_on else R.string.settings_notif_off), color = if (allowed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                if (!allowed) OutlinedButton({
                    ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.settings_notif_allow)) }

                Group(stringResource(R.string.settings_strip))
                SwitchRow(state.prefs.showDayStrip, onDayStrip) { Text(stringResource(R.string.settings_strip_show), Modifier.weight(1f)) }
                Text(stringResource(R.string.settings_strip_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val calendars = state.lists.filter { it.holdsEvents }
                if (calendars.isEmpty()) Text(stringResource(R.string.settings_strip_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.prefs.showDayStrip) calendars.forEach { l ->
                    SwitchRow(l.showEvents, { onShowEvents(l.id, it) }, Modifier.padding(start = 8.dp)) {
                        Row(Modifier.padding(end = 12.dp).size(12.dp).background(listColorOf(l.colorHex), CircleShape)) {}
                        Text(l.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Group(stringResource(R.string.settings_look))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_artwork))
                        Text(stringResource(R.string.settings_artwork_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(state.prefs.artwork, onArtwork)
                }
                Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Appearance.System to R.string.appearance_system, Appearance.Light to R.string.appearance_light, Appearance.Dark to R.string.appearance_dark).forEach { (a, label) ->
                        FilterChip(state.prefs.appearance == a, { onAppearance(a) }, { Text(stringResource(label)) })
                    }
                }
                Text(stringResource(R.string.settings_theme), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ThemeCatalog.all.forEach { f ->
                    val dark = state.prefs.appearance == Appearance.Dark || (state.prefs.appearance == Appearance.System && androidx.compose.foundation.isSystemInDarkTheme())
                    val c = if (dark) f.dark else f.light
                    val selected = f.id == state.prefs.themeId
                    Row(
                        Modifier.fillMaxWidth().clickable { onTheme(f.id) }
                            .border(BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline), RoundedCornerShape(12.dp))
                            .background(c.bg, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.size(20.dp).background(c.accent, CircleShape)) {}
                            Row(Modifier.size(20.dp).background(c.secondary, CircleShape)) {}
                        }
                        Text(f.name, Modifier.padding(start = 12.dp).weight(1f), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Group(stringResource(R.string.settings_sync))
                val last = state.lastSyncMs?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
                Text(if (last != null) stringResource(R.string.sync_last, last) else stringResource(R.string.sync_never))
                state.lastReport?.let { r ->
                    when {
                        r.offline -> Text(stringResource(R.string.sync_offline), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        r.errorCode == 401 -> Text(stringResource(R.string.sync_wrong_password), color = MaterialTheme.colorScheme.error)
                        r.error != null -> Text(stringResource(R.string.sync_error, r.errorCode), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (state.unreadable > 0) Text(stringResource(R.string.settings_unreadable, state.unreadable), color = MaterialTheme.colorScheme.error)
                if (state.pending.isNotEmpty()) Text(stringResource(R.string.settings_waiting, state.pending.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.failed.isNotEmpty()) {
                    Text(stringResource(R.string.settings_failed_head), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    state.failed.forEach { f ->
                        Text(stringResource(R.string.settings_failed_row, f.title.ifBlank { stringResource(R.string.failed_deleted_task) }, f.code))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ onRetry(f.id) }) { Text(stringResource(R.string.settings_retry)) }
                            TextButton({ onDiscard(f.id) }) { Text(stringResource(R.string.settings_discard)) }
                        }
                    }
                }
                Button(onSync, enabled = !state.syncing) { Text(stringResource(R.string.nav_refresh)) }
                Text(stringResource(R.string.settings_sync_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Group(stringResource(R.string.speed))
                val sp = state.speed
                Text(
                    stringResource(R.string.speed_start, sp.coldStartMs?.toString() ?: "–") + "\n" +
                        stringResource(R.string.speed_load, sp.loadMs?.toString() ?: "–") + "\n" +
                        stringResource(R.string.speed_sync, sp.syncMs?.toString() ?: "–"),
                    style = MaterialTheme.typography.bodyMedium,
                )

                Group(stringResource(R.string.settings_about))
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
                Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_crash), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.crashLog.isBlank()) Text(stringResource(R.string.settings_crash_none), style = MaterialTheme.typography.bodySmall)
                else {
                    Text(state.crashLog.take(2000), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClearCrash) { Text(stringResource(R.string.settings_crash_clear)) }
                }
                Row(Modifier.width(1.dp)) {}
            }
        }
    }
}

@Composable
private fun Group(title: String) {
    Text(title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

/** Where new tasks go when no list is on screen: the inbox (the list called "Tasks") or any writable list. */
@Composable
private fun DefaultListDialog(state: UiState, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    val current = state.prefs.defaultListHref
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_default_list)) },
        text = {
            Column {
                (listOf<Pair<String?, String>>(null to stringResource(R.string.settings_default_inbox)) + state.taskLists.filter { it.editable }.map { it.href to it.name }).forEach { (href, name) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(href) }.heightIn(min = 48.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(current == href, { onPick(href) })
                        Text(name, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.edit_cancel)) } },
    )
}
