# BookahTasks

A native Android to-do app (Kotlin, Jetpack Compose) for people who keep their tasks in **Nextcloud**. Your tasks stay in your Nextcloud task lists as standard CalDAV `VTODO` items, so the Nextcloud Tasks web app and other CalDAV apps keep seeing the same tasks. BookahTasks is a calm, fast front end on top of them, in the same look as its sister app BookahCalendar.

English and Greek. Greek: see [README.el.md](README.el.md).

## What it does
- **Schedule** as the home screen: overdue tasks first, then tasks without a date, then the coming days. Also **Today** (with "This evening"), **Inbox**, **Upcoming**, **Someday**, **Logbook** (finished tasks by day), one page per list, and tags.
- **Quick add by sentence**, in Greek or English: "Pay the electricity bill tomorrow 6pm !1 #home" or "Πλήρωσε ΔΕΗ αύριο 6μμ !1 #σπίτι" becomes a task due tomorrow at 18:00, high priority, tag "home". Recognised words light up as chips; tap a chip to turn it back into text.
- **Works offline.** Every change is saved on the phone first and sent to Nextcloud when the server can be reached; a small mark shows "waiting to sync". If a task was changed elsewhere in the meantime, you choose which version to keep.
- **Repeats**: daily, weekly on chosen days, monthly (a day of the month or "the third Wednesday"), yearly, every N, with an end date or a number of times; also "repeat after I complete it".
- **Several reminders per task** (at the start, before the start or the deadline, or at a set time), as exact alarms, restored after a restart. The notification has **Done**, **+1 hour** and **Tomorrow** buttons.
- **Swipes**: swipe right to finish a task, swipe left to reschedule it (Today, This evening, Tomorrow, Weekend, Next week, Someday, a date). Undo after every finish, delete and move.
- **Overdue tamer**: move all overdue tasks at once to Today, Tomorrow, the weekend, next week or Someday, with one Undo.
- **Manual order** inside a list (a Reorder mode with drag handles and up/down buttons), stored the way the Nextcloud Tasks web app stores it.
- **Search** across titles, notes, tags and the "waiting for" person (Greek accents and final sigma do not matter), from the top bar and the menu.
- **Waiting for**: mark a task as waiting on someone, with a check-back date; it leaves Today until then.
- **Day strip**: today's events from your Nextcloud calendars shown above the tasks, read-only (can be switched off per calendar).
- **Share to BookahTasks** from any app (a shared link becomes the task's link), and a quick-settings tile for a new task.
- **Links with BookahCalendar**: BookahCalendar shows your tasks on their days and can open a task here; a tap on a day-strip event opens BookahCalendar when it is installed.
- 11 colour themes, light, dark or system appearance, optional seasonal artwork, large-text and screen-reader support.

## How it works with Nextcloud
BookahTasks talks CalDAV to your own Nextcloud server and uses the task lists of the Nextcloud **Tasks** app (collections that hold `VTODO` items). A list named "Tasks" is used as the inbox. Every feature is stored in standard task fields, and an edit changes only the lines you changed, so nothing another app wrote is lost:

| Feature | Stored as |
|---|---|
| Title, notes | `SUMMARY`, `DESCRIPTION` |
| When (start), deadline | `DTSTART`, `DUE` |
| Done | `STATUS:COMPLETED`, `COMPLETED`, `PERCENT-COMPLETE:100` |
| Priority | `PRIORITY` (written as 1 high, 5 medium, 9 low; read as 1 to 4, 5, 6 to 9) |
| Tags | `CATEGORIES` |
| Subtasks | `RELATED-TO` on the subtask |
| Reminders | one `VALARM` per reminder (ours carry an `X-BOOKAH-REMINDER` line) |
| Repeats | `RRULE` (plus `X-BOOKAH-REPEAT-FROM:COMPLETION` for "repeat after I complete it") |
| Manual order | `X-APPLE-SORT-ORDER` |
| Link | `URL` |
| Someday, Waiting for | the tags `someday` and `waiting` (the check-back date is the start, the person a "Waiting for:" line in the notes) |

Calendars that hold only events are read for the day strip and never changed.

## Requirements
- An Android phone with **Android 12 or newer** (64-bit ARM, which is every recent phone).
- A Nextcloud server with the **Tasks** app, reachable from the phone (directly or over your own VPN), and an **app password** made for BookahTasks (Nextcloud: Settings, Security, Devices and sessions).

## Install
Download the APK from the [Releases](../../releases) page and open it on the phone (allow installing from your browser or file manager when Android asks). Later versions install over the earlier one.

Every release is signed with the same key. Its certificate SHA-256 fingerprint is

`634ad38b96fb32bba4de6ee6f5b43181b379371f650b68a8140c9bd76e87c646`

(also in [android/release-signing-cert.sha256](android/release-signing-cert.sha256) and in each release's notes). You can check a download with `apksigner verify --print-certs BookahTasks-X.Y.Z.apk`.

On first start type your server address (for example `https://nextcloud.example.org`), your user name and the app password.

## Privacy
The app contacts only **your Nextcloud server**, and **GitHub** only to check for a newer version (one anonymous request to this repository's public releases list, in builds that have the check; nothing about you or your tasks is sent). No analytics, no advertising, no crash upload, no account with us, no AI or cloud service of any kind. Your app password is stored encrypted with the Android Keystore and is never shown or logged. A crash log, if any, stays on the phone (Settings, About).

## Build from source
Requirements: a JDK 17 or newer (Android Studio's bundled one works), the Android SDK (`ANDROID_HOME`, or `sdk.dir` in `android/local.properties`), and the Gradle wrapper that is included.

```
cd android
gradlew.bat :core-data:testDebugUnitTest :design:testDebugUnitTest :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
```

(`./gradlew` on Linux and macOS.) The debug APK is in `android/app/build/outputs/apk/debug/`. The tests use generated tasks only and need no server. Some extra tests read files from a folder named by the environment variable `BOOKAH_PRIVATE_DIR`, or talk to a real server through `BOOKAH_LIVE_*` variables; they skip themselves when those are not set.

A signed release: put your own signing key's details in `android/keystore.properties` (not part of the repository; `storeFile`, `storePassword`, `keyAlias`, `keyPassword`) and run `tools/release-android.ps1 -Version X.Y.Z` in Windows PowerShell. `-ReleasesRepo owner/name` names the GitHub repository whose releases the app checks for updates; without it a build never checks.

## Layout
`android/` the app (`app`, `core-data`, `design`), `docs/` credits and icon drafts, `tools/` helper scripts.

## Licence
MIT, see [LICENSE](LICENSE). Third-party notices: [docs/CREDITS.md](docs/CREDITS.md) and [docs/THIRD-PARTY-NOTICES.md](docs/THIRD-PARTY-NOTICES.md).
