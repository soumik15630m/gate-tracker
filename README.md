# Studyspace

A personal exam-preparation workspace for **native Android and desktop web**, with offline storage and account-based sync. OLED black, muted sage controls, teal analytics, and gold streaks. Built with Expo / React Native, TypeScript, and a small Node.js + SQLite sync server. Apache-2.0 licensed, preserving this repository’s original license.

## Included

- Multiple exam workspaces, countdowns, weekly targets, milestones, and exam archiving.
- Syllabus trees with unlimited logical nesting, three-state checklists, completion dates, search, move/reorder/rename, and outline import with a preview.
- Manual study entries assigned to any level of a syllabus. Parent totals never double-count time.
- Independent topic confidence, daily focus, revision scheduling, and an error journal.
- Mock-test results, section scores, comparable-series score charts, and optionally linked time entries.
- Activity heatmaps, time trends, subject distribution, coverage, completion momentum, streaks, neglected subjects, and personal milestones.
- Weekly reflections with automatic study summaries and a quick-capture inbox.
- Full JSON backup/restore with a recovery point, plus study-log and mock CSV exports.
- Offline edit queues, automatic foreground sync, retry-safe server writes, and recoverable concurrent-edit conflicts.
- GitHub Actions for automatic compatible Android OTA delivery, CI checks, and tagged native APK builds.
- Keyboard shortcuts: Ctrl/Cmd + K to log study time; Ctrl/Cmd + Z to undo the last workspace change outside text fields.

The initial local workspace contains **clearly labelled sample data**. Use **Settings → Start fresh** for your own data. Creating a sync account starts an empty workspace. Export/import can transfer your existing local data into that account.

## Run locally

Requires Node.js 24 or newer.

```sh
npm ci
npm run dev
```

The web development app is at `http://localhost:8081`; the API is at `http://localhost:3001`. Alternatively, serve the production build and API together:

```sh
npm run build
npm start
```

Open `http://localhost:3001`. The production web build also caches its application shell for offline reloads after the first successful load. API responses are never put in the service-worker cache.

## Android

### Development

```sh
npm run android
```

This starts Expo for an Android emulator or Expo Go on a phone. Use the same sync server and account on the phone and web. If using a physical phone, keep both devices on the same network and use the computer’s LAN address, for example `http://192.168.1.20:3001`, in **Settings → Sync server address**. `localhost` on the phone refers to the phone. Your firewall must permit the API port on the network you intend to use.

### Personal-use APK

With Android Studio’s SDK, JDK 17+ (the bundled JDK works), and the required SDK licenses/tooling already configured:

```sh
npm run apk
```

On Windows the helper detects Android Studio’s usual JDK and SDK locations; elsewhere set `JAVA_HOME` and `ANDROID_HOME`. The output is `build/studyspace-preview.apk`, targeting ARM64 phones. This standalone preview includes the native application and its JavaScript bundle, uses the generated development signing key, and permits HTTP for local-network sync. The Android-generated directory is intentionally ignored; Expo regenerates it from the tracked config and plugin.

For a production release, set `EXPO_PUBLIC_API_URL` to your HTTPS server and configure your own release signing. The production EAS profile leaves cleartext HTTP disabled and builds a signed personal-distribution APK:

```sh
npx eas-cli build --platform android --profile production
```

EAS requires your own Expo account and signing configuration. Use the `store` profile for a Play Store app bundle, or `preview` for an HTTP-enabled LAN-testing APK. A local native bundle check is available with `npm run export:android`; it does not create an APK or replace testing on a physical phone.

## Automatic Android OTA from GitHub

