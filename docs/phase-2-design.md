# BookahTasks Phase 2 design: capture and editing (approved 17 Nov 2026)

Goal (plan, section 10): quick add with the sentence parser, task sheet (editor), complete, delete, move, subtasks, tags, priority, manual order, swipes, Undo, the outbox and conflict handling. **This is the first phase that changes Nextcloud**, so safety comes first. The "Tasks" list already exists in the owner's account (found 16 Nov) and is used as the inbox; nothing is created.

## 1. What this phase delivers, in plain words
The owner can add a task in a second (even with no network), tick it, edit every field, move it, delete it with an Undo, and everything reaches Nextcloud on its own and shows in the Nextcloud Tasks web app. If the same task was changed in two places, nothing is lost: the app shows both versions and he picks.

## 2. Safety rules for writing (hard)
1. **No build that can write ships before Build A passes on a throw-away list** `bookahtest-...` (with the owner's yes at that moment, removed for good afterwards, never with `X-NC-CalDAV-No-Trashbin` in app code: that header lives only in `spike/`).
2. **An edit changes only the lines the user changed.** Every other line of the stored item (alarms, `X-` lines, lines written by other apps) stays as it was (rule kept from BookahCalendar). Tested on the real-shape fixtures: an unchanged edit comes back identical apart from `DTSTAMP`, `LAST-MODIFIED` and `SEQUENCE`.
3. **New task:** `PUT` with `If-None-Match: *`. **Edit:** `PUT` with `If-Match: <etag>`. **Delete:** `DELETE` with `If-Match`, so it goes to Nextcloud's trash bin.
4. **A refused write (412, the item changed meanwhile) never overwrites.** The app fetches the server copy and keeps both; the conflict dialog (copied from BookahCalendar's) lets the owner keep his, keep the server's, or keep both.
5. The first build able to write to his real list reaches his phone only after rule 1, and the release notes say so.

## 3. Architecture
- **Outbox** (new SQLite table in the same database, survives the app being killed): rows `id, kind (create | edit | delete | move), href, base_etag, new_ics, created_at, attempts, state`. A change is applied to the local copy at once (the screen updates, a small "waiting to sync" mark shows) and queued. The store's `version()` is bumped by every local apply so `reload()` redraws.
- **Push before pull:** `SyncEngine` sends queued changes in order, then pulls. A network failure leaves the row queued; a 412 turns it into a conflict; other 4xx/5xx are shown in Settings with the status code.
- **Push on its own:** a WorkManager job that waits for a network connection runs as soon as something is queued, so it does not wait for the 15-minute refresh (target from the plan: reaches Nextcloud within 1 minute of the tailnet coming back).
- **Patcher** (`core-data`, pure Kotlin, JVM tests): edits the raw item text line by line (set, replace or remove one property; add or remove a `VALARM` block), unfolds and refolds long lines, and bumps `DTSTAMP`, `LAST-MODIFIED`, `SEQUENCE`.
- **Move to another list** = create in the new list, then delete in the old one, queued as one pair; a failure between the two leaves the original in place (never zero copies).
- **Sentence parser** (`core-data`, pure Kotlin, no network): a test table of Greek and English phrases. Parts become chips as you type; tapping a chip turns it back into text. Syntax: `!1` High, `!2` Medium, `!3` Low; `#tag`; `@list`; dates and times such as "αύριο 6μμ", "tomorrow 6pm", "next Monday", "every Monday".
- Reuse BookahCalendar's conflict dialog and writer by copying; that repo is not changed.

## 4. Builds, in order (each ends with tests; the owner is asked only at the gates)
- **Build A: write core** (`core-data`, no screens): outbox, push step, patcher, 412 handling, JVM tests with a fake server. **Gate:** a round trip on a throw-away list with the owner's yes: create, edit, delete, and a conflict made by editing the same task in the web app; counts and status codes only.
- **Build B: editor and actions:** tick (round tick box becomes live), task page becomes the editor (title, notes, when, deadline, reminders, repeat, list, tags, priority, subtasks, link), "+" button with a quick-add sheet taking a plain title, delete with Undo bar, move to another list, "waiting to sync" mark, Settings: default list.
- **Build C:** sentence parser and chips, swipes (right = done, left = reschedule), Undo for every complete, delete and move, drag order, conflict dialog polish. Drag goes last: nobody has seen what a drag in the web app writes to `X-APPLE-SORT-ORDER`, so use gapped integers and check the web app after a drag.

## 5. Decisions (defaults chosen; the owner can change them)
| # | Question | Default | Why |
|---|---|---|---|
| 1 | Which list is the inbox and the default for new tasks | His existing **"Tasks"** list; nothing is created | It exists and is his; the plan allows it |
| 2 | Ticking a **repeating** task in Phase 2 | It moves start and deadline to the next date from its rule (one task per series) | Matches the web app and the plan; a plain "complete" would quietly end his series. "Repeat after I complete it" is Phase 3 |
| 3 | Ticking a **parent with open subtasks** | **The owner chose (17 Nov): the parent and all its open subtasks are completed** (default had been parent only). One Undo restores all of them | His decision |

## 6. Not in Phase 2
Several reminders with notification actions, "waiting for", Evening logic changes, "repeat after I complete it" (Phase 3); the overdue reschedule buttons, Day strip, share, tile, search (Phase 5); widgets and shared lists (after 1.0).

## 7. Approval
**Approved by the owner on 17 Nov 2026** with his answers: default list = existing "Tasks"; repeating tick = move to the next date; parent tick = parent and all open subtasks. Original text: The owner answers the three decisions in section 5 (or says "defaults") and says yes to this design. Then the implementation plan is written and Build A starts.
