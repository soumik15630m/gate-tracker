# Study Tracker

A personal exam-preparation workspace for **native Android and desktop web**. Android is Kotlin + Jetpack Compose with Room storage; desktop web uses React Native Web / Expo and TypeScript. Both clients use the same Node.js + SQLite sync API and JSON backup format. OLED black, muted sage controls, teal analytics and gold streaks. Apache-2.0 licensed.

## Included

- Multiple exam workspaces, countdowns, weekly targets, milestones and archiving.
- Detailed study plans with dated blocks, syllabus links, activities, planned duration, notes and weekday repeats. Planned effort stays separate from actual study time.
- Unlimited logical syllabus nesting, partial checklists, automatic closing dates, search, move/reorder/edit, and indented-outline import with preview.
- Optional 1–5-star importance on every subject and topic, editable anytime and independent of confidence. Revision and planning support exact-star, minimum-star and N/A filters, subject/parent/final-item level filters, and matching an item's own, immediate-parent or subject rating. A five-star child remains discoverable under a three-star parent.
- Smart topic autocomplete across deep syllabus trees, including typo tolerance, word prefixes, ancestor context and breadcrumb disambiguation. Android searches run off the UI thread; only displayed suggestions assemble breadcrumb labels.
- Add multiple subjects or sibling child topics as editable rows in one form with a single Save all action. The name field focuses automatically; Enter saves the list, while multiline inputs retain new lines.
- Subject-only lecture/test totals and a scrollable numbered picker when completing any branch. Selections determine chapter/subtopic counts and roll up to subject progress without duplicate credit.
- Manual study entries against any syllabus level, independent confidence, daily focus and scheduled revision. Add a subject or successive child topics inside the open logging form, with breadcrumbs and a depth-by-depth picker. Time drafts remain intact.
- Numbered lecture completion in study entries, bounded by the subject total. Already credited lectures are disabled; the API rejects out-of-range or duplicate lecture credit. Counts roll up to the subject without automatically closing topics.
- A monthly lecture calendar in Plan: set daily subject lecture targets, edit or remove them, and see progress calculated from lectures logged for that day. Changing an entry date updates daily progress. Plans never add study minutes.
- One-tap stopwatch on every screen, with pause/resume and Finish & log. Signed-in accounts share one timer across web and Android: a compact header identifies where it started, either device controls it, and foreground clients refresh about every two seconds. Server timestamps keep elapsed time consistent; background clients catch up on reopening. Finishing pauses without logging, and saving atomically creates one study entry and clears the timer across devices. Closing the form retains the paused timer. Shared controls need a connection; offline displays show the last known state. Signed-out timers stay on their device. Earlier unsaved local timers are imported once when possible and preserved in a recovery copy.
- A large OLED focus dial with calm breathing animation and reduced-motion support. Start opens the dial immediately; the compact header remains available across screens.
- Optional Android stopwatch notifications with a live chronometer, pause/resume quick actions, Finish & log, and a separate lock-screen visibility choice. Enabled notifications keep an active shared timer connected in the background; force-stopping the app or phone power controls can interrupt background refresh.
- Optional Android evening reminders for unfinished lecture targets, including missed days. Reminder time is adjustable and delivery is approximate under Android background scheduling. Stopwatch and planner notifications have separate controls.
- Mock results, section scores, comparable-series trends, mistake journals and optional deduplicated study entries.
- Streaks, heatmaps, study-time trends, coverage, subject distribution, neglected subjects and approximate completion forecasts.
- Hover/focus/tap chart details on web, tap study charts on Android, and quick revision scheduling from Today.
- Weekly reflections, quick-capture inbox, undo, JSON backup/restore with recovery points and CSV exports.
- Offline edits, account-based synchronization and recoverable concurrent-edit conflicts.
- Native signed-APK update checks, checksum and certificate verification, and Android-approved installation.

The initial local workspace contains **labelled sample data**. Use **Settings → Start fresh** for your own data. A newly created sync account starts empty. Export/import transfers local work into that account.

Lecture attribution (`lectureIds`, `lectureSubjectId`) and daily targets (`lectureTarget`) are optional JSON fields. Old entries and backups remain valid; no data reset, backfill, or Room schema migration is needed. Update Android before using the new calendar targets on web. Syllabus progress and study history are separate: deleting a time entry removes its calendar credit while keeping the syllabus lecture selections until you edit them.

