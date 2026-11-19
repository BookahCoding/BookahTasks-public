package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val A = "/cal/tasks/a.ics"
private const val MOVED = "/cal/home/a.ics"

private class MoveRig {
    val dav = FakeDav().also { it.twoLists = true }
    val store = MemoryTaskStore()
    val engine = SyncEngine(store)
    val outbox = Outbox(store) { 1000L }
    val l1: TaskList; val l2: TaskList
    init {
        dav.seed(A, "SERVER-TEXT")
        runBlocking { engine.sync(dav) }
        l1 = store.lists().first { it.href == "/cal/tasks/" }; l2 = store.lists().first { it.href == "/cal/home/" }
    }
    fun sync() = runBlocking { engine.sync(dav) }
}

/** Findings of the whole-branch review of Build B (17 Nov): each test was written first and seen failing. */
class ReviewFixesBTest {
    // C1: the delete half of a move waits for its create; it is never sent when the create did not arrive
    @Test fun theDeleteOfAMoveWaitsForItsCreate() {
        val r = MoveRig()
        r.outbox.move(A, r.l2); r.dav.rejectPutWith = 503
        r.sync()
        assertTrue(r.dav.log.none { it == "DELETE $A" })
        assertNotNull(r.dav.objects[A])
    }

    @Test fun whenTheCreateArrivesTheDeleteFollowsInTheSameRun() {
        val r = MoveRig()
        r.outbox.move(A, r.l2)
        assertEquals(2, r.sync().pushed)
        assertNull(r.dav.objects[A]); assertNotNull(r.dav.objects[MOVED]); assertTrue(r.store.outboxAll().isEmpty())
    }

    // C1: giving up on the refused create of a move puts the original back, so nothing is lost
    @Test fun discardingTheRefusedCreateOfAMoveRestoresTheOriginal() {
        val r = MoveRig()
        r.outbox.move(A, r.l2); r.dav.rejectPutWith = 403
        r.sync()
        val failed = r.store.outboxAll().first { it.kind == OutboxKind.Create }
        assertEquals(OutboxState.Failed, failed.state)
        r.outbox.discard(failed.id)
        assertEquals("SERVER-TEXT", r.store.objectByHref(A)!!.ics); assertNull(r.store.objectByHref(MOVED))
        assertTrue(r.store.outboxAll().isEmpty())
        r.dav.rejectPutWith = null
        r.sync()
        assertNotNull(r.dav.objects[A])
    }

    // C2: undoing a delete keeps the edit that was queued before it
    @Test fun undoOfADeleteKeepsAQueuedEdit() {
        val r = MoveRig()
        r.outbox.edit(A, "EDITED"); r.outbox.delete(A)
        r.outbox.recreate(r.l1.id, A, "EDITED")
        val row = r.store.outboxAll().single()
        assertEquals(OutboxKind.Edit, row.kind); assertEquals("EDITED", row.ics); assertEquals("e1", row.baseEtag)
        r.sync()
        assertEquals("EDITED", r.dav.objects[A]!!.second)
    }

    // I1: undoing a move before anything was sent leaves nothing queued and the original in place
    @Test fun undoOfAMoveBeforeThePushLeavesNothingQueued() {
        val r = MoveRig()
        val moved = r.outbox.move(A, r.l2)
        r.outbox.move(moved, r.l1)
        assertTrue(r.store.outboxAll().isEmpty())
        assertEquals("SERVER-TEXT", r.store.objectByHref(A)!!.ics); assertNull(r.store.objectByHref(MOVED))
        // and the restored task can be edited again
        r.outbox.edit(A, "EDIT")
        assertEquals(OutboxKind.Edit, r.store.outboxAll().single().kind)
    }

    // I2: undoing a delete while the delete is being sent must not lose the task
    @Test fun undoWhileTheDeleteIsBeingSentIsNotLost() {
        val r = MoveRig()
        r.outbox.delete(A)
        r.dav.duringDelete = { r.dav.duringDelete = null; r.outbox.recreate(r.l1.id, A, "SERVER-TEXT") }
        r.sync()
        assertEquals("SERVER-TEXT", r.store.objectByHref(A)!!.ics)          // the same run's pull did not remove it
        assertEquals(OutboxKind.Create, r.store.outboxAll().single().kind)
        r.sync()
        assertNotNull(r.dav.objects[A])
    }
}
