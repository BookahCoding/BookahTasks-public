# BookahTasks Phase 1 report (closed 17 Nov 2026)

**CLOSED 17 Nov 2026** (see section 6 at the end). Older status text follows, written before the phone checks:

**Status:** the read-only app is built and works on an emulator with generated data, including the real release configuration. Phase 1 is **not closed**: it ends when the owner has installed a private pre-release on his phone and signed in with a new app password (steps in section 5). Nothing was pushed; there is no GitHub repository; nothing was installed on a phone; nothing was written to Nextcloud in this phase (the app cannot write yet).

## 1. Implemented
- **Data core** (`android/core-data`, 35 JVM tests): CalDAV list discovery with kinds (tasks, events, both) and server order, sync of task lists only (sync tokens, ETags, full resync on a refused token, offline keeps the cache), SQLite cache of the raw item text, VTODO parser (dates in the phone's zone, floating times as wall-clock, TZID without an embedded zone, priority bands, tags with `someday` and `waiting` lifted out, `RELATED-TO` parents, reminders, repeat rule, completed copies), view rules (Today with overdue and Evening, Upcoming, Inbox, Someday, Logbook, list tree, drawer counts).
- **App** (`android/app`): sign-in with a hidden password field (https only, nothing saved unless the server answered), drawer (five smart views with counts, lists with their colour and a show/hide box, tags, Sync now, Settings), Today (date in words, folded overdue group, This evening), Inbox, Upcoming (day headings, time only in rows), Someday, Logbook (by finish day), list view with nested subtasks, tag view, read-only task page (start, deadline, finished, reminders in words, repeat in words, list, tags, priority, link that opens the browser, notes, subtasks), Settings (account and sign out, evening hour, appearance, 11 theme families, sync state and last error, background refresh note, version, privacy line, on-phone crash log, count of unreadable items), WorkManager refresh every 15 minutes, English and Greek.
- **Look:** round tick boxes in the list's own colour, priority as a flag glyph plus a word (spec 2.1), overdue dates the only red text, spoken descriptions for every row.
- **Tools:** `tools/release-android.ps1` (copied from BookahCalendar; refuses to run if the emulator-only flags are set; publishes only with `-Publish -OwnerApproved`), `tools/make-icon.py` and the three icon drafts in `docs/icon-drafts/` (`sheet.png`).

## 2. Verified, and how
- **JVM tests:** 40 pass (39 core-data, 1 app), including `RealServerShapesTest`: the three tasks exactly as the Nextcloud Tasks web app rewrote them in the Phase 0 write test (committed fixtures, generic spike titles) parse correctly (completed plain task with both reminders, link, tags and sort order 10; completed child keeping its parent with a UTC deadline; the ticked repeating task is still one **open** task with its weekly rule), and **the real calendar-home answer saved in Phase 0 parses to 4 lists (2 task-only, 2 event-only), with inbox, outbox and trash bin dropped** (that test reads the private file only when `BOOKAH_PRIVATE_DIR` points at `C:\the build PC\bookahtasks`, prints counts only, and is skipped otherwise).
- **Emulator `BookahTasksTest`** (own AVD, port 5558; the other emulators were not touched), generated tasks from the debug-only seeder (3 task lists, 1 event-only calendar that must be ignored, 78 tasks): Today, drawer, Upcoming, Logbook, a list, a task page, Settings, Back from Settings, sign-in screen. The event-only calendar does not appear. Greek (date "Δευτέρα 16 Νοεμβρίου", priorities, "Απόψε", a long Greek title wraps to three lines), dark mode, the largest system text size (rows reflow, nothing clipped).
- **Real release configuration** (R8 full optimisation, 3.1 MB, built with `-PallAbis -PseedRelease`, debug-signed): shows the seeded tasks, no crash in the log, `am start -W` total 366 to 418 ms. This is the check that found BookahCalendar's hidden parse bug; here the same keep rule is in place and parsing works.
- **Server side** (Phase 0): list creation shape, kept fields, repeat behaviour of the web app.

## 3. Not verified
- A **real sign-in and real sync** (needs the owner to type the app password on the phone). The CalDAV client is the one proven in BookahCalendar, and the list parser and task parser now pass on responses really saved from the server in Phase 0, but no request has been made by the app itself.
- Anything **on the phone**: the 1 s cold-start target on the Xiaomi 15T, HyperOS and the 15-minute refresh, swipe feel.
- Real tasks through the parser (there are none yet; your real "Tasks" list is made in Phase 2).
- Back-key order: **observed** that one Back press closes Settings and returns to Today (screenshot); the task page, the drawer and leaving the app by Back were not observed. TalkBack, landscape and a native Greek read of the strings are also not verified.
- Not built yet: the in-app updater (it needs a public repo), the artwork (Today banner, drawer strip, empty states), "Week of" grouping in Upcoming, the final icon.

## 4. Things I learned (also in HANDOFF.md)
Screenshots must be taken from Git Bash (PowerShell redirection corrupts PNGs); do not swipe from the screen edge (system gesture); `avdmanager` reads Google's repository list when making an AVD (no image download was needed); a Kotlin source folder added to a variant needs `kotlin.directories`, not `java.srcDir`; Python `-c` strings turned a path into a control character in a vault note (fixed; use the file editor).

## 4b. Finding: a list called "Tasks" already exists (checked 16 Nov, evening)
The Phase 0 report said no list named Tasks existed. The later probe run shows **one now does** (a second new task list appeared in your account after the write test; I never touched it). The app already treats a list named "Tasks" as the inbox, so it works with that list as it is. **Consequence for Phase 2:** the plan's "create a new Tasks list" is no longer needed if that list is the one you want. Default I will assume unless you say otherwise: **use your existing "Tasks" list as the inbox and create nothing.** This corrects the Phase 0 finding and the plan's section 8 line.

## 4c. First run on the phone (The owner, 16 Nov, 22:58 to 23:00, screenshots)
Verified on his Xiaomi: 0.1.0 installs, sign-in with a new app password works over Tailscale, the first sync finished at 22:58 with no error, his real list "Tasks" appears in the drawer in its own colour with 0 open tasks, the event-only calendars do not appear, Today shows the empty-state sentence, Settings shows the account, the evening hour, the theme gallery (he chose Akihabara, dark) and "No crashes recorded". **Not yet seen:** any real task (he has none), so real task rows, due dates and reminders on the phone, speed numbers, background refresh timing, the launcher icon. Phase 1 stays open until he has a few real tasks to look at (see HANDOFF).

## 5. Done after the owner's "yes to all" (16 Nov, night), and what is left for him
Done: signing key made (backup needed), icon A is the launcher icon, private repo `BookahCoding/BookahTasks` created, pushed, **pre-release `v0.1.0` published** (https://github.com/BookahCoding/BookahTasks/releases/tag/v0.1.0; the APK has no test seeder and no x86 code, signed with the new key). Left for the owner: the phone steps below. The text after this paragraph is the plan he said yes to.

## 5a. The yes that was asked (one yes, defaults chosen)
**Recommended: say "yes to all of it", and I do it in this order, each as its own step:**
1. **Create the release signing key** in `C:\the build PC\bookahtasks\signing\` (like BookahCalendar). The password file stays untracked; only the certificate fingerprint is committed.
2. **Create the private GitHub repository `BookahCoding/BookahTasks`, push the three local commits, and publish pre-release `v0.1.0`** (the signed APK, 3 MB), as with BookahCalendar. Plus a **standing yes** for later pushes and pre-releases to this private repo (merges, tags of final releases and any public repo still ask).
3. **Icon:** I recommend **draft A** (a vermilion sun with a sumi brush tick on washi paper; the clearest at small sizes, `docs/icon-drafts/sheet.png`). Say "A", or pick B or C. The chosen draft becomes the launcher icon in 0.1.1.

**Your steps on the phone (in order, prerequisites included):**
1. Back up the folder `C:\the build PC\bookahtasks\signing\` (a lost key means no more updates over the installed app).
2. On the phone, log in to GitHub in the browser (the release is private).
3. Download `BookahTasks-0.1.0.apk` from the release page and allow installs from that browser when Android asks.
4. Turn Tailscale on.
5. In Nextcloud (web), go to Settings, Security, and make a new **app password** named "BookahTasks".
6. Open BookahTasks, type the server address, the user name and that app password. Tell me what you see; Settings shows the last sync time and any error.

## 6. Phase 1 closed (17 Nov 2026)
**Verified on the owner's Xiaomi, release build, real server (his screenshots):**
- Real sign-in, list discovery and first sync (16 Nov, 22:58); his real list "Tasks" and the empty state.
- With a generated throw-away list `bookahtest-p1` (10 tasks, made with his yes, **deleted for good the same day**: HTTP 204, nothing left): Today (overdue folded, This evening), Upcoming, a list with a nested subtask, a long title wrapping, Someday, Logbook, and the marks for priority, reminder, repeat, tags and subtask count, all correct.
- **0.1.1 additions** (The owner asked for them): **Schedule** screen (drawer, under Today; all lists: overdue, no date, then days), **"Week of"** grouping for days beyond the first seven (seen: "Week of 23 November"), **Speed** section in Settings. Measured on the phone: screen opened to tasks shown **246 ms** (target 1 s), read of the saved copy 5 ms, sync over the tailnet 1097 ms. No crashes recorded. 43 JVM tests pass.
- Releases: v0.1.0 and v0.1.1, private pre-releases in BookahCoding/BookahTasks.

**Still not verified:** background refresh timing on HyperOS, Back-key order, TalkBack, a native Greek read, landscape, the launcher icon look, sync of changes (the app cannot write yet), the in-app updater (not built; needs a public repo), artwork (not built).
