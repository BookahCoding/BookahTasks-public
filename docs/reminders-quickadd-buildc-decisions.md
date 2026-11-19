# Build C and reminders: decisions ledger (17-18 Nov 2026)

```
# ledger — reminders + quick add + Schedule home (The owner's feedback 17 Nov night)
Ruling: notifications are computed on the phone from each open task's start and deadline (no extra VALARM written to Nextcloud): at the start moment; 60 minutes before a deadline that has a time. Existing VALARM reminders from other apps also fire (deduplicated by minute). — keeps the server data unchanged and avoids double alarms — cost if wrong: other apps would not show these reminders.
Ruling: a start or deadline with only a date reminds at 09:00 that day ("starts today" / "due today"); a deadline reminder that would fall before the start is skipped — cost if wrong: The owner wants another default hour (easy to change; a setting later).
Ruling: quick add has two rows of date/time chips: Start (primary) and an optional Deadline; Enter on the keyboard adds and keeps the sheet open for the next task; the Add button adds and closes.
Ruling: Schedule becomes the home screen (first in the menu, opens on start).
R1 planner: complete (d8e1864, 9 tests RED(unresolved)→GREEN, suite all green; one test typo fixed (day 32))
R2 Android layer: complete (e775c68); builds; NOT yet verified on emulator
UX: quick add chips + Schedule home complete (0fce7c2); emulator: Schedule opens first; sheet shows When/Deadline/priority/list; Today + 09:00 chips worked; Reminder test: exact alarms at start and due-60 scheduled, notification 'Starts now' with Done/Snooze fired, Done completed the task (Logbook, waiting to sync). Not seen: Settings notification line, Greek, deadline row/priority chips, add-another with Done key
Advisor blocker: fixed (midnight/22:59 reminders from the coerced 00:00/23:59) — CoercedDatesTest 5 RED→GREEN, suite green
Final C1 (review): fixed C1 midnight/22:59 reminders (advisor found it first) — CoercedDatesTest RED→GREEN (+ reading 00:00/23:59 back as dates)
Final C1: fixed I1 (due-but-undelivered alarms were cancelled by the next reschedule) — reschedule keeps keys whose time has come
Final C1: fixed I2 (concurrent reschedules, stale alarm firing early) — Mutex around rescheduleReminders; at fire time the alarm shows only if the task still plans exactly that reminder (stillPlanned)
Final C1: fixed I3/I4 (stale Done moved a repeat twice; Done failed silently, skipped subtasks, appeared on read-only lists) — Done checks stillPlanned, completes open subtasks like a tick, cancels the notification only after success, no Done button on read-only lists
Final C1: fixed I5 (a deadline under an hour away never reminded) — new kind DueNow at the deadline; ReminderPlannerTest RED→GREEN
Final C1: fixed I6 (debug reminder test wrote into a real list) — only on the seeded fake account, firstOrNull
Final C1: fixed (minor) inexact fallback when the exact set fails; time zone and clock change receivers; Back returns to Schedule; Next week hidden on Sunday; Add clears the text at once; sign-out cancels alarms
Final C1: Ruling: I7 (a reminder missed while the phone was off is not shown afterwards) — deferred; needs a record of the last plan; cost if wrong: a missed notification after a long power-off
Final C1: Ruling: I8 (an overdue repeating task has no reminder until ticked) — kept: one task per series, reminders follow the task's current dates; cost if wrong: a daily task not ticked yesterday is silent today
Final C1: Ruling: I9 (hiding a list silences its reminders) — kept, same as BookahCalendar's hidden calendars; cost if wrong: The owner hides a list and loses its notifications; to be told in Settings later
Final C1: minor (deferred): chips lost on rotation; Settings notification line does not refresh on return; VM zone fixed at start; reminder text time for stored lines; repeated hour in autumn (rebuild from local time); double parse per reload; time picked with no date uses today even if it has passed
C1 parser: complete (db1bc18); 17 tests RED(unresolved)→GREEN (one test expectation swapped, fixed)
C2 sheet wired: complete (6f834b5); emulator: sentence tints + Tomorrow chip + confirm line seen; Time chip moved after the first days
C3 swipes + reschedule sheet + overdue fold: complete (03d5fa5); emulator: swipe left opened the sheet for the right task, swipe right finished a task with the Undo bar. NOT done: drag order (deferred), gesture-navigation edge test, debug seed for conflict/failed rows
C4: seen on a screen now (emulator, seeded): conflict dialog (both titles + 3 choices), Settings 'Changes Nextcloud refused (answer 403)' with Try again/Discard. Still not seen: sign-out dialog, move dialog, Greek strings
Final C2 (Build C review, opus): 1 critical, 14 important, ~12 minor. One fix pass, parser/reschedule test-first (RED compile + GREEN: QuickParserReviewTest 9, RescheduleTest +2, suite 188/188), screens checked on the emulator.
Final C2: fixed C1 (a tapped or carried-over chip beat typed words yet the words were cut from the title) — typed words win while lit, a chip tap dismisses the kind so the words return to the title; emulator: second sentence "Dentist friday 9am !1 @home" saved Fri 20 Nov 09:00, High, Home (not the carried tomorrow 18:00)
Final C2: fixed I1 (swipe-right dead on a row whose task changed) — rememberUpdatedState; also positionalThreshold 0.4
Final C2: fixed I2 (deadline without time pulled to the start's clock time) and I3 (repeating task skewed by reschedule) — Reschedule compares dates only when a time is missing; repeating tasks move start and deadline by the same days
Final C2: fixed I4 (finished copy picked by the sheet lookup), I14 (swipes and the Reschedule button on read-only lists)
Final C2: fixed I5-I11 parser (separate am/pm markers incl. μ.μ., tonight at 9, dd.mm dates and 12.50 no longer a time, στις 5 Μαΐου / την Παρασκευή, έως τις 17:00, 1/2 kg and 24/7, year after a month, Greek preposition/article absorbed), I12 (deadline typed with time only takes the start's date in the sheet), I13 (a switched-off part is forgotten when its words go)
Final C2: fixed (minor) Someday no longer rewrites dates nor repeats on an already parked task; reschedule plans from the stored text; tags need a letter; trailing punctuation and a leading '(' ; regexes built once; the sheet lookup
Final C2: Ruling: I11 ('at 5 Main Street', Greek weekday words that are also names) — accepted: the words are tinted and the chip switches them off; cost if wrong: an occasional wrong date until he taps the chip
Final C2: Ruling: dotted times (18.30) now need 'at' or 'στις'; cost if wrong: Greek users typing bare 18.30 get no time (the picker still works)
Final C2: minor (deferred): a typed time already past today saves today (the time picker rolls to tomorrow, the parser does not); 'Someday'/'This evening' offered on Sunday/after the evening hour; absolute VALARM reminders do not move on reschedule; chips lost on rotation; the swipe API overload is deprecated in material3 1.4.0 (works); bare 3:16 style clock times
Final C2: DEFERRED by me: drag order (needs a live check of what the Nextcloud web app shows) — told to the owner
```
