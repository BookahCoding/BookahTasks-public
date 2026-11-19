package dev.bookah.tasks.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.bookah.tasks.R
import dev.bookah.tasks.data.Task
import dev.bookah.tasks.data.TaskNode
import dev.bookah.tasks.data.TaskOrder

/** One row of the reorder list: the task, how deep it sits, the tasks at its level (as shown) and its place among them. */
private class Entry(val task: Task, val depth: Int, val group: List<Task>, val index: Int, var blockEnd: Int = 0)

private fun flatten(nodes: List<TaskNode>, depth: Int = 0, out: MutableList<Entry> = ArrayList()): List<Entry> {
    val group = nodes.map { it.task }
    nodes.forEachIndexed { i, n ->
        val e = Entry(n.task, depth, group, i)
        out.add(e)
        flatten(n.children, depth + 1, out)
        e.blockEnd = out.size - 1          // the last row of this task's subtree
    }
    return out
}

/** The tree with [href] put between its new neighbours within its own level (shown at once after a move, until the saved copy has it). */
private fun moved(nodes: List<TaskNode>, href: String, after: String?, before: String?): List<TaskNode> {
    val i = nodes.indexOfFirst { it.task.href == href }
    if (i >= 0) {
        val to = TaskOrder.indexBetween(nodes.map { it.task }, href, after, before) ?: return nodes
        return nodes.toMutableList().apply { add(to, removeAt(i)) }
    }
    return nodes.map { TaskNode(it.task, moved(it.children, href, after, before)) }
}

/** "Moved to position N of M": what a screen reader says after every move made here. */
private fun announceMove(view: android.view.View, text: String) {
    @Suppress("DEPRECATION") view.announceForAccessibility(text)
}

/** A grip of six dots, the usual sign of a drag handle (different from the menu's three lines). */
@Composable
private fun GripMark(color: Color) {
    Canvas(Modifier.size(width = 14.dp, height = 20.dp)) {
        val r = 2.dp.toPx()
        for (col in 0..1) for (row in 0..2) drawCircle(color, r, Offset(r + col * (size.width - 2 * r), r + row * (size.height - 2 * r) / 2))
    }
}

/**
 * The list screen in reorder mode (manual order, X-APPLE-SORT-ORDER): each row has a drag handle on the left and up/down arrows on the
 * right; a screen reader gets "Move up" and "Move down" as actions on the row and hears the new position after each move. A task moves only
 * among the tasks at its level (its parent never changes), and its subtasks move with it. Swipes and taps are off in this mode.
 * While a drag is in progress the rows stay as they were when it began (a sync meanwhile neither moves rows under the finger nor ends the
 * drag); the drop is passed on as its two neighbours, which the saved copy then resolves. No auto-scroll: a drag stays within the visible rows.
 */
