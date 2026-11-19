# BookahTasks Phase 5 design: Our additions (18 Nov 2026)

Goal (plan, section 10 and 4.2): overdue tamer, day strip, share to BookahTasks and the quick-settings tile, Waiting for, search.
Branch `phase-5` (made from `phase-3`, which is not merged yet). Nothing is pushed, merged or released without the owner's yes.
Everything stays in standard fields (plan, section 6). These are my rulings; the owner can change any of them.

## 1. Overdue tamer (plan 4.2.2)

- **Weekend** (new `RescheduleTarget.Weekend`) = Saturday of this week; on a Saturday or a Sunday it is the **next** Saturday (the
  weekend that is already under way is not offered as "the weekend"). Same time-of-day rules as the other targets (`Reschedule.plan`).
- **One task:** the swipe-left / Reschedule sheet (now with Weekend), opened by a swipe, by the screen-reader action, or by a small
  "Move" button at the end of each row inside the opened Overdue group.
- **All of them:** the opened Overdue group (Today and Schedule) shows a row of buttons Today, Tomorrow, Weekend, Next week, Someday.
  A tap asks **"Move N tasks to Tomorrow?"** (plural forms, EN and GR); Yes moves them, then an Undo bar puts all of them back.
- Planned by a pure function (`OverdueTamer.plan`) from the tasks shown in the dialog and their stored text **read again under the write
  lock**: a task that is no longer open and overdue (changed meanwhile), a task in a read-only list, and a task with a change waiting for
  the owner (conflict, refused change, delete waiting) are skipped and counted. Written by `Outbox.editEach`: one store lock, each task
  on its own (a task that cannot be written is counted, the others still go). A repeating task keeps the gap between start and deadline
  (existing `Reschedule.plan` rule). The message after the move says how many moved and how many were left (read-only / could not be
  moved). Undo touches only tasks still exactly as the move left them.

## 2. Day strip (plan 4.2.3)

- **Which calendars:** every calendar in the owner's Nextcloud calendar home that holds events (Events-only lists, and lists that hold
  both), including shared and birthday calendars, because they all sit in the same home collection. Subscriptions are left out (they
  are not calendars in that home). **Never written:** the push only sends rows of the outbox, and nothing ever queues an event.
- **Storage:** a new table `events (href, list_id, etag, ics, span_from, span_to)` (database version 4, upgrade adds it and a
  `show_events` column on `lists`). Event-only calendars are kept **out of the `objects` table** on purpose: the task screens read every
  row of `objects` on each reload, and an event calendar with years of events would slow every task screen. `span_from`/`span_to`
  (epoch days, padded by one day, open-ended for an endless repeat) let the strip read only the events that can touch today.
  Events of a "both" list are already in `objects` (the task sync fetches the whole collection) and are read from there.
- **Sync:** the same sync-collection + multiget as for tasks, after the task lists, in the same run. A failure on one event calendar never
  fails the task sync, never shows the error line, and leaves that calendar's cached events as they were (it is retried next time).
  Event calendars are synced even when hidden, so showing one again is instant.
