# BookahTasks Phase 2 report: capture and editing (17 to 18 Nov 2026)

**Status:** built and released as pre-releases v0.2.0 to v0.5.1 (private repo); 0.5.1 (the quick-add crash fix) was published on 18 Nov. Phase 2 is the first phase that changes Nextcloud. It was approved on 17 Nov (`docs/phase-2-design.md`) and grew after the owner used 0.2.0 on his phone and asked for more. **Waiting for the owner:** his feedback on the newest builds, then his "Start phase 3". Nothing in this report contains the server address, the user name, the app password or the contents of his real lists.

## 1. What Phase 2 delivered, in plain words
The owner can add a task in a second (even with no network), tick it, edit it, move it, delete it with an Undo, and it reaches Nextcloud by itself and shows in the Nextcloud Tasks web app. If the same task was changed in two places nothing is lost: the app shows both versions and he chooses.

**Planned (the approved design):**
- **Write core (Build A):** a line-careful editor of the stored task text (every line it does not touch, such as alarms and other apps' lines, comes back unchanged), an outbox (a queue of changes that survives the app being closed), push before pull, safe writes (`If-None-Match` for new, `If-Match` for edits and deletes, delete goes to the Nextcloud trash bin), conflict handling (keep mine, keep Nextcloud's, keep both), and a background job that sends as soon as the phone has a network.
- **Editor and actions (Build B):** "+" quick add, tick box with Undo, the task page as an editor (title, notes, start, deadline, priority, tags, list, Someday, subtasks, delete), move between lists, "Waiting to sync" mark, conflict dialog, Retry or Discard for refused changes, a warning on sign-out while changes wait, default list in Settings.
- **The owner's three answers:** the default list is his existing "Tasks" list (nothing created); ticking a repeating task moves it to its next date; ticking a parent finishes the parent and all its open subtasks (one Undo brings them back).

**Extras the owner asked for after using 0.2.0 (18 Nov):**
- **Schedule as the home screen** (first in the menu, opens at start).
- **Reminders:** a notification at the start and one hour before a timed deadline; a date without a time reminds at 09:00; "due now" when the deadline is under an hour away; Done and Snooze buttons on the notification; rebuilt after a reboot, an update or a time-zone change.
- **Quick add by sentence** (Greek and English): "tomorrow 6pm", "αύριο 6μμ", "next Monday", `!1` `!2` `!3` for priority, `#tag`, `@list`, deadlines ("by Friday"). Understood words are tinted and become chips; tapping a chip turns it back into plain text. The "+" sheet also has When, Deadline, priority and list chips, and the keyboard's Done key adds the task and keeps the sheet open for the next one.
- **Swipes:** right = done, left = reschedule sheet; the same actions are on the task page and as screen-reader actions. Overdue tasks fold into one group on Schedule.
- **Deep link** `bookahtasks://task?...` that opens one task's page (made for BookahCalendar to open a task; it works only for an app that names BookahTasks directly, not from a web page).
- **Drag order, built as a Reorder mode** (a Reorder / Done button on a writable list, a grip on each row, up and down arrows). This differs from the design spec, which said "long-press a row to drag": see section 5.

## 2. Versions
| Version | Date | What it shipped |
|---|---|---|
| v0.2.0 | 17 Nov | Builds A and B: the first build that can change his real lists (release notes said so). 5.8 MB |
| v0.3.0 | 17 to 18 Nov | Schedule as home, "+" sheet with When / Deadline / priority / list, reminders and notifications, start 00:00 and deadline 23:59 written by the editor read back as dates |
| v0.4.0 | 18 Nov | Build C: sentence parser with chips, swipes, reschedule sheet, folded overdue on Schedule, drawer opens by the menu button only |
| v0.5.0 | 18 Nov | Build D: deep link, drag order (Reorder mode) in list screens |
| v0.5.1 | 18 Nov | Fix for the quick-add crash (section 6). Published as a private pre-release on 18 Nov; the owner confirmed it works |

(v0.1.2, the seasonal artwork, was published in between on 17 Nov; it is not Phase 2 work.)

