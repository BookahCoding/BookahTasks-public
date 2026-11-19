# Phase 2 Build A: decisions ledger (17 Nov 2026)

Kept from the build session so later sessions see every ruling and deferred minor. Plain log lines, newest last.

```
# SDD ledger — plan: docs/superpowers/plans/2026-11-17-phase-2-build-a-write-core.md
Spec: docs/phase-2-design.md (approved 17 Nov 2026)
Pre-flight: T1 IcsPatcher -> T2? no; -> T6 (setProperty UID), T8 (newTask/touch/setProperty/escapeText). T2 Outbox/OutboxItem/store methods -> T3 (Sqlite impl), T5, T6. T4 WriteResult/put/delete/get -> T5 (engine), FakeDav (T5) -> T6. T5 SyncReport fields -> T8. Names checked in plan self-review: consistent.
Ruling: work on branch phase-2-build-a in the main checkout, not a worktree — the Android build needs untracked local.properties/keystore.properties and cached Gradle state, a worktree would not build offline — cost if wrong: none (branch is local, merge to main later with the owner's yes).
Ruling: no task-done script; test command output checked by hand, ledger line written with each commit — scripts not needed for a single-module Gradle project — cost: none.
Task 1: Ruling: two plan test assertions were wrong, not the patcher — the foreign DESCRIPTION line in SAMPLE is >75 octets by design (octet check now covers only lines the patcher wrote), and biweekly returns text newlines as CRLF (compare normalised) — cost if wrong: none, assertions still check fold limit and round trip.
Task 1: complete (commit 4b95e4c, tests: :core-data:testDebugUnitTest -> 50/50 pass, IcsPatcherTest 9/9; RED seen first: unresolved IcsPatcher)
Standing rule for the rest: edit .kt files only with Write/Edit (AGENTS.md); no python string surgery on Kotlin.
Task 2: complete (commit d1829dc, tests: :core-data:testDebugUnitTest -> 59/59, OutboxTest 9/9; RED seen first: unresolved Outbox)
Task 3: complete (commit 75666e4, verified: emulator-5558 self-test RED 'NotImplementedError' then GREEN 'before=0 inserted=Create/Pending/null updated=Conflict/412/e/x/2 after=0' after upgrading a version-1 database, twice across force-stop; JVM suite all pass). Ruling: the appended OUTBOX_SQL constant was added with a one-line shell append (plain ASCII) instead of Edit — no effect on content.
Task 4: complete (commit 2c5bc13, tests: :core-data:testDebugUnitTest -> 65/65, CalDavWriteTest 6/6; RED seen first: unresolved put/WriteResult)
Task 5: Ruling: wrapped settle() and the 412 follow-up get() in IOException/CalDavException handling (plan code let them escape push) — a dropped connection or 401 right after a successful PUT must end the push as offline/auth, not crash the sync — cost if wrong: none. Plan said 14 PushTest tests; 13 were written in the plan text, all 13 run.
Task 5: complete (commit fa1924b, tests: :core-data:testDebugUnitTest -> 78/78, PushTest 13/13; RED seen first: unresolved pushed/conflicts on SyncReport)
Task 6: Ruling: plan defect — ConflictTest and PushTest both declared a private class Rig in the same package (Kotlin redeclaration); renamed to ConflictRig in ConflictTest — cost if wrong: none.
Task 6: complete (commit e12289b, tests: :core-data:testDebugUnitTest -> 85/85, ConflictTest 7/7; RED seen first: unresolved keepMine/keepServer/keepBoth)
Task 7: complete (commit b9a8fde, tests: :core-data + :app testDebugUnitTest -> 86/86; :app:assembleDebug ok; emulator-5558: app starts on Today with seeded tasks, no AndroidRuntime errors). Note: no JVM test for PushWorker (WorkManager); plan check was compile + smoke start.
Task 8: code written and committed (f6fca0a); live test is skipped without BOOKAH_LIVE_SERVER (suite stays green). NOT RUN: waiting for the owner's yes (stop condition: writes to his server).
Task 8: complete (live gate run with the owner's yes at 17 Nov: MKCOL 201, gradle exit 0, DELETE list 204, bookahtest- left 0; steps create/edit/clash/keep-mine/keep-server/delete all passed — counts only in C:\the build PC\bookahtasks\live-roundtrip.txt)
Final review (fresh reviewer, opus, whole branch): 4 critical, 7 important, ~18 minor, 5 declined-to-judge. Re-graded by effect; critical and important in ONE fix pass, each with a test written first (11 RED seen, then GREEN, suite 97/97, 2 skipped by design):
Final: fixed review-1 delete during an edit push undone — deleteDuringAnEditPushStaysDeleted RED→GREEN, suite 97/97
Final: fixed review-2 delete of a just-created task during its send lost — deleteOfAJustCreatedTaskDuringItsSendIsSentAsADelete RED→GREEN, suite 97/97
Final: fixed review-3 push sent from a stale copy of the queue — aRowChangedDuringThePushIsNotSentFromTheOldCopy RED→GREEN, suite 97/97
Final: fixed review-4 failed ETag lookup escaped the sync (and my Task 5 ledger claim was wrong: only IOException was caught) — aFailedEtagLookupAfterAWriteDoesNotEscapeTheSync RED→GREEN, suite 97/97
Final: fixed review-5 lost answer showed as a false conflict — aLostAnswerIsNotAFalseConflict, aLostAnswerOnACreateDoesNotMakeADuplicateConflict RED→GREEN, suite 97/97
Final: fixed review-6 redirected write counted as success — aRedirectedWriteIsRefusedAndNotFollowed RED→GREEN, suite 97/97
Final: fixed review-7 keep-both broke on repeating tasks (UID in completed copies) — keepBothRewritesTheUidInEveryPartOfTheItem RED→GREEN, suite 97/97
Final: fixed review-8 keep-server left a stale copy — keepingTheServersCopyClearsTheSyncToken RED→GREEN, suite 97/97
Final: fixed review-9 one failing lookup blocked the queue and the pull — aFailedLookupOnOneConflictDoesNotBlockTheRestOfTheQueue RED→GREEN, suite 97/97
Final: fixed review-11 create not atomic with its queue row — aCreateKilledBetweenTheTwoWritesStillHasItsRow RED→GREEN, suite 97/97
Final: Ruling: review-10 (sign-out deletes every unsent change) not fixed in Build A — it is behaviour of the sign-out screen (AppViewModel), which Build B touches; recorded as a Build B requirement (block or warn while the outbox is not empty) — cost if wrong: before Build B ships, signing out would lose unsent changes (nothing queues yet, so no loss today).
Final: Ruling: review-5 fix compares the server text with the queued text exactly; if Nextcloud rewrites the text on save the lost-answer case still shows as a conflict (safe, never an overwrite) — cost if wrong: one extra conflict dialog after a dropped connection.
Final: minor (deferred): setProperty deletes every repeated line of the same name (unsafe for CATEGORIES/RELATED-TO; Build B needs a multi-value API)
Final: minor (deferred): readProperty drops parameters (DUE;TZID=...) and splits on the first colon
Final: minor (deferred): setProperty replaces a line's parameters; caller must pass them (document for Build B)
Final: minor (deferred): patcher drops trailing blank lines / adds a final newline; mixed CRLF+LF text split; fixture test ignores empty lines
Final: minor (deferred): weak U+FFFD assertion and no 4-byte (emoji) fold test
Final: minor (deferred): settle stamps the new ETag on possibly stale local text; GET-after-PUT ETag may belong to another client's write (take text from GET too)
Final: minor (deferred): DELETE with If-Match on an already-gone item gives 412 (conflict) instead of simply done
Final: minor (deferred): retry rules: 408/423/429 treated as permanent; 5xx retries forever (PushWorker returns success on 5xx, so only every 15 min); a refusal can mark a newer unsent text Failed
Final: minor (deferred): no retry(id) for Failed rows; Failed/Conflict rows freeze the item against pulls (documented only in a test comment)
Final: minor (deferred): rows of a vanished list are dropped silently (no count reported)
Final: minor (deferred): no onDowngrade in SqliteTaskStore (installing an older version over a version-2 database crashes)
Final: minor (deferred): PushWorker APPEND_OR_REPLACE chains one full sync per enqueue
Final: minor (deferred): Outbox.count() and pendingHrefs() skip the store lock
Final: minor (deferred): push report counted pushed+1 on an offline return inside settle (now fixed incidentally: counter no longer incremented there)
Final: minor (deferred): debug self-test receiver can leave a row behind and is exported (debug builds only)
Final: minor (deferred): FakeDav.syncCollection never returns removed entries (pull-does-not-remove-locked path untested)
Final: minor (deferred): the literal 'X-NC-CalDAV-No-Trashbin' appeared in a CalDavClient comment (reworded during the fix pass)
Final: declined to judge (reviewer): weak ETags (cleanEtag strips W/), DUE with TZID/VTIMEZONE and DUE/DURATION combinations, hrefs needing percent-encoding, line endings of fetched text, host name in Gradle failure reports — all Build B concerns.
```
