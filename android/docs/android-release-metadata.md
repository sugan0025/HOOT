# Android release metadata

The Android updater looks for stable GitHub Releases whose tag matches
`android-vMAJOR.MINOR.PATCH`. Each release must include these two assets:

- `Hoot-Android.apk`
- `Hoot-Android-update.json`

The JSON asset must contain the following fields:

| Field | Meaning |
| --- | --- |
| `schemaVersion` | Integer `1` |
| `applicationId` | `app.hoot` |
| `tag` | Exact Android release tag, such as `android-v1.2.0` |
| `versionCode` | Positive integer equal to the APK's Android version code |
| `versionName` | Display version, such as `1.2.0` |
| `releaseNotes` | Short plain text release notes |
| `apkUrl` | HTTPS browser download URL for the APK attached to this same GitHub release |
| `apkSizeBytes` | Exact APK asset size in bytes |
| `sha256` | Lowercase SHA-256 hex digest of the APK |
| `signerSha256` | Lowercase SHA-256 hex digest of the APK signing certificate |

The updater obtains the release list and asset sizes from GitHub's public API,
then requires the metadata tag, APK URL and size to match that API response. It
also checks the installed app ID, version code, current signing certificate and
downloaded APK signature before asking Android to install it. The certificate
fingerprint must remain stable across updates. Never include signing secrets in
the APK or this metadata file.

No release workflow or release has been created by this implementation. The
current repository must eventually attach both assets before the updater can
find an update.