Repository: [soumik15630m/gate-tracker](https://github.com/soumik15630m/gate-tracker).

The native app includes `expo-updates`. After the one-time setup below, pushes to `main` publish an Android OTA update automatically, after TypeScript and the domain/API tests pass. The app checks on launch and when returning to the foreground, downloads compatible improvements, and loads them on the next launch. **Settings → App updates** also offers a manual check and a save-before-restart action. Updates do not force a restart during study entry.

The native runtime uses Expo’s **fingerprint** policy. A JS/UI/asset-only change can update an installed compatible binary. A native dependency, native configuration, or plugin change produces a different runtime and requires a new APK. An existing binary never intentionally loads an update for a different runtime. Keep the built-in Expo error-recovery behaviour enabled.

### One-time setup

1. Create or choose a project in your Expo account. Copy its public project UUID, owner, and slug.
2. Link it locally and commit the resulting public configuration:

   ```sh
   npm run ota:configure -- <project-uuid> <expo-owner> <project-slug>
   ```

3. In the GitHub repository’s **Settings → Secrets and variables → Actions**, add **`EXPO_TOKEN`** as a secret. Obtain it from your own Expo account’s access-token settings. Never paste it into a source file, issue, or chat.
4. Optionally set repository variables `EXPO_PROJECT_ID` and `EXPO_OWNER` instead of committing the IDs. The public config is preferable because it keeps local and cloud builds aligned.
5. Set `EXPO_PUBLIC_API_URL` to the same reachable HTTPS sync server in your **EAS production environment** and GitHub repository variables. For preview, use the matching EAS preview environment. `eas update --environment` uses the selected EAS environment, so native builds and updates must share those values.
6. Run your first EAS Android build interactively to initialise signing credentials, then install that APK:

   ```sh
   npx eas-cli build --platform android --profile production
   ```

7. Push a JS change to `main`, or run **Actions → Android OTA → Run workflow**. The workflow publishes only Android and targets `production` by default. Manual runs can target `preview` instead.

Until a valid Expo project is configured, local APKs work with embedded code and show an honest “not linked” update state. Adding a project ID later requires installing **one newly configured APK** before that phone can receive OTA. Study/account sync and application-code OTA are separate systems.

### Workflows

| Workflow | Trigger | Result |
| --- | --- | --- |
| `Checks` | Pull requests, pushes to main, manual | Types, domain/API tests, production web and Android-bundle builds |
| `Android OTA` | App-code pushes to main, manual | Validated update published through EAS to the selected channel |
| `Android APK` | A `v*` tag, manual | Signed EAS APK build using the selected channel and build profile |

The publishing workflows require `EXPO_TOKEN` and a linked project. Before setup, an automatic push skips publishing and writes setup instructions to the workflow summary. A manual publishing/build request fails explicitly if configuration is missing. Fork pull requests run checks without receiving publishing secrets. The APK workflow uses the signing credentials initialised in your Expo account and returns an EAS download link in its logs. Expo services use your own account and its service limits.

For rollback, use the EAS dashboard or republish a previously known-good update to the same channel and runtime. [Expo’s OTA documentation](https://docs.expo.dev/eas-update/getting-started/) describes delivery and runtime matching; [the GitHub integration](https://docs.expo.dev/eas-update/github-actions/) covers access-token setup.

## Sync & self-hosting

No external database or paid service is required. The server stores accounts, hashed sessions, versioned study records, and mutation receipts in `data/studyspace.sqlite`. Passwords are hashed with scrypt and a per-user random salt. Android tokens use SecureStore; web tokens use browser storage. Accounts and caches are isolated by server and user.

In **Settings**, enter your server, create an account, and use that same account on both devices. Sync runs shortly after edits, every 15 seconds while the app is open, and when the native app returns to the foreground. Native background execution is not promised. Offline changes remain queued until the app can reach the server.

Edits use server versions rather than device clocks. Retries use mutation IDs to avoid duplicate writes. If a device edits stale records, the pending batch is preserved as a conflict and the server applies none of that batch. **Settings** lets you review and choose the device or server versions together, keeping branch changes coherent. Separate, non-conflicting records merge normally.

Configure optional environment variables by copying `.env.example` to `.env`:

| Variable | Default | Purpose |
| --- | --- | --- |
| `HOST` | `0.0.0.0` | Listen address |
| `PORT` | `3001` | API and production-web port |
| `DATA_DIR` | `./data` | Persistent SQLite directory |
| `ALLOWED_ORIGINS` | Local development origins | Comma-separated additional web origins |
| `EXPO_PUBLIC_API_URL` | Current host / Expo development host | Default API address embedded into the client build |

The simplest deployment serves the production web app and API from the same origin. Put your HTTPS reverse proxy in front of port 3001 and persist `DATA_DIR`. A Docker setup is included:

```sh
docker compose up --build -d
```

This serves the app on port 3001 with a named volume for SQLite. Back up that volume, including SQLite’s WAL state, using an application-consistent backup or while the server is stopped. User-facing JSON exports contain study data only, not accounts or authentication credentials.

This personal-tool release does not include email delivery, password-reset emails, scheduled server jobs, or OS push notifications. Review reminders are visible in the app’s revision queue. There are no account emails sent by the application.

## Progress rules

- Coverage counts **final checklist items**, so headings do not inflate the percentage.
- Parents complete when all final descendants complete; the parent date is the latest descendant closing date.
- Completing a branch preserves existing dates. Reopening clears current completion dates. No completion-event history is manufactured.
- Confidence is independent of completion and can be edited for any topic or subject.
- Time logged at any depth contributes to its ancestors and counts once in exam totals.
- The main streak covers all exams. A qualifying day meets the configurable minimum number of logged minutes. Yesterday’s streak remains alive during today, and future entries do not qualify.
- The account timezone defines today. Manually selected study dates do not shift when devices change timezone.
- Completion forecasts use recent checklist-closing pace and are explicitly approximate; they do not estimate mastery.
- Mock-score trends compare a single exam and test series. A series label is how you group comparable formats.

## Checks

```sh
npm run check
npm test
npm run test:api
npm run build
npm run export:android
```

Domain tests cover timezone and streak boundaries, nested coverage, time roll-up, outline import, cycle prevention, validation, edit coalescing, and edits made during sync. API integration tests use a temporary in-memory SQLite database and local HTTP server to verify authentication, account isolation, idempotent retries, conflict preservation, atomic branch changes, rollback, and logout.

## Project structure

```text
src/App.tsx             Adaptive Android / desktop shell
src/screens/            The eight application screens
src/forms.tsx           Exam, study-session and mock forms
src/core/               Shared domain rules and sample data
src/store.tsx           Offline cache, account state, outbox and sync
src/charts.tsx          Cross-platform SVG charts
server/index.mjs        Authentication, SQLite sync and static web hosting
shared/validation.mjs   Client/server import and record validation
plugins/                Android manifest configuration
scripts/                Development, offline-web and APK build helpers
tests/                  Domain and API integration coverage
```

Architecture references: [Expo universal web development](https://docs.expo.dev/workflow/web/), [Node SQLite](https://nodejs.org/api/sqlite.html), and [Expo SecureStore](https://docs.expo.dev/versions/v55.0.0/sdk/securestore/).
