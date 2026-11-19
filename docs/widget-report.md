# Today widget (1.1): report

Built on branch `widget` from `main` (1.0.0). Spec: `docs/superpowers/specs/2026-11-19-today-widget-design.md` (section 10 lists the changes made while building). Plan: `docs/superpowers/plans/2026-11-19-today-widget.md`.

## What was built
`WidgetRows` (rows of today's tasks, pure), `TaskCompletion` (the tick and reopen, shared with the notification's Done button), `WidgetPalette` (colours), and the Android widget: provider, list service, one invisible click activity, a tick receiver, layouts, English and Greek strings, redraw hooks (sync, push, app edits, notification buttons, theme/appearance/evening-hour changes, restart, date change).

## Tests (JVM)
`WidgetRowsTest` (12, includes a 2,000-set fuzz), `TaskCompletionTest` (10; removing the stale-stamp guard makes 2 of them fail), `WidgetPaletteTest` (2: every theme, light and dark, 4.5:1). Whole suite green (design, core-data, app).

## Seen on emulator-5558 (debug build, seeded fake account; shots in `C:\the build PC\bookahtasks\widget-shots\`)
- The widget in the system picker and placed on the home screen: themed panel, header with count and +, Overdue row, Today heading, rows with time, list name and Repeats, ring in the list colour.
- Tick (shots 07, 20): row struck through, circle filled, count drops; tap again reopens (08). Repeating task: row leaves, "Next date set: Morning stretches" appears (09). Row tap opens that task in the app (10). + opens quick add (11).
- A tick made in the app shows on the widget at once (20).
- System text 2.0: rows grow, nothing clipped, list scrolls (13). Dark mode after the fix: black panel, readable text, accent + (16); back to light (15).
- Greek (app language): headings and "Repeats" in Greek (17); after the header fix the title is set from the app's strings (not re-shot after the fix).
- Release configuration (R8, local build with the seeder): widget draws and ticks (26, 27).

## Bugs found on screen and fixed
1. Open-task circles were filled discs (the ring shape had no explicit empty fill). Fixed (06).
2. The widget stayed light when the phone went dark (only the current mode was drawn). Fixed with night-aware colours (12 vs 16).
3. The header title and the + description came from the layout and ignored an app-only language. Now set in code.

## NOT verified
- How the owner's HyperOS launcher updates widgets (some launchers delay or skip updates); TalkBack by ear.
- Resizing small and large, more than 30 tasks ("+ N more"), the empty state and the signed-out state on a real widget, a theme change in Settings, a sync from the server, a change of the date or time zone. (All are covered by the code paths and the pure tests, but not seen.)
- The ring colour of a list that is dark blue on a black panel is low in contrast (it is the list's own colour from Nextcloud, shown as it is, like in the app).
- The live write check (the tick code moved out of the notification handler) has not been run: it needs the owner's yes at that moment.

## Left to do (each with the owner's yes)
Live write check on a throw-away list, merge `widget` into `main`, release 1.1.0 (private build, then the public release).

## Whole-branch review (fresh reviewer, 19 Nov 2026) and the fix pass
- **Critical, fixed:** a task on a read-only list that is due today (not overdue) would have drawn as a permanent "Loading..." row: `setImportantForAccessibility` is not allowed in RemoteViews (checked in the SDK source). The line is removed. The emulator seed only has overdue read-only tasks, so this was not seen on screen, and the fix is not seen on screen either.
- **Important, fixed:** no redraw after sign-out (the widget kept the tasks), after a sync or sign-in seen by the app, or after hiding a list; the "today" change at midnight relied on a broadcast that manifest receivers do not get (an alarm now); undoing a parent's tick left its subtasks finished (test written first, RED then GREEN: `reopeningAParentReopensTheSubtasksTheTickClosedAndOnlyThose`).
- **Minor, fixed:** the error row is never blank; a tick without a stamp is refused; the tick receiver records errors in the on-phone crash log; the struck row and the note go two minutes after a tick (alarm).
- **Deferred minors:** priority on the ring, headings marked for screen readers, the conflict toast, a 3:1 ring check, a few tests that only echo their input (`WidgetRecent` expiry, the evening hour, subtask in conflict).
- Not re-checked on the emulator after these fixes (the build and the unit tests pass).
