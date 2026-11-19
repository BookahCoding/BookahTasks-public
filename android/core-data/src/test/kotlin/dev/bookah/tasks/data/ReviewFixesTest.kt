package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val A = "/cal/tasks/a.ics"
private const val B = "/cal/tasks/b.ics"
private fun ics(summary: String, uid: String = "a") =
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:$uid\r\nSUMMARY:$summary\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

private class FixRig {
    val dav = FakeDav()
    val store = MemoryTaskStore()
    val engine = SyncEngine(store)
    val outbox = Outbox(store) { 1000L }
    val list: TaskList
    init {
        dav.seed(A, ics("A-1")); dav.seed(B, ics("B-1", "b"))
        runBlocking { engine.sync(dav) }
        list = store.lists().single()
    }
    fun sync() = runBlocking { engine.sync(dav) }
}

/** Findings of the whole-branch review (17 Nov): each test was written first and seen failing. */
class ReviewFixesTest {
    // review 1: a delete made while an edit of the same item is being sent must not be undone
    @Test fun deleteDuringAnEditPushStaysDeleted() {
        val r = FixRig()
        r.outbox.edit(A, ics("A-2"))
        r.dav.duringPut = { r.dav.duringPut = null; r.outbox.delete(A) }
        r.sync()
        assertNull(r.store.objectByHref(A))                                // the same run's pull did not bring it back
        assertEquals(OutboxKind.Delete, r.store.outboxAll().single().kind)
        assertEquals(1, r.sync().pushed)
        assertNull(r.dav.objects[A])
    }

    // review 2: deleting a task whose create is being sent must still end up deleted on the server
    @Test fun deleteOfAJustCreatedTaskDuringItsSendIsSentAsADelete() {
        val r = FixRig()
        val href = r.outbox.create(r.list, "n1", ics("New", "n1"))
        r.dav.duringPut = { r.dav.duringPut = null; r.outbox.delete(href) }
        r.sync()
        assertNull(r.store.objectByHref(href))
        assertEquals(OutboxKind.Delete, r.store.outboxAll().single().kind)
        assertEquals(1, r.sync().pushed)
        assertNull(r.dav.objects[href])
    }

    // review 3: a row changed after the push began is read again just before it is sent
    @Test fun aRowChangedDuringThePushIsNotSentFromTheOldCopy() {
        val r = FixRig()
        r.outbox.edit(A, ics("A-2")); r.outbox.edit(B, ics("B-2", "b"))
        r.dav.duringPut = { r.dav.duringPut = null; r.outbox.delete(B) }  // while A is being sent, the owner deletes B
        r.sync()
        assertTrue(r.dav.log.none { it == "PUT $B" })
        assertNull(r.dav.objects[B])
    }

    // review 4: a failing follow-up request must end the push as an error report, never escape
    @Test fun aFailedEtagLookupAfterAWriteDoesNotEscapeTheSync() {
        val r = FixRig(); r.dav.noEtagOnPut = true; r.dav.getFails[A] = 503
        r.outbox.edit(A, ics("A-2"))
        val rep = r.sync()
        assertEquals(503, rep.errorCode)
        assertEquals(1, r.store.outboxAll().size)
    }

    // review 5: a write that reached the server but whose answer was lost is not a conflict next time
    @Test fun aLostAnswerIsNotAFalseConflict() {
        val r = FixRig(); r.dav.dropAnswerOnce = true
        r.outbox.edit(A, ics("A-2"))
        assertTrue(r.sync().offline)
        val rep = r.sync()
        assertEquals(0, rep.conflicts); assertEquals(1, rep.pushed)
        assertTrue(r.store.outboxAll().isEmpty())
        assertEquals(r.dav.objects[A]!!.first, r.store.objectByHref(A)!!.etag)
    }

