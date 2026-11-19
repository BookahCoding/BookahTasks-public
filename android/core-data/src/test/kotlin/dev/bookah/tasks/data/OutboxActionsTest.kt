package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val HREF = "/cal/tasks/a.ics"

private class ActionsRig {
    val store = MemoryTaskStore()
    val lists = store.replaceLists(
        listOf(
            RemoteList("/cal/tasks/", "Tasks", "#A63F2D", true, ListKind.Tasks, 0),
            RemoteList("/cal/home/", "Home", "#245A86", true, ListKind.Tasks, 1),
        ),
    )
    val one = lists[0]; val two = lists[1]
    val outbox = Outbox(store) { 1000L }
    init { store.upsertObjects(one.id, listOf(RemoteObject(HREF, "e1", "SERVER-TEXT"))) }
}

class OutboxActionsTest {
    @Test fun undoingADeleteThatIsStillQueuedDropsTheRowAndRestoresTheLocalCopy() {
        val r = ActionsRig()
        r.outbox.delete(HREF)
        r.outbox.recreate(r.one.id, HREF, "SERVER-TEXT")
        assertTrue(r.store.outboxAll().isEmpty())
        assertEquals("e1", r.store.objectByHref(HREF)!!.etag); assertEquals("SERVER-TEXT", r.store.objectByHref(HREF)!!.ics)
    }

    @Test fun undoingADeleteThatWasAlreadySentCreatesItAgainAtTheSameAddress() {
        val r = ActionsRig()
        r.outbox.delete(HREF)
        r.store.outboxRemove(r.store.outboxAll().single().id)             // the push has sent it
        r.outbox.recreate(r.one.id, HREF, "SERVER-TEXT")
        val row = r.store.outboxAll().single()
        assertEquals(OutboxKind.Create, row.kind); assertEquals(HREF, row.href); assertNull(row.baseEtag)
        assertEquals("", r.store.objectByHref(HREF)!!.etag)
    }

    @Test fun recreatingAnItemThatStillExistsIsRefused() {
        val r = ActionsRig()
        try { r.outbox.recreate(r.one.id, HREF, "x"); fail("expected a refusal") } catch (_: IllegalStateException) {}
        r.outbox.edit(HREF, "EDIT")
        try { r.outbox.recreate(r.one.id, HREF, "x"); fail("expected a refusal") } catch (_: IllegalStateException) {}
    }

    @Test fun moveCreatesInTheNewListFirstThenDeletesTheOld() {
        val r = ActionsRig()
        val newHref = r.outbox.move(HREF, r.two)
        assertEquals("/cal/home/a.ics", newHref)
        assertEquals(listOf(OutboxKind.Create, OutboxKind.Delete), r.store.outboxAll().map { it.kind })
        assertEquals(listOf("/cal/home/a.ics", HREF), r.store.outboxAll().map { it.href })
        assertEquals("SERVER-TEXT", r.store.objectByHref(newHref)!!.ics); assertEquals(r.two.id, r.store.objectByHref(newHref)!!.listId)
        assertNull(r.store.objectByHref(HREF))
    }

    @Test fun movingAQueuedCreateIsJustACreateInTheNewList() {
        val r = ActionsRig()
        val href = r.outbox.create(r.one, "n1", "NEW")
        val moved = r.outbox.move(href, r.two)
        val row = r.store.outboxAll().single()
        assertEquals(OutboxKind.Create, row.kind); assertEquals(moved, row.href); assertEquals("NEW", row.ics)
    }

    @Test fun movingAnEditedItemSendsTheEditedTextAndDeletesTheOriginal() {
        val r = ActionsRig()
        r.outbox.edit(HREF, "EDITED")
        val moved = r.outbox.move(HREF, r.two)
        val rows = r.store.outboxAll()
        assertEquals(listOf(OutboxKind.Create, OutboxKind.Delete), rows.map { it.kind })
        assertEquals("EDITED", rows[0].ics); assertEquals("e1", rows[1].baseEtag); assertEquals("/cal/home/a.ics", moved)
    }

    @Test fun movingToTheSameListOrAnItemInConflictIsRefused() {
        val r = ActionsRig()
        try { r.outbox.move(HREF, r.one); fail("same list") } catch (_: IllegalArgumentException) {}
        r.outbox.edit(HREF, "EDIT")
        r.store.outboxUpdate(r.store.outboxAll().single().copy(state = OutboxState.Conflict))
        try { r.outbox.move(HREF, r.two); fail("conflict") } catch (_: IllegalStateException) {}
        assertEquals(1, r.store.outboxAll().size)                          // nothing was created before the refusal
    }

    @Test fun retryPutsAFailedRowBackInTheQueue() {
        val r = ActionsRig()
        r.outbox.edit(HREF, "EDIT")
        val failed = r.store.outboxAll().single().copy(state = OutboxState.Failed, attempts = 3, lastCode = 403)
        r.store.outboxUpdate(failed)
        r.outbox.retry(failed.id)
        val row = r.store.outboxAll().single()
        assertEquals(OutboxState.Pending, row.state); assertEquals(0, row.attempts); assertEquals(0, row.lastCode)
        try { r.outbox.retry(row.id); fail("a pending row cannot be retried") } catch (_: IllegalStateException) {}
    }
}
