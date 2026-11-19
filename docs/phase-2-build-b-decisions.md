# Phase 2 Build B: decisions ledger (17 Nov 2026)

Kept from the build session: rulings, fixes from the independent review, and deferred minors.

```
# ledger — Build B plan: docs/superpowers/plans/2026-11-17-phase-2-build-b-editor-and-actions.md
Ruling: reminders and repeat rules view-only until Phase 3 (plan's phase list; patcher preserves them) — cost if wrong: The owner waits one phase for those pickers.
Ruling: delete/move of a parent takes its subtasks along; one Undo restores all — avoids orphans — cost if wrong: a surprise if he wanted only the parent.
Ruling: ticking a repeating parent advances only the parent — cost if wrong: subtasks stay as they were.
T1+T2: complete (ea8f23a, tests: :core-data:testDebugUnitTest -> 117/117; RED seen first both times: unresolved keepParams/readAllProperties, unresolved TaskEdit)
T3: complete (e8406b1, tests: :core-data:testDebugUnitTest all pass; RED seen first: unresolved recreate)
T3: Ruling/note: my previous T3 commit was made with 1 test red (movingAnEditedItem...: a queued Edit row was converted in place into the Delete, queued BEFORE the Create); real bug, fixed in the next commit (f079aef) — suite green after. Branch is not merged, so no red state reaches main.
T4: code written (22d9398); skipped without BOOKAH_LIVE_SERVER; NOT RUN yet: needs the owner's yes (writes to his server).
T4: live gate run 1 (The owner's yes, 17 Nov): create parent was REFUSED by the server (pushFailed=1) and both test lists were removed (204, 0 left). Cause (likely, to confirm by run 2): my sample task and the editor's date rule wrote a DATE-only DTSTART with a timed DUE; iCalendar/Sabre requires identical value types. Fixed: setDates makes a mixed pair timed (start 00:00, deadline 23:59), test written first (2 RED then GREEN, suite 128/128). Ruling: this is a consequence worth telling the owner: a start date with no time plus a deadline with a time shows the start as 00:00.
T4: complete — live gate run 2 (The owner's yes): all steps passed on two throw-away lists (create, repeating advance keeps RRULE/VALARM/sort/someday, fields, subtask, tick, delete, undo after send, MOVE between lists incl. parent link, move back, cleanup); lists removed 204, 0 left. Learned: Nextcloud accepts the same UID in a second calendar while the first copy exists (create-first move works). B1 gated.
B2 (screens): complete (403f450); verified on emulator-5558 with the debug seeder (fake server keeps every write queued): FAB + quick add (goes to Tasks), tick + Undo bar, Inbox shows 'Waiting to sync', editor (priority dialog, date picker, time picker, No time, subtask add with waiting mark), delete + Undo restores parent+subtask with fields; JVM suite green. NOT visually verified: conflict dialog, sign-out dialog, move dialog, Settings default list/failed rows, Greek strings (compile-checked only).
Final review B (fresh reviewer, opus): 4 critical (C1 move can leave zero copies; C2 undo of delete drops a queued edit; C3 closing the page writes stale title/notes; C4 parallel actions erase each other), 10 important, ~15 minor. Fix pass in ONE go, core fixes test-first.
Final B: fixed C1 (move can leave zero copies: delete waits for its create via OutboxItem.after, discard/keepServer of a refused move create restores the original) — theDeleteOfAMoveWaitsForItsCreate, discardingTheRefusedCreateOfAMoveRestoresTheOriginal RED→GREEN, suite 134/134
Final B: fixed C2 (undo of a delete dropped a queued edit) — undoOfADeleteKeepsAQueuedEdit RED→GREEN
Final B: fixed I1 (undo of a move before the push) — undoOfAMoveBeforeThePushLeavesNothingQueued RED→GREEN
Final B: fixed I2 (undo while the delete is in flight) — undoWhileTheDeleteIsBeingSentIsNotLost RED→GREEN
Final B: note: SQLite outbox gets a column after_href (database version 3, ALTER for 2→3); verify upgrade on the emulator
Final B: fixed C3 (closing the page wrote stale title/notes) — dirty flags per field + re-seed when not dirty (UI; compile + emulator run, no JVM test: Compose state)
Final B: fixed C4 (parallel actions erase each other) — all write actions go through one Mutex lane `write {}` in AppViewModel (UI/VM; emulator run)
Final B: fixed I3 (undo of a tick restored a stale snapshot) — undo restores only items whose text is still exactly the post-tick text, and touches DTSTAMP/SEQUENCE
Final B: fixed I4 (double tap advanced a repeating task twice) — toggle acts only if the stored task still matches what was drawn
Final B: fixed I5 (some errors crashed) — guarded catches Exception (rethrows cancellation)
Final B: fixed I6 (finished copies of a repeating task acted on the whole series) — copies cannot be opened or ticked
Final B: fixed I7 (typed text lost when the open task changes) — commit() before opening a subtask and before a move
Final B: fixed I8 (tick box had no name/role/state for TalkBack) — toggleable with Checkbox role, task name and state
Final B: fixed I10 (quick add could target hidden lists) — targetList only uses visible, writable lists
Final B: fixed (minor) duplicate quick add on double tap; DatePicker year range; Logbook duplicate LazyColumn key (pre-existing crash risk); title newlines; PushWorker comment corrected
Final B: Ruling: I9 (a conflict on the delete half of a move reads 'You deleted it') not fixed — rare (the original must change on the server in the seconds around a move) and safe by default (keep Nextcloud's cancels the move); cost if wrong: one confusing dialog.
Final B: minor (deferred): conflict dialog actions use the first conflict at tap time, not the one shown; dialog cannot be dismissed and same-title conflicts look identical
Final B: minor (deferred): signOutNow does not take syncLock or cancel PushWorker (a running sync can re-insert after clearAll); signOut reads the outbox on the main thread
Final B: minor (deferred): every action appends another delayed full sync (APPEND_OR_REPLACE): ten quick edits mean ten back-to-back syncs
Final B: minor (deferred): setDates rewrites DTSTART from the parsed value when only the deadline changes (TZID/floating -> UTC); EXDATE/RDATE ignored by the repeat advance; a repeating subtask advances instead of completing when its parent is ticked; adding a time to a date-only repeating task with date-only UNTIL may mismatch value types
Final B: minor (deferred): ticks/edits allowed on read-only lists produce Failed rows; partial subtree delete still says 'Task deleted'; Settings count has no conflict count and 'changes' plural; reload() calls can finish out of order; subtree actions are not one transaction (kill mid-way = half done, no loss)
Final B: minor (deferred): live-buildb.ps1 uses the trash-bin bypass header (allowed for throw-away lists in tools/, as in Build A's script); TimePicker at very large font not checked
```
