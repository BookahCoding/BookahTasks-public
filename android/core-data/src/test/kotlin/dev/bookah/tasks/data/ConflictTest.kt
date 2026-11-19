package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HREF = "/cal/tasks/a.ics"
private fun ics(summary: String, uid: String = "a") =
    "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:$uid\r\nSUMMARY:$summary\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

private class ConflictRig {
    val dav = FakeDav()
    val store = MemoryTaskStore()
    val engine = SyncEngine(store)
    val outbox = Outbox(store) { 1000L }
    val list: TaskList
    init { dav.seed(HREF, ics("Original")); runBlocking { engine.sync(dav) }; list = store.lists().single() }
    fun sync() = runBlocking { engine.sync(dav) }
    /** An edit of mine against a server copy that someone else changed: leaves one Conflict row. */
    fun conflict(): OutboxItem {
        outbox.edit(HREF, ics("Mine")); dav.seed(HREF, ics("Theirs")); sync()
        return store.outboxAll().single().also { assertEquals(OutboxState.Conflict, it.state) }
    }
    fun summary(text: String?) = text?.let { IcsPatcher.readProperty(it, "SUMMARY") }
}

class ConflictTest {
    @Test fun keepMineSendsMyVersionAgainstTheServersCurrentEtag() {
        val r = ConflictRig(); val row = r.conflict()
        r.outbox.keepMine(row.id)
        assertEquals(OutboxState.Pending, r.store.outboxAll().single().state)
        assertEquals(1, r.sync().pushed)
        assertEquals("Mine", r.summary(r.dav.objects[HREF]!!.second))
    }

    @Test fun keepServerDropsMyChangeAndTakesTheServersCopy() {
        val r = ConflictRig(); val row = r.conflict()
        r.outbox.keepServer(row.id)
        assertTrue(r.store.outboxAll().isEmpty())
        assertEquals("Theirs", r.summary(r.store.objectByHref(HREF)!!.ics))
        assertEquals(r.dav.objects[HREF]!!.first, r.store.objectByHref(HREF)!!.etag)
        assertEquals(0, r.sync().pushed)
    }

    @Test fun keepBothRestoresTheServersCopyAndCreatesMineAsANewTask() {
        val r = ConflictRig(); val row = r.conflict()
        val newHref = r.outbox.keepBoth(row.id, "new-uid")
        assertEquals("/cal/tasks/new-uid.ics", newHref)
        assertEquals("Theirs", r.summary(r.store.objectByHref(HREF)!!.ics))
        assertEquals("Mine", r.summary(r.store.objectByHref(newHref)!!.ics))
        assertEquals("new-uid", IcsPatcher.readProperty(r.store.objectByHref(newHref)!!.ics, "UID"))
        assertEquals(1, r.sync().pushed)
        assertEquals("Mine", r.summary(r.dav.objects[newHref]!!.second)); assertEquals("Theirs", r.summary(r.dav.objects[HREF]!!.second))
    }

    @Test fun anEditWhoseItemWasDeletedOnTheServerCanBeKeptAsACreate() {
        val r = ConflictRig()
        r.outbox.edit(HREF, ics("Mine")); r.dav.objects.remove(HREF); r.sync()
        val row = r.store.outboxAll().single()
        assertNull(row.serverIcs)
        r.outbox.keepMine(row.id)
        assertEquals(OutboxKind.Create, r.store.outboxAll().single().kind)
        assertEquals(1, r.sync().pushed)
        assertEquals("Mine", r.summary(r.dav.objects[HREF]!!.second))
    }

    @Test fun keepServerForAnItemGoneOnTheServerRemovesItHere() {
        val r = ConflictRig()
        r.outbox.edit(HREF, ics("Mine")); r.dav.objects.remove(HREF); r.sync()
        r.outbox.keepServer(r.store.outboxAll().single().id)
        assertNull(r.store.objectByHref(HREF)); assertTrue(r.store.outboxAll().isEmpty())
    }

    @Test fun aDeleteThatMetAChangedServerCopyCanBeKeptOrCancelled() {
        val r = ConflictRig()
        r.outbox.delete(HREF); r.dav.seed(HREF, ics("Theirs")); r.sync()
        val row = r.store.outboxAll().single()
        assertEquals(OutboxKind.Delete, row.kind); assertEquals(OutboxState.Conflict, row.state)
        r.outbox.keepServer(row.id)                                       // cancel my delete: the server's copy comes back
        assertEquals("Theirs", r.summary(r.store.objectByHref(HREF)!!.ics))
        r.outbox.delete(HREF); r.dav.seed(HREF, ics("Theirs 2")); r.sync()
        r.outbox.keepMine(r.store.outboxAll().single().id)                // insist on deleting
        assertEquals(1, r.sync().pushed)
        assertNull(r.dav.objects[HREF])
    }

    @Test fun discardUndoesAFailedCreateAndRestoresAFailedEdit() {
        val r = ConflictRig(); r.dav.rejectWith = 403
        val href = r.outbox.create(r.list, "n1", ics("New", "n1")); r.outbox.edit(HREF, ics("Mine"))
        r.sync()
        assertEquals(2, r.store.outboxAll().count { it.state == OutboxState.Failed })
        r.store.outboxAll().forEach { r.outbox.discard(it.id) }
        assertTrue(r.store.outboxAll().isEmpty())
        assertNull(r.store.objectByHref(href))
        r.dav.rejectWith = null
        r.sync()                                                           // the cleared sync token makes this a full fetch
        assertEquals("Original", r.summary(r.store.objectByHref(HREF)!!.ics))
    }
}
