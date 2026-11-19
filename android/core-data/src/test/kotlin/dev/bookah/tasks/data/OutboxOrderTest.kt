package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

private val ZONE = ZoneId.of("Europe/Athens")
private val NOW = Instant.parse("2026-11-18T10:00:00Z")

private fun todo(uid: String, order: Long?) = listOfNotNull(
    "BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VTODO", "UID:$uid", "DTSTAMP:20261101T000000Z", "CREATED:20261101T000000Z", "SUMMARY:$uid",
    order?.let { "X-APPLE-SORT-ORDER:$it" }, "STATUS:NEEDS-ACTION", "END:VTODO", "END:VCALENDAR",
).joinToString("\r\n") + "\r\n"

/** A list with tasks a, b, c (orders given), each stored with ETag "e-<uid>". */
private class OrderEnv(orders: List<Pair<String, Long?>>) {
    val store = MemoryTaskStore()
    val list: TaskList = store.replaceLists(listOf(RemoteList("/cal/home/", "Home", "#245A86", true, ListKind.Tasks, 0))).single()
    val outbox = Outbox(store) { 1000L }
    init { store.upsertObjects(list.id, orders.map { (u, o) -> RemoteObject(href(u), "e-$u", todo(u, o)) }) }
    fun href(uid: String) = "/cal/home/$uid.ics"
    fun order(uid: String): String? = IcsPatcher.readProperty(store.objectByHref(href(uid))!!.ics, "X-APPLE-SORT-ORDER")
    fun shown(): List<String> = TaskViews.listTree(TaskParser.parseAll(store.allObjects(), ZONE), list.id).map { it.task.uid }
    fun snapshot() = store.allObjects().map { it.href to it.ics }.toSet() to store.outboxAll()
}

/** Writing a move (Outbox.reorder): planned on the saved copy under the store lock, all or nothing, one queued edit per changed task. */
class OutboxOrderTest {
    @Test fun anOrdinaryMoveQueuesOneEditAgainstTheStoredEtag() {
        val e = OrderEnv(listOf("a" to 1024L, "b" to 2048L, "c" to 3072L))
        assertEquals(1, e.outbox.reorder(e.list.id, e.href("c"), after = e.href("a"), before = e.href("b"), zone = ZONE, now = NOW))
        val row = e.store.outboxAll().single()
        assertEquals(OutboxKind.Edit, row.kind); assertEquals("e-c", row.baseEtag); assertEquals(OutboxState.Pending, row.state)
        assertEquals("1536", e.order("c"))
        assertEquals(listOf("a", "c", "b"), e.shown())
    }

    @Test fun theMoveFollowsItsNeighboursNotAnIndexWhenASyncAddedATask() {
        // the screen showed a, b, c and the owner put c after a; meanwhile a sync brought x (order 1500) between a and b
        val e = OrderEnv(listOf("a" to 1024L, "x" to 1500L, "b" to 2048L, "c" to 3072L))
        e.outbox.reorder(e.list.id, e.href("c"), after = e.href("a"), before = e.href("b"), zone = ZONE, now = NOW)
        assertEquals(listOf("a", "c", "x", "b"), e.shown())
    }

    @Test fun aVanishedNeighbourWritesNothing() {
        val e = OrderEnv(listOf("a" to 1024L, "b" to 2048L, "c" to 3072L))
        assertEquals(0, e.outbox.reorder(e.list.id, e.href("c"), after = "/cal/home/gone.ics", before = "/cal/home/gone2.ics", zone = ZONE, now = NOW))
        assertTrue(e.store.outboxAll().isEmpty())
    }

    @Test fun aTaskWithARefusedConflictedOrDeletedChangeBlocksTheWholeRenumber() {
        for (blocker in listOf(OutboxState.Failed to OutboxKind.Edit, OutboxState.Conflict to OutboxKind.Edit, OutboxState.Pending to OutboxKind.Delete)) {
            // orders 1, 2, 3: putting c between a and b renumbers b and c; b has a change the server refused (or a conflict, or a delete)
            val e = OrderEnv(listOf("a" to 1L, "b" to 2L, "c" to 3L))
            e.store.outboxInsert(OutboxItem(0, blocker.second, e.list.id, e.href("b"), "e-b", if (blocker.second == OutboxKind.Delete) null else "REFUSED", 1L, state = blocker.first, lastCode = 400))
            val before = e.snapshot()
            try {
                e.outbox.reorder(e.list.id, e.href("c"), after = e.href("a"), before = e.href("b"), zone = ZONE, now = NOW)
                fail("should refuse: $blocker")
            } catch (x: IllegalStateException) { /* the owner gets the usual message */ }
            assertEquals("nothing written for $blocker", before, e.snapshot())          // c not written either; the refused row stays refused
        }
    }

    @Test fun aRefusedChangeIsNeverPutBackInTheQueueByAMove() {
        val e = OrderEnv(listOf("a" to 1024L, "b" to 2048L, "c" to 3072L))
        e.store.outboxInsert(OutboxItem(0, OutboxKind.Edit, e.list.id, e.href("c"), "e-c", "REFUSED", 1L, state = OutboxState.Failed, lastCode = 403))
        try { e.outbox.reorder(e.list.id, e.href("c"), after = null, before = e.href("a"), zone = ZONE, now = NOW); fail() } catch (x: IllegalStateException) {}
        assertEquals(OutboxState.Failed, e.store.outboxAll().single().state)
    }

    @Test fun onlyTheChangedLineAndTheStampsDiffer() {
        val e = OrderEnv(listOf("a" to 1024L, "b" to 2048L))
        val old = e.store.objectByHref(e.href("b"))!!.ics
        e.outbox.reorder(e.list.id, e.href("b"), after = null, before = e.href("a"), zone = ZONE, now = NOW)
        val new = e.store.objectByHref(e.href("b"))!!.ics
        val changed = (new.lines() - old.lines().toSet()).map { it.substringBefore(':') }.toSet()
        assertEquals(setOf("X-APPLE-SORT-ORDER", "DTSTAMP", "LAST-MODIFIED", "SEQUENCE"), changed)
    }
}
