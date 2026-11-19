package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Runs only through tools\live-roundtrip.ps1, which makes the throw-away list "bookahtest-a1" first and removes it for good afterwards.
 * Writes only counts and status codes to the private result file. Never prints the server address or the password.
 */
class LiveRoundTripTest {
    private val zone = ZoneId.of("Europe/Athens")
    private fun env(n: String) = System.getenv(n)

    @Test fun createEditConflictResolveDelete() = runBlocking {
        assumeTrue(env("BOOKAH_LIVE_SERVER") != null)
        val slug = env("BOOKAH_LIVE_SLUG")!!
        require(slug.startsWith("bookahtest-")) { "refusing: not a throw-away list" }
        val out = StringBuilder()
        fun note(s: String) { out.appendLine(s) }
        try {
            val api = CalDavClient(Http.newClient(), Session(env("BOOKAH_LIVE_SERVER")!!, env("BOOKAH_LIVE_USER")!!, env("BOOKAH_LIVE_PASSWORD")!!))
            val store = MemoryTaskStore(); val engine = SyncEngine(store, events = false); val outbox = Outbox(store)          // tasks only (Phase 5 added event calendars to the sync)
            val s0 = engine.sync(api); note("sync lists=${s0.lists} errorCode=${s0.errorCode}")
            val list = store.lists().first { it.name == slug }
            suspend fun serverTitle(uid: String): String? {
                val fresh = MemoryTaskStore(); SyncEngine(fresh, events = false).sync(api)
                return TaskParser.parseAll(fresh.allObjects(), zone).firstOrNull { it.uid == uid }?.title
            }

            // 1 create (Greek title with a comma and a semicolon)
            val uid = UUID.randomUUID().toString()
            val title = "Live check Πρώτο, με κόμμα; ΕΛΛΗΝΙΚΑ"
            val href = outbox.create(list, uid, IcsPatcher.newTask(uid, title, Instant.now()))
            val r1 = engine.sync(api); note("create pushed=${r1.pushed} conflicts=${r1.conflicts} failed=${r1.pushFailed} queued=${outbox.count()}")
            assertEquals(1, r1.pushed); assertEquals(title, serverTitle(uid))

            // 2 edit, keeping every other line
            val before = store.objectByHref(href)!!.ics
            outbox.edit(href, IcsPatcher.touch(IcsPatcher.setProperty(before, "SUMMARY", IcsPatcher.escapeText("Live check edited")), Instant.now()))
            val r2 = engine.sync(api); note("edit pushed=${r2.pushed} conflicts=${r2.conflicts} queued=${outbox.count()}")
            assertEquals(1, r2.pushed); assertEquals("Live check edited", serverTitle(uid))

            // 3 a clash: someone else changes the item, then I edit against the old copy
            val theirs = IcsPatcher.touch(IcsPatcher.setProperty(store.objectByHref(href)!!.ics, "SUMMARY", "Other actor"), Instant.now())
            val put = api.put(href, theirs, ifMatch = store.objectByHref(href)!!.etag, createOnly = false); note("other actor put=${put::class.simpleName}")
            outbox.edit(href, IcsPatcher.setProperty(store.objectByHref(href)!!.ics, "SUMMARY", "Mine"))
            val r3 = engine.sync(api); note("clash pushed=${r3.pushed} conflicts=${r3.conflicts}")
            assertEquals(1, r3.conflicts)
            val row = store.outboxAll().single(); assertEquals(OutboxState.Conflict, row.state); assertNotNull(row.serverIcs)

            // 3a keep mine
            outbox.keepMine(row.id)
            val r3a = engine.sync(api); note("keep mine pushed=${r3a.pushed} conflicts=${r3a.conflicts}")
            assertEquals("Mine", serverTitle(uid))

            // 4 a second clash, resolved by keeping the server's copy
            val theirs2 = IcsPatcher.touch(IcsPatcher.setProperty(store.objectByHref(href)!!.ics, "SUMMARY", "Other actor 2"), Instant.now())
            api.put(href, theirs2, ifMatch = store.objectByHref(href)!!.etag, createOnly = false)
            outbox.edit(href, IcsPatcher.setProperty(store.objectByHref(href)!!.ics, "SUMMARY", "Mine 2"))
            val r4 = engine.sync(api); assertEquals(1, r4.conflicts)
            outbox.keepServer(store.outboxAll().single().id)
            val r4a = engine.sync(api); note("keep server queued=${outbox.count()} pushed=${r4a.pushed}")
            assertEquals("Other actor 2", serverTitle(uid)); assertEquals(0, outbox.count())

            // 5 delete
            outbox.delete(href)
            val r5 = engine.sync(api); note("delete pushed=${r5.pushed} conflicts=${r5.conflicts}")
            assertEquals(1, r5.pushed); assertNull(serverTitle(uid))
            note("RESULT: all steps passed")
        } catch (t: Throwable) {
            note("RESULT: FAILED (${t::class.simpleName})")
            throw t
        } finally {
            File(env("BOOKAH_PRIVATE_DIR") ?: ".", "live-roundtrip.txt").writeText(out.toString())
        }
    }
}
