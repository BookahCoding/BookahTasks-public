# BookahTasks design spec (draft 1, 16 Nov 2026)

Status: **approved by the owner on 16 Nov 2026** ("1 yes, 2 sure, 3 sure, 4 yes"), with priority as a flag glyph plus a word in the muted line. Built so far (Build B): everything in section 2 that Phase 1 covers, except the Today header artwork, the drawer header strip and the empty-state artwork (Build C), and "Week of" grouping in Upcoming (it groups by day for now). Earlier status: draft, waiting for approval. No screen is built before he says yes (plan, section 7). Changes after approval need his yes. Structure and wording are my own proposal from the plan's research (Things' calm, TickTick's speed); **no screenshots were used and no other app's artwork or wording is copied.** If the owner saves screenshots he likes into the vault's `design-reference/`, I adjust.

## 1. Principles
- **Calm first.** Much white space, one accent per row, no badges, streaks or points.
- **Capture is instant** (Phase 2); Phase 1 is read-only, so the "+" button is shown only from Phase 2.
- **Nextcloud is the truth.** Every screen shows the phone's cached copy and a small "last synced" line; a failed sync never empties a screen.
- **Greek first-class:** long Greek titles wrap (never cut to one line), Greek dates and relative words ("σήμερα", "αύριο", "σε 3 μέρες"), Greek and English strings from day one, 24-hour clock, week starts Monday.

## 2. Structure

