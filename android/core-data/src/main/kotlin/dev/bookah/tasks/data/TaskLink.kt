package dev.bookah.tasks.data

/** A request from another app (BookahCalendar) to show one task. [listHref] is only a hint for which list to prefer. */
data class TaskLink(val uid: String, val listHref: String?)

/**
 * Reads `bookahtasks://task?uid=<encoded UID>&list=<encoded list href>` (the contract with BookahCalendar; `list` is optional).
 * Nothing in the link is trusted: anything unexpected gives null, never an exception. Values are decoded once, the way Android's own
 * `Uri.getQueryParameter` does (so a `+` is a space and a real plus arrives as `%2B`). A link can only open a screen; it never changes anything.
 */
object TaskLinks {
    const val MAX_LINK = 4096
    const val MAX_UID = 1024
    private const val PREFIX = "bookahtasks://"

    fun parse(raw: String?): TaskLink? {
        if (raw == null || raw.length > MAX_LINK) return null
        if (!raw.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return null
        val rest = raw.substring(PREFIX.length).substringBefore('#')
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
        if (!rest.substring(0, authorityEnd).equals("task", ignoreCase = true)) return null          // no user, no port, no other host
        val afterHost = rest.substring(authorityEnd)
        val path = afterHost.substringBefore('?')
        if (path != "" && path != "/") return null
        val query = if ('?' in afterHost) afterHost.substringAfter('?') else ""
        var uid: String? = null
        var list: String? = null
        var uidSeen = false
        var listSeen = false
        for (part in query.split('&')) {
            if (part.isEmpty() || '=' !in part) continue
            val key = decode(part.substringBefore('=')) ?: return null
            val value = decode(part.substringAfter('=')) ?: return null
            when (key) {
                "uid" -> if (!uidSeen) { uidSeen = true; uid = value }
                "list" -> if (!listSeen) { listSeen = true; list = value }
            }
        }
        val u = uid?.takeIf { it.isNotBlank() && it.length <= MAX_UID && clean(it) } ?: return null
        val l = list?.takeIf { it.isNotEmpty() }
        if (l != null && !clean(l)) return null
        return TaskLink(u, l)
    }

    /** No control characters and no broken text (an escape that was not valid UTF-8 decodes to the replacement character). */
    private fun clean(s: String) = s.none { it < ' ' || it == '\u007F' || it == '�' }

    private fun decode(s: String): String? = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The path of a list address, with one trailing slash; a full address ("https://host/path/") gives its path. */
    fun listPath(href: String): String {
        val noScheme = href.indexOf("://").let { i -> if (i < 0) href else href.substring(i + 3).let { r -> r.indexOf('/').let { j -> if (j < 0) "/" else r.substring(j) } } }
        return noScheme.trimEnd('/') + "/"
    }

    /**
     * The task the link names among [tasks] (the open-to-view copy, never a finished copy of a repeating task). The list is a preference,
     * not a filter: while a task is being moved the same UID is briefly in two lists, and an unknown list still finds the task.
     */
    fun find(tasks: List<Task>, lists: List<TaskList>, link: TaskLink): Task? {
        val found = tasks.filter { !it.isOccurrenceCopy && it.uid == link.uid }
        if (found.isEmpty()) return null
        link.listHref?.let { want ->
            val path = listPath(want)
            val ids = lists.filter { listPath(it.href) == path }.map { it.id }.toSet()
            found.firstOrNull { it.listId in ids }?.let { return it }
        }
        return found.first()
    }
}