Shared timers use an additive `account_timers` table, separate from preparation records. Existing users, syllabi, study entries and backups are retained.

Importance is an optional JSON field: old records and version 1 backups remain valid and display N/A until rated. Adding ratings does not require a database reset, a Room schema change, or rewriting existing records. Install Android updates over the existing app to retain its private storage; uninstalling or clearing app data removes local storage. Settings provides a JSON export for keeping your own backup copy.

## Desktop web and sync server

Requires Node.js 24+:

```sh
npm ci
npm run dev
```

Development web: `http://localhost:8081`; API: `http://localhost:3001`. To serve the production web and API together:

```sh
npm run build
npm start
```

Open `http://localhost:3001`. After a successful first load, the web application shell is cached for offline reloads. API responses are never cached by its service worker. Ctrl/Cmd + K opens study logging; Ctrl/Cmd + Z undoes a workspace change outside text fields.

## Native Android

Open `native-android/` in Android Studio, or use JDK 17+, Android SDK platform 36 and build tools 36.0.0:

```sh
npm run android       # debug APK
npm run test:android  # native domain tests
npm run apk           # release tests, lint and optimized signed APK
```

The release output is `build/studyspace-android.apk`. The debug output is `build/studyspace-android-debug.apk`. Android contains **no JavaScript runtime or WebView**. Release builds use R8 and resource shrinking. Minimum Android version: 7.0 / API 24; target: Android 16 / API 36.

The helper detects Android Studio's usual SDK/JDK locations on Windows. Elsewhere set `JAVA_HOME` and `ANDROID_HOME`. Open Android Studio's SDK manager to install the required components and accept licenses. The Gradle wrapper is tracked; Node dependencies are unnecessary when building directly with `native-android/gradlew`.

Local builds use the ignored `signing/development.keystore`. When available, the helper copies the former local APK's key to preserve upgrade compatibility; fresh checkouts generate a development key. Keep your signing key backed up privately. A different certificate requires a separate installation and cannot update the existing app. Supply your own release key using:

```text
NATIVE_KEYSTORE_PATH
NATIVE_KEYSTORE_PASSWORD
NATIVE_KEY_ALIAS
NATIVE_KEY_PASSWORD
```

Set `STUDYSPACE_API_URL` before compiling to embed a default HTTPS sync-server address. It remains editable in Settings. Use the same server and account on Android and web. For local testing, private LAN HTTP addresses such as `http://192.168.1.20:3001` are supported. Public servers require HTTPS. `localhost` on a physical phone means the phone itself; emulator access to the host is `http://10.0.2.2:3001`.

### Migration from the former Expo Android app

The package ID remains `org.studyspace.app`. A correctly signed upgrade preserves app data and imports recognizable previous AsyncStorage workspace caches into Room without modifying the old database. Account caches remain isolated by server and user. Sign in again to reconnect synchronization. Export a JSON backup before upgrading, especially if your old APK used a different signing certificate. Uninstalling an app removes its private storage.

### Performance design and device tests

Syllabus trees are indexed iteratively on a background dispatcher, including coverage and time rollups. Lists use stable keys and lazy rows rather than recursive composables. Search and outline parsing run off the UI thread. Room uses WAL transactions and coalesces offline changes; network, file and backup work use IO dispatchers. Charts draw directly on Canvas.

Run instrumented tests on a **dedicated test emulator**; their fixture replaces its local workspace:

```sh
cd native-android
./gradlew :app:connectedDebugAndroidTest
```

The tests exercise native forms, confidence, completion, logging, backup/restore, a 5,000-topic list and screen navigation. JVM tests include a 10,000-level tree. Emulator results do not establish frame performance on a physical Poco X6; evaluate the optimized release APK on that device.

For the native API round-trip and conflict test, start `node scripts/native-test-server.mjs` in a separate terminal, then append `'-Pandroid.testInstrumentationRunnerArguments.syncServer=http://10.0.2.2:3137'` to the Gradle test command. The fixture uses an in-memory database and does not touch your preparation database.

## Automatic Android updates from GitHub

The **Native Android** workflow runs on native-code pushes to `main`, `v*` tags and manual dispatch. It builds with Gradle on the GitHub runner, runs unit tests and lint, and publishes the signed APK plus `android-release.json` to the rolling prerelease **android-latest**. No Expo account or EAS build service is used for native Android.

