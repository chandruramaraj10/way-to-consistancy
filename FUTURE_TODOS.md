# Way to Consistency — Future To‑Dos

This document is the implementation backlog for integrations and larger product features. The app must remain offline-first: core tasks, reminders, money, reports, saved runs, and exports must continue to work without an account or internet connection.

## Working rules

- Ask for sensitive permissions only when the user opens the related feature.
- Explain what will be read, written, or shared before requesting access.
- Keep imported records traceable with a source and external ID.
- Make imports and notification actions idempotent so retries cannot create duplicates.
- Never upload health, location, financial, or photo data without explicit consent.
- Provide a useful empty/error state when a service, permission, API key, or network connection is unavailable.
- Add schema migrations and automated tests for every persisted-data change.

## Phase 1 — Local Android integrations

Highest value with the fewest external accounts or servers.

### Health Connect

- [ ] Add the Health Connect client and availability check.
- [ ] Add an in-app explanation screen before requesting health permissions.
- [ ] Read daily steps.
- [ ] Read resting/recorded heart-rate samples and display their source and timestamp.
- [ ] Read sleep sessions and show duration without making medical claims.
- [ ] Import exercise sessions classified as walks or runs.
- [ ] Write completed GPS runs back as exercise sessions.
- [ ] Use Health Connect distance as a clearly labelled fallback when the app GPS distance is zero or invalid.
- [ ] Never silently combine distance sources; store which source won and why.
- [ ] Add duplicate detection using source, external ID, start time, and duration.
- [ ] Add permission-revoked, unavailable, and no-data states.

Definition of done: a user can grant only the required permissions, see steps/sleep/heart-rate summaries, import a run once, and write an app-recorded run without duplication.

### Android Calendar provider

- [ ] Request calendar read/write permissions only from an Integrations screen.
- [ ] Let the user choose which device calendars are visible.
- [ ] Show calendar events in the Plan timeline without turning them into tasks.
- [ ] Visually distinguish meetings from tasks and habits.
- [ ] Export one task occurrence or a recurring task series as calendar events.
- [ ] Store the Android calendar event ID for later updates or deletion.
- [ ] Prevent feedback loops when an exported event is read back.
- [ ] Handle all-day events, recurring meetings, time zones, and deleted calendars.

Definition of done: selected local calendars appear offline in Plan, and exporting a task creates one updateable calendar event rather than duplicates.

### Activity recognition

- [ ] Add the Android activity-recognition permission and API.
- [ ] Detect sustained walking or running, not single noisy transitions.
- [ ] Show a notification offering **Start run** or **Not now**.
- [ ] Add a cooldown after dismissal so the app does not nag repeatedly.
- [ ] Never start GPS recording automatically without confirmation.
- [ ] Add a setting to disable detection completely.

Definition of done: a sustained outdoor walk/run can trigger one useful prompt while false starts and repeated prompts are controlled.

### Photos and journey

- [ ] Use the Android system photo picker instead of broad storage permission.
- [ ] Add a camera alignment screen with an optional adjustable ghost overlay of the previous photo.
- [ ] Allow mirror, opacity, crop, and alignment adjustments before saving.
- [ ] Preserve original photos; store generated thumbnails separately.
- [ ] Add an on-device face-aligned before/after preview.
- [ ] Generate a local timelapse video with progress and cancellation states.
- [ ] Ensure photo backups and exports require an explicit choice.

Definition of done: users can reproduce framing safely without uploading photos or granting full-gallery access.

### Android convenience features

- [x] Block run start/resume when Location Services are off and pause an active run if GPS is disabled.
- [x] Add an offline waveform trimmer for imported reminder sounds.
- [x] Add a lock-screen alarm view where hardware volume keys silence and snooze the reminder.
- [ ] Add a home-screen widget showing streak, next task, and quick actions.
- [ ] Add a one-tap **Start run** widget action.
- [ ] Add dynamic App Shortcuts for Start run, Add task, and Log expense.
- [ ] Add lock-screen-safe notification actions for Done, Snooze, and Start run.
- [ ] Route every task action through the existing occurrence availability check.
- [ ] Add Android alarm-clock intent support where appropriate.
- [ ] Investigate exact-alarm and Do Not Disturb access without requesting policy-sensitive access by default.

Definition of done: shortcuts remain safe before scheduled task unlock times and work after process/device restarts.

### Sharing and exports

- [x] Generate a privacy-reviewed run card with distance, time, pace, and an optional route.
- [x] Add an option to hide the route or trim its start/end for location privacy.
- [x] Generate a streak/consistency share card using the current theme.
- [x] Share through the Android system share sheet.
- [ ] Add accessible PDF reports alongside existing CSV, backup, and GPX exports.
- [ ] Let users choose the included date range and data categories.

Definition of done: exported files open in standard apps and share cards never reveal location or financial details unless selected.

## Phase 2 — Provider integrations

These require provider registration, OAuth credentials, redirect URIs, privacy-policy updates, and secure token storage.

### Fitness services

- [ ] Fitbit: research current activity, sleep, heart-rate, and export capabilities.
- [ ] Garmin: confirm developer-program access before implementation.
- [ ] Google Fit: confirm current API availability and migration path before using it; prefer Health Connect on Android.
- [ ] Add per-provider sync direction, date range, last-sync status, retry, and disconnect controls.
- [ ] Encrypt refresh tokens using Android Keystore-backed storage.

### Calendars and task services

