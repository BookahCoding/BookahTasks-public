# BookahTasks Phase 5 report: Our additions (18 Nov 2026)

Branch `phase-5`, made from `phase-3` (not merged yet). Local commits only: nothing pushed, merged or released. Design and rulings:
`docs/phase-5-design.md`. Checked on emulator-5558 (Android 14, zone Europe/Bucharest) with the generated (seeded) account only.
**No write to the real Nextcloud; the live check is written, not run.** Screenshots: `C:\the build PC\bookahtasks\phase5-shots\` (00 to 32).

## 1. Implemented
1. **Overdue tamer.** The opened Overdue group on Schedule and Today has "Move all to" Today / Tomorrow / Weekend / Next week / Someday;
   a tap asks "Move N tasks?" (says how many sit in read-only lists and stay), Yes moves them, one Undo puts all back. Each overdue row
   has a Reschedule button (one task) that opens the existing sheet, which now has **Weekend** too (`RescheduleTarget.Weekend` = this
   week's Saturday; on Saturday or Sunday the next one). Core: `OverdueTamer.plan` re-reads every task under the write lock and skips
   (counts) read-only, changed-meanwhile and blocked tasks (conflict, refused change, delete waiting); `Outbox.editEach` writes each task
   on its own under one store lock (one failure never stops the others). Repeating tasks keep their start-deadline gap (existing rule).
2. **Day strip.** Event-only calendars are now synced (same sync-collection + multiget, after the task lists) into a **new `events`
   table** with a day span per object (database **version 4**, upgrade adds the table and a `show_events` column; a "both" list's events
   are read from the objects the task sync already keeps). `DayEvents.forDay` expands today (logic adapted from BookahCalendar's
   Expander, which was only read): all-day by date, timed by overlap, multi-day, RRULE, EXDATE (TZID, UTC, date), RECURRENCE-ID moved
   into / out of the day, cancelled, TZID with or without a VTIMEZONE block, floating, DST. Pills (calendar colour, time, title;
   all-day first) at the top of Schedule and Today, also on an empty day. Tap = open BookahCalendar if installed (`<queries>` added), else
   nothing. Settings > Day strip: "Show calendar events on Today" (default on) and one switch per calendar. An event calendar's failure
   never fails the task sync or shows the error line (`SyncReport.eventErrors`). Events are never queued or written.
3. **Share to BookahTasks + tile.** `ShareActivity` (exported, `ACTION_SEND` `text/plain` only, no screen) splits the text with
   `SharedText.parse` (input cut to 20,000 chars; first http(s) link -> URL, max 2,000, no spaces or control characters; title = subject,
   else first line without the link, else the link itself, max 250; other lines -> notes; never throws, no network) and opens the
   quick-add sheet in MainActivity pre-filled with a "Link: ..." line; the add path writes `URL` (`TaskEdit.setUrl` refuses a value that
   could add lines) and the notes. MainActivity cleans the extras again (`SharedText.of`) because it is exported. Signed out: a short
   message, text dropped. Quick-settings tile **"New task"** (`NewTaskTile`, `BIND_QUICK_SETTINGS_TILE`): `startActivityAndCollapse`
   with a PendingIntent on Android 14+, the Intent form on 12-13; `unlockAndRun` when locked; monochrome icon `ic_tile_add`.
4. **Waiting for.** `TaskEdit.setWaiting` / `endWaiting`: tag `waiting`, check-back date as a date `DTSTART`, `Waiting for: <person>`
   as the first notes line (every other line kept; a Greek `Αναμονή για:` line is read too); a deadline before the check-back date moves
   up to it (the dialog says so); repeating tasks cannot wait (core refuses, the row is not offered); ending removes tag and person line,
   the start stays. Task page row "Waiting for" with a dialog (person, Tomorrow / In 3 days / Next week / In 2 weeks / Pick a date, Stop
   waiting, Undo bar). **Waiting** smart view in the drawer (count, by check-back date). Row chip "Waiting for Maria" (with "check back
   <day>" when the row shows the deadline). The existing view rules already hide a task with a future start (checked by tests).
5. **Search.** `TaskSearch.search` (core-data): title, notes, tags, waiting person; all words must match; NFD + combining marks removed
   + `Locale.ROOT` lower case + final sigma = sigma; plain `contains`; query max 200 chars, 200 results; title hits first, then open,
   then by deadline. Search page from a top-bar button and the drawer, drawn over the current screen; 150 ms pause, worked out off the main
   thread inside `runCatching`; "Include done"; a result opens the task page and Back returns to the results. Scope: lists shown in the app.
6. Debug seed extended: two event calendars (one read-only) with today's all-day, timed (TZID without a block, UTC), weekly from ten weeks
   ago, a moved occurrence, a trip, a night ferry, a yearly birthday, and three that must NOT show; an event in a "both" list; 17
   overdue tasks incl. a repeating one and two in a read-only list; waiting tasks; Greek and accented titles. Debug-only
   `SearchTestReceiver` types a search with `\uXXXX` escapes (adb cannot type Greek).
7. Live check `tools\live-phase5.ps1` + `LivePhase5Test` (section 6 of the design). **Not run.**

## 2. Verified
**Tests:** 354 JVM tests, 0 failures (core-data 353 incl. 7 skipped by design = the live checks without their environment; app 1).
Before Phase 5: 288. New: `OverdueTamerTest` 9, `DayEventsTest` 18 (incl. the Athens all-day leak, end at 00:00, DST, run with the
JVM zone set to New York), `EventSyncTest` 8 (own table, never written, failure isolated, show switch kept, removed calendar, a sync
with `events = false` never asks for an event calendar),
`SharedTextTest` 12 (incl. a 5000-case fuzz), `WaitingTest` 8, `TaskSearchTest` 9 (incl. a 6000-case fuzz and a 1,000,000-char query),
`LivePhase5Test` 2 (skipped). RED for every new test class was a **compile failure** (the APIs did not exist yet); no test was seen
failing against a wrong implementation (e.g. the Athens all-day leak test passed on the first green run, so it was never shown to catch
the naive version). Changed on purpose: `SyncAndListsTest.onlyListsThatHoldTasksAreFetched` now expects the event calendar to be
fetched (last) into the events table. The older live checks (`LiveRoundTripTest`, `LiveBuildBTest`, `LiveOrderTest`, `LivePhase3Test`)
and part (a) of `LivePhase5Test` now sync with `events = false`, so they never download the real event calendars.
EN/GR strings: 308 / 308, same names.

**Seen on emulator-5558:**
- Install **over** the Phase 3 build (no uninstall): old data shown, no crash, database `user_version` 4 (00).
- Day strip on Schedule (01-03): A friend's birthday, Name day, Trip (multi-day), Team meeting 09:00-10:30, Lunch with the client 13:00-14:00
  (from the "both" list), Piano lesson (moved) 14:00-15:00, Call with London 16:00-16:30 (UTC), Yoga 19:00-20:00 (weekly since 10 weeks),
  Night ferry "from 22:00"; none of the three "SHOULD NOT SHOW" events. Dark mode on Today (27). Hiding Birthdays in Settings removed it
  from the strip (30, 31). Tap on a pill with BookahCalendar not installed: nothing happened, no crash (32).
- Overdue tamer: group open with the buttons and per-row Reschedule (04); one task to Weekend via the sheet, count 17 -> 16 (05, 06);
  "Move 14 tasks? To: Tomorrow / 2 are in read-only lists" (07); after Move: 2 left (the read-only ones) and "15 tasks moved" with Undo
  (10, second run after a fix); **Undo brought all 17 back with their old dates** (11). Greek question "Να μετακινηθούν 15 εργασίες;" (29).
- Found and fixed on screen: after Move, a message dialog for the read-only skips covered the Undo bar until it timed out (08, 09).
  Read-only skips are now only named in the question.
- Share: text with a link -> title "Great article about green tea", "Link: https://example.com/tea?id=7" (12); after Add the stored text
  has `SUMMARY` and `URL:https://example.com/tea?id=7` (read from the phone's database). Subject + link -> title "Ferry strike on Friday"
  with "on Friday" lit as a date (13). Link only -> the link is the title (14). A second share replaced the first one's text.