Configure four GitHub Actions secrets using your own private signing key:

| Secret | Value |
| --- | --- |
| `NATIVE_KEYSTORE_BASE64` | Base64-encoded keystore bytes |
| `NATIVE_KEYSTORE_PASSWORD` | Keystore password |
| `NATIVE_KEY_ALIAS` | Signing alias |
| `NATIVE_KEY_PASSWORD` | Key password |

An optional repository variable `EXPO_PUBLIC_API_URL` supplies the default sync address to both web and native builds. The legacy variable name is retained for compatibility. Publishing fails explicitly if the signing key is missing. Pull-request checks receive no publishing secrets.

The app checks GitHub at most daily on launch and offers a manual check in **Settings → App updates**. Downloaded APKs must match the release SHA-256, package ID, expected version and installed signing certificate. Installation requires Android's approval. Native application code cannot be silently replaced through JavaScript OTA. Study-data synchronization is independent of application updates.

The old Expo OTA workflow has been removed. Legacy Expo configuration remains only for the web build and historical source compatibility. To roll back application behavior, publish a corrected build with a higher version code; Android normally rejects version downgrades.

## Sync and self-hosting

The hosted website is [gate-tracker-blond-two.vercel.app](https://gate-tracker-blond-two.vercel.app). Vercel serves the desktop web application and `api/index.mjs`; `server/cloud.mjs` stores online accounts and study data in a persistent Neon PostgreSQL database. The native GitHub build embeds this address as its default sync server. Use the same account on both devices. Production database credentials live in Vercel environment variables and are never included in the web bundle or APK.

The cloud API uses per-account row locks, atomic batches, bulk record writes, idempotent receipts and a database-backed authentication rate limit. Application traffic uses a pooled connection managed with Vercel's `attachDatabasePool`; schema initialization uses the direct connection. `vercel.json` configures Node 24 deployment, the static output, API routing and the Singapore function region. The local SQLite server below remains usable without any cloud service.

The server persists accounts, hashed sessions, versioned records and mutation receipts in `data/studyspace.sqlite`. Passwords use scrypt with random salts. Native bearer tokens are encrypted using Android Keystore; web tokens use browser storage. Caches are isolated by server and account.

Sync runs after edits and every 15 seconds while the app is active. Android also schedules network-constrained WorkManager retries; Android controls their timing. Offline changes stay queued. Server versions detect conflicting changes; mutation IDs make retries safe. If any record in a pending batch is stale, none of that batch is applied. Settings lets you keep local or server versions together.

Copy `.env.example` to `.env` for optional server configuration:

| Variable | Default | Purpose |
| --- | --- | --- |
| `HOST` | `0.0.0.0` | Listen address |
| `PORT` | `3001` | Web and API port |
| `DATA_DIR` | `./data` | Persistent SQLite directory |
| `ALLOWED_ORIGINS` | Local development origins | Additional comma-separated browser origins |
| `EXPO_PUBLIC_API_URL` | Current host / development host | Web default API address |
| `STUDYSPACE_API_URL` | Empty | Native default API address at build time |
| `DATABASE_URL` | Unset | Cloud API pooled PostgreSQL connection, server only |
| `DATABASE_URL_UNPOOLED` | Unset | Direct PostgreSQL connection for schema initialization, server only |

Serve web and API from the same HTTPS origin with a reverse proxy, and persist `DATA_DIR`. Docker is included:

```sh
docker compose up --build -d
```

This exposes port 3001 and uses a named SQLite volume. Use an application-consistent database backup or stop the server before copying SQLite files. JSON exports contain study records, not credentials. A serverless host needs a persistent database adapter; do not deploy this SQLite server onto an ephemeral filesystem.

This personal tool has no account email delivery, password-reset emails or OS push reminders. Revision reminders live in the app.

For cloud API verification, install `server/` dependencies and start `node --env-file=.env.cloud.local scripts/cloud-test-server.mjs`. This creates a randomly named temporary schema and limits the fixture's database search path to that schema. Run the API test with `TEST_CLOUD_URL=http://127.0.0.1:3138` and `TEST_CLOUD_SCHEMA` from `build/cloud-test-schema.txt`. Stop the fixture with `POST http://127.0.0.1:3138/_fixture/stop` to drop its schema. `scripts/cleanup-cloud-test.mjs` can remove that same guarded test schema after an interrupted run. The fixture never falls back to production tables.

## Progress rules

- Coverage counts final checklist items; headings do not inflate percentages.
- Parents close when all final descendants close, using the latest descendant closing date.
- Completing a branch preserves existing closing dates; reopening clears current dates. There is no manufactured completion-event history.
- Confidence is independent of completion, editable at every depth.
- Time assigned at any depth contributes to its ancestors and counts once in exam totals.
- The global streak uses the configurable daily minimum. Yesterday's streak remains alive during today; future entries do not qualify.
- Account timezone defines today. Manually selected dates do not shift across timezones.
- Forecasts use recent checklist-closing pace and are approximate, not measures of mastery.
- Mock trends compare a single exam and test series.

## Checks and source

```sh
npm run check
npm test
npm run test:api
npm run build
npm run test:android
```

`native-android/app/src/main/java/org/studyspace/app/` contains native UI, domain rules, Room storage, sync and the signed updater. `src/` contains the desktop web client; `shared/validation.mjs` defines the server record contract; `server/index.mjs` implements authentication, sync and web hosting. `tests/` covers web domain behavior and API authentication, isolation, idempotency, conflicts, rollback and logout. Native font licenses are in `native-android/licenses/`.


## Automatic study planning

Workspaces can represent exams, semesters, courses or personal learning. Deadlines are optional. Undated goals need a planner end date before automatic lecture targets can be generated.

Set each subject's lecture pace in Syllabus or Plan. Daily pace applies on the selected study weekdays; weekly pace is spread over eligible days. New-subject forms include this choice and default to one lecture per study day, Monday–Saturday. A pace of zero disables automatic scheduling. Plan also supports rest dates and an optional combined daily lecture limit.

Saving lecture totals, pacing or deadlines automatically rebuilds future editable targets. Known totals schedule only remaining lectures, using unique credits from topic progress and study logs. Unset/zero totals continue through the exam deadline or earlier planner end. Scheduling windows are limited to two years. Capacity shortfalls and overbooked protected targets are shown explicitly. Past days, days with lecture logs, and locked/manual targets are preserved. Editing a daily target locks it. Removing an automatic day makes it a rest day for that workspace.

Catch-up reserves and locks available slots in a chosen future window without changing the main deadline. Original missed targets and partial carry counts remain visible. Work that cannot fit remains overdue; carried work is excluded from reminders on its original day.

Today combines daily lecture targets, revision and timed study blocks. Optional question-practice counts (correct, incorrect, skipped) can be recorded against any syllabus level while logging time. Attempted questions are correct plus incorrect; accuracy excludes skipped questions. CSV and JSON exports include these new fields. Insights compares planned lectures and time with actual effort, reports practice accuracy, and highlights important topics that still need confidence using independent importance filters.

Lecture forecasts use the last 28 calendar days of unique logged lectures and appear after at least three logged lectures with a known subject total. Checklist coverage, confidence and practice accuracy remain separate.

This release adds fields to existing JSON records. It does not reset caches, migrate Room tables, rename the Android application ID, change account scope or replace signing certificates. Existing backup format identifiers and server URLs remain compatible. Install the new APK over the existing app; do not uninstall to update.


## Release prompts and live timer notifications

The web app compares the running bundle with uncached `/version.json` metadata on launch, return, and once per minute while visible. A new release displays an in-app toast with Update now after the main editors are closed. Updating saves the current workspace before reloading. Android checks the public signed release manifest when due on launch/return and every five minutes while foregrounded, with an in-app snackbar and a persistent Update now / Install update action. Downloaded APKs must match the published checksum, package, version and installed certificate before installation. Android installation approval and, when needed, the allow-installation setting remain system-controlled. Debug builds do not automatically offer production APKs.

An enabled Android shared-stopwatch notification observes timer state changes immediately and polls the account timer every five seconds independently of the activity lifecycle. During an active shared timer it uses a timed partial wake lock, releases it when finished or stopped, and can recover after a service restart. The new quiet default-importance notification channel declares public lock-screen visibility and includes quick actions in the public layout. The focus screen links directly to the channel settings, since Android/HyperOS privacy and background restrictions can still override an app's requested visibility or service lifetime. No background monitoring runs when stopwatch notifications are disabled or no timer is active.
