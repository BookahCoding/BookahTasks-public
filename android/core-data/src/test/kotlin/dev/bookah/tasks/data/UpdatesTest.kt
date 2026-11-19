package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test fun versionsParseAndCompare() {
        assertEquals(listOf(1, 0, 0), parseVersion("v1.0.0")); assertEquals(listOf(0, 8, 12), parseVersion("0.8.12"))
        assertNull(parseVersion("1.0")); assertNull(parseVersion("v1.0.0-rc1")); assertNull(parseVersion("latest"))
        assertTrue(isNewerVersion(listOf(1, 0, 0), listOf(0, 8, 0))); assertTrue(isNewerVersion(listOf(0, 10, 0), listOf(0, 9, 5)))
        assertFalse(isNewerVersion(listOf(1, 0, 0), listOf(1, 0, 0))); assertFalse(isNewerVersion(listOf(0, 9, 0), listOf(1, 0, 0)))
    }

    private fun check(body: String, current: String, code: Int = 200, repo: String = "owner/name"): Pair<UpdateResult, okhttp3.mockwebserver.RecordedRequest?> {
        MockWebServer().use { s ->
            s.enqueue(MockResponse().setResponseCode(code).setBody(body))
            s.start()
            val r = runBlocking { UpdateChecker(OkHttpClient(), s.url("/").toString().trimEnd('/')).check(repo, current) }
            return r to s.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS)      // null when the checker refused before asking
        }
    }

    private fun rel(tag: String, draft: Boolean = false, url: String = "https://github.com/owner/name/releases/tag/$tag") =
        """{"tag_name":"$tag","html_url":"$url","draft":$draft,"prerelease":false,"assets":[]}"""

    @Test fun offersTheHighestNewerVersion() {
        val (r, req) = check("[${rel("v0.8.0")},${rel("v1.0.1")},${rel("v1.0.0")}]", "1.0.0")
        assertEquals(UpdateResult.Available("1.0.1", "https://github.com/owner/name/releases/tag/v1.0.1"), r)
        assertEquals("/repos/owner/name/releases?per_page=30", req!!.path)
    }

    @Test fun sendsNothingAboutTheUser() {
        val (_, req) = check("[]", "1.0.0")
        val names = req!!.headers.names().map { it.lowercase() }.toSet()
        assertTrue(names.all { it in setOf("user-agent", "accept", "host", "connection", "accept-encoding") })
        assertEquals(Http.USER_AGENT, req.getHeader("User-Agent"))
    }

    @Test fun upToDateWhenNothingIsNewer() {
        assertEquals(UpdateResult.UpToDate("1.0.0"), check("[${rel("v1.0.0")},${rel("v0.9.0")}]", "1.0.0").first)
        assertEquals(UpdateResult.UpToDate("1.0.0"), check("[]", "1.0.0").first)
    }

    @Test fun draftsOddTagsAndForeignPagesAreIgnored() {
        val body = "[${rel("v2.0.0", draft = true)},${rel("nightly")},${rel("v3.0.0", url = "https://evil.example/x")}]"
        assertEquals(UpdateResult.UpToDate("1.0.0"), check(body, "1.0.0").first)
    }

    @Test fun errorsAreReported() {
        assertEquals(UpdateResult.Failed, check("{}", "1.0.0", code = 403).first)
        assertEquals(UpdateResult.Failed, check("not json", "1.0.0").first)
        val bad = check("[]", "1.0.0", repo = "bad repo")
        assertEquals(UpdateResult.Failed, bad.first); assertNull("no request for a malformed repository name", bad.second)
        assertEquals(UpdateResult.Failed, check("[]", "dev").first)
    }

    @Test fun anUnreachableServerIsReported() {
        val r = runBlocking { UpdateChecker(OkHttpClient(), "http://127.0.0.1:1").check("owner/name", "1.0.0") }
        assertEquals(UpdateResult.Unreachable, r)
    }
}