- Tile: added with `cmd statusbar add-tile`, shown as "New task" in the panel (15b), **tapped on screen** from Home: BookahTasks opened with
  an empty quick-add sheet (16d). (`cmd statusbar click-tile` did the same, 16.)
- Search: "plumb" found "Call the plumber" (18); a result opened the task page and Back returned to the results (19, 22). Greek via the
  debug receiver: "θεσσαλονικη" found "Ταξίδι στη Θεσσαλονίκη" (25); "ΤΗΣ δεη" (capital sigma, no accents) found the ΔΕΗ task (26).
- Waiting: dialog with "Maria" and In 3 days (20); saved: page shows "Waiting for Maria, check back Sat 21 Nov", start Sat 21 Nov, Undo bar
  (21); stored text `CATEGORIES:waiting`, `DTSTART;VALUE=DATE:20261121`, `DESCRIPTION:Waiting for: Maria` (database). Waiting view with
  five tasks sorted by check-back date, persons and the Greek name shown (24); drawer with Waiting 5 and Search (23). A waiting task whose
  date is today is back in Today; the plumber task (Saturday) is not (27).
- Greek once (28, 29); dark once (27).

## 3. NOT verified
- The live check (needs the owner's yes); anything on the real server: real event calendars (sizes, shared and birthday calendars, Outlook
  zone names), the time a first full event sync takes, what the Nextcloud web app shows for `Waiting for:` and `URL`.
