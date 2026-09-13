# Hermes Relay Android

Native Android delivery repository for the Hermes Relay Client. This is the
feature-parity sibling of the SwiftUI iOS Client; it owns Android lifecycle,
permissions, accessibility, presentation, audio, secure Profile storage, and
deliberate per-profile Local History.

## Current state

This repository is the Android bootstrap only. The launchable Compose shell is
honest about the missing Hermes connection. It does not invent protocol frames,
responses, credentials, audio, Device operations, or Local History. The first
delivery slices are the Android surface stories `A-1` through `A-3`, `2-A-1`
through `2-A-2`, `3-A-1` through `3-A-6`, and `5-A-1`.

## Toolchain

- JDK 21 (Gradle/Kotlin runtime; Java 17-compatible Android bytecode)
- Gradle Wrapper 9.7.1
- Android Gradle Plugin 9.4.0
- Kotlin 2.4.20 with the matching Compose compiler plugin
- Compile/target SDK 37; minimum SDK 26
- Compose BOM 2026.08.00

Android 16 (API 36) and Android 17 (API 37) are both supported by the API 26
minimum and API 37 compile/target configuration.

The repository uses the Android Gradle Plugin and a Gradle wrapper so local
builds and future CI jobs can share the same build version. Do not commit
`local.properties`, SDK paths, tokens, profile files, audio captures, or
signing material.

## Local setup

Install JDK 21 and Android SDK command-line tools, then install platform-tools,
platform 37, and build-tools 36.0.0. Point `JAVA_HOME` and
`ANDROID_SDK_ROOT` at those local installations. The exact SDK path belongs in
the ignored `local.properties` file or the Android CLI environment, never in
the repository.

Run the verification commands from this directory:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
scripts/check-apk-metadata.sh
scripts/check-missing-sdk.sh
```

With an Android emulator or device available, also run:

```bash
./gradlew connectedDebugAndroidTest
```

To install the debug shell on a connected device or emulator:

```bash
./gradlew installDebug
```

There is no live Hermes endpoint requirement for the bootstrap tests or build.

## GitHub actions and releases

Pull requests and pushes to `main` run the JVM, build, lint, and APK metadata
checks. Instrumentation tests remain a local-device/emulator check for Android
16 (API 36) and Android 17 (API 37). Release Please maintains the version and
changelog; merging its release PR creates a `v*` tag and packages a release APK
with a `SHA256SUMS.txt` file.

The release APK is signed with a developer keystore so it can be sideloaded and
upgraded in place. CI reads it from four repository secrets:

| Secret | Contents |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `RELEASE_KEY_PASSWORD` | Password for that key |

Create the keystore once and upload it, keeping the `.jks` and its passwords out
of the repository:

```bash
keytool -genkeypair -v -keystore release.jks -storetype PKCS12 \
  -alias hermes-relay -keyalg RSA -keysize 4096 -validity 10000
base64 -i release.jks | gh secret set RELEASE_KEYSTORE_BASE64
gh secret set RELEASE_KEYSTORE_PASSWORD
gh secret set RELEASE_KEY_ALIAS
gh secret set RELEASE_KEY_PASSWORD
```

Keep `release.jks` backed up somewhere safe: losing it means future releases are
signed with a different key, and installed builds can no longer upgrade.

Local `assembleRelease` runs without those values fall back to the Android debug
key, so an offline build still works. To sign locally, set
`RELEASE_KEYSTORE_FILE` plus the same three password/alias variables in the
environment. Play Store distribution remains intentionally outside this
repository.

## Story map and status

This repository owns the Android surface story map in
`_bmad-output/implementation-artifacts/story-index.yaml` and formal delivery
status in `sprint-status.yaml`. Story specifications and validation records
remain beside the Android implementation.

The sibling TUI coverage index and epic snapshot are read-only context for
cross-repository applicability and dependencies. An Android status transition
does not require an edit to the TUI repository or the product hub. Update the
product hub only when implementation changes durable shared intent or a
cross-surface decision.

## Boundary rules

Hermes remains the session, model, speech, and voice-session protocol authority.
Android presentation code will consume typed adapters and normalized events; it
will not parse Hermes wire frames or silently replay an uncertain turn. Android
and iOS share capability and safety contracts, not source files or credentials.