### 2.1 Global
- **Top app bar** (56 dp, Surface): menu button, the screen's title (large, 22 sp), a sync indicator (spinner while syncing; a quiet cloud-off mark when offline). Search comes in Phase 5.
- **Drawer** (80% width, SidebarSurface): header strip with our artwork and the app name; the smart views **Today, Inbox, Upcoming, Someday, Logbook** (icon, name, count on the right, current one in a filled pill); then **Lists** (colour dot in the list's own colour, name, open count; tap shows that list; a small eye toggles show/hide); a footer link to **Settings**.
- **Task row** (min 56 dp, grows with text): round tick box at the left (about 24 dp, outlined; filled with the list colour when done; **read-only in Phase 1, so it shows state only and a tap opens the task**), title (16 sp, wraps to 3 lines), below it one muted line (14 sp) with what matters: deadline or time, list name when not in a list view, tags as small chips, and small marks for repeat, reminders, link, subtasks ("2 of 3") and "waiting to sync" (Phase 2). Overdue dates are in the Danger colour, and **only they are**: red means one thing. **Priority is a small flag glyph with a word in the muted line ("High", "Medium", "Low"), in the muted text colour, not a colour**; it is read aloud and works without colour. The quick-add syntax of Phase 2 matches it: `!1` = High, `!2` = Medium, `!3` = Low. (Default decided with the advisor, 16 Nov; the owner can change it.) A subtask is indented by 32 dp under its parent.
- **Task page** (full screen, read-only in Phase 1): close at the left, title (28 sp), then rows with a leading icon: notes, when (start), deadline, reminders (each in words, "30 minutes before the deadline"), repeat in words, list (colour dot), tags, priority, link (opens the browser), subtasks, and the Nextcloud sync time. Phase 2 turns it into the editor.
- **Empty states** carry our artwork and one warm sentence ("Nothing for today. Enjoy it." / "Τίποτα για σήμερα.").
- Edge to edge; touch targets at least 48 dp; text follows the system size (rows reflow, never clip).

### 2.2 Today
Order, top to bottom: (1) **overdue group**, one folded row "Overdue · 3" (red count, chevron); opened it lists those tasks; in Phase 5 it gains the reschedule buttons (Today, Tomorrow, Weekend, Next week, Someday). (2) The day's tasks. (3) A heading **This evening** (from 18:00, setting) with its tasks. The Day strip of calendar events comes in Phase 5. Header: our banner art with the date in words ("Monday 16 November").

### 2.3 Inbox, Upcoming, Someday, Logbook, a list
- **Inbox:** open undated tasks (of the "Tasks" list once it exists; before that, of every list).
- **Upcoming:** day headings ("Tomorrow", "Wednesday 18 November", then by date; later weeks as "Week of …"), tasks under them. Tasks sleep here until their start date.
- **Someday:** parked tasks (tag `someday`), a plain list.
- **Logbook:** finished tasks grouped by the day they were finished ("Today", "Yesterday", dates), newest first, titles struck through.
- **A list:** its open tasks in the manual order (`X-APPLE-SORT-ORDER`), subtasks nested; the list's colour on the title dot. Finished tasks are in the Logbook, not here.

### 2.4 Sign-in and Settings
- **Sign-in** (first start): three fields, server address, user name, **app password in a hidden field**, and one line saying where to make an app password; "Sign in" tests the connection and says in plain words what is wrong (wrong password, no network: "is Tailscale on?"). Nothing is stored until it works; the password goes into the Android Keystore.
- **Settings:** Account (address and user, **Sign out**), General (Evening starts at: 18:00; Week starts on: Monday; Language: system / English / Greek), Look (theme gallery of the 11 families with live preview; Light / Dark / System), Sync (last sync time, "Sync now", note that background refresh runs about every 15 minutes), Updates (manual check against the public GitHub releases list once a public repo exists; off in this build), About (version, credits, open-source licences, crash log kept on the phone).

## 3. Skin (same system as BookahCalendar and Hikyaku)
- **Themes:** the 11 Hikyaku families, each Light and Dark, default **Washi & Ink**; neutral off-white or black large surfaces plus two accents (primary for the main action and today marks, secondary for selection and switches). The token code and the picker are copied from BookahCalendar (`ThemeCatalog`, `Contrast`), unit-tested for contrast.
- **Layer mapping** as BookahCalendar's spec 3.2: screen Bg, bars and sheets Surface, drawer SurfaceSidebar, cards/groups SurfaceRaised (light mode adds a 1 dp Border hairline), dialogs and menus Surface3 (dark).
- **List colours** are user data from Nextcloud and shown exactly (tick box fill, dots). Text on a filled tick box is black or white by contrast, as in the calendar app.
- **Artwork:** original ukiyo-e vector drawings, tinted from the theme: the drawer header strip, Today's header banner, and empty states. Never behind task text.
- **Icon:** ukiyo-e style in Hikyaku's palette (vermilion `#A63F2D`, washi `#F4EDD9`, sumi ink `#2B2822`, indigo `#245A86`), a **brush-stroke tick**; adaptive with a monochrome layer; a family with the Hikyaku and BookahCalendar icons. **Three drafts** for the owner to choose from (a separate sheet, `docs/icon-drafts/`, made after this spec is approved):
  A: a vermilion sun disc with a sumi brush tick across a washi square; B: a great-wave crest curling around a tick; C: a bamboo stem forming a tick on an indigo field.
- **Type and shape:** system font (reads Greek well), title 28 sp, large title 22 sp, body 16 sp, secondary 14 sp, chips 12 sp; radii 4, 8, 12, 16 dp and full round; motion 180 ms, none under reduced motion.

## 4. Accessibility
Every tick box, chip and mark has a spoken name ("Pay the bill, due tomorrow 18:00, high priority, repeats weekly, 2 reminders"); targets 48 dp; contrast checks for every theme in unit tests; works at the largest system text size (rows grow).

## 5. Items from the plan's section 7 that arrive later (nothing is dropped)
| Plan item | Where it appears | Phase |
|---|---|---|
| **Tags** in the drawer (a "Tags" group under the lists; tap shows tasks with that tag; `someday` and `waiting` are hidden there) | Drawer | 1 (read-only filter), editing tags in 2 |
| Settings: **Default list** (where quick add puts a task; default the new "Tasks" list) | Settings > General | 2 |
| Settings: **Default reminder** (default: at the task's time) | Settings > General | 3 |
| Settings: **Sync state and queue** ("N changes waiting to send", last error) | Settings > Sync | 2 (the queue exists from then; Phase 1 shows last sync and last error) |
| Search and saved filters | Top bar | 5 |
| Quick add, task editor, swipes, Undo, drag order, "+" button | Throughout | 2 |

## 6. Approval
The owner: **one yes** (or "change X") for this spec. The structure (smart views and lists in the drawer, Today with a folded overdue group and "This evening"), read-only ticks in Phase 1 and the three icon drafts (made in Build C, you only pick one) are already in the plan, so they are not questions.

## 7. Phase 2 addition (draft, 17 Nov 2026): what changes on screen
- **"+" button** (bottom right, 56 dp, the list's accent): appears in Today, Inbox, Upcoming, Schedule, a list. Opens a **quick-add sheet** from the bottom: one text field with the keyboard already up, a row of chips for what was recognised (date, time, repeat, priority, tag, list), "Add" at the right. A task added inside a list or in Today stays there; elsewhere it goes to the default list ("Tasks"). It saves with no wait, online or not.
- **Tick box becomes live:** a tap completes (fills with the list colour, the row folds away after a moment); a **Undo bar** at the bottom for 5 seconds after every complete, delete and move. A repeating task moves to its next date instead (the bar says so).
- **Task page becomes the editor:** every row is tappable (date and time pickers, reminder picker, repeat picker, list chooser, tag chips, priority menu, subtask list with an "add subtask" row, notes). Edits save as you leave a field; no Save button. Delete is in the page's overflow menu, with Undo.
- **Swipes (Build C):** right = done, left = reschedule sheet (Today, Tomorrow, Weekend, Next week, Someday, pick a date).
- **"Waiting to sync" mark:** a small cloud-up glyph at the end of the muted line, with the words in the spoken description; it disappears when Nextcloud has the change.
- **Conflict dialog:** both versions side by side (title, dates, notes), three buttons: keep mine, keep the server's, keep both (the server's becomes a second task).
- **Settings:** new **Default list** (General), and the sync section shows the number of changes waiting and the last error with its status code.
- **Drag order (Build C):** long-press a row in a list view to drag; nothing else drags.
