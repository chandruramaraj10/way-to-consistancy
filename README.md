# Way to Consistency

An offline-first tasks, habits, reminders, personal finance, reports, and progress-journal app.

## Browser app

Open [way-to-consistency.html](way-to-consistency.html) in a modern browser. The interface and its supplied images are local. User records are stored in that browser on that device; use the app's backup export before moving or clearing browser data.

Browser reminders are best-effort while the page is open. For Android alarms that can arrive while the app is closed, build and install the native Android app described in [android/README.md](android/README.md).

## Android build

Open the `android` directory in Android Studio with JDK 17 and Android SDK Platform 35 installed. Build the debug variant to create `android/app/build/outputs/apk/debug/app-debug.apk`. The Android app bundles the HTML and local image assets for offline use.

## Project assets

- `assets/brand-logo.png` and `assets/theme-gallery.png` are the app's local logo and UI reference gallery.
- `Gemini_Generated_Image_rd5rntrd5rntrd5r.png` and `Gemini_Generated_Image_vtqd4avtqd4avtqd.png` are supplied generated image references.
- `Way_to_Consistency_Master_UI_UX_and_Build_Blueprint.pdf` is the product and implementation blueprint.