# Way to Consistency for Android

This is a native Android shell around the existing offline HTML application. The Gradle build copies `../way-to-consistency.html` into the APK as `index.html` and bundles the supplied logo and theme gallery from `../assets`. Keep the root HTML as the single UI source. The installed app does not request internet access.

## Build the APK

Requirements: Android Studio (or Gradle 8.9), JDK 17, and Android SDK Platform 35. Open this `android` directory in Android Studio and build the `app` debug variant, or run:

```sh
cd android
gradle assembleDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Copy that APK to the Android device and install it. Android may ask the user to allow installation from that file source. For public distribution, create and securely retain a signed release key, then build the release variant in Android Studio.

The first build needs access to download Gradle and Android build dependencies. The resulting APK runs without internet. An Android SDK is not installed in the current development environment, so the APK cannot be built here.

## Reminders

Task reminder settings are synchronized to Android `AlarmManager`. On first use, allow notifications and exact alarms in Android settings. If exact-alarm access is unavailable, the app falls back to Android's inexact idle alarm. Android notification channels let users change sound and vibration at OS level. A notification's Done action updates the task when the app opens; Snooze schedules it again using the configured snooze duration.

Tasks can optionally track a target amount and unit such as 10 km. Progress belongs to an individual scheduled date, so recurring tasks start each date at zero. Reaching the target marks that date done unless early completion has been disabled. One-time due-date tasks allow early completion by default.

Alarms are restored after reboot, app replacement, and device time or timezone changes. Android can still defer alarms under battery restrictions, and a user force-stopping the app disables scheduled work until the app is opened again. Imported browser audio is not copied into Android notification channels; native notifications use the system notification sound or silence according to the task's sound and quiet-hours settings. Browser-only HTML continues to use best-effort in-page reminders and cannot guarantee delivery when Chrome suspends or closes it.

Camera, microphone, and photo-file access are requested only when the corresponding feature is used. Journal photos and application data remain in app-local WebView storage.