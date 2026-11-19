package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HREF = "/cal/tasks/a.ics"

private class Rig {
    val dav = FakeDav()
    val store = MemoryTaskStore()
    val engine = SyncEngine(store)
    val outbox = Outbox(store) { 1000L }
    val list: TaskList
    init {
        dav.seed(HREF, "SERVER-1")
        runBlocking { engine.sync(dav) }                           // first pull: the phone knows the list and the item
        list = store.lists().single()
    }
    fun sync() = runBlocking { engine.sync(dav) }
}

class PushTest {
    @Test fun aNewTaskIsCreatedWithIfNoneMatchAndTheRowIsRemoved() {
        val r = Rig()
        val href = r.outbox.create(r.list, "n1", "NEW-1")
        val rep = r.sync()
        assertEquals(1, rep.pushed); assertEquals(0, rep.conflicts)
        assertEquals("NEW-1", r.dav.objects[href]!!.second)
        assertTrue(r.store.outboxAll().isEmpty())
        assertEquals(r.dav.objects[href]!!.first, r.store.objectByHref(href)!!.etag)
    }

    @Test fun anEditIsSentAgainstTheStoredEtagAndTheNewEtagIsKept() {
        val r = Rig()
        r.outbox.edit(HREF, "EDIT-1")
        assertEquals(1, r.sync().pushed)
        assertEquals("EDIT-1", r.dav.objects[HREF]!!.second)
        assertEquals(r.dav.objects[HREF]!!.first, r.store.objectByHref(HREF)!!.etag)
        r.outbox.edit(HREF, "EDIT-2")                              // a second edit right after must not look like a conflict
        val rep = r.sync()
        assertEquals(1, rep.pushed); assertEquals(0, rep.conflicts)
        assertEquals("EDIT-2", r.dav.objects[HREF]!!.second)
    }

    @Test fun aDeleteRemovesTheItemOnTheServer() {
        val r = Rig()
        r.outbox.delete(HREF)
        assertEquals(1, r.sync().pushed)
        assertNull(r.dav.objects[HREF]); assertNull(r.store.objectByHref(HREF))
    }

    @Test fun missingEtagIsFetched() {
        val r = Rig(); r.dav.noEtagOnPut = true
        r.outbox.edit(HREF, "EDIT-1"); r.sync()
        assertEquals(r.dav.objects[HREF]!!.first, r.store.objectByHref(HREF)!!.etag)
        r.outbox.edit(HREF, "EDIT-2")
        assertEquals(0, r.sync().conflicts)
    }

    @Test fun offlineKeepsEverythingQueued() {
        val r = Rig(); r.dav.offline = true
        r.outbox.edit(HREF, "EDIT-1"); r.outbox.create(r.list, "n1", "NEW-1")
        val rep = r.sync()
        assertTrue(rep.offline); assertEquals(0, rep.pushed)
        assertEquals(2, r.store.outboxAll().size)
        assertTrue(r.store.outboxAll().all { it.state == OutboxState.Pending })
        assertEquals("EDIT-1", r.store.objectByHref(HREF)!!.ics)
        r.dav.offline = false
        assertEquals(2, r.sync().pushed)
    }

    @Test fun wrongPasswordStopsPushWithoutFailingRows() {
        val r = Rig(); r.dav.authFails = true
        r.outbox.edit(HREF, "EDIT-1")
        val rep = r.sync()
        assertEquals(401, rep.errorCode)
        assertEquals(OutboxState.Pending, r.store.outboxAll().single().state)
    }

    @Test fun aChangedServerCopyBecomesAConflictAndNothingIsOverwritten() {
        val r = Rig()
        r.outbox.edit(HREF, "MINE")
        r.dav.seed(HREF, "THEIRS")                                  // someone else changed it meanwhile
        val rep = r.sync()
        assertEquals(1, rep.conflicts); assertEquals(0, rep.pushed)
        val row = r.store.outboxAll().single()
        assertEquals(OutboxState.Conflict, row.state); assertEquals("THEIRS", row.serverIcs); assertNotNull(row.serverEtag)
        assertEquals("THEIRS", r.dav.objects[HREF]!!.second)
        assertEquals("MINE", r.store.objectByHref(HREF)!!.ics)
    }

