# Way to Consistency for Android

This is a native Android shell around the existing offline HTML application. The Gradle build copies `../way-to-consistency.html` into the APK as `index.html` and bundles the supplied logo and theme gallery from `../assets`. Keep the root HTML as the single UI source. Task and run data stay on-device.

The WebView UI uses the same bundled SVG icon registry as the browser build. Schema version 4 replaces supplied task, goal, and category emoji fields with semantic `iconKey` values; schema version 5 adds motivation, accountability-partner, and run-music preferences while preserving older backups. Android notification titles remain plain user-visible text without adding emoji.

Run and streak cards are generated on-device and shared through Android's system share sheet using a temporary cache file. Installed WhatsApp, Instagram, and Facebook apps can be selected directly; missing or incompatible targets fall back to the Android Sharesheet. Spotify and YouTube Music open through validated provider links. A local run track is selected through Android's document picker and retains read access without requesting broad storage permission. Music playback remains separate from the foreground GPS service.

## Health and activity detection

Health Connect is a real permission-driven connection, not a capability label. After consent, the app reads today's aggregated steps, the latest heart-rate sample in the last 24 hours, and the latest sleep-session duration. A newly completed GPS run is written to Health Connect as an exercise session plus distance when write access is granted. Health data remains on-device and is never placed in a share card. Android 13 and earlier require the Health Connect provider app; Android 14 and newer include it in the system.

Activity detection uses Google Play services Activity Transition API. Enabling it requests activity-recognition and notification permissions, subscribes to walking/running transitions, and displays a confirmation notification with a 30-minute cooldown. GPS recording does not start until the user confirms and the existing location checks pass.

Google Calendar/Drive require production OAuth registration and credentials that must not be embedded in an APK. Their connection remains visibly unavailable until the required Google Cloud project, consent configuration, Android OAuth client, and production signing fingerprints are supplied.

## Build the APK

Requirements: Android Studio (or Gradle 8.9), JDK 17, and Android SDK Platform 35. Open this `android` directory in Android Studio and build the `app` debug variant, or run:

```sh
cd android
gradle assembleDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Copy that APK to the Android device and install it. Android may ask the user to allow installation from that file source. For public distribution, create and securely retain a signed release key, then build the release variant in Android Studio.

The first build needs access to download Gradle and Android build dependencies. Core task and run data remains local. Google Maps route tiles are not implemented in this version; saved runs use the bundled offline SVG route preview instead.

## Reminders

Task reminder settings are synchronized to Android `AlarmManager`. On first use, allow notifications and exact alarms in Android settings. If exact-alarm access is unavailable, the app falls back to Android's inexact idle alarm. Android notification channels let users change sound and vibration at OS level. Audible reminders request a lock-screen alarm view and retain the notification as a fallback when Android does not allow full-screen presentation. While that alarm view is ringing, either hardware volume button silences only this reminder and snoozes it; it does not change the phone's global media or ringer volume. Reminder notifications have Done, Snooze, and Skip actions. Skip records the occurrence as skipped without opening the app.

The app keeps a private recovery snapshot as data changes and schedules a weekly JSON backup. On Android 10 and newer, automatic backups are written under `Downloads/WayToConsistency/Backups`; on older Android they are kept in the app's Documents folder. The newest eight backups are retained. Settings also offers **Create backup now** and **Restore latest backup**. These are local-device backups, not cloud sync, and uninstalling the app can remove its private recovery copies.

Habits provides one account-wide streak freeze per ISO week. Apply it to a missed occurrence of a recurring habit during that week. The occurrence remains missed in reports, but is neutral when calculating that habit's streak.

Tasks can optionally track a target amount and unit such as 10 km. Progress belongs to an individual scheduled date, so recurring tasks start each date at zero. Reaching the target marks an unlocked occurrence done.

Task availability is selected explicitly whenever a task is saved. **Available anytime** permits occurrence updates before its date. **Unlock at scheduled date/time** keeps completion, progress, skip/cancel, and individual rescheduling read-only until the occurrence start time, or local midnight when no start time is set. Older backups are migrated without changing their previous early-completion behavior.

Alarms are restored after reboot, app replacement, and device time or timezone changes. Android can still defer alarms under battery restrictions, and a user force-stopping the app disables scheduled work until the app is opened again. Browser-only HTML continues to use best-effort in-page reminders and cannot guarantee delivery when Chrome suspends or closes it.

## GPS runs

Open **More > Run** and start a run to request precise location access. GPS points, route, distance, elapsed time, kilometer splits, and notes are saved on this device. The run cannot start or resume while the device Location switch or GPS provider is off; the app explains the problem and opens Location Settings. Switching Location off during a run pauses it immediately, clears its distance anchor, and starts a fresh route segment only after the user explicitly resumes. An Android foreground notification stays visible while a run is recording; pause or finish from the app. Keep location services enabled, allow precise location, and wait for an accurate fix outdoors. After finishing, open the run from history to view its offline route preview. Saved runs can optionally add distance to a kilometer goal and can be exported as GPX. Browser-only HTML cannot record a screen-locked GPS run.

Distance is calculated from accepted GPS fixes with accuracy, age, movement-noise, and running-speed checks. Small movements accumulate from the last accepted fix. Pausing starts a new route segment so movement during the pause is never added. The app pauses recovery conservatively after a reboot or an invalid monotonic clock state. The built-in route preview is offline; Google Maps tiles are marked as not implemented.

## Camera and sounds

Camera and microphone permissions are requested only when their features are used. The bundled page uses Android's trusted local HTTPS asset origin so WebView can grant camera access. Photos and app data stay in app-local WebView storage. Imported sounds open an offline waveform trimmer before they are saved. A clip can be up to 30 seconds and is converted to mono 16 kHz WAV so its size and Android playback behavior remain predictable. Imported or recorded sounds are saved in the app's private files. When creating or editing a task, **Choose from device…** opens Android's notification ringtone picker; the selected system sound is stored on that task. Android may retain notification-channel choices until changed in system notification settings.