## 3. Verified, and how
- **JVM tests (no phone needed):** 43 at the end of Phase 1; **97** after Build A; **135** after Build B; **188** after Build C and the reminders; **223** after Build D (4 skipped by design: they need private data); **224 at 0.5.1**, 0 failures. Every serious fix in the review passes began with a test that failed first.
- **Live checks on the owner's real Nextcloud, always on throw-away lists `bookahtest-...`, each with his yes at that moment, each removed for good afterwards (204, nothing left), counts and status codes only:**
  - Build A (`bookahtest-a1`): create, edit, a deliberate clash (1 conflict, no overwrite), keep mine, keep Nextcloud's, delete. Passed, and repeated on the final code after the review fixes.
  - Build B (two lists): run 1 failed on purpose-found ground (Nextcloud refuses a start with no time combined with a deadline with a time; fixed), run 2 passed every step including a **move between two lists**, a repeating tick, subtasks, and an undo after the delete had been sent. Repeated on the final code before 0.2.0.
  - Order write (Build D, `bookahtest-order`): the stored order values were written and read back as planned. **This checks the stored property only, not what any app displays.**
- **Emulator** (own test device, generated data, a fake server so every change stays queued): "+" and quick add, tick and Undo, the waiting mark, the editor and its pickers, delete and Undo, database upgrades 1 to 2 to 3 with rows kept, the conflict dialog and the refused-change rows (shown with debug test data), reminders firing with Done and Snooze, the typed sentence lighting up chips, both swipes, the deep link cold and warm (one task in Recents), junk links without a crash, quick-add text kept under a link, Reorder mode with drags to the top, arrows on a subtask and a renumbering tie.
- **Independent reviews:** a fresh reviewer read each build (Build A: 4 serious, 7 important; Build B: 4 serious, 10 important; reminders: 1 serious found first by the advisor, 8 important; Build C: 1 serious, 14 important; Build D: a fix pass as well). All serious and important points were fixed. The worst were a move that could delete the original although the copy never arrived, an undo that dropped an unsent edit, and a delete made during a send that was silently undone.
- **On the owner's phone:** he used 0.2.0 and gave the feedback that produced the extras. He also tried BookahCalendar 1.3.0 ("Everything works"), which is not this app.

## 4. NOT verified (do not assume)
- **The owner's phone for the newest builds:** 0.4.0, 0.5.0 and 0.5.1 have not been seen on it; the only phone feedback recorded is on 0.2.0.
- **Reminders on HyperOS** (his phone may stop them unless Autostart is on and battery is set to "No restrictions"), and how soon the background push runs after the tailnet returns.
- **TalkBack** by ear (labels and actions exist and compile; "Moved to position N of M" was never heard).
- **Greek on screen** for the newer strings (dialogs, Settings additions, reorder texts): the keys match English, but many were never looked at.
- **Screens never looked at:** the sign-out dialog and the move dialog; the **drop line in the middle of a list** while dragging (hidden under the lifted row in every screenshot); swipes with gesture navigation (an edge swipe is the system Back).
- **The Nextcloud Tasks web app's display of the order** we write: not checked. A task with no order and no creation date sorts last in our app; the web app's source suggested it would put such a task first. The owner should compare a reordered list in the web app.
- A real BookahCalendar sending the deep link; a very large queue; very large text in the pickers; the sentence parser in daily use.

## 5. Decisions taken (full lists in the decisions files)
- Default list is the existing "Tasks"; repeating tick moves to the next date; parent tick finishes its open subtasks (The owner).
- A move is "create in the new list, then delete the old one", and the delete waits for the create, so a failure never leaves zero copies.
- Delete or move of a parent takes its subtasks along, with one Undo.
- Reminders are worked out on the phone from the start and deadline, nothing extra is written to Nextcloud; a reminder kept for a hidden list is silenced (same as BookahCalendar).
- A start with no time next to a deadline with a time shows the start as 00:00 (the server requires both to be the same kind).
- Typed words beat a chip they would contradict; dotted times such as 18.30 need "at" or "στις"; "at 5 Main Street" may be read as a time, but the tinted words and chip make it one tap to undo.
- Drag order is an explicit Reorder mode, not long-press, and it writes the order in whole-number steps: one task halfway between its neighbours, or only the smallest run around it renumbered. **`docs/design-spec.md` still says long-press and has not been corrected.**
- The deep link opens a screen only, never starts an action; a task in a hidden list counts as not found.