- BookahCalendar opening from a pill (not installed on emulator-5558); the tile on Android 12-13 (Intent path) and on a locked phone
  (`unlockAndRun`); a real share from another app's share sheet (only `am start` to ShareActivity); styled (CharSequence) shared text;
  signed-out share message.
- The waiting "deadline moves" warning, "Stop waiting", and its Undo on screen (tests only); the Someday / Next week / Today move-all
  targets on screen (Tomorrow seen; others by tests); the read-only/changed-meanwhile message after a move (tests only).
- The strip after midnight (recomputed on resume when the date changed: code, not seen); TalkBack on pills and the new buttons; large text;
  the owner's phone. (Birthdays was switched on again and is back on the strip, shot 33.)
- ~~The release (R8) configuration was not built.~~ Done in the review fix pass (section 4b): a local release-configuration build ran on
  the emulator. Still not seen: the real signed release from `tools\release-android.ps1`.
- A change to an event inside a "both" list refreshing the strip (fixed in code after review: the strip also follows the task store's
  version when such a list is shown; not seen on screen).

## 4. Decisions (rulings; the owner can change them)
1. Weekend = Saturday of this week; on Saturday/Sunday the next Saturday.
2. Event-only calendars get their own table (not `objects`), so task screens never read thousands of events on each reload; "both" lists'
   events are read from `objects`. Hidden calendars are still synced (instant when shown again).
3. Every calendar in the home collection counts (shared and birthday calendars too). Nextcloud's own "enabled" flag of a calendar is not
   read; the per-calendar switch in Settings is ours.
4. Share opens BookahTasks itself (the sheet sits in the app, not over the sharing app); a link-only share uses the link as the title.
5. Waiting: one fixed English marker `Waiting for:` in the notes (Greek marker read too); a later check-back date pulls the deadline up;
   no waiting for repeating tasks; ending keeps the start date.
6. Search covers lists shown in the app (hidden lists stay hidden everywhere), all words must match, finished copies of repeating tasks
   never listed.
7. The quick-add parser reads a shared title like typed text (a headline "... on Friday" lights a date chip; tap it off).
8. Commit trailer: the session's attribution reminder says `Claude Opus 5.5`; the brief said Sonnet; the commits carry the reminder's line
   (as in Phase 3).

