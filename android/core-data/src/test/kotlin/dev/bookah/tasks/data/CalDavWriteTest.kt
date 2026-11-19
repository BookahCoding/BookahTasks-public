package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

private fun withServer(block: (MockWebServer, CalDavClient) -> Unit) {
    MockWebServer().use { s ->
        s.start()
        block(s, CalDavClient(Http.newClient(), Session(s.url("/").toString().trimEnd('/'), "u", "p")))
    }
}

class CalDavWriteTest {
    @Test fun creatingSendsIfNoneMatchStarAndReadsTheEtag() = withServer { s, c ->
        s.enqueue(MockResponse().setResponseCode(201).setHeader("ETag", "\"abc\""))
        val r = runBlocking { c.put("/cal/tasks/a.ics", "TEXT", ifMatch = null, createOnly = true) }
        assertEquals(WriteResult.Ok("abc"), r)
        val req = s.takeRequest()
        assertEquals("PUT", req.method); assertEquals("/cal/tasks/a.ics", req.path)
        assertEquals("*", req.getHeader("If-None-Match")); assertNull(req.getHeader("If-Match"))
        assertEquals("TEXT", req.body.readUtf8())
        assertEquals("text/calendar; charset=utf-8", req.getHeader("Content-Type"))
    }

    @Test fun editingSendsIfMatchWithQuotes() = withServer { s, c ->
        s.enqueue(MockResponse().setResponseCode(204))
        val r = runBlocking { c.put("/cal/tasks/a.ics", "TEXT", ifMatch = "e1", createOnly = false) }
        assertEquals(WriteResult.Ok(null), r)
        val req = s.takeRequest()
        assertEquals("\"e1\"", req.getHeader("If-Match")); assertNull(req.getHeader("If-None-Match"))
    }

    @Test fun answersAreMappedToResults() = withServer { s, c ->
        s.enqueue(MockResponse().setResponseCode(412)); s.enqueue(MockResponse().setResponseCode(403)); s.enqueue(MockResponse().setResponseCode(503))
        assertEquals(WriteResult.PreconditionFailed, runBlocking { c.put("/a.ics", "T", "e1", false) })
        assertEquals(WriteResult.Rejected(403), runBlocking { c.put("/a.ics", "T", "e1", false) })
        assertEquals(WriteResult.Rejected(503), runBlocking { c.put("/a.ics", "T", "e1", false) })
    }

    @Test fun wrongPasswordThrows() = withServer { s, c ->
        s.enqueue(MockResponse().setResponseCode(401))
        try { runBlocking { c.put("/a.ics", "T", "e1", false) }; fail("expected CalDavException") } catch (e: CalDavException) { assertEquals(401, e.code) }
    }

    @Test fun deleteUsesIfMatchAndTreatsMissingAsDone() = withServer { s, c ->
        s.enqueue(MockResponse().setResponseCode(204)); s.enqueue(MockResponse().setResponseCode(404)); s.enqueue(MockResponse().setResponseCode(412))
        assertEquals(WriteResult.Ok(null), runBlocking { c.delete("/cal/tasks/a.ics", "e1") })
        val req = s.takeRequest()
        assertEquals("DELETE", req.method); assertEquals("\"e1\"", req.getHeader("If-Match")); assertNull(req.getHeader("X-NC-CalDAV-No-Trashbin"))
        assertEquals(WriteResult.Ok(null), runBlocking { c.delete("/cal/tasks/a.ics", "e1") })
        assertEquals(WriteResult.PreconditionFailed, runBlocking { c.delete("/cal/tasks/a.ics", "e1") })
    }

    @Test fun getReturnsTheObjectOrNull() = withServer { s, c ->
        s.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"e7\"").setBody("SERVER-TEXT"))
        s.enqueue(MockResponse().setResponseCode(404))
        assertEquals(RemoteObject("/cal/tasks/a.ics", "e7", "SERVER-TEXT"), runBlocking { c.get("/cal/tasks/a.ics") })
        assertNull(runBlocking { c.get("/cal/tasks/a.ics") })
    }
}
