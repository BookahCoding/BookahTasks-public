package dev.bookah.tasks.data

/**
 * The person a "waiting" task waits on, kept as the first line of its notes (plan section 6). One fixed English marker is written so every
 * version and both languages read it the same way; a line with the Greek marker is read too. Nothing here throws.
 */
object Waiting {
    const val MARKER = "Waiting for:"
    private val markers = listOf(MARKER, "Αναμονή για:")
    private const val MAX_PERSON = 100

    private fun personLine(line: String): String? =
        markers.firstOrNull { line.trimStart().startsWith(it, ignoreCase = true) }?.let { m -> line.trimStart().substring(m.length).trim() }

    /** The person on the first line of [notes], or null. */
    fun personOf(notes: String?): String? = notes?.lineSequence()?.firstOrNull()?.let { personLine(it) }?.ifBlank { null }

    /**
     * [notes] with the person line set to [person] (null or blank removes it); every other line stays as it was. A first line that looks
     * like ours is only taken as ours when the task is already waiting ([wasWaiting]); otherwise it is the owner's own text and stays.
     */
    fun withPerson(notes: String?, person: String?, wasWaiting: Boolean = true): String? {
        val lines = notes?.split("\n").orEmpty().toMutableList()
        if (wasWaiting && lines.isNotEmpty() && personLine(lines[0]) != null) lines.removeAt(0)
        val p = person?.replace('\n', ' ')?.replace('\r', ' ')?.trim()?.take(MAX_PERSON)?.ifBlank { null }
        if (p != null) lines.add(0, "$MARKER $p")
        return lines.joinToString("\n").ifBlank { null }
    }
}
