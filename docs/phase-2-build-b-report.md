# BookahTasks Phase 2, Build B report: editor and actions (17 Nov 2026)

**Status:** built, reviewed by a fresh reviewer, fixed, and checked on an emulator and (the editing logic) on two throw-away lists on the owner's Nextcloud. **Not released and not merged**: this is the first build that can change his real lists, so it waits for his yes (branch `phase-2-build-b`). Decisions and deferred minors: `docs/phase-2-build-b-decisions.md`. Plan: `docs/superpowers/plans/2026-11-17-phase-2-build-b-editor-and-actions.md`.

## What the app can do now
- **"+" button and quick add:** a title goes to the list on screen, else the default list from Settings, else the inbox ("Tasks"). Saved at once, sent when Nextcloud is reachable.
- **Tick box (its own 48 dp target):** finishes a task; a repeating task moves to its next date; a parent also finishes its open subtasks (The owner's choices). Ticking a finished task reopens it. Every tick, delete and move shows an **Undo bar**.
- **Task page as the editor:** title, notes, start, deadline (date picker then time or "No time"), priority, tags, list (moves the task and its subtasks), "Someday" switch, add subtasks, delete (with its subtasks, one Undo). Reminders and repeat rules are shown but not editable until Phase 3.
- **"Waiting to sync"** mark on each task whose change has not reached Nextcloud yet.
- **Conflicts:** a dialog (keep mine, keep Nextcloud's, keep both). **Refused changes:** Retry or Discard in Settings. **Sign out** warns while changes are waiting. **Settings:** default list, count of waiting changes.

## Verified
- **JVM tests:** 135 pass, 3 skipped by design (two live tests and the real-data test), 0 failures. New in Build B: patcher, task edits (dates, repeating advance, tags, priority, complete), outbox undo/move/retry, 18 tests from the two review fix passes.
- **Live gate on throw-away lists** (The owner's yes, run twice): the first run found that Nextcloud refuses a start without a time combined with a deadline with a time (the standard requires the same kind); fixed. The second run passed every step: create, repeating tick (RRULE, reminder, sort order, hidden tag kept), field edits, subtask, tick, delete, undo after the delete was sent, **move between two lists** (Nextcloud accepts the same task ID in a second list), move back, cleanup (lists removed for good, none left).
- **Emulator** (generated data, fake server so every change stays queued): "+" and quick add, tick and Undo, the waiting mark, the editor (priority, date picker, time picker, "No time", subtask add), delete and Undo restoring parent, subtask and fields, database upgrade 2 to 3 with rows kept.
- **Strings:** English and Greek have the same keys and format specifiers.

## Independent review and the fixes
A fresh reviewer found four serious problems and ten important ones; all serious and important ones are fixed. The worst: **a move could delete the original although the copy in the new list never arrived** (now the delete waits for its create, and giving up on a refused move puts the original back); undoing a delete dropped an unsent edit; closing the task page could overwrite a newer title from Nextcloud; two quick actions could erase each other (all changes now run one at a time). Core fixes have tests; screen fixes were checked on the emulator. About 15 smaller points are listed in the decisions file.

## Not verified yet (do not assume)
- The conflict dialog, sign-out dialog, move dialog, Settings default list and failed-change rows, and every new Greek string have **not been looked at** on a screen (compile-checked only).
- On the owner's phone: nothing from Build B (the build is not installed).
- A second live run of the gate **after** the review fixes (the move pairing and undo changes touch the write path): to be done with his yes before release.
- Background push timing on HyperOS; very large queues; TalkBack by ear; large font sizes in the pickers.

## Next
With the owner's yes: run the live gate again, merge, and publish 0.2.0 as a pre-release with release notes saying **this is the first build that can change his real lists**. Then Build C: the sentence parser with chips, swipes (done, reschedule), drag order, polish.
