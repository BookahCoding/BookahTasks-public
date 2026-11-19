package dev.bookah.tasks.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** How a write ended. [Ok.etag] has no quotes and is null when the server sent none. */
sealed interface WriteResult {
    data class Ok(val etag: String?) : WriteResult
    /** 412: the item is not as the change expected (changed or gone on the server, or already there for a create). */
    data object PreconditionFailed : WriteResult
    /** Any other refusal except 401 (which throws): 403, 5xx, ... */
    data class Rejected(val code: Int) : WriteResult
}

/** What the sync engine needs from a CalDAV server; tests use a fake. A wrong password (401) throws [CalDavException]; network trouble throws IOException. */
interface CalDavApi {
    suspend fun listLists(): List<RemoteList>
    suspend fun syncCollection(listHref: String, token: String?): SyncDelta
    suspend fun multiget(listHref: String, hrefs: List<String>): List<RemoteObject>

    /** Creates ([createOnly], `If-None-Match: *`) or replaces ([ifMatch], `If-Match: "<etag>"`) one item. */
    suspend fun put(href: String, ics: String, ifMatch: String?, createOnly: Boolean): WriteResult

    /** Deletes one item into Nextcloud's trash bin. An item that is already gone counts as done. */
    suspend fun delete(href: String, ifMatch: String?): WriteResult

    /** The server's current copy of one item, or null when it is gone. */
    suspend fun get(href: String): RemoteObject?
}

/**
 * The CalDAV requests the app needs: list the collections, ask what changed since a sync token, fetch objects (PROPFIND and REPORT),
 * and write one item at a time (PUT, DELETE, GET), always with an ETag precondition so nothing is overwritten by accident.
 */
class CalDavClient(private val http: OkHttpClient, private val session: Session) : CalDavApi {
    private val xmlType = "application/xml; charset=utf-8".toMediaType()
    private val base = session.server.toHttpUrl()

