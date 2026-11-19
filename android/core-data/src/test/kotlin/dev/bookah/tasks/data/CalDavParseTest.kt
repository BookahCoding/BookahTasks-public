package dev.bookah.tasks.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalDavParseTest {
    private fun entry(href: String, types: String, comps: String?, color: String? = "#00679eff", order: String? = "3", privs: String = "<d:privilege><d:write/></d:privilege>") =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop><d:displayname>${href.trimEnd('/').substringAfterLast('/')}</d:displayname>
<d:resourcetype><d:collection/>$types</d:resourcetype>${if (color != null) "<x1:calendar-color>$color</x1:calendar-color>" else ""}${if (order != null) "<x1:calendar-order>$order</x1:calendar-order>" else ""}
${if (comps != null) "<cal:supported-calendar-component-set>$comps</cal:supported-calendar-component-set>" else ""}<d:current-user-privilege-set>$privs</d:current-user-privilege-set></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"""

    private fun multistatus(vararg e: String) =
        """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav" xmlns:x1="http://apple.com/ns/ical/" xmlns:cs="http://calendarserver.org/ns/" xmlns:nc="http://nextcloud.org/ns">${e.joinToString("")}</d:multistatus>"""

    @Test fun kindsAreReadFromTheComponentSet() {
        val xml = multistatus(
            entry("/dav/ev/", "<cal:calendar/>", """<cal:comp name="VEVENT"/>"""),
            entry("/dav/todo/", "<cal:calendar/>", """<cal:comp name="VTODO"/>"""),
            entry("/dav/both/", "<cal:calendar/>", """<cal:comp name="VEVENT"/><cal:comp name="VTODO"/>"""),
            entry("/dav/nocomps/", "<cal:calendar/>", null),
        )
        val lists = CalDavClient.parseListCollections(xml)
        assertEquals(listOf(ListKind.Events, ListKind.Tasks, ListKind.Both, ListKind.Events), lists.map { it.kind })
        assertEquals("#00679E", lists[0].colorHex); assertEquals(3, lists[0].order)
        assertTrue(lists[1].canWrite); assertTrue(ListKind.Tasks.holdsTasks); assertFalse(ListKind.Events.holdsTasks)
    }

    @Test fun inboxOutboxTrashBinSubscriptionsAndDeletedListsAreLeftOut() {
        val xml = multistatus(
            entry("/dav/", "", null),
            entry("/dav/inbox/", "<cal:schedule-inbox/>", """<cal:comp name="VEVENT"/>"""),
            entry("/dav/trashbin/", "<nc:trash-bin/>", null),
            entry("/dav/gone/", "<cal:calendar/><nc:deleted-calendar/>", """<cal:comp name="VTODO"/>"""),
            entry("/dav/real/", "<cal:calendar/>", """<cal:comp name="VTODO"/>""", color = null, order = null),
            entry("/dav/journal/", "<cal:calendar/>", """<cal:comp name="VJOURNAL"/>"""),
        )
        val lists = CalDavClient.parseListCollections(xml)
        assertEquals(listOf("/dav/real/"), lists.map { it.href })
        assertEquals(0, lists[0].order); assertEquals(null, lists[0].colorHex)
    }

    @Test fun readOnlyListsAreNotWritable() {
        val xml = multistatus(entry("/dav/ro/", "<cal:calendar/>", """<cal:comp name="VTODO"/>""", privs = "<d:privilege><d:read/></d:privilege>"))
        assertFalse(CalDavClient.parseListCollections(xml).single().canWrite)
    }

    @Test fun syncDeltaAndMultiget() {
        val delta = CalDavClient.parseSyncDelta(
            """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/l/a.ics</d:href><d:propstat><d:prop><d:getetag>"abc"</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
<d:response><d:href>/l/b.ics</d:href><d:status>HTTP/1.1 404 Not Found</d:status></d:response><d:sync-token>http://sabre.io/ns/sync/7</d:sync-token></d:multistatus>""",
        )
        assertEquals(listOf(ChangedEntry("/l/a.ics", "abc")), delta.changed); assertEquals(listOf("/l/b.ics"), delta.removed); assertEquals("http://sabre.io/ns/sync/7", delta.newToken)
        val objs = CalDavClient.parseMultiget(
            """<d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/l/a.ics</d:href><d:propstat><d:prop><d:getetag>"abc"</d:getetag><c:calendar-data>BEGIN:VCALENDAR
END:VCALENDAR</c:calendar-data></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>""",
        )
        assertEquals("abc", objs.single().etag); assertTrue(objs.single().ics.startsWith("BEGIN:VCALENDAR"))
    }
}
