# BookahTasks Phase 1 design: the read-only app (16 Nov 2026)

Goal (plan, section 10): sign-in, sync, cache, lists, Today / Upcoming / Inbox / Logbook (and Someday), themes, icon drafts, updater. **Nothing in Phase 1 writes to Nextcloud**, so no safety rule about writes is needed yet (the writer arrives in Phase 2 with the owner's yes for the "Tasks" list).

## Build order
- **Build A (done 16 Nov): data core** in `android/core-data` (package `dev.bookah.tasks.data`), JVM tests.
  Copied from BookahCalendar and renamed: `Http`, `Session` (hidden-input sign-in model, https only), `KeystoreVault`, `Updates`; `design` module (11 theme families, contrast rules). Adapted: `CalDavClient` (lists every live collection with its kind: tasks, events or both, plus the server's order; read-only: PROPFIND and REPORT only), `SyncEngine` (fetches only lists that hold tasks), `TaskStore` and `SqliteTaskStore` (lists and raw item text; no source column, no feeds). New: `Task` and `TaskParser` (VTODO to a task: dates in the phone's zone, floating times kept as wall-clock, TZID handled, priority bands, tags with the two special tags `someday` and `waiting` lifted out, parent by `RELATED-TO`, reminders, repeat rule, completed copies with `RECURRENCE-ID` marked), `TaskViews` (Today with overdue and Evening, Upcoming, Inbox, Someday, Logbook, list tree, drawer counts).
- **Build B (after the owner approves `design-spec.md`): app module**: sign-in screen (hidden password field), view model, drawer, the five smart views and list views, task page (read-only), Settings, theme picker, background refresh (WorkManager, 15 minutes), crash log, Greek and English strings. Minified build and a real-configuration run on an emulator before anything goes to the phone.
- **Build C: icon drafts** (three, for the owner to choose), banner art, release script copied from BookahCalendar, signing key (needs the owner's yes and he must back it up), pre-release (needs his yes), updater (manual check, needs a public repo to be useful).

## Decisions made while building A
- Task-only lists are found by their component set; Personal (events only) is known but never fetched.
- A completed repeating task could in theory arrive as extra objects with `RECURRENCE-ID` (the Tasks web app's source suggests it; the Phase 0 test showed it does not). They are parsed and shown only in the Logbook.
- Sorting follows the web app: `X-APPLE-SORT-ORDER`, else seconds since 2001 of `CREATED`.
- Tasks are never copied into events and the cache holds only the raw item text, so an edit in Phase 2 can change only the lines the user changed.

## Verified so far (Build A)
35 JVM tests pass (parser on generated tasks shaped like the Phase 0 write-test results, views, sync engine against a fake server, CalDAV XML parsing). **Not verified:** anything against the real server in code (the Phase 0 scripts did that), anything on a phone or emulator, real data through `TaskParser` (there are no real tasks yet; the real list "Tasks" does not exist until Phase 2).
