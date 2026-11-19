package dev.bookah.tasks.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Phase 3 gate (repeats and several reminders). Runs only through tools\live-phase3.ps1, which makes one throw-away list (bookahtest-p3)
 * first and removes it for good afterwards. Records only counts and yes/no lines; never the server address, the password or a task's text.
 * Written in Phase 3, NOT run (it needs the owner's yes at that moment).
 *
 * What it checks, through the app's own write path (outbox, PUT with If-Match): the RRULE and `X-BOOKAH-REPEAT-FROM` stored as written;
 * our reminder blocks stored with their marker; another app's VALARM byte for byte after our edits; a Kind 1 and a Kind 2 tick read back.
 * What it cannot check: whether the Nextcloud Tasks web app keeps the X- lines after IT edits the task, and what it shows or rings.
 */
class LivePhase3Test {
    private val zone = ZoneId.of("Europe/Athens")
    private fun env(n: String) = System.getenv(n)

    @Test fun repeatsAndRemindersAgainstTheRealServer() = runBlocking {
        assumeTrue(env("BOOKAH_LIVE_SERVER") != null)
        val slug = env("BOOKAH_LIVE_SLUG")!!
        require(slug.startsWith("bookahtest-")) { "refusing: not a throw-away list" }
        val out = StringBuilder()
        fun note(s: String) { out.appendLine(s) }
        try {
            val api = CalDavClient(Http.newClient(), Session(env("BOOKAH_LIVE_SERVER")!!, env("BOOKAH_LIVE_USER")!!, env("BOOKAH_LIVE_PASSWORD")!!))
            val store = MemoryTaskStore(); val engine = SyncEngine(store, events = false); val outbox = Outbox(store)          // tasks only (Phase 5 added event calendars to the sync)
            engine.sync(api)
            val list = store.lists().first { it.name == slug }
            suspend fun syncOk(label: String, expectPushed: Int) {
                val r = engine.sync(api)
                note("$label pushed=${r.pushed} conflicts=${r.conflicts} failed=${r.pushFailed} errorCode=${r.errorCode} queued=${outbox.count()}")
                assertEquals(label, expectPushed, r.pushed); assertEquals(label, 0, r.conflicts + r.pushFailed + r.errorCode)
            }
            /** The server's copy, read with a fresh store (nothing of ours in memory). */
            suspend fun serverIcs(uid: String): String {
                val f = MemoryTaskStore(); SyncEngine(f, events = false).sync(api)
                val id = f.lists().first { it.name == slug }.id
                return f.allObjects().first { it.listId == id && TaskParser.parse(it, zone).any { t -> t.uid == uid } }.ics
            }
            fun task(ics: String) = TaskParser.parse(StoredObject("/x.ics", 0, "", ics), zone).first { !it.isOccurrenceCopy }
            fun mine(uid: String) = store.allObjects().first { TaskParser.parse(it, zone).any { t -> t.uid == uid } }
            fun edit(uid: String, f: (String) -> String) { val o = mine(uid); outbox.edit(o.href, IcsPatcher.touch(f(o.ics), Instant.now())) }
            val foreign = listOf("BEGIN:VALARM", "ACTION:DISPLAY", "DESCRIPTION:From another app", "TRIGGER:-PT45M", "X-OTHER-APP:kept", "END:VALARM")
            fun foreignKept(ics: String) = IcsPatcher.alarmBlocks(ics).any { it == foreign }
            fun ourCodes(ics: String) = task(ics).reminders.mapNotNull { it.ours }.sorted()
            val today = LocalDate.now(zone)

            // 1 a weekly task on Monday and Thursday with two of our reminders and one "from another app", made the way the app makes it
            val uid = UUID.randomUUID().toString()
            var ics = IcsPatcher.newTask(uid, "Live phase 3", Instant.now())
            ics = TaskEdit.setDates(ics, TaskWhen(today, java.time.LocalTime.of(9, 0)), TaskWhen(today, java.time.LocalTime.of(10, 0)), zone)
            ics = TaskEdit.setRepeat(ics, RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)), zone, today)
            ics = TaskEdit.setReminders(ics, listOf(ReminderChoice.Before(false, 0), ReminderChoice.Before(true, 60)), zone)
            ics = IcsPatcher.editAlarms(ics, edit = { _, b -> b }, add = listOf(foreign))
            outbox.create(list, uid, ics)
            syncOk("create", 1)
            run {
                val s = serverIcs(uid)
                val rrule = IcsPatcher.readProperty(s, "RRULE")
                note("rrule stored as written=${rrule == "FREQ=WEEKLY;BYDAY=MO,TH"} noRepeatFromLine=${IcsPatcher.readProperty(s, RepeatRules.FROM_PROPERTY) == null}")
                note("our reminders=${ourCodes(s)} foreign kept=${foreignKept(s)} alarms=${IcsPatcher.alarmBlocks(s).size}")
                assertEquals("FREQ=WEEKLY;BYDAY=MO,TH", rrule); assertEquals(listOf("DUE-60", "START-0"), ourCodes(s)); assertTrue(foreignKept(s))
            }

            // 2 the second kind: every 2 days after completion
            edit(uid) { TaskEdit.setRepeat(it, RepeatSpec(RepeatFreq.Daily, 2, fromCompletion = true), zone, today) }
            syncOk("kind 2", 1)
            run {
                val s = serverIcs(uid)
                note("kind2 rrule=${IcsPatcher.readProperty(s, "RRULE") == "FREQ=DAILY;INTERVAL=2"} repeatFrom=${IcsPatcher.readProperty(s, RepeatRules.FROM_PROPERTY) == "COMPLETION"} fromCompletionRead=${task(s).repeat?.fromCompletion}")
                assertEquals("COMPLETION", IcsPatcher.readProperty(s, RepeatRules.FROM_PROPERTY))
            }

            // 3 change our reminders: one removed, one added; the other app's block stays byte for byte
            edit(uid) { TaskEdit.setReminders(it, listOf(ReminderChoice.Before(false, 0), ReminderChoice.Before(true, 10)), zone) }
            syncOk("reminders edit", 1)
            run {
                val s = serverIcs(uid)
                note("after edit our reminders=${ourCodes(s)} foreign kept=${foreignKept(s)}")
                assertEquals(listOf("DUE-10", "START-0"), ourCodes(s)); assertTrue(foreignKept(s))
            }

            // 4 tick (kind 2): start moves to today + 2 at 09:00, still open, rule and X- line kept
            edit(uid) { TaskEdit.complete(it, Instant.now(), zone).ics }
            syncOk("tick kind 2", 1)
            run {
                val t = task(serverIcs(uid))
                note("kind2 tick start=+${java.time.temporal.ChronoUnit.DAYS.between(today, t.start!!.date)}d open=${t.isOpen} ruleKept=${t.repeat?.fromCompletion == true} remindersKept=${t.reminders.size}")
                assertEquals(today.plusDays(2), t.start!!.date); assertTrue(t.isOpen); assertEquals(true, t.repeat?.fromCompletion)
            }

            // 5 back to the fixed kind with COUNT=2: one tick moves it to the next Monday or Thursday after its start, the next one finishes it
            edit(uid) { TaskEdit.setRepeat(it, RepeatSpec(RepeatFreq.Weekly, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), end = RepeatEnd.Count(2)), zone, today) }
            syncOk("kind 1 with count", 1)
            val before = task(serverIcs(uid)).start!!
            edit(uid) { TaskEdit.complete(it, Instant.now(), zone).ics }
            syncOk("tick kind 1", 1)
            run {
                val s = serverIcs(uid); val t = task(s)
                val ok = t.start!!.date > before.date && t.start!!.date.dayOfWeek in setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY) && t.start!!.time == before.time
                note("kind1 tick next chosen day=$ok count=${IcsPatcher.readProperty(s, "RRULE")?.contains("COUNT=1")} noRepeatFromLine=${IcsPatcher.readProperty(s, RepeatRules.FROM_PROPERTY) == null}")
                assertTrue(ok)
            }
            edit(uid) { TaskEdit.complete(it, Instant.now(), zone).ics }
            syncOk("last tick", 1)
            run { val t = task(serverIcs(uid)); note("last tick completed=${t.status == TaskStatus.Completed}"); assertEquals(TaskStatus.Completed, t.status) }

            // 6 clean up
            for (o in store.allObjects().filter { it.listId == list.id }) outbox.delete(o.href)
            engine.sync(api)
            note("NOT CHECKED HERE: what the Nextcloud Tasks web app shows or rings for these, and whether it keeps the X- lines after it edits the task")
            assertNotNull(note("RESULT: all steps passed"))
        } catch (t: Throwable) {
            note("RESULT: FAILED (${t::class.simpleName}: ${t.message?.take(120)})")
            throw t
        } finally {
            File(env("BOOKAH_PRIVATE_DIR") ?: ".", "live-phase3.txt").writeText(out.toString())
        }
    }
}