- **Expansion:** biweekly (logic adapted from BookahCalendar's `Expander`, read only): all-day, timed, multi-day, RRULE, EXDATE,
  RECURRENCE-ID (moved into and out of today), cancelled occurrences, TZID with or without a VTIMEZONE block, floating times (the
  phone's zone), DST. All-day events are matched **by date** (start date <= today < end date), timed events by overlap with today's
  local midnight-to-midnight; an event that ends exactly at 00:00 is not on the next day.
- **Screen:** Today and Schedule (top), a horizontal strip of small pills: all-day first, then by start time; each pill has the
  calendar's colour bar, the time ("All day", "09:00-10:30", "until 14:00", "from 20:00") and the title. It shows on an empty day too
  (The owner has few tasks). Tap = open BookahCalendar if it is installed (launch intent; the manifest declares the package in `<queries>`),
  else nothing. Read-only: no tick, no edit.
- **Settings > Day strip:** a switch "Show calendar events on Today" (default on) and one switch per event calendar (stored as
  `show_events` in the phone's copy, kept across syncs). Worked out off the main thread in `reload()` and again when the date changes.

## 3. Share to BookahTasks and the tile (plan 4.2.5)

- A new small `ShareActivity` (exported, only `ACTION_SEND` `text/plain`, no screen of its own) cleans the shared text and hands an
  explicit internal action (`dev.bookah.tasks.action.QUICK_ADD`) to MainActivity, which opens the quick-add sheet pre-filled. The add
  goes through the existing add path; the link is stored in the standard `URL` property.
- **Splitting** (pure `SharedText.parse`, never throws, fuzz-tested): the text is cut to 20,000 characters first; the **first** http(s)
  link becomes the URL (cut to 2,000 characters; spaces and control characters end it, so it can never add lines to the item); the
  title is `EXTRA_SUBJECT` when given, else the first line of the text without the link, else (only a link was shared) the link itself
  (no network: the page title is never fetched); the title is cut to 250 characters. Text beyond the title (other lines) goes to notes.
  The quick-add parser also reads the title, so words such as "tomorrow" in a shared headline light their chip: tap it off as usual.
- **Signed out:** the share opens the sign-in screen; the shared text is dropped (a short message says so).
- Cold start: the shared text waits until the session is read (as the deep link does). After it is handled the intent is cleared, so a
  rotation or Recents never opens the sheet again. A new share replaces the text in an open sheet.
- **Quick-settings tile "New task"** (`NewTaskTile`, a `TileService` guarded by `BIND_QUICK_SETTINGS_TILE`): opens BookahTasks with the
  quick-add sheet. Android 14+ uses `startActivityAndCollapse(PendingIntent)` (the Intent version throws there); Android 12-13 the Intent
  version. On a locked phone it asks to unlock first (`unlockAndRun`).

## 4. Waiting for (plan 4.2.7)

- Stored as the plan says: tag `waiting` (hidden as a tag), the check-back date in `DTSTART` (a date), the person as **the first line
  of the notes**: `Waiting for: <person>` (one fixed English marker so any version and any language reads it; the Greek screen shows its
  own words; a line written as `Αναμονή για: <person>` is read too). Every other line of the notes keeps its content (the DESCRIPTION
  value is written again, so its escaping and outer spaces can change).
- **Deadline:** if the check-back date is after the deadline, the deadline moves to the check-back date (the existing rule that a
  deadline is never before the start; the dialog says so first). So a waiting task always leaves Today and Overdue until its date and
  then comes back by itself (start date reached), with no special rule in the views (checked by tests).
- **Repeating tasks** cannot be marked waiting (moving DTSTART would move the whole series); the action is not offered and the core
  refuses.
- **Ending waiting** removes the tag and the person line; the start date stays.
- UI: task page row "Waiting for" (person field + quick dates: Tomorrow, In 3 days, Next week, In 2 weeks, Pick a date) and "Stop
  waiting"; a **Waiting** smart view in the drawer (count, sorted by check-back date, rows show "Waiting for Maria" and the date); the
  row chip says "Waiting for Maria" (or "Waiting").

## 5. Search (plan 4.1)

- A search page from a top-bar button (and the drawer), drawn over the current screen like Settings; Back returns to that screen, and a
  task opened from the results closes back to the results.
- Pure `TaskSearch.search` in core-data: title, notes, tags (and the waiting person), **all typed words must match** (in any field), case
  and accent insensitive (NFD, combining marks removed, final sigma = sigma, `Locale.ROOT` lower case), plain `contains` (never a regex
  built from typing), query cut to 200 characters, at most 200 results; title hits first, then open before done, then by date.
- **Scope:** every list shown in the app (a hidden list is hidden everywhere, as for deep links); finished tasks only with the "Include
  done" switch; copies of finished occurrences of a repeating task are never listed (they share the series' address).
- Live as you type (150 ms pause), worked out off the main thread, wrapped so typing can never crash the app (fuzz test).

## 5b. Review fix pass (18 Nov 2026, evening): rulings changed or added

- **Move-all skips repeating tasks (ruling, confirmed by the owner 19 Nov 2026).** The question says "N repeating tasks stay: move them one by one"; the
  one-task Reschedule of a repeating task is unchanged (start and deadline keep their gap). Plan and writes happen under the store lock,
  and each write only if the task's text is still the one the plan was made from (compare and set, Undo too).
- **Nothing read from one list escapes a sync.** A task list whose answer cannot be read (not valid XML, a storage error) is counted
  (`listErrors`, a quiet line "Some lists could not be read") and the other lists still sync; an event calendar's trouble of any kind
  (also a dropped connection) is counted and the next calendar is tried. A sync that throws anyway becomes a report, never a crash
  (sign-in, refresh, both workers).
- **Event download:** chunks of 50 written as they arrive (day spans worked out outside any lock); a cut-off download keeps what arrived
  and the next run fetches only events whose ETag the phone lacks; the sync token is kept only when a calendar is complete, and only then
  does a full fetch remove events the server no longer lists. At most 3,000 events and 90 seconds per run (the rest next run). An event
  over 1,000,000 characters is not kept (Android cannot read such a row back), and a too-large row is never read by the strip query.
- **Sign-in syncs tasks only**, saves the session, then a normal refresh brings the events in the background.
- **RDATE** (extra dates) is read: timed values as moments, dates for all-day events; PERIOD values are ignored.
- **Declined invitations** are not on the strip: an attendee with PARTSTAT=DECLINED whose address is `<login>@...` (or the login, or its
  name is the login). The phone does not know the owner's e-mail address, so this is a heuristic.
- **Waiting:** a first notes line `Waiting for: ...` is only taken as ours when the task already has the `waiting` tag; otherwise it is
  the owner's own text and stays (the person line is added above it).
- A calendar whose kind changes between events-only and tasks-and-events drops its rows in the other table and is fetched in full again.
- The push never sends calendar text that holds an event or no task.
- The strip is worked out in its own job (the task screens never wait for it), not at all when switched off, and a tap (also the spoken
  "Open in BookahCalendar" action) is only offered when BookahCalendar is installed.

## 6. Live checks (written, NOT run)

`tools\live-phase5.ps1` + `LivePhase5Test`: (a) throw-away list `bookahtest-p5` (removed for good): mark waiting / end waiting stored as
written, move-all of overdue tasks and its undo read back; (b) **read-only** against the real calendars: counts event calendars, events
synced and today's instances, through an API wrapper whose writes throw. Counts and status codes only. Needs the owner's yes at that moment.
