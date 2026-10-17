# Way to Consistency

An offline-first tasks, habits, reminders, personal finance, reports, and progress-journal app.

## Icons and data compatibility

The interface uses a bundled semantic SVG icon registry, so controls remain sharp, consistent, and fully available offline across all six themes. Tasks, goals, and categories store an editable `iconKey` instead of a supplied emoji. Schema version 4 migrates known legacy emoji to matching icons, infers a useful icon from the record name when needed, and uses a neutral fallback for unknown custom values. Schema version 5 adds offline-first motivation, accountability-partner, and run-music preferences. Imports from schema versions 1–4 remain supported; new exports retain schema version 5.

## Motivation, sharing, and music

Run and streak share cards are generated locally as privacy-reviewed PNG images. Run cards can omit the route or trim the first and last 10%, and never include raw coordinates, notes, timestamps, or a map background. Android uses the system share sheet and offers direct handoff to installed WhatsApp, Instagram, or Facebook apps with a safe Sharesheet fallback; browsers use Web Share when available and otherwise download the image.

Accountability uses one private on-device partner profile, manually exchanged summary codes, rate-limited gentle nudges, and no leaderboard. Spotify and YouTube Music use validated HTTPS links; Android can also retain access to a user-selected local audio file. Music launches separately from GPS so tracking never depends on playback. The existing offline audio trimmer remains the path for choosing a custom reminder sound.

## Device integrations

The Android app connects to Health Connect only after explicit permission. The Connections screen reads today's aggregated steps, the latest heart-rate sample from the last day, and the most recent sleep duration, and completed GPS runs are written back as exercise and distance records when write access is granted. Activity detection uses Google Play services transition events; when enabled, it offers a Start run notification for walking/running with a 30-minute cooldown and never starts GPS without confirmation.

Google Calendar/Drive deliberately show **OAuth setup needed** until registered provider credentials, consent configuration, and production signing information are supplied. No connection is simulated and no client secret is embedded in the HTML or APK.

Icons are decorative where their adjacent label already names the action. Icon-only controls have accessible names, and status is always paired with visible text rather than relying on color. Decorative particles stop when reduced motion is enabled.

## Task availability

Saving a task requires choosing whether its occurrences are available immediately or unlock at their scheduled date and start time. A scheduled occurrence without a start time unlocks at local midnight. Until it unlocks, occurrence-level completion, progress, skip/cancel, and rescheduling actions are read-only; editing the whole task remains available. Backups created before schema version 2 are migrated automatically and keep their previous early-completion behavior.

## Browser app

Open [way-to-consistency.html](way-to-consistency.html) in a modern browser. The interface and its supplied images are local. User records are stored in that browser on that device; use the app's backup export before moving or clearing browser data.

Browser reminders are best-effort while the page is open. For Android alarms that can arrive while the app is closed, build and install the native Android app described in [android/README.md](android/README.md).

## Android build

Open the `android` directory in Android Studio with JDK 17 and Android SDK Platform 35 installed. Build the debug variant to create `android/app/build/outputs/apk/debug/app-debug.apk`. The Android app bundles the HTML and local image assets for offline use.

GPS distance uses accepted, accuracy-filtered fixes. Small movements accumulate from the last accepted anchor, paused movement is excluded, and route segments are separated after resume. The inline route preview works offline. Google Maps tiles are visibly marked as not implemented in this version. Real-device outdoor testing remains important because GPS accuracy varies by device and environment.

## Project assets

- `assets/brand-logo.png` and `assets/theme-gallery.png` are the app's local logo and UI reference gallery.
- `Gemini_Generated_Image_rd5rntrd5rntrd5r.png` and `Gemini_Generated_Image_vtqd4avtqd4avtqd.png` are supplied generated image references.
- `Way_to_Consistency_Master_UI_UX_and_Build_Blueprint.pdf` is the product and implementation blueprint.
## UI refresh (design-sheet look)
- Six themes: Minimal, Nature, Gaming, Car, Space and the new **Shadow Fight** (belt-rank widget, brush-script headings).
- Theme emblem cards cut from the design sheet live in `assets/emblems/`; fonts are bundled in `assets/fonts/` so it works offline.
- Home dashboard: icon-badge stat tiles, mission rows with progress bars, quick-action tiles, Budget / Goals / Monthly-report cards and a tagline strip.
- Android bundles the whole `assets/` folder automatically, so no build changes are needed.

## Photo backgrounds
Put `minimal|nature|gaming|car|space|shadow.jpg` in `assets/backgrounds/` and each theme shows that photo instead of the built-in illustration (see the README.txt in that folder). Users can still upload their own picture in Settings.

## Pictures from the media sheet
- `assets/photos/` – 6 car + 6 nature photos (thumbs in `thumbs/`). Gaming, Car, Nature and Minimal use one by default; the Photo library in Settings lets users pick any.
- `assets/heroes/` – 6 character cards for the Home hero card (Settings → Hero card). Gaming and Shadow Fight show one by default.
- `assets/fx/` – transparent effect sprites: used for the task-complete pop, floating particles (leaves, sparks, specks), widget glows and the spinning ring around the profile photo.
- The character cards depict well-known anime characters; keep them for personal use or replace them before publishing the app.
