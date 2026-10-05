<p align="center">
  <img src="assets/pathline-app-icon-512.png" alt="Pathline app icon" width="112" height="112">
</p>

<h1 align="center">Pathline</h1>

<p align="center">
  Your places, trips, and routes. A personal timeline on your phone.
</p>
<p align="center">
  <a href="https://play.google.com/store/apps/details?id=net.extrawdw.apps.locationhistory">Google Play</a> ·
  <a href="https://locationhistory.apps.extrawdw.net/">Website</a> ·
  <a href="#data-api">Data API</a> ·
  <a href="#build-from-source">Build from source</a> ·
  <a href="https://locationhistory.apps.extrawdw.net/privacy.html">Privacy Policy</a>
</p>

Pathline records your location history on Android and organizes it into a daily timeline of visits and trips. Review where you went, follow your routes on a map, and add context to the places you visit. Your timeline is stored locally in an encrypted database, and no account is required.

## What you can do

- **Review your day.** Browse visits and trips by date, replay a trip on the map, and inspect distance, duration, speed, and altitude when recorded data is available.
- **Refine your timeline.** Confirm places and travel modes, split activities, and add tags and notes.
- **Keep track of familiar places.** Save and edit places, merge duplicates, and revisit their visit history.
- **Choose how to record.** Use battery saver, balanced, or high accuracy profiles. Motion detection and geofencing help reduce unnecessary GPS use while recording in the background.
- **Back up and export.** Save your timeline to a folder you choose, optionally encrypt backups with a password or supported passkey, and share trips as GPX files.
- **Connect other apps.** Give apps on the same device permission to use your timeline through the [Pathline Data API](#data-api).

## Get started

Pathline requires **Android 14 or newer** and uses **Google Play services** for location and activity recognition.

1. Install Pathline from [Google Play](https://play.google.com/store/apps/details?id=net.extrawdw.apps.locationhistory).
2. Follow onboarding to grant location, physical activity, and notification permissions. Choose **Allow all the time** for location if you want to record while the app is closed.
3. Allow background operation or auto-start if your device asks, then choose your recording profile in **Settings**.
4. Open the timeline to review your visits and trips. Set up a backup folder in **Settings** to keep a recoverable copy of your data.

Place search, automatic place suggestions, and travel-time queries use your own Google Maps Platform API key, configured under **Settings → Google Maps Platform**. The app includes key setup and testing tools. You can record your timeline and manage places manually without configuring these optional services.

## Privacy and storage

- **Local history.** The timeline database is encrypted with SQLCipher; its encryption key is protected by Android Keystore. Pathline does not require a cloud account.
- **Google services.** Map display, place search, and travel-time features send the relevant map areas, coordinates, or queries to Google to provide those features.
- **Diagnostics.** Builds configured with Firebase enable crash and performance reporting by default. You can turn it off with **Share crash & performance reports** in Settings.
- **App access.** Third-party data access is off by default. You choose which permissions to grant and can review access history or revoke access in Settings.

See the [privacy policy](https://locationhistory.apps.extrawdw.net/privacy.html) for more information.

## Backups and GPX

Choose a backup folder through Android's file picker, using local storage or a compatible cloud storage provider. Backups support optional password or passkey encryption; passkey encryption requires a credential provider that supports the WebAuthn PRF extension. Scheduled backups run while the device is charging and connected to a network.

Restoring a Pathline backup replaces the existing timeline data. GPX exports are unencrypted route files for use in other apps; they do not contain a complete Pathline backup and cannot be used to restore it. You can share an individual trip as a GPX attachment or configure weekly GPX exports.

## Data API

Pathline exposes an Android `ContentProvider` for integrations on the same device. With the user's approval, clients can read visits, trips, routes, samples, and saved places, search recorded data, and manage annotations such as tags and notes with separate write permissions.

Start with the [integration guide](https://locationhistory.apps.extrawdw.net/api.html) for API revision 3, setup, and permission handling. Download the [latest contract](https://locationhistory.apps.extrawdw.net/contracts/PathlineContract.kt), or use a pinned contract from the [revision history](https://locationhistory.apps.extrawdw.net/api-revisions.html). The repository also includes [PathlineContract.kt](app/src/main/java/net/extrawdw/apps/locationhistory/api/PathlineContract.kt) and the reference [PathlineClient.kt](app/src/main/java/net/extrawdw/apps/locationhistory/api/PathlineClient.kt).

## Build from source

The app is written in Kotlin with Jetpack Compose and Material 3, using Room with SQLCipher, Hilt, and WorkManager.

Open this repository in Android Studio with support for the Android Gradle Plugin version in [the version catalog](gradle/libs.versions.toml). Install **Android SDK Platform 37**, and use a [Gradle-compatible JDK](https://docs.gradle.org/current/userguide/compatibility.html#java_runtime), such as JDK 21. The repository includes the Gradle wrapper.

To display maps in your own build, supply a Maps SDK for Android key in the root `local.properties`:

```properties
MAPS_SDK_API_KEY=your_maps_sdk_key
```

Alternatively, set the `PATHLINE_MAPS_SDK_API_KEY` environment variable. Restrict the key to the Maps SDK for Android, the package `net.extrawdw.apps.locationhistory`, and your app's signing certificate. This build-time key is separate from the user-configured Places and Routes key described above.

Firebase configuration is optional. To enable it for your build, add your own `app/google-services.json`; without that file, Firebase services remain disabled. Both configuration files are ignored by Git.

Build a debug APK and run local checks:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. On Windows, use `gradlew.bat`. To run instrumented tests, connect an Android 14+ test device or emulator and run:

```sh
./gradlew :app:connectedDebugAndroidTest
```

## Feedback and contributions

Report bugs or suggest improvements through [GitHub Issues](https://github.com/dingwen07/Pathline/issues). Include the app version, Android version, device model, and steps to reproduce. Remove personal location data and API keys from any logs or screenshots you share.

For code changes, keep pull requests focused and describe how you verified the behavior.
