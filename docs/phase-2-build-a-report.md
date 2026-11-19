# BookahTasks Phase 2, Build A report: the write core (17 Nov 2026)

**Status:** done, reviewed, fixed and gated twice (before and after the review fixes). Decisions and deferred minors: `docs/phase-2-build-a-decisions.md`. Nothing visible changed in the app and nothing was released: this build gives the data layer the ability to create, edit and delete tasks on Nextcloud safely. Screens that use it are Build B.

## What was built (branch `phase-2-build-a`, nine commits)
1. **IcsPatcher**: edits one task's stored text line by line; every untouched line (alarms, `X-` lines, other apps' folding) comes back byte for byte; long Greek titles fold on UTF-8 boundaries; works on the series, not on a completed copy.
2. **Outbox**: every change is applied on the phone at once and queued, one row per task (repeated edits share a row; a create that is deleted before sending leaves nothing to send).
3. **SQLite outbox table** (database version 2, upgrades the existing version-1 database).
4. **CalDAV writes**: `PUT` with `If-None-Match: *` (new) or `If-Match: "<etag>"` (edit), `DELETE` with `If-Match` into Nextcloud's trash bin (no bypass header anywhere in app code), `GET` of the server's copy.
5. **Push before pull** in the sync engine; a pull never overwrites, resurrects or removes a task that still has a queued change, and a full resync keeps them.
6. **Conflict handling**: when the server copy changed meanwhile, nothing is overwritten; the row waits with both versions; keep mine, keep the server's, or keep both (the code the later dialog will call). Refused changes (4xx) can be discarded.
7. **PushWorker** (sends as soon as the phone has a network) and the outbox in the app graph.
8. **Live round trip** script and test.

## Verified
- **JVM tests:** 97 pass, 2 skipped by design (the live test and the real-data test, which need private data), 0 failures. New: 9 patcher, 9 outbox, 6 CalDAV write (against a local mock server, checking the exact headers), 13 push, 7 conflict, 11 from the review fix pass.
- **Emulator** (`BookahTasksTest`, generated data): the version-1 database upgraded to version 2; insert, update and remove of an outbox row worked in the real SQLite, in two separate app processes; the app still starts on Today with seeded tasks, no crash.
- **Live gate on the owner's real Nextcloud, throw-away list `bookahtest-a1`, run with his yes (17 Nov):** list created (201); create pushed 1; edit pushed 1; a deliberate clash gave 1 conflict and no overwrite; keep mine pushed 1; second clash resolved with keep server, queue empty; delete pushed 1; the list was deleted for good (204) and 0 mentions of `bookahtest-` remain. His real lists were not touched. Counts only (`C:\the build PC\bookahtasks\live-roundtrip.txt`).

## Independent review and the fix pass
A fresh reviewer (the most capable model, who had not seen my work) read the whole branch. It found four serious problems and seven important ones, all real, all fixed with a failing test first: a delete made while a send was in flight could be silently undone (the task came back); a failed follow-up request could crash the sync; a write whose answer was lost looked like a conflict; a redirected write counted as success; "keep both" broke on repeating tasks; "keep the server's" could leave a stale copy; one failing task could block the whole queue; a create could be lost if the app was killed at the wrong moment. One more is a Build B requirement (signing out must not silently delete unsent changes). About 18 smaller points are noted for later in the ledger. The live gate was repeated on the final code after the fix pass (17 Nov, with the owner's yes): same result, all steps passed, throw-away list removed again (204, 0 left).

## Decisions I took (also in the ledger)
- Worked on a branch in the main checkout (not a worktree): the Android build needs untracked local files.
- Two plan test assertions were wrong, not the code (a foreign long line is left alone by design; the reader returns CR LF for a newline in text). Fixed in the tests.
- A plan test class name clashed (`Rig`); renamed.
- A guard I claimed in the first version only covered a lost connection, not a 401 or server error on the follow-up request: the reviewer caught it and it is now really fixed and tested.

## Not verified yet
- How fast HyperOS runs the push after the tailnet comes back (the worker exists, nothing calls it until Build B).
- A very large queue.
- How the Nextcloud Tasks web app shows a task after the patcher edited one with alarms (needs real edits from Build B).
- A failed (4xx) change has no way out except discard, which has no screen yet (Build B).

## Next
Build B (editor and actions): tick (with the rules the owner chose: repeating moves to the next date; a parent completes its open subtasks), task page as editor, the "+" button with a plain title, delete with Undo, move, the waiting-to-sync mark, default list. First build that can change his real list, so its release notes will say so.