- [ ] Google Calendar OAuth and selectable-calendar import.
- [ ] Outlook/Microsoft Graph calendar OAuth and import.
- [ ] One-way task-to-event export before attempting two-way sync.
- [ ] Define conflict rules before enabling two-way calendar sync.
- [ ] Todoist import with project, label, recurrence, and completion mapping.
- [ ] Google Tasks import with list selection and duplicate detection.
- [ ] Notion import using an explicitly selected database and field mapping.
- [ ] Provide a dry-run summary before a large first import.

Definition of done: reconnecting or retrying never duplicates tasks, events, or runs, and disconnecting does not destroy local records.

### Music

- [x] Launch a selected Spotify, YouTube Music, or local audio selection when a run begins.
- [x] Use provider-supported links; do not attempt background playback without permission.
- [x] Allow local audio selection and trimming for alarms where Android policy permits it.
- [x] Keep run recording independent of the music provider.

## Phase 3 — Backup and multi-device sync

### Google Drive backup

- [ ] Back up encrypted app data to the Google Drive app-data folder.
- [ ] Add automatic frequency, Wi-Fi-only, retention, and manual backup settings.
- [ ] Verify backup integrity before replacing local data.
- [ ] Provide restore preview, schema migration, and rollback on failure.
- [ ] Keep photos/audio opt-in because they can make backups large.

### Optional cloud account

- [ ] Choose Firebase or Supabase only after documenting cost, offline behavior, and data ownership.
- [ ] Keep account creation optional.
- [ ] Design stable IDs, revision metadata, tombstones, and conflict resolution first.
- [ ] Encrypt sensitive records in transit and at rest.
- [ ] Add export, account deletion, device removal, and complete cloud-data deletion.
- [ ] Test edits made offline on multiple devices before public release.

Definition of done: offline edits survive, deterministic conflicts are explained, and users can fully leave the cloud service.

## Phase 4 — Wearables and social accountability

### Wear OS

- [ ] Add a watch tile for next task, streak, and run start.
- [ ] Support Done and Snooze from the watch.
- [ ] Enforce scheduled-task locks on both phone and watch.
- [ ] Add configurable haptic habit nudges.
- [ ] Keep watch actions idempotent during delayed synchronization.

### Accountability

- [x] Add a consent-first, one-partner local profile with manual update sharing.
- [x] Share only selected streak and completion summaries, not raw health/location/financial data.
- [x] Add encouraging nudges with rate limits and blocking controls.
- [x] Avoid public leaderboards and coercive comparison by default.
- [ ] Add reporting, removal, and abuse safeguards before launch.

The checked accountability items are the offline/manual first release. Real-time friend sync, accounts, and online delivery remain future work and require a backend plus abuse controls.

## Phase 5 — AI assistance

AI features must clearly label generated suggestions and must not make medical, diagnostic, or financial claims.

### Weekly review

- [ ] Build a deterministic local summary first using existing task/run/money calculations.
- [ ] Let users choose which categories may be sent to a network model.
- [ ] Redact notes, locations, photos, and financial amounts by default.
- [ ] Show evidence behind every recommendation.

### Smart scheduling

- [ ] Calculate completion patterns locally by weekday and time window.
- [ ] Suggest changes instead of silently rescheduling tasks.
- [ ] Respect calendar conflicts, quiet hours, availability locks, and time zones.
- [ ] Explain why a time was suggested and allow permanent dismissal.

### Voice capture

- [ ] Add on-device speech recognition when available.
- [ ] Parse title, date, time, recurrence, and reminder into a reviewable draft.
- [ ] Require confirmation before saving ambiguous dates or recurring schedules.
- [ ] Route saved tasks through the existing availability-policy popup.

## Cross-cutting engineering work

- [x] Create an Integrations screen foundation with device capability and permission status. Add last-sync and disconnect controls when the first provider can connect.
- [x] Extend schema version 3 with provider preferences and reusable `origin` metadata (`provider`, `externalId`, `revision`, and `syncedAt`).
- [ ] Add a background-work policy covering battery limits, retries, backoff, and network constraints.
- [ ] Add structured, privacy-safe logs for bridge, health, calendar, OAuth, sync, and export failures.
- [ ] Add import previews and undo for bulk operations.
- [ ] Add unit tests for deduplication, merge/conflict rules, time zones, and permission loss.
- [ ] Add Android instrumentation tests for Health Connect, calendar, notifications, photo picker, widgets, and process restart.
- [ ] Update the privacy documentation and Play Store data-safety declaration before release.

## Decisions needed before provider work

- [ ] Decide whether any external fitness provider is needed beyond Health Connect.
- [ ] Select the first online calendar provider: Google Calendar is the recommended first choice.
- [ ] Decide whether provider tokens are handled entirely on-device or through a small backend.
- [ ] Decide whether cloud sync is a paid/optional feature and select Firebase or Supabase.
- [ ] Decide retention rules for location routes, health summaries, photos, and cloud backups.
- [ ] Obtain production package name, signing certificate fingerprints, OAuth credentials, redirect URIs, privacy-policy URL, and provider review approvals.

## Suggested implementation order

1. Integrations screen and shared source/deduplication model.
2. Android Calendar provider.
3. Health Connect.
4. Activity Recognition.
5. Photo picker and ghost overlay.
6. Widgets, shortcuts, sharing, and PDF export.
7. Google Calendar.
8. Drive backup.
9. Optional cloud sync.
10. Wear OS and accountability.
11. AI review, scheduling, and voice capture.
