# BookahTasks Phase 3 design: Time (18 Nov 2026)

Goal (plan, section 10): start and deadline logic, Evening, Someday, several reminders with notification actions, repeats of both kinds.
Everything stays in standard task fields (plan, section 6); two harmless `X-BOOKAH-` lines are added where the standard has no field, and
other apps ignore them. Branch `phase-3`; nothing is pushed, merged or released in this phase without the owner's yes.

## 1. Repeats

**Editor** (task page, "Repeat" row, and quick add by sentence): None, Daily, Weekly (on chosen days), Monthly (same day of the month,
or the nth weekday such as "third Wednesday" / "last Friday"), Yearly; "every N"; end: never, on a date (UNTIL), after N times (COUNT).
It writes one standard `RRULE` line (`FREQ`, `INTERVAL`, `BYDAY`, `COUNT` or `UNTIL`); every other line of the task stays as it was.

- A rule the editor cannot show (BYMONTH, BYSETPOS, BYHOUR, several monthly days, ...) is shown as "Custom rule (made in another app)"
  and kept as it is until the owner saves a new rule in its place.
- **A repeating task needs a date.** `TaskEdit.setRepeat` itself enforces it (not only the screen): a task with no start and no
  deadline gets a start date: today, or for weekly-on-days the first chosen day from today on. The editor says so before saving.
  Quick add does the same ("every monday" alone = starts on the coming Monday, or today when today is a Monday).
- **UNTIL is the same kind of value as the dates**: a date for date-only tasks, else the end of that day as a UTC time. When the
  dates later change kind, UNTIL is rewritten to match.
- The start is not moved onto a chosen weekday (BookahCalendar does that for events). For a task the next tick lands on a chosen day.

**Quick add**: `every day`, `every monday`, `every monday and thursday`, `every 2 weeks`, `every other week`, `every month`, `every year`,
`κάθε μέρα`, `κάθε Δευτέρα`, `κάθε Δευτέρα και Πέμπτη`, `κάθε 2 εβδομάδες`, `κάθε μήνα`, `κάθε χρόνο`. The words light a "Repeat" chip
(tap it off and the words go back into the title). `every`/`κάθε` without a unit after it stays a title word.

## 2. Two kinds of repeat

| | Kind 1: fixed schedule (default) | Kind 2: "repeat after I complete it" |
|---|---|---|
| Stored | `RRULE` | `RRULE` (the interval) + `X-BOOKAH-REPEAT-FROM:COMPLETION` |
| Tick | start and deadline move together to the **next occurrence of the rule after the current start** (Phase 2 behaviour, approved 17 Nov; kept) | the next date is counted **from the moment of the tick** |

**Kind 1, the owner's ruling (19 Nov 2026):** a task that is **not overdue** moves one step (as in Phase 2). An **overdue** task (its deadline,
or without one its start, is before today) jumps to the **first occurrence after today**; start and deadline move together and keep
their gap; COUNT goes down by every occurrence passed, and the task completes for good when COUNT or UNTIL runs out during the jump.
Undo puts the exact old text back. Kind 2 is not affected. The Nextcloud web app may still move an overdue task one step.

**A next date that cannot be worked out never ends a series:** an impossible rule from another app (e.g. 30 February), or a search that
passes 100,000 occurrences or 2 seconds, leaves the task unchanged (`Completion.Unchanged`): the app says so; a notification's Done leaves
the notification in place. Only a rule that really ran out (COUNT or UNTIL) finishes the task.

**A repeating task always keeps a date:** clearing both start and deadline is refused (core and screen, with a message).