## 6. Lesson learned: the 18 Nov quick-add crash
The owner typed "Take th" in the "+" sheet on Schedule and the app crashed. **Cause:** the sentence parser reads the word after a lead-in word such as "the", "on", "στις", "την", "το", "τις". The keyboard completed "th" to "the", which was the last word typed, and the parser read past the end of the text. The mistake came in with the Build C parser rewrite, so **0.4.0 and 0.5.0 were both affected.** **Fix (0.5.1):** a bounds check, a test for exactly this case (seen failing first), and a **6000-case fuzz test** that throws random word salad at the parser; a later commit also makes the sheet treat the text as plain if the parser ever throws. **Lesson:** the parser runs on every keystroke, so it must answer every possible input; before this, the false-positive tests covered real phrases but not half-typed ones. Keep the fuzz test and add a similar one for any code that reads text as it is typed.

## 7. Deferred items (honest list, from the decisions files)
**Not built in Phase 2:** several reminders and their editing, repeat-rule editing, "repeat after I complete it" (Phase 3 by plan); share, tile, search, Day strip (Phase 5); widgets and shared lists (after 1.0); auto-scroll while dragging.
**Known behaviours accepted or left:**
- A reminder missed while the phone was off is not shown afterwards; an overdue repeating task has no reminder until it is ticked; a typed time already past today saves today.
- A renumbering in Reorder mode is several separate writes: if the server refuses one, the order on the server can be mixed until the conflict is resolved (nothing is lost).
- A conflict on the delete half of a move reads "You deleted it" (rare, safe by default).
- Each action queues another delayed full sync (ten quick edits mean ten syncs).
- Signing out does not stop a sync that is running; the conflict dialog acts on the first conflict at tap time, not always the one shown, and cannot be dismissed.
- Editing the deadline of a task rewrites its start from the parsed value (a time-zone or floating start becomes UTC); a repeat's excluded dates are ignored when advancing; a repeating subtask advances instead of finishing when its parent is ticked.
- 408, 423 and 429 answers are treated as permanent refusals; a 5xx answer retries only every 15 minutes; failed and conflicting rows freeze their task against updates from the server.
- Installing an older version over a newer database would crash (no downgrade rule), recorded as a note for any hotfix on an old build.
- Swipe, "This evening" and "Someday" offered when they make little sense (Sunday, after the evening hour); chips lost when the phone is rotated; the swipe call used is marked deprecated in the library (it works).
- Absolute reminders from other apps do not move when a task is rescheduled.
- The Settings notification line does not refresh on return; reminder text for stored lines shows the time as stored.
- The in-app updater is still not built; the seasonal artwork is AI-made raster and its use in a public release is undecided.

## 8. What Phase 3 starts with
The owner's "Start phase 3" comes first, after his feedback on 0.4.0, 0.5.0 and 0.5.1 on the phone. Scope from the plan: **editing the repeat rule, several reminders per task (with notification actions), "repeat after I complete it".** Before building: (1) confirm on his phone that reminders ring on HyperOS, (2) ask him to look at one reordered list in the Nextcloud Tasks web app, (3) correct `docs/design-spec.md` for Reorder mode, (4) done: 0.5.1 is published and the owner says it works, and (5) decide which of the deferred items above join Phase 3 (the repeat-advance gaps and the reminder-after-power-off gap touch the same code). Any change to the write path needs the throw-away list check again, with his yes first.

## 9. Pointers
`HANDOFF.md`; `docs/phase-2-design.md`; `docs/phase-2-build-a-report.md`, `docs/phase-2-build-b-report.md`; decisions in `docs/phase-2-build-a-decisions.md`, `docs/phase-2-build-b-decisions.md`, `docs/reminders-quickadd-buildc-decisions.md`.