@Composable
fun ReorderList(env: RowEnv, nodes: List<TaskNode>) {
    val view = LocalView.current
    val res = LocalContext.current.resources
    var override by remember { mutableStateOf<Triple<String, String?, String?>?>(null) }
    // the saved copy was read again (it now holds the new order), or a move ended without a write (refused): show the saved order
    LaunchedEffect(env.state.tasks, env.state.orderAttempts) { override = null }
    val live = remember(nodes, override) { override?.let { (h, a, b) -> moved(nodes, h, a, b) } ?: nodes }
    val liveEntries = remember(live) { flatten(live) }
    var frozen by remember { mutableStateOf<List<Entry>?>(null) }          // the rows as they were when the drag began
    val entries = frozen ?: liveEntries
    val flatIndex = remember(entries) { entries.withIndex().associate { it.value.task.href to it.index } }
    val listState = rememberLazyListState()
    var dragHref by remember { mutableStateOf<String?>(null) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableStateOf<Int?>(null) }

    fun commit(e: Entry, to: Int) {
        if (to == e.index || to !in e.group.indices) return
        val (after, before) = TaskOrder.neighbours(e.group, e.task.href, to)
        // the list would otherwise keep its first row in place by key, so a row moved above it would vanish above the top edge
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        override = Triple(e.task.href, after, before)
        env.actions.reorder(e.task, after, before)
        announceMove(view, res.getString(R.string.order_moved, to + 1, e.group.size))
    }

    /** Where the dragged row would land among its level: the number of the others whose middle is above the dragged row's middle. */
    fun landing(e: Entry): Int {
        val rows = frozen ?: return e.index
        val at = rows.withIndex().associate { it.value.task.href to it.index }
        val info = listState.layoutInfo.visibleItemsInfo
        val myIndex = at[e.task.href] ?: return e.index
        val me = info.firstOrNull { it.index == myIndex } ?: return e.index
        val middle = me.offset + me.size / 2f + dragY
        return e.group.filter { it.href != e.task.href }.count { o ->
            val oi = at[o.href] ?: return@count false
            val v = info.firstOrNull { it.index == oi }
            if (v != null) v.offset + v.size / 2f < middle else oi < myIndex
        }
    }

    fun endDrag(drop: Boolean, who: Entry) {
        val to = target ?: who.index
        dragHref = null; dragY = 0f; target = null; frozen = null          // one frame: no lifted row is ever left behind
        if (drop) commit(who, to)
    }

    val commitNow by rememberUpdatedState<(Entry, Int) -> Unit> { e, to -> commit(e, to) }
    val landingNow by rememberUpdatedState<(Entry) -> Int> { landing(it) }
    val endNow by rememberUpdatedState<(Boolean, Entry) -> Unit> { drop, who -> endDrag(drop, who) }
    val liveNow by rememberUpdatedState(liveEntries)

    // where to draw the drop line while dragging: above a row (top) or below the last row of the last task's subtree (bottom)
    val dragged = dragHref?.let { h -> entries.firstOrNull { it.task.href == h } }
    val lineTop: Int?
    val lineBottom: Int?
    run {
        val e = dragged; val t = target
        if (e == null || t == null || t == e.index) { lineTop = null; lineBottom = null; return@run }
        val others = e.group.filter { it.href != e.task.href }
        if (t < others.size) { lineTop = flatIndex[others[t].href]; lineBottom = null }
        else { lineTop = null; lineBottom = flatIndex[others.last().href]?.let { entries[it].blockEnd } }
    }

    val lineColor = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.order_hint), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(entries.size, key = { "r|" + entries[it].task.href + "|" + entries[it].task.uid }) { k ->
                val e = entries[k]
                val cur by rememberUpdatedState(e)
                val isDragged = e.task.href == dragHref
                val line = Modifier.drawWithContent {
                    drawContent()
                    val w = 3.dp.toPx()
                    if (lineTop == k) drawLine(lineColor, Offset(0f, w / 2), Offset(size.width, w / 2), w)
                    if (lineBottom == k) drawLine(lineColor, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                }
                val placed = if (isDragged) Modifier.zIndex(1f).graphicsLayer { translationY = dragY; shadowElevation = 8.dp.toPx() } else Modifier.animateItem()
                ReorderRow(
                    e.task, env, e.depth, canUp = e.index > 0, canDown = e.index < e.group.size - 1, lifted = isDragged,
                    onUp = { commitNow(cur, cur.index - 1) }, onDown = { commitNow(cur, cur.index + 1) },
                    handle = Modifier.pointerInput(e.task.href) {
                        detectVerticalDragGestures(
                            onDragStart = { frozen = liveNow; dragHref = cur.task.href; dragY = 0f; target = cur.index },
                            onDragEnd = { endNow(true, cur) },
                            onDragCancel = { endNow(false, cur) },
                            onVerticalDrag = { change, dy -> change.consume(); dragY += dy; target = landingNow(cur) },
                        )
                    },
                    modifier = placed.then(line),
                )
            }
        }
    }
}

@Composable
private fun ReorderRow(
    t: Task, env: RowEnv, depth: Int, canUp: Boolean, canDown: Boolean, lifted: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, handle: Modifier, modifier: Modifier,
) {
    val title = t.title.ifBlank { stringResource(R.string.untitled_task) }
    val up = stringResource(R.string.order_move_up)
    val down = stringResource(R.string.order_move_down)
    val grip = stringResource(R.string.order_drag)
    // one stop for a screen reader: the title (read once, from the text), the handle's purpose, and Move up / Move down as actions
    Row(
        modifier.fillMaxWidth()
            .background(if (lifted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background)
            .padding(start = (4 + (depth * 32).coerceAtMost(96)).dp, end = 4.dp)
            .semantics(mergeDescendants = true) {
                customActions = listOfNotNull(
                    if (canUp) CustomAccessibilityAction(up) { onUp(); true } else null,
                    if (canDown) CustomAccessibilityAction(down) { onDown(); true } else null,
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, Modifier.weight(1f).padding(start = 12.dp, top = 12.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyLarge,
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        IconButton(onUp, enabled = canUp) { Icon(Icons.Filled.KeyboardArrowUp, up) }
        IconButton(onDown, enabled = canDown) { Icon(Icons.Filled.KeyboardArrowDown, down) }
        Box(handle.size(48.dp).semantics { contentDescription = grip }, contentAlignment = Alignment.Center) { GripMark(env.color(t)) }
    }
}