**Monthly on the 29th to 31st:** months without that day are skipped by the fixed schedule (the standard's rule; 31 Jan, then 31 Mar);
the second kind takes the month's last day (31 Jan + 1 month = 28 Feb). The editor says so.

**Kind 2 rule shapes**
- Plain interval (no BY parts): the local date of the tick plus N days / weeks / months / years (`java.time`; 31 Jan + 1 month = 28 Feb);
  the time of day of the start stays; the deadline keeps its distance from the start. Hourly/minutely rules (other apps only): the
  tick moment plus N hours/minutes.
- With BY parts (weekly on Monday and Thursday, the third Wednesday, ...): the rule is anchored at the tick and the first occurrence on
  a **later day** than the tick is taken: a Mon/Thu task ticked on Tuesday moves to Thursday.
- Both kinds: `COUNT` goes down by one per tick and the last tick finishes the task for good; a next date after `UNTIL` finishes it.

**The Nextcloud web app** ignores `X-BOOKAH-REPEAT-FROM`: a tick there follows its own standard behaviour (it moves the dates by the
rule, the fixed schedule). Kind 2 is a BookahTasks feature; whether the web app keeps the X- line after it edits a task is not verified.

## 3. Several reminders

**Choices** (task page, "Reminders"): At start; 10 min / 1 hour / 1 day before the start; At the deadline; 10 min / 1 hour / 1 day before
the deadline; At a set date and time. Any number. A "before/at start" choice is offered only when there is a start, a deadline choice
only when there is a deadline.

**Storage**: one `VALARM` per reminder (`ACTION:DISPLAY`, `DESCRIPTION`, `TRIGGER`), with `X-BOOKAH-REMINDER:<choice>` inside the block
so we know it is ours. The `TRIGGER` is standard so other apps ring at the same moment:
- timed value: `TRIGGER;RELATED=START:PT0S`, `TRIGGER;RELATED=END:-PT1H`, ...
- date-only value (counted from 00:00 of that day by the standard): "At start" = `PT9H` (09:00), "1 day before" = `-PT15H`
  (09:00 the day before), "10 min before" = `PT8H50M`.
- set time: `TRIGGER;VALUE=DATE-TIME:20261120T150000Z`.

**Defaults and explicit lists (ruling)**
- A task with **none of our VALARMs** and no `X-BOOKAH-REMINDERS:NONE` line gets the **default reminders** (17 Nov rule, unchanged):
  at a timed start; one hour before a timed deadline (or at the deadline when the task was made less than an hour before it);
  09:00 on a date-only start or deadline day.
- **Our list replaces the defaults** for that task as soon as one of our VALARMs exists. Removing the last one writes
  `X-BOOKAH-REMINDERS:NONE` (no reminders at all); "Use the default reminders" removes our blocks and that line.
- The editor shows the **effective list**: the defaults (marked "default") or our own list. Changing anything while the defaults are
  shown turns them into our own VALARMs first, so what is shown is exactly what gets written. One pure function in `core-data`
  (`ReminderSet`) gives both the screen and the writer.
- **Alarms written by other apps** always fire too (as before) and are shown read-only ("set in another app"); they are never removed
  or rewritten. A date-only task's foreign relative alarm still counts from 09:00 (Phase 1 behaviour, kept so nothing moves); ours count
  from 00:00 as the standard says. The difference is noted as a risk.
- Editing keeps every foreign `VALARM` block byte for byte, and keeps our own blocks that were not removed byte for byte (other apps
  add lines such as `ACKNOWLEDGED` inside them).
- **Dates change**: when a date our reminder depends on is removed, that reminder is removed (and if it was the last of ours,
  `X-BOOKAH-REMINDERS:NONE` is written so the defaults do not come back unasked); when a date changes kind (date-only to timed or back)
  our trigger is rewritten for the new kind, so "At start" stays at the start. When a repeating task moves on, our "at a set date and
  time" reminders move by the same amount, so such a task never goes silent.
- **What counts as ours:** a block whose `X-BOOKAH-REMINDER` code this version understands. A block with our marker but an unknown code
  (a later version) is treated like another app's: kept untouched, shown read-only, never deleted. If another app strips our marker, the
  block becomes "another app's" for us: it still rings and is shown read-only, and (if it was the last of ours) the task falls back to
  the defaults unless the NONE line is there.
- **Deadline less than an hour away:** the default list then holds "at the deadline" instead of "1 hour before"; the screen and the
  alarms use the same function (`ReminderSet.of`), and editing from that list keeps the deadline reminder.
- **UNTIL** is written in the kind of the dates as STORED (a date-only start beside a timed deadline is stored as 00:00, so UNTIL is a
  UTC time); an end before the start is refused.
- `ReminderPlanner` stays deduped (one alarm per task per minute) and bounded (14 days, the 40 nearest).

## 4. Notification actions

Android shows at most three buttons. **Ruling:** Done, **+1 hour**, **Tomorrow**. "Snooze 10 min" is replaced by "+1 hour": a local
reminder again in one hour, nothing is written to Nextcloud (lost if the phone restarts in that hour). Tomorrow uses the same rule as the
swipe-left sheet (`Reschedule`), Done the same as the tick (repeating tasks move on, a parent finishes its open subtasks), both through
the outbox under the same write lock as the screens. **This evening**: tapping the notification opens that task with the reschedule
sheet already open (Today, This evening, Tomorrow, Next week, Someday, a date). Done and Tomorrow are not offered on a read-only list.
A stale notification (the task was changed, finished or deleted meanwhile) does nothing except go away; nothing crashes. The
notification is dismissed only after the change is saved. All of it works with the app closed (a broadcast receiver).

## 5. Start, deadline, Evening, Someday

Checked against plan 4.1 with tests: Today = starts today or earlier, due today, not overdue; This evening = today's time at or after
the evening hour; Upcoming = a future start, or a future deadline with no start; Someday = only in Someday. Gap found and fixed: a task
with a future start but a deadline today (only possible from another app) was in both Today and Upcoming; it is now only in Today.

## 6. Live check (written, not run)

`tools\live-phase3.ps1` + `LivePhase3Test`: throw-away list `bookahtest-p3`, removed for good (`X-NC-CalDAV-No-Trashbin: 1`): the RRULE
and the X- repeat line stored as written, our reminder blocks stored with their marker, a foreign VALARM byte for byte after our edit,
a Kind 1 and a Kind 2 tick read back. Needs the owner's yes at that moment.