    @Test fun aLostAnswerOnACreateDoesNotMakeADuplicateConflict() {
        val r = FixRig(); r.dav.dropAnswerOnce = true
        val href = r.outbox.create(r.list, "n1", ics("New", "n1"))
        assertTrue(r.sync().offline)
        val rep = r.sync()
        assertEquals(0, rep.conflicts); assertEquals(1, rep.pushed)
        assertTrue(r.store.outboxAll().isEmpty())
        assertEquals(r.dav.objects[href]!!.first, r.store.objectByHref(href)!!.etag)
    }

    // review 7: "keep both" on a repeating task (completed copies carry the same UID)
    @Test fun keepBothRewritesTheUidInEveryPartOfTheItem() {
        val withCopy = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:a\r\nRECURRENCE-ID:20261109T080000Z\r\nSUMMARY:Copy\r\nEND:VTODO\r\n" +
            "BEGIN:VTODO\r\nUID:a\r\nSUMMARY:Series\r\nRRULE:FREQ=WEEKLY\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"
        val r = FixRig()
        r.outbox.edit(A, withCopy); r.dav.seed(A, ics("Theirs")); r.sync()
        val newHref = r.outbox.keepBoth(r.store.outboxAll().single().id, "new-uid")
        val mine = r.store.objectByHref(newHref)!!.ics
        assertEquals(0, Regex("UID:a\r\n").findAll(mine).count())
        assertEquals(2, Regex("UID:new-uid\r\n").findAll(mine).count())
    }

    // review 8: after "keep the server's" the next pull must refetch the item
    @Test fun keepingTheServersCopyClearsTheSyncToken() {
        val r = FixRig()
        r.outbox.edit(A, ics("Mine")); r.dav.seed(A, ics("Theirs")); r.sync()
        r.outbox.keepServer(r.store.outboxAll().single().id)
        assertNull(r.store.lists().single().syncToken)
    }

    // review 9: one item whose server copy cannot be fetched must not block the others
    @Test fun aFailedLookupOnOneConflictDoesNotBlockTheRestOfTheQueue() {
        val r = FixRig()
        r.outbox.edit(A, ics("A-mine")); r.outbox.edit(B, ics("B-mine", "b"))
        r.dav.seed(A, ics("A-theirs")); r.dav.getFails[A] = 503
        val rep = r.sync()
        assertEquals(0, rep.errorCode); assertEquals(1, rep.pushed); assertEquals(1, rep.pushFailed)
        assertEquals("B-mine", IcsPatcher.readProperty(r.dav.objects[B]!!.second, "SUMMARY"))
        assertEquals(OutboxState.Pending, r.store.outboxAll().single().state)       // A stays queued and is tried again
    }

    // review 11: the queue row exists before the local copy does
    @Test fun aCreateKilledBetweenTheTwoWritesStillHasItsRow() {
        val store = MemoryTaskStore()
        val list = store.replaceLists(listOf(RemoteList("/cal/tasks/", "Tasks", "#fff", true, ListKind.Tasks, 0))).single()
        val failing = object : TaskStore by store {
            override fun upsertObjects(listId: Long, objects: List<RemoteObject>) = throw IllegalStateException("process killed")
        }
        try { Outbox(failing).create(list, "n1", ics("New", "n1")) } catch (_: IllegalStateException) {}
        assertEquals(OutboxKind.Create, store.outboxAll().single().kind)
    }

    // review 6: a redirected write is a refusal, never a success
    @Test fun aRedirectedWriteIsRefusedAndNotFollowed() {
        MockWebServer().use { s ->
            s.start()
            val c = CalDavClient(Http.newClient(), Session(s.url("/").toString().trimEnd('/'), "u", "p"))
            s.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/elsewhere/a.ics"))
            assertEquals(WriteResult.Rejected(301), runBlocking { c.put("/cal/tasks/a.ics", "T", "e1", false) })
            s.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/elsewhere/a.ics"))
            assertEquals(WriteResult.Rejected(302), runBlocking { c.delete("/cal/tasks/a.ics", "e1") })
            assertEquals(2, s.requestCount)
        }
    }
}
