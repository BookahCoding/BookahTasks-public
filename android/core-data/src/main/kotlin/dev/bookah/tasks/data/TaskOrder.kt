package dev.bookah.tasks.data

/**
 * Manual order inside a list (plan, section 6): the `X-APPLE-SORT-ORDER` number that the Nextcloud Tasks web app and Apple's apps use,
 * smaller first. A task without one is ordered by its creation time (the web app does this, seen in its source in Phase 0). A task with
 * neither is put last: that is our choice, NOT checked against the web app (its source seemed to put such a task first).
 * A move writes one task, halfway between its new neighbours; only when no whole number fits there is a run of tasks renumbered with
 * gaps. A renumber is several separate PUTs: if the server refuses one of them (412, the task changed meanwhile), the others still go
 * through, so the server can hold a mixed order until the conflict is resolved. Nothing is lost; only the order is off.
 * Values written are always between 1 and the largest 32-bit number (other apps may read it as one); values outside that range written
 * by other apps are read as they are and never copied.
 */
object TaskOrder {
    const val GAP = 1024L
    const val MAX = Int.MAX_VALUE.toLong()
    /** The order of a task that has neither its own order nor a creation time: last. */
    const val NO_ORDER = MAX

    /** The one order every list view uses: the number, then the title for ties. */
    val comparator: Comparator<Task> = compareBy<Task>({ it.sortOrder }, { it.title.lowercase() })

    /** The tasks shown at the same level as [href] (the top level, or the children of the same parent), in the order shown; null if it is not shown. */
    fun groupOf(nodes: List<TaskNode>, href: String): List<Task>? {
        if (nodes.any { it.task.href == href }) return nodes.map { it.task }
        for (n in nodes) groupOf(n.children, href)?.let { return it }
        return null
    }

    /**
     * The place for [href] in [group] when the owner put it after [after] and before [before] (addresses of the rows around the drop, as
     * the screen showed them; null = no row on that side). Neighbours, not an index, so a task that a sync added meanwhile does not shift
     * the target. [after] wins; if it is gone, [before] is used; null when the task or both neighbours are gone.
     */
    fun indexBetween(group: List<Task>, href: String, after: String?, before: String?): Int? {
        if (group.none { it.href == href }) return null
        val others = group.filter { it.href != href }
        after?.let { a -> others.indexOfFirst { it.href == a }.takeIf { it >= 0 }?.let { return it + 1 } }
        before?.let { b -> others.indexOfFirst { it.href == b }.takeIf { it >= 0 }?.let { return it } }
        return null
    }

    /** The rows that would sit right before and right after [href] once moved to [newIndex] in [group] (what the screen passes on). */
    fun neighbours(group: List<Task>, href: String, newIndex: Int): Pair<String?, String?> {
        val others = group.filter { it.href != href }
        val to = newIndex.coerceIn(0, others.size)
        return others.getOrNull(to - 1)?.href to others.getOrNull(to)?.href
    }

    /**
     * The new order values after moving [href] to [newIndex] within [group] (as shown, see [comparator]). Only the values that change are
     * returned, keyed by address: normally one; none when nothing moves.
     */
    fun plan(group: List<Task>, href: String, newIndex: Int): Map<String, Long> {
        val from = group.indexOfFirst { it.href == href }
        if (from < 0 || group.size < 2) return emptyMap()
        val to = newIndex.coerceIn(0, group.size - 1)
        if (to == from) return emptyMap()
        val order = group.toMutableList().apply { add(to, removeAt(from)) }
        between(order.getOrNull(to - 1)?.sortOrder, order.getOrNull(to + 1)?.sortOrder)?.let { return mapOf(href to it) }
        // no whole number fits: grow a run of tasks around the new place (down first, then up, in turn) until its outer neighbours leave
        // room for all of it, and space the run out with gaps. Tasks outside the run keep their values, so few tasks are written.
        var i = to
        var j = to
        var down = true
        while (true) {
            val lo = if (i > 0) maxOf(order[i - 1].sortOrder, 0L) else 0L
            val hi = if (j < order.size - 1) minOf(order[j + 1].sortOrder, MAX + 1) else MAX + 1
            val n = j - i + 1
            if (hi - lo - 1 >= n) {
                val step = minOf(GAP, (hi - lo) / (n + 1))
                val out = LinkedHashMap<String, Long>()
                for (k in i..j) { val v = lo + (k - i + 1) * step; if (order[k].sortOrder != v) out[order[k].href] = v }
                return out
            }
            val canDown = j < order.size - 1
            val canUp = i > 0
            if (canDown && (down || !canUp)) j++ else i--
            down = !down
        }
    }

    /** A whole number strictly between [lo] and [hi] (null = no neighbour on that side) within 1..MAX, or null when none fits. */
    private fun between(lo: Long?, hi: Long?): Long? {
        val v = when {
            lo == null && hi == null -> GAP
            lo == null -> if (hi!! - GAP >= 1) hi - GAP else hi / 2
            hi == null -> if (lo <= MAX - GAP) lo + GAP else lo + (MAX + 1 - lo) / 2
            hi - lo >= 2 -> lo + (hi - lo) / 2
            else -> return null
        }
        return v.takeIf { it in 1..MAX && (lo == null || it > lo) && (hi == null || it < hi) }
    }
}
