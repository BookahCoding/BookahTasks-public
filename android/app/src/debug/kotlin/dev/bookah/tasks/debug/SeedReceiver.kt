package dev.bookah.tasks.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.bookah.tasks.BookahApp
import dev.bookah.tasks.data.ListKind
import dev.bookah.tasks.data.RemoteList
import dev.bookah.tasks.data.RemoteObject
import dev.bookah.tasks.data.Session
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `adb shell am broadcast -a dev.bookah.tasks.SEED -n dev.bookah.tasks/.debug.SeedReceiver --ei count 800`
 * Replaces the cache with generated tasks (three task lists and one event-only calendar) and a fake session, so the app opens straight
 * on Today. Syncing then fails (the fake server does not exist), which also tests the offline path: cached tasks stay.
 * Only the debug build contains this class.
 */
class SeedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val count = intent.getIntExtra("count", 60)
        val graph = (context.applicationContext as BookahApp).graph
        runBlocking { graph.sessions.save(Session("https://tasks.invalid", "tester", "not-a-real-password")) }
        graph.store.clearAll()
        val lists = graph.store.replaceLists(
            listOf(
                RemoteList("/cal/tasks/", "Tasks", "#A63F2D", true, ListKind.Tasks, 0),
                RemoteList("/cal/home/", "Home", "#245A86", true, ListKind.Tasks, 1),
                RemoteList("/cal/work/", "Work", "#E9D859", true, ListKind.Both, 2),
                RemoteList("/cal/events/", "Events only", "#00679E", true, ListKind.Events, 3),
                // Phase 5: a read-only task list (the overdue tamer skips it) and a read-only event calendar
                RemoteList("/cal/shared/", "Shared (read only)", "#7A5C9E", false, ListKind.Tasks, 4),
                RemoteList("/cal/birthdays/", "Birthdays", "#2E7D5B", false, ListKind.Events, 5),
            ),
        )
        val inbox = lists[0]; val home = lists[1]; val work = lists[2]; val events = lists[3]; val shared = lists[4]; val birthdays = lists[5]
        val fmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun utc(day: LocalDate, h: Int, m: Int = 0) = fmt.format(day.atTime(h, m).atZone(zone).toInstant().atOffset(ZoneOffset.UTC))
        fun date(day: LocalDate) = day.format(DateTimeFormatter.BASIC_ISO_DATE)
        val out = HashMap<Long, MutableList<RemoteObject>>()
        var n = 0
        fun add(list: dev.bookah.tasks.data.TaskList, uid: String, body: String) {
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//generated//EN\r\nBEGIN:VTODO\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\nCREATED:20260101T000000Z\r\n$body" +
                "END:VTODO\r\nEND:VCALENDAR\r\n"
            out.getOrPut(list.id) { ArrayList() }.add(RemoteObject("${list.href}$uid.ics", "e${n++}", ics))
        }
        fun alarm(trigger: String) = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:r\r\nTRIGGER$trigger\r\nEND:VALARM\r\n"

        // every view has something
        add(inbox, "in1", "SUMMARY:Call the plumber\r\n")
        add(inbox, "in2", "SUMMARY:Buy a birthday card\r\nCATEGORIES:errands\r\n")
        // a manual order (X-APPLE-SORT-ORDER) on some Home tasks, so a move there writes one task; the generated ones tie (same CREATED)
        add(home, "today1", "SUMMARY:Water the plants\r\nDTSTART;VALUE=DATE:${date(today)}\r\nCATEGORIES:home\r\nX-APPLE-SORT-ORDER:1024\r\n")
        add(home, "today2", "SUMMARY:Send the report\r\nDUE:${utc(today, 11, 30)}\r\nPRIORITY:1\r\nX-APPLE-SORT-ORDER:2048\r\n" + alarm(":-PT30M"))
        add(home, "evening1", "SUMMARY:Cook dinner for friends\r\nDTSTART:${utc(today, 19, 0)}\r\nPRIORITY:5\r\nX-APPLE-SORT-ORDER:3072\r\n")
        add(work, "over1", "SUMMARY:Pay the electricity bill\r\nDUE;VALUE=DATE:${date(today.minusDays(3))}\r\nPRIORITY:1\r\nCATEGORIES:bills\r\n")
        add(work, "over2", "SUMMARY:Renew the parking permit\r\nDUE;VALUE=DATE:${date(today.minusDays(1))}\r\n")
        add(work, "up1", "SUMMARY:Dentist\r\nDTSTART:${utc(today.plusDays(1), 9, 0)}\r\nURL:https://example.com/dentist\r\n")
        add(work, "up2", "SUMMARY:Plan the team offsite\r\nDUE;VALUE=DATE:${date(today.plusDays(5))}\r\nPRIORITY:9\r\n")
        add(work, "wait1", "SUMMARY:Hear back about the quote\r\nDTSTART;VALUE=DATE:${date(today.plusDays(3))}\r\nCATEGORIES:waiting\r\nDESCRIPTION:Waiting for the supplier\r\n")
        add(home, "some1", "SUMMARY:Learn to play the shamisen\r\nCATEGORIES:someday\r\n")
        add(home, "rep1", "SUMMARY:Take out the bins\r\nDTSTART;VALUE=DATE:${date(today)}\r\nDUE;VALUE=DATE:${date(today)}\r\nRRULE:FREQ=WEEKLY;BYDAY=MO,TH\r\n" + alarm(":-PT10M"))
        add(home, "parent1", "SUMMARY:Plan the garden\r\nDTSTART;VALUE=DATE:${date(today)}\r\nX-APPLE-SORT-ORDER:4096\r\n")
        add(home, "kid1", "SUMMARY:Order seeds\r\nRELATED-TO:parent1\r\nSTATUS:COMPLETED\r\nCOMPLETED:${utc(today, 8, 0)}\r\nPERCENT-COMPLETE:100\r\n")
        add(home, "kid2", "SUMMARY:Buy a spade\r\nRELATED-TO:parent1\r\nX-APPLE-SORT-ORDER:1024\r\n")
        add(home, "kid3", "SUMMARY:Mark out the beds\r\nRELATED-TO:parent1\r\nX-APPLE-SORT-ORDER:2048\r\n")
        add(home, "greek1", "SUMMARY:Πλήρωσε τον λογαριασμό της ΔΕΗ και ετοίμασε όλα τα χαρτιά για την εφορία πριν από την προθεσμία\r\nDUE:${utc(today, 18, 30)}\r\nCATEGORIES:σπίτι\r\nPRIORITY:1\r\n")
        add(home, "done1", "SUMMARY:Fix the shelf\r\nSTATUS:COMPLETED\r\nCOMPLETED:${utc(today, 9, 0)}\r\nPERCENT-COMPLETE:100\r\n")
        add(home, "done2", "SUMMARY:Book the flights\r\nSTATUS:COMPLETED\r\nCOMPLETED:${utc(today.minusDays(1), 20, 0)}\r\n")
        add(home, "done3", "SUMMARY:Old finished task without a date\r\nSTATUS:COMPLETED\r\n")
        // Phase 3: repeats of both kinds, ends, a rule from another app, several reminders (ours with the marker, plus one from another app)
        fun ours(trigger: String, code: String) = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:Reminder\r\n$trigger\r\nX-BOOKAH-REMINDER:$code\r\nEND:VALARM\r\n"
        add(home, "rep2", "SUMMARY:Water the balcony plants\r\nDTSTART;VALUE=DATE:${date(today.minusDays(1))}\r\nRRULE:FREQ=DAILY;INTERVAL=3\r\nX-BOOKAH-REPEAT-FROM:COMPLETION\r\n")
        add(work, "rep3", "SUMMARY:Team review\r\nDTSTART:${utc(today.plusDays(1), 10)}\r\nDUE:${utc(today.plusDays(1), 11)}\r\nRRULE:FREQ=MONTHLY;BYDAY=3WE;COUNT=6\r\n")
        add(home, "rep4", "SUMMARY:Pay the rent\r\nDUE;VALUE=DATE:${date(today.plusDays(3))}\r\nRRULE:FREQ=MONTHLY;UNTIL=${date(today.plusMonths(6))}\r\n")
        add(home, "rep5", "SUMMARY:Change the clocks\r\nDTSTART;VALUE=DATE:${date(today.plusDays(10))}\r\nRRULE:FREQ=YEARLY;BYMONTH=3,10;BYDAY=-1SU\r\n")
        add(home, "rep6", "SUMMARY:Morning stretches\r\nDTSTART:${utc(today, 7, 30)}\r\nRRULE:FREQ=DAILY\r\n")
        add(work, "multi1", "SUMMARY:Submit the tax form\r\nDTSTART:${utc(today.plusDays(2), 9)}\r\nDUE:${utc(today.plusDays(2), 17)}\r\n" +
            ours("TRIGGER;RELATED=START:PT0S", "START-0") + ours("TRIGGER;RELATED=END:-PT1H", "DUE-60") + ours("TRIGGER;RELATED=END:-PT24H", "DUE-1440") + alarm(":-PT30M"))
        // review fix pass: a daily task 30 days late (a tick jumps to tomorrow), and one whose only reminder is a set moment (it moves with each tick)
        add(home, "late1", "SUMMARY:Take the vitamins\r\nDTSTART;VALUE=DATE:${date(today.minusDays(30))}\r\nRRULE:FREQ=DAILY\r\n")
        add(home, "atonly1", "SUMMARY:Evening walk\r\nDTSTART;VALUE=DATE:${date(today)}\r\nRRULE:FREQ=DAILY\r\n" +
            ours("TRIGGER;VALUE=DATE-TIME:${utc(today, 20, 0)}", "AT"))
        add(home, "quiet1","SUMMARY:Read a chapter\r\nDTSTART:${utc(today.plusDays(1), 21)}\r\nX-BOOKAH-REMINDERS:NONE\r\n")
        // Phase 5: many overdue tasks (one repeating with start and deadline, two in the read-only list), waiting tasks with a person
        add(home, "od1", "SUMMARY:Return the library books\r\nDUE;VALUE=DATE:${date(today.minusDays(6))}\r\n")
        add(home, "od2", "SUMMARY:Reply to the school email\r\nDUE:${utc(today.minusDays(2), 16)}\r\n")
        add(inbox, "od3", "SUMMARY:Book the car service\r\nDUE;VALUE=DATE:${date(today.minusDays(9))}\r\nPRIORITY:5\r\n")
        add(work, "od4", "SUMMARY:Send the invoice\r\nDUE;VALUE=DATE:${date(today.minusDays(4))}\r\n")
        add(home, "od5", "SUMMARY:Clean the gutters\r\nDTSTART;VALUE=DATE:${date(today.minusDays(5))}\r\nDUE;VALUE=DATE:${date(today.minusDays(3))}\r\nRRULE:FREQ=MONTHLY\r\n")
        add(shared, "od6", "SUMMARY:Shared: sign the club form\r\nDUE;VALUE=DATE:${date(today.minusDays(2))}\r\n")
        add(shared, "od7", "SUMMARY:Shared: bring the projector\r\nDUE;VALUE=DATE:${date(today.minusDays(1))}\r\n")
        add(work, "wait2", "SUMMARY:Contract signed copy\r\nDTSTART;VALUE=DATE:${date(today.plusDays(2))}\r\nCATEGORIES:waiting\r\nDESCRIPTION:Waiting for: Maria\\nShe sends it by post\r\n")
        add(home, "wait3", "SUMMARY:Builder's estimate\r\nDTSTART;VALUE=DATE:${date(today.plusDays(7))}\r\nDUE;VALUE=DATE:${date(today.plusDays(10))}\r\nCATEGORIES:waiting\r\nDESCRIPTION:Waiting for: Νίκος\r\n")
        add(home, "wait4", "SUMMARY:Parcel from the shop\r\nDTSTART;VALUE=DATE:${date(today)}\r\nCATEGORIES:waiting\r\nDESCRIPTION:Waiting for: the courier\r\n")
        add(home, "search1", "SUMMARY:Ταξίδι στη Θεσσαλονίκη\r\nDESCRIPTION:Εισιτήρια και ξενοδοχείο\r\nDUE;VALUE=DATE:${date(today.plusDays(12))}\r\n")
        add(home, "search2", "SUMMARY:Café with a friend\r\nDTSTART:${utc(today.plusDays(4), 11)}\r\n")

        // Phase 5 day strip: events today (all-day, timed in the phone's zone, UTC, weekly from months ago, a moved occurrence, a trip),
        // and some that must NOT show (yesterday's all-day, an occurrence removed by EXDATE, one moved away)
        val tz = zone.id
        fun local(day: LocalDate, h: Int, m: Int = 0) = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").format(day.atTime(h, m))
        val evOut = HashMap<Long, MutableList<RemoteObject>>()
        fun ev(list: dev.bookah.tasks.data.TaskList, uid: String, vararg bodies: String) {
            val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//generated//EN\r\n" +
                bodies.joinToString("") { "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\n$it" + "END:VEVENT\r\n" } + "END:VCALENDAR\r\n"
            evOut.getOrPut(list.id) { ArrayList() }.add(RemoteObject("${list.href}$uid.ics", "v${n++}", ics))
        }
        ev(events, "e-allday", "SUMMARY:Name day\r\nDTSTART;VALUE=DATE:${date(today)}\r\nDTEND;VALUE=DATE:${date(today.plusDays(1))}\r\n")
        ev(events, "e-yesterday", "SUMMARY:SHOULD NOT SHOW yesterday\r\nDTSTART;VALUE=DATE:${date(today.minusDays(1))}\r\nDTEND;VALUE=DATE:${date(today)}\r\n")
        ev(events, "e-meeting", "SUMMARY:Team meeting\r\nDTSTART;TZID=$tz:${local(today, 9)}\r\nDTEND;TZID=$tz:${local(today, 10, 30)}\r\n")
        ev(events, "e-utc", "SUMMARY:Call with London\r\nDTSTART:${utc(today, 16)}\r\nDTEND:${utc(today, 16, 30)}\r\n")
        ev(events, "e-weekly", "SUMMARY:Yoga\r\nDTSTART;TZID=$tz:${local(today.minusWeeks(10), 19)}\r\nDTEND;TZID=$tz:${local(today.minusWeeks(10), 20)}\r\nRRULE:FREQ=WEEKLY\r\n")
        ev(events, "e-exdate", "SUMMARY:SHOULD NOT SHOW (exdate)\r\nDTSTART;TZID=$tz:${local(today.minusDays(5), 7)}\r\nRRULE:FREQ=DAILY\r\nEXDATE;TZID=$tz:${local(today, 7)}\r\n")
        ev(events, "e-moved",
            "SUMMARY:Piano lesson\r\nDTSTART;TZID=$tz:${local(today.minusDays(7), 17)}\r\nDTEND;TZID=$tz:${local(today.minusDays(7), 18)}\r\nRRULE:FREQ=DAILY\r\n",
            "SUMMARY:Piano lesson (moved)\r\nRECURRENCE-ID;TZID=$tz:${local(today, 17)}\r\nDTSTART;TZID=$tz:${local(today, 14)}\r\nDTEND;TZID=$tz:${local(today, 15)}\r\n")
        ev(events, "e-trip", "SUMMARY:Weekend trip\r\nDTSTART;VALUE=DATE:${date(today.minusDays(1))}\r\nDTEND;VALUE=DATE:${date(today.plusDays(2))}\r\n")
        ev(events, "e-night", "SUMMARY:Night ferry\r\nDTSTART;TZID=$tz:${local(today, 22)}\r\nDTEND;TZID=$tz:${local(today.plusDays(1), 7)}\r\n")
        ev(birthdays, "b-maria", "SUMMARY:A friend's birthday\r\nDTSTART;VALUE=DATE:${date(today.withYear(1990))}\r\nDTEND;VALUE=DATE:${date(today.withYear(1990).plusDays(1))}\r\nRRULE:FREQ=YEARLY\r\n")
        for ((id, list) in evOut) graph.store.upsertEvents(id, list)
        // a list that holds tasks and events: its event sits among the objects (as the sync stores it) and shows on the strip too
        out.getOrPut(work.id) { ArrayList() }.add(RemoteObject("${work.href}w-lunch.ics", "v${n++}",
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//generated//EN\r\nBEGIN:VEVENT\r\nUID:w-lunch\r\nDTSTAMP:20260101T000000Z\r\nSUMMARY:Lunch with the client\r\n" +
                "DTSTART;TZID=$tz:${local(today, 13)}\r\nDTEND;TZID=$tz:${local(today, 14)}\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"))

        // bulk for speed
        for (i in 0 until count) {
            val list = listOf(inbox, home, work)[i % 3]
            val day = today.plusDays((i * 5 % 40 - 12).toLong())
            add(list, "gen$i", "SUMMARY:Generated task $i\r\n" + (if (i % 4 != 0) "DUE;VALUE=DATE:${date(day)}\r\n" else "") + (if (i % 7 == 0) "CATEGORIES:bulk\r\n" else ""))
        }
        for ((id, list) in out) graph.store.upsertObjects(id, list)
        graph.store.setSyncToken(inbox.id, null)
    }
}
