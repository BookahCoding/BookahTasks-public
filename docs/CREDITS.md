# Credits

**BookahTasks** is maintained by **BookahCoding**.

## Upstream / third-party

The libraries inside the app, their licences and the notice texts they ask for are in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

- Hikyaku and Herald design tokens (theme colour families, Washi & Ink and the others) - MIT, Copyright (c) 2026 Giton22 (see `THIRD-PARTY-HERALD-LICENSE.txt`). The Hokusai-inspired themes are interpretive colour sets, not measured artwork.
- biweekly 0.6.8 (iCalendar parsing and repeat rules) - BSD-2-Clause, Copyright (c) 2013-2024 Michael Angstadt; with vinnie 2.0.2 (MIT, Copyright (c) 2016 Michael Angstadt) and Jackson core 2.16.1 (Apache-2.0), which it brings in.
- OkHttp 5.5.1 and Okio 3.18.1, Kotlin 2.4.20 and kotlinx (coroutines 1.11.0, serialization 1.11.0, atomicfu), AndroidX (Compose BOM 2026.09.00 with Material 3 and Material icons core, Activity 1.13.0, Core 1.19.1, Lifecycle 2.11.0, DataStore 1.3.0, WorkManager 2.10.1 and the AndroidX libraries they bring in), Guava ListenableFuture, JSpecify, JetBrains annotations - Apache-2.0.
- Build and test only, never shipped: Gradle and the Android Gradle plugin, JUnit 4.13.2, MockWebServer, kotlinx-coroutines-test.
- Code copied from BookahCalendar (same maintainer, MIT): CalDAV client, sync engine, SQLite cache, Keystore vault, update check, theme catalogue, release script.

## Icon
The launcher icon (a vermilion sun disc with a sumi-ink brush tick) and the three drafts in `docs/icon-drafts/` are original vector drawings made for this project (`tools/make-icon.py`), released under the repository's MIT licence.

## Seasonal artwork
The twelve month pictures in `android/app/src/main/res/drawable-nodpi/art_*.webp` are AI-generated ukiyo-e style scenes made by the owner of this project with an AI image tool; they are the same set as BookahCalendar's month pictures, not drawn by hand and not taken from another app. `tools/make-art.py` crops and shrinks them; the full-size originals are not in the repository. They are decoration only (Settings, Look, Seasonal artwork switches them off) and are released under the repository's MIT licence.
