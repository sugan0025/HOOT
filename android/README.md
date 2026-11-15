# HOOT for Android

This is the native Android app, kept separate from the existing Windows Avalonia/C# projects. It uses Kotlin,
Jetpack Compose, and the Android document picker. It does not change the Windows app or use Google Play.

## Build

- Android Studio or Android SDK platform 36
- JDK 17

From this directory:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The Android application ID is `app.hoot`; minimum Android version is API 26. Local SDK paths, keystores, and APK
outputs are excluded from Git.

## Android release and in-app updates

Android releases use separate `android-vMAJOR.MINOR.PATCH` tags and are published as their own GitHub Releases.
They are deliberately marked **not latest**, so the Windows updater continues to see the Windows `v*` release as
the repository's latest release.

The Android release workflow builds a signed APK and attaches:

- `Hoot-Android.apk`
- `Hoot-Android-update.json`

Before pushing an Android release tag, configure these GitHub Actions repository secrets:

- `HOOT_ANDROID_KEYSTORE_BASE64` — base64 encoding of the Android release keystore
- `HOOT_ANDROID_KEYSTORE_PASSWORD`
- `HOOT_ANDROID_KEY_ALIAS`
- `HOOT_ANDROID_KEY_PASSWORD`

The Java keystore passwords must use ASCII characters. If either secret contains non-ASCII characters, the workflow
fails with the secret name only; it never prints the secret value. Keep using the same signing key so existing Android
installs remain update-compatible.

For each later Android release, increment `versionCode`, set `versionName` in `app/build.gradle.kts`, and push the
matching `android-v<versionName>` tag. Android version numbers are independent of Windows HOOT versions.

The signing certificate must match the Android app's established signing identity. The prototype APK reports
SHA-256 certificate fingerprint `30e9d23fb1a37a9d9dc1f117e2366d23a17186cf745b0570b7b909f75602fcd6`; the release workflow
rejects a different signer so existing Android installs can accept updates. Never commit the keystore or passwords.

The updater validates the release metadata, APK size and SHA-256, package ID, version code, and signing certificate.
Android still requires the user to approve package installation in system settings; the app cannot silently install
its own APK.

The Android 1.0.3 APK will be available at:

```text
https://github.com/sugan0025/HOOT/releases/download/android-v1.0.3/Hoot-Android.apk
```

That URL becomes usable after the `android-v1.0.3` release workflow succeeds.