    @Test fun aServerCopyThatIsGoneIsReportedAsAConflictWithNoServerText() {
        val r = Rig()
        r.outbox.edit(HREF, "MINE")
        r.dav.objects.remove(HREF)
        assertEquals(1, r.sync().conflicts)
        assertNull(r.store.outboxAll().single().serverIcs)
    }

    @Test fun serverTroubleKeepsTheRowForRetryAndARefusalMarksItFailed() {
        val r = Rig()
        r.outbox.edit(HREF, "EDIT-1"); r.dav.rejectWith = 503
        var rep = r.sync()
        assertEquals(1, rep.pushFailed)
        var row = r.store.outboxAll().single()
        assertEquals(OutboxState.Pending, row.state); assertEquals(1, row.attempts); assertEquals(503, row.lastCode)
        r.dav.rejectWith = 403
        rep = r.sync()
        row = r.store.outboxAll().single()
        assertEquals(OutboxState.Failed, row.state); assertEquals(403, row.lastCode)
        r.dav.rejectWith = null
        assertEquals(0, r.sync().pushed)                           // a failed row waits for the owner (Build B: retry or discard)
    }

    @Test fun editDuringPushIsKept() {
        val r = Rig()
        r.outbox.edit(HREF, "EDIT-1")
        r.dav.duringPut = { r.dav.duringPut = null; r.outbox.edit(HREF, "EDIT-2") }
        assertEquals(1, r.sync().pushed)
        val row = r.store.outboxAll().single()                      // the newer edit is still queued, against the new etag
        assertEquals(OutboxKind.Edit, row.kind); assertEquals("EDIT-2", row.ics); assertEquals(r.dav.objects[HREF]!!.first, row.baseEtag)
        assertEquals("EDIT-2", r.store.objectByHref(HREF)!!.ics)
        assertEquals(1, r.sync().pushed)
        assertEquals("EDIT-2", r.dav.objects[HREF]!!.second)
    }

    @Test fun pullDoesNotOverwriteQueuedEdit() {
        val r = Rig(); r.dav.rejectWith = 503
        val other = "/cal/tasks/other.ics"
        r.dav.seed(other, "X"); r.sync()                            // the phone learns about a second item
        r.outbox.edit(HREF, "MINE"); r.outbox.delete(other)
        r.sync()                                                    // push is refused (503), then the pull runs and offers both server copies
        assertEquals("MINE", r.store.objectByHref(HREF)!!.ics)             // the pull saw SERVER-1 but left the queued edit alone
        assertNull(r.store.objectByHref(other))                            // and did not bring back the queued delete
    }

    @Test fun fullResyncKeepsQueuedItems() {
        val r = Rig(); r.dav.rejectWith = 503
        val href = r.outbox.create(r.list, "n1", "NEW-1")
        r.outbox.edit(HREF, "MINE")
        r.store.setSyncToken(r.list.id, null)                       // the server refused the token: the next pull is a full fetch
        r.sync()
        assertEquals("NEW-1", r.store.objectByHref(href)!!.ics)
        assertEquals("MINE", r.store.objectByHref(HREF)!!.ics)
        assertEquals(2, r.store.outboxAll().size)
    }

    @Test fun rowsOfOneItemGoInOrderAndAConflictBlocksOnlyItsOwnItem() {
        val r = Rig()
        r.dav.seed("/cal/tasks/b.ics", "B-1"); r.sync()
        r.outbox.edit(HREF, "A-MINE"); r.outbox.edit("/cal/tasks/b.ics", "B-MINE")
        r.dav.seed(HREF, "A-THEIRS")
        val rep = r.sync()
        assertEquals(1, rep.conflicts); assertEquals(1, rep.pushed)
        assertEquals("B-MINE", r.dav.objects["/cal/tasks/b.ics"]!!.second)
    }
}
