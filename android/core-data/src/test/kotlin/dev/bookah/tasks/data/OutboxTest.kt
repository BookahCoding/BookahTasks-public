package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val HREF = "/cal/tasks/a.ics"

private class Env {
    val store = MemoryTaskStore()
    val list: TaskList = store.replaceLists(listOf(RemoteList("/cal/tasks/", "Tasks", "#A63F2D", true, ListKind.Tasks, 0))).single()
    val outbox = Outbox(store) { 1000L }
    init { store.upsertObjects(list.id, listOf(RemoteObject(HREF, "e1", "SERVER-TEXT"))) }
}

class OutboxTest {
    @Test fun createAppliesLocallyAndQueuesOneRow() {
        val e = Env()
        val v = e.store.version()
        val href = e.outbox.create(e.list, "new-uid", "NEW-TEXT")
        assertEquals("/cal/tasks/new-uid.ics", href)
        assertEquals("NEW-TEXT", e.store.objectByHref(href)!!.ics)
        val row = e.store.outboxAll().single()
        assertEquals(OutboxKind.Create, row.kind); assertNull(row.baseEtag); assertEquals("NEW-TEXT", row.ics)
        assertTrue(e.store.version() > v)
    }

    @Test fun editAppliesLocallyKeepingTheEtagAndQueuesAgainstThatEtag() {
        val e = Env()
        e.outbox.edit(HREF, "EDIT-1")
        assertEquals("EDIT-1", e.store.objectByHref(HREF)!!.ics)
        assertEquals("e1", e.store.objectByHref(HREF)!!.etag)
        val row = e.store.outboxAll().single()
        assertEquals(OutboxKind.Edit, row.kind); assertEquals("e1", row.baseEtag)
    }

    @Test fun repeatedEditsShareOneRowAndKeepTheFirstBaseEtag() {
        val e = Env()
        e.outbox.edit(HREF, "EDIT-1"); e.outbox.edit(HREF, "EDIT-2")
        val row = e.store.outboxAll().single()
        assertEquals("EDIT-2", row.ics); assertEquals("e1", row.baseEtag)
    }

    @Test fun editingAQueuedCreateStaysACreate() {
        val e = Env()
        val href = e.outbox.create(e.list, "n", "NEW")
        e.outbox.edit(href, "NEW-2")
        val row = e.store.outboxAll().single()
        assertEquals(OutboxKind.Create, row.kind); assertEquals("NEW-2", row.ics)
    }

    @Test fun deletingAQueuedCreateLeavesNothingToSend() {
        val e = Env()
        val href = e.outbox.create(e.list, "n", "NEW")
        e.outbox.delete(href)
        assertTrue(e.store.outboxAll().isEmpty())
        assertNull(e.store.objectByHref(href))
    }

    @Test fun deletingAQueuedEditBecomesADeleteAgainstTheOriginalEtag() {
        val e = Env()
        e.outbox.edit(HREF, "EDIT-1"); e.outbox.delete(HREF)
        val row = e.store.outboxAll().single()
        assertEquals(OutboxKind.Delete, row.kind); assertEquals("e1", row.baseEtag); assertEquals("EDIT-1", row.ics)          // the queued edit's text rides along (never sent) so an Undo can give it back
        assertNull(e.store.objectByHref(HREF))
    }

    @Test fun deleteQueuesARowAndRemovesTheLocalCopy() {
        val e = Env()
        e.outbox.delete(HREF)
        assertEquals(OutboxKind.Delete, e.store.outboxAll().single().kind)
        assertNull(e.store.objectByHref(HREF))
        assertEquals(setOf(HREF), e.outbox.pendingHrefs())
    }

    @Test fun anItemInConflictCannotBeEditedUntilResolved() {
        val e = Env()
        e.outbox.edit(HREF, "EDIT-1")
        e.store.outboxUpdate(e.store.outboxAll().single().copy(state = OutboxState.Conflict))
        try { e.outbox.edit(HREF, "EDIT-2"); fail("expected IllegalStateException") } catch (_: IllegalStateException) {}
    }

    @Test fun clearAllAndRemovedListsDropTheirRows() {
        val e = Env()
        e.outbox.edit(HREF, "EDIT-1")
        e.store.replaceLists(emptyList())
        assertTrue(e.store.outboxAll().isEmpty())
        val e2 = Env(); e2.outbox.edit(HREF, "x"); e2.store.clearAll()
        assertTrue(e2.store.outboxAll().isEmpty())
    }
}