## 4b. Review fix pass (18 Nov 2026, evening)
Tests first, **RED seen against the wrong behaviour** (not a compile error: new parameters were added as no-op stubs first): 14 failing
tests, then green. All in `Phase5ReviewTest` unless said otherwise.
- **C1** nothing escapes sync(): `aRuntimeErrorInAnEventCalendarIsCountedNotThrown`, `aBrokenXmlAnswerInAnEventCalendarIsCountedNotThrown`,
  `aBrokenAnswerForOneTaskListNeverEscapesAndTheOtherListsStillSync` (SAXParseException and IllegalStateException in a task list), and
  `aDroppedConnectionOnOneCalendarDoesNotSkipTheOnesAfterIt`. The app turns any sync exception into a report (sign-in shows "Sign-in did
  not finish", workers retry or finish quietly): code, not unit-tested (no app test harness).
- **I1** `editEachWithTheExpectedOldTextLeavesATaskThatChangedMeanwhile` (a pull between plan and write is never overwritten); the screen
  plans and writes under `synchronized(graph.store)`, Undo checks and writes under it too.
- **I2** `anEventWithOnlyExtraDatesShowsOnThoseDays` (RDATE timed, all-day, PERIOD ignored, RDATE with RRULE and EXDATE).
- **I3/I5** `anEventDownloadCutOffHalfwayKeepsWhatArrivedAndGoesOnFromThere`, `oneSyncFetchesAtMostItsLimitAndTheNextOneContinues`,
  `aFullFetchRemovesEventsTheServerNoLongerHasOnlyAfterItFinished` (passed already; guards the new order), `anOversizedEventIsSkippedAndTheOthersAreKept`.
  Sign-in uses `SyncEngine(events = false)` then refreshes.
- **I4** `WaitingTest.theOwnersOwnWaitingForLineIsKeptWhenTheTaskWasNotWaiting`.
- **I6** (ruling) `moveAllSkipsRepeatingTasksAndCountsThem`; `OverdueTamerTest.aRepeatingTaskMovedOnItsOwnKeepsTheGapBetweenStartAndDeadline`
  replaces the old bulk version.
- **I7** strip in its own job, skipped when off, "both" list spans cached per ETag: code (seen working on screen, not unit-tested).
- Minors: `onDowngrade` no-op; `aCalendarThatStartsHoldingTasksDropsItsEventRowsAndToken`; `editEach` catches every exception (Undo bar still
  for what moved); oversized rows never read by the strip query; `anEventTheOwnerDeclinedIsHidden`; pill tap and its spoken action only
  when BookahCalendar is installed; strip heading marked as a heading; Settings and "Include done" switches are toggleable rows with a label;
  `msg_moved_skipped` no longer mentions read-only lists (they are named in the question); `thePushRefusesTextThatIsNotATask`.
- Not done: `debugSearch` stays in the main `AppGraph` (a flow nobody sends to in a release build; moving it needs a debug-only hook).
- Two of my own test mistakes fixed while going green: the fake counts the task list's multiget too (cut-off test numbers).

**Release (R8) configuration checked** (local only, `-PallAbis -PseedRelease`, debug-signed because the worktree has no keystore, never
published): build clean, no "Missing class" or R8 warnings in the output; installed over the debug build on emulator-5558 (not
debuggable), seeded: cold start 438 ms, Schedule shows, day strip incl. timed, UTC, weekly, moved and "from 22:00" events (r01, r02),
move-all question with "2 in read-only lists" and "1 repeating task stays" (r03), "14 tasks moved", the repeating task left with its
Reschedule button (r04), a tick with "Task done" + Undo (r05), share opens the filled sheet with the link (r06), the tile tapped from
Home opens the sheet (r07), Greek search via the test receiver (r08); logcat had no FATAL, ClassNotFound, NoSuchMethod or NoClassDefFound.
**The release-style build is still installed on emulator-5558** (a later debug install over it works: same debug key).

**Tests after the pass:** 369 JVM tests, 0 failures (core-data 368 incl. 7 skipped by design; app 1). EN/GR strings 311 / 311.

## 5. Risks
- **The write path changed** (bulk edits through `editEach`, `URL` and `DESCRIPTION` on add, waiting edits): per the standing rule the
  live gate must pass (with the owner's yes) before any release.
- First sync after the update downloads every event of every calendar once (years of events); later syncs are incremental. Size and time
  on the real account are unknown (the read-only live check measures them). **Sign-in now waits for that first full event download too**
  (sign-in is a sync), so a first sign-in can take longer.
- A calendar with an unknown (non-Olson) TZID falls back to biweekly's reading of its VTIMEZONE block; an event with a TZID Java does not
  know and no block is read in UTC by biweekly (could be hours off). Seen only in tests.
- `MainActivity` and `ShareActivity` are exported: any app can open the quick-add sheet pre-filled (the owner still has to tap Add; text
  is cleaned and cut).
- The strip shows only events from up to one day around today in the span index (padded); an event whose stored span is wrong would be
  missed (unreadable objects are always looked at).
- Vault plan "For agents" and the project note's status: not updated by this session (read-only for it).
