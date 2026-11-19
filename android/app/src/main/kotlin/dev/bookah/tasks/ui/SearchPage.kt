package dev.bookah.tasks.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import dev.bookah.tasks.R
import dev.bookah.tasks.data.Task
import dev.bookah.tasks.data.TaskSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The search page (plan 4.1), drawn over the current screen like Settings: every list shown in the app, live as you type (after a short
 * pause, worked out off the main thread), finished tasks with the switch. A result opens the task page; closing it comes back here.
 */
@Composable
fun SearchPage(env: RowEnv, query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    var includeDone by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Task>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val tasks = env.state.tasks
    LaunchedEffect(query, includeDone, tasks) {
        delay(150)
        // typing must never crash the app: whatever happens in the search, the answer is "nothing found"
        results = withContext(Dispatchers.Default) { runCatching { TaskSearch.search(tasks, query, includeDone) }.getOrDefault(emptyList()) }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.nav_back)) }
                OutlinedTextField(
                    query, { onQuery(it.replace("\n", " ").take(TaskSearch.MAX_QUERY)) }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    trailingIcon = if (query.isEmpty()) null else ({ IconButton({ onQuery("") }) { Icon(Icons.Filled.Close, stringResource(R.string.search_clear)) } }),
                )
            }
            SwitchRow(includeDone, { includeDone = it }, Modifier.padding(horizontal = 20.dp)) {
                Text(stringResource(R.string.search_include_done), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            when {
                TaskSearch.words(query).isEmpty() -> Hint(stringResource(R.string.search_start))
                results.isEmpty() -> Hint(stringResource(R.string.search_none))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results.size, key = { results[it].href + "|" + results[it].uid }) { i ->
                        val t = results[i]
                        TaskRow(t, env.color(t), env.listName(t), env.words, env.today, env.zone, env.progress(t), pending = t.href in env.state.pending) { env.onOpen(t) }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: IllegalStateException) {} }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
