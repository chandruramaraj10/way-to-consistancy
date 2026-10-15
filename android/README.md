# Way to Consistency for Android

This is a native Android shell around the existing offline HTML application. The Gradle build copies `../way-to-consistency.html` into the APK as `index.html` and bundles the supplied logo and theme gallery from `../assets`. Keep the root HTML as the single UI source. Task and run data stay on-device; internet is used only for Google Maps tiles when viewing a route.

## Build the APK

Requirements: Android Studio (or Gradle 8.9), JDK 17, and Android SDK Platform 35. Open this `android` directory in Android Studio and build the `app` debug variant, or run:

```sh
cd android
gradle assembleDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Copy that APK to the Android device and install it. Android may ask the user to allow installation from that file source. For public distribution, create and securely retain a signed release key, then build the release variant in Android Studio.

The first build needs access to download Gradle and Android build dependencies. Core task and run data remains local. Google Maps route tiles need an internet connection and a Google Maps SDK for Android API key with Maps SDK for Android enabled. Keep the key out of source control; provide it through Gradle's user properties (for example, `~/.gradle/gradle.properties` with `GOOGLE_MAPS_API_KEY=...`) when building. Restrict the key to the app package and signing certificate. The app reports when the key is missing.

## Reminders

Task reminder settings are synchronized to Android `AlarmManager`. On first use, allow notifications and exact alarms in Android settings. If exact-alarm access is unavailable, the app falls back to Android's inexact idle alarm. Android notification channels let users change sound and vibration at OS level. Reminder notifications have Done, Snooze, and Skip actions. Skip records the occurrence as skipped without opening the app.

The app keeps a private recovery snapshot as data changes and schedules a weekly JSON backup. On Android 10 and newer, automatic backups are written under `Downloads/WayToConsistency/Backups`; on older Android they are kept in the app's Documents folder. The newest eight backups are retained. Settings also offers **Create backup now** and **Restore latest backup**. These are local-device backups, not cloud sync, and uninstalling the app can remove its private recovery copies.

Habits provides one account-wide streak freeze per ISO week. Apply it to a missed occurrence of a recurring habit during that week. The occurrence remains missed in reports, but is neutral when calculating that habit's streak.

Tasks can optionally track a target amount and unit such as 10 km. Progress belongs to an individual scheduled date, so recurring tasks start each date at zero. Reaching the target marks an unlocked occurrence done.

Task availability is selected explicitly whenever a task is saved. **Available anytime** permits occurrence updates before its date. **Unlock at scheduled date/time** keeps completion, progress, skip/cancel, and individual rescheduling read-only until the occurrence start time, or local midnight when no start time is set. Older backups are migrated without changing their previous early-completion behavior.

Alarms are restored after reboot, app replacement, and device time or timezone changes. Android can still defer alarms under battery restrictions, and a user force-stopping the app disables scheduled work until the app is opened again. Browser-only HTML continues to use best-effort in-page reminders and cannot guarantee delivery when Chrome suspends or closes it.

## GPS runs

Open **More > Run** and start a run to request precise location access. GPS points, route, distance, elapsed time, kilometer splits, and notes are saved on this device. An Android foreground notification stays visible while a run is recording; pause or finish from the app. Keep location services enabled, allow precise location, and wait for an accurate fix outdoors. After finishing, open the run from history and choose **Open Google Maps route** to view its recorded path in the app. Saved runs can optionally add distance to a kilometer goal and can be exported as GPX. Browser-only HTML cannot record a screen-locked GPS run.

Distance is calculated from accepted GPS fixes with accuracy, age, movement-noise, and running-speed checks. Small movements accumulate from the last accepted fix. Pausing starts a new route segment so movement during the pause is never added. The app pauses recovery conservatively after a reboot or an invalid monotonic clock state. The built-in route preview is offline; Google Maps tiles are optional.

## Camera and sounds

Camera and microphone permissions are requested only when their features are used. The bundled page uses Android's trusted local HTTPS asset origin so WebView can grant camera access. Photos and app data stay in app-local WebView storage. Imported or recorded sounds are saved in the app's private files. When creating or editing a task, **Choose from device…** opens Android's notification ringtone picker; the selected system sound is stored on that task. Android may retain notification-channel choices until changed in system notification settings.