    override suspend fun listLists(): List<RemoteList> {
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:a="http://apple.com/ns/ical/"><d:prop>
            <d:displayname/><d:resourcetype/><a:calendar-color/><a:calendar-order/><c:supported-calendar-component-set/><d:current-user-privilege-set/></d:prop></d:propfind>"""
        val home = "/remote.php/dav/calendars/${session.user}/"
        return parseListCollections(send("PROPFIND", home, body, depth = "1"))
    }

    override suspend fun syncCollection(listHref: String, token: String?): SyncDelta {
        val body = """<?xml version="1.0"?><d:sync-collection xmlns:d="DAV:"><d:sync-token>${token.orEmpty()}</d:sync-token><d:sync-level>1</d:sync-level><d:prop><d:getetag/></d:prop></d:sync-collection>"""
        return parseSyncDelta(send("REPORT", listHref, body, depth = null, tokenRequest = token != null))
    }

    override suspend fun multiget(listHref: String, hrefs: List<String>): List<RemoteObject> {
        if (hrefs.isEmpty()) return emptyList()
        val list = hrefs.joinToString("") { "<d:href>${xmlEscape(it)}</d:href>" }
        val body = """<?xml version="1.0"?><c:calendar-multiget xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:prop><d:getetag/><c:calendar-data/></d:prop>$list</c:calendar-multiget>"""
        return parseMultiget(send("REPORT", listHref, body, depth = "1"))
    }

    private val calendarType = "text/calendar; charset=utf-8".toMediaType()

    /** Writes never follow a redirect: OkHttp would turn a redirected PUT or DELETE into a GET, which "succeeds" without writing anything. */
    private val writeHttp = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private fun requestFor(href: String): Request.Builder {
        val url = base.resolve(href) ?: throw CalDavException(0, "Bad address: $href")
        return Request.Builder().url(url).header("Authorization", session.authHeader).header("User-Agent", Http.USER_AGENT)
    }

    override suspend fun put(href: String, ics: String, ifMatch: String?, createOnly: Boolean): WriteResult = withContext(Dispatchers.IO) {
        val req = requestFor(href).put(ics.toRequestBody(calendarType))
            .apply { if (createOnly) header("If-None-Match", "*"); if (ifMatch != null) header("If-Match", "\"$ifMatch\"") }.build()
        writeHttp.newCall(req).execute().use { resp ->
            when {
                resp.code in 200..299 -> WriteResult.Ok(resp.header("ETag")?.let { cleanEtag(it) })
                resp.code == 412 -> WriteResult.PreconditionFailed
                resp.code == 401 -> throw CalDavException(401, "The server did not accept the user name or app password.")
                else -> WriteResult.Rejected(resp.code)
            }
        }
    }

    override suspend fun delete(href: String, ifMatch: String?): WriteResult = withContext(Dispatchers.IO) {
        // no trash-bin bypass header, ever: deleted tasks go to Nextcloud's trash bin
        val req = requestFor(href).delete().apply { if (ifMatch != null) header("If-Match", "\"$ifMatch\"") }.build()
        writeHttp.newCall(req).execute().use { resp ->
            when {
                resp.code in 200..299 || resp.code == 404 -> WriteResult.Ok(null)
                resp.code == 412 -> WriteResult.PreconditionFailed
                resp.code == 401 -> throw CalDavException(401, "The server did not accept the user name or app password.")
                else -> WriteResult.Rejected(resp.code)
            }
        }
    }

    override suspend fun get(href: String): RemoteObject? = withContext(Dispatchers.IO) {
        writeHttp.newCall(requestFor(href).get().build()).execute().use { resp ->
            when {
                resp.code == 404 -> null
                resp.code == 401 -> throw CalDavException(401, "The server did not accept the user name or app password.")
                resp.code != 200 -> throw CalDavException(resp.code, "The server answered ${resp.code}.")
                else -> RemoteObject(href, resp.header("ETag")?.let { cleanEtag(it) } ?: throw CalDavException(0, "The server sent no ETag."), resp.body.string())
            }
        }
    }

    private suspend fun send(method: String, path: String, body: String, depth: String?, tokenRequest: Boolean = false): String = withContext(Dispatchers.IO) {
        val url = base.resolve(path) ?: throw CalDavException(0, "Bad address: $path")
        val req = Request.Builder().url(url)
            .method(method, body.toRequestBody(xmlType))
            .header("Authorization", session.authHeader)
            .header("User-Agent", Http.USER_AGENT)
            .apply { if (depth != null) header("Depth", depth) }
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body.string()
            when {
                resp.code == 207 -> text
                tokenRequest && (resp.code == 403 || resp.code == 409) -> throw SyncTokenInvalidException()
                resp.code == 401 -> throw CalDavException(401, "The server did not accept the user name or app password.")
                resp.code == 404 -> throw CalDavException(404, "The list address was not found on the server.")
                else -> throw CalDavException(resp.code, "The server answered ${resp.code}.")
            }
        }
    }

    companion object {
        private const val DAV = "DAV:"
        private const val CALDAV = "urn:ietf:params:xml:ns:caldav"
        private const val APPLE = "http://apple.com/ns/ical/"

        /**
         * Every live calendar collection that holds tasks and/or events. Subscriptions, the scheduling inbox and outbox, the trash bin
         * and calendars sitting in Nextcloud's trash (resource type "deleted-calendar") are left out.
         */
        fun parseListCollections(xml: String): List<RemoteList> {
            val out = ArrayList<RemoteList>()
            for (resp in responses(xml)) {
                val prop = okProp(resp) ?: continue
                val resourceType = child(prop, DAV, "resourcetype") ?: continue
                if (child(resourceType, CALDAV, "calendar") == null) continue
                if (descendants(resourceType).any { it.localName == "deleted-calendar" }) continue
                val comps = child(prop, CALDAV, "supported-calendar-component-set")
                val names = if (comps == null) listOf("VEVENT") else children(comps, CALDAV, "comp").map { it.getAttribute("name").uppercase() }
                val todo = "VTODO" in names
                val event = "VEVENT" in names
                if (!todo && !event) continue
                val kind = when { todo && event -> ListKind.Both; todo -> ListKind.Tasks; else -> ListKind.Events }
                val href = text(resp, DAV, "href") ?: continue
                val privs = child(prop, DAV, "current-user-privilege-set")
                    ?.let { p -> descendants(p).map { it.localName } }.orEmpty().toSet()
                val canWrite = "all" in privs || "write" in privs || "write-content" in privs || "bind" in privs
                val order = text(prop, APPLE, "calendar-order")?.trim()?.toIntOrNull() ?: 0
                out.add(
                    RemoteList(
                        href, text(prop, DAV, "displayname")?.ifBlank { null } ?: href.trimEnd('/').substringAfterLast('/'),
                        normalizeColor(text(prop, APPLE, "calendar-color")), canWrite, kind, order,
                    ),
                )
            }
            return out
        }

        fun parseSyncDelta(xml: String): SyncDelta {
            val doc = parse(xml)
            val token = doc.documentElement.let { text(it, DAV, "sync-token") }
            val changed = ArrayList<ChangedEntry>()
            val removed = ArrayList<String>()
            for (resp in responses(doc)) {
                val href = text(resp, DAV, "href") ?: continue
                if (href.endsWith("/")) continue                               // the collection itself
                val status = text(resp, DAV, "status")
                if (status != null && " 404 " in status) { removed.add(href); continue }
                val prop = okProp(resp) ?: continue
                changed.add(ChangedEntry(href, cleanEtag(text(prop, DAV, "getetag").orEmpty())))
            }
            return SyncDelta(token, changed, removed)
        }

        fun parseMultiget(xml: String): List<RemoteObject> {
            val out = ArrayList<RemoteObject>()
            for (resp in responses(xml)) {
                val href = text(resp, DAV, "href") ?: continue
                val prop = okProp(resp) ?: continue
                val data = text(prop, CALDAV, "calendar-data") ?: continue
                out.add(RemoteObject(href, cleanEtag(text(prop, DAV, "getetag").orEmpty()), data))
            }
            return out
        }

        /** "#00679eff" and "#00679E" both become "#00679E": the alpha part is dropped, the colour itself is never altered. */
        fun normalizeColor(raw: String?): String? {
            val s = raw?.trim() ?: return null
            val m = Regex("^#([0-9a-fA-F]{6})([0-9a-fA-F]{2})?$").matchEntire(s) ?: return null
            return "#" + m.groupValues[1].uppercase()
        }

        private fun cleanEtag(s: String) = s.trim().removeSurrounding("\"").removePrefix("W/").removeSurrounding("\"")

        private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun parse(xml: String) = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // the server is trusted, but a document type declaration is never needed here
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        private fun responses(xml: String) = responses(parse(xml))
        private fun responses(doc: org.w3c.dom.Document): List<Element> =
            doc.getElementsByTagNameNS(DAV, "response").let { n -> (0 until n.length).map { n.item(it) as Element } }

        /** The prop block of the propstat whose status is 200, or null. */
        private fun okProp(resp: Element): Element? {
            for (ps in children(resp, DAV, "propstat")) {
                val status = text(ps, DAV, "status").orEmpty()
                if (" 200 " in status || status.isEmpty()) return child(ps, DAV, "prop")
            }
            return null
        }

        private fun children(e: Element, ns: String, name: String): List<Element> {
            val out = ArrayList<Element>()
            var n: Node? = e.firstChild
            while (n != null) { if (n is Element && n.namespaceURI == ns && n.localName == name) out.add(n); n = n.nextSibling }
            return out
        }

        private fun child(e: Element, ns: String, name: String) = children(e, ns, name).firstOrNull()
        private fun text(e: Element, ns: String, name: String) = child(e, ns, name)?.textContent
        private fun descendants(e: Element): List<Element> {
            val out = ArrayList<Element>()
            val all = e.getElementsByTagName("*")
            for (i in 0 until all.length) out.add(all.item(i) as Element)
            return out
        }
    }
}
