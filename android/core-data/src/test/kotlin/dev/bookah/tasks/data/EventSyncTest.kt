package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private const val EV = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:ev1\r\nDTSTAMP:20261101T000000Z\r\nSUMMARY:Dentist\r\n" +
    "DTSTART;VALUE=DATE:20261118\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
private const val OLD_EV = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VEVENT\r\nUID:ev2\r\nDTSTAMP:20261101T000000Z\r\nSUMMARY:Old\r\n" +
    "DTSTART;VALUE=DATE:20200101\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
private const val TODO = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//t//EN\r\nBEGIN:VTODO\r\nUID:t1\r\nDTSTAMP:20261101T000000Z\r\nSUMMARY:Task\r\nEND:VTODO\r\nEND:VCALENDAR\r\n"

private class EventRig {
    val dav = FakeDav().apply {
        eventLists = listOf(RemoteList("/cal/personal/", "Personal", "#00679E", true, ListKind.Events, 5), RemoteList("/cal/birthdays/", "Birthdays", "#E9D859", false, ListKind.Events, 6))
        seed("/cal/tasks/t1.ics", TODO)
        seed("/cal/personal/ev1.ics", EV)
        seed("/cal/personal/ev2.ics", OLD_EV)
    }
    val store = MemoryTaskStore()
    val engine = SyncEngine(store)
    fun personal() = store.lists().first { it.href == "/cal/personal/" }
}

class EventSyncTest {
    @Test fun eventCalendarsAreSyncedIntoTheirOwnTableNotAmongTheTasks() = runBlocking {
        val r = EventRig()
        val rep = r.engine.sync(r.dav)
        assertNull(rep.error)
        assertEquals(2, r.store.eventObjects(r.personal().id).size)
        assertTrue(r.store.allObjects().none { it.href.startsWith("/cal/personal/") })          // the task screens never see events
        assertEquals(1, r.store.allObjects().size)
        assertEquals(2, rep.eventLists); assertEquals(2, rep.eventsChanged)
    }

    @Test fun onlyEventsThatCanTouchTheDayAreRead() = runBlocking {
        val r = EventRig()
        r.engine.sync(r.dav)
        val d = LocalDate.of(2026, 11, 18).toEpochDay()
        assertEquals(listOf("/cal/personal/ev1.ics"), r.store.eventsTouching(d, d).map { it.href })
    }

    @Test fun eventsAreNeverWritten() = runBlocking {
        val r = EventRig()
        r.engine.sync(r.dav); r.engine.sync(r.dav)
        assertTrue(r.dav.log.none { it.startsWith("PUT") || it.startsWith("DELETE") })
        assertTrue(r.store.outboxAll().isEmpty())
    }

    @Test fun aChangeAndARemovalOnTheServerReachThePhone() = runBlocking {
        val r = EventRig()
        r.engine.sync(r.dav)
        r.dav.objects.remove("/cal/personal/ev2.ics")
        r.store.setSyncToken(r.personal().id, null)                // the fake answers a full listing; a full fetch replaces everything
        r.engine.sync(r.dav)
        assertEquals(listOf("/cal/personal/ev1.ics"), r.store.eventObjects(r.personal().id).map { it.href })
    }

    @Test fun aFailingEventCalendarNeverFailsTheTaskSync() = runBlocking {
        val r = EventRig()
        r.engine.sync(r.dav)
        r.dav.syncFails["/cal/personal/"] = 500
        r.dav.seed("/cal/tasks/t2.ics", TODO.replace("t1", "t2"))
        val rep = r.engine.sync(r.dav)
        assertNull(rep.error); assertEquals(0, rep.errorCode); assertEquals(1, rep.eventErrors)
        assertEquals(2, r.store.allObjects().size)                                     // the new task arrived
        assertEquals(2, r.store.eventObjects(r.personal().id).size)                    // the cached events stay
        r.dav.syncFails["/cal/personal/"] = 0                                          // the network drops during the events
        val rep2 = r.engine.sync(r.dav)
        assertNull(rep2.error); assertTrue(rep2.eventErrors >= 1)
    }

    @Test fun theShowSwitchOfAnEventCalendarSurvivesASync() = runBlocking {
        val r = EventRig()
        r.engine.sync(r.dav)
        r.store.setShowEvents(r.personal().id, false)
        r.engine.sync(r.dav)
        assertEquals(false, r.personal().showEvents)
        assertEquals(true, r.store.lists().first { it.href == "/cal/birthdays/" }.showEvents)
    }

    @Test fun aSyncWithoutEventsNeverAsksForAnEventCalendar() = runBlocking {
        val r = EventRig()
        val rep = SyncEngine(r.store, events = false).sync(r.dav)
        assertNull(rep.error)
        assertTrue(r.dav.log.none { it.startsWith("SYNC /cal/personal/") || it.startsWith("SYNC /cal/birthdays/") })
        assertTrue(r.dav.log.any { it == "SYNC /cal/tasks/" })
        assertEquals(0, rep.eventLists)
    }

    @Test fun aCalendarRemovedOnTheServerTakesItsEventsWithIt() = runBlocking {
        val r = EventRig()
        r.engine.sync(r.dav)
        val id = r.personal().id
        r.dav.eventLists = r.dav.eventLists.drop(1)
        r.engine.sync(r.dav)
        assertTrue(r.store.eventObjects(id).isEmpty())
        val d = LocalDate.of(2026, 11, 18).toEpochDay()
        assertTrue(r.store.eventsTouching(d, d).isEmpty())
    }
}
