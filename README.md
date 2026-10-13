# Hermes Relay Android

Native Android delivery repository for the Hermes Relay Client. This is the
feature-parity sibling of the SwiftUI iOS Client; it owns Android lifecycle,
permissions, accessibility, presentation, audio, secure Profile storage, and
deliberate per-profile Local History.

## Current state

The Compose client supports paired HomeBridge connections and explicit direct
Standard connections. Standard uses unmodified Hermes, separate credentials,
identity-scoped Local History, and no-replay recovery; it never falls back to
Home. Implementation, merge, emulator and physical-device acceptance are recorded
separately in the local story specifications and validation records.

## Toolchain

- JDK 21 (Gradle/Kotlin runtime; Java 17-compatible Android bytecode)
- Gradle Wrapper 9.8.0
- Android Gradle Plugin 9.4.1
- Kotlin 2.4.20 with the matching Compose compiler plugin
- Compile/target SDK 37; minimum SDK 26
- Compose BOM 2026.09.00

Android 16 (API 36) and Android 17 (API 37) are both supported by the API 26
minimum and API 37 compile/target configuration.

The repository uses the Android Gradle Plugin and a Gradle wrapper so local
builds and future CI jobs can share the same build version. Do not commit
`local.properties`, SDK paths, tokens, profile files, audio captures, or
signing material.

## Local setup

Install JDK 21 and Android SDK command-line tools, then install `platform-tools`,
`platforms;android-37.0`, and `build-tools;36.0.0`. Point `JAVA_HOME` at the
actual JDK home and `ANDROID_HOME`/`ANDROID_SDK_ROOT` at the SDK. On an
Apple Silicon Mac with Homebrew JDK 21:

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
sdkmanager --sdk_root="$ANDROID_HOME" \
  "cmdline-tools;latest" "platform-tools" "platforms;android-37.0" "build-tools;36.0.0"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Use the SDK-local command-line tools, including `apkanalyzer`: a Homebrew-root
tool can otherwise fail to find build-tools in the separate SDK. Do not use
the Homebrew formula prefix itself as `JAVA_HOME`; Kotlin needs the JDK's
`Contents/Home`. Machine-local SDK paths belong in ignored `local.properties`
or the shell environment, never in tracked configuration.

In Android Studio, open this repository's root folder, not `app/`. Select
JDK 21 under **Settings → Build, Execution, Deployment → Build Tools → Gradle**.
The IDE's bundled JBR need not be 21, but Gradle must be able to find the
project's Java 21 toolchain. Sync the project, select the **app** run
configuration, choose an API 36 or 37 device in Device Manager, and press
**Run**. A Mac ARM64 emulator needs the ARM64 system image. Running Devices
shows the emulator when Studio launches it.

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

To install the debug app on a connected device or emulator:

```bash
./gradlew installDebug
```

There is no live Hermes endpoint requirement for unit tests or builds.
`RelayConfigurationTest` clears the app's credential preferences; run it only
on an isolated test emulator, not an already paired personal device.

### Direct Standard setup

In **Configure relay**, choose **Standard**, enter a secure `https`/`wss`
endpoint, a display name, a Hermes profile (blank means `default`), and the
Standard token. Setup checks `session.create` before saving anything.
The token is stored in its own Android Keystore-backed slot, not Profile JSON,
Local History, diagnostics, or Home credentials. Endpoint changes require
fresh token entry. Existing unlinked Profiles remain **Legacy — needs setup**;
they are not silently migrated.

Standard offers typed streaming, on-device speech capture, and response audio
through `/api/audio/speak-stream`. Audio starts after the text response
completes, so this is not low-latency incremental speech. An audio failure
leaves the text reply available. Stop sends the verified remote interrupt and
always stops local playback.

Reconnect restores the in-process session without sending the prompt again;
it does not erase uncertain delivery. Only a successful deliberate **New
conversation** clears uncertainty and adds a Local History divider. Earlier
history remains local and is never resent. Session references are not restored
across process restarts, and Standard does not expose Home conversation or
approval menus. Profile/mode changes are blocked while input, capture, a turn,
or uncertain delivery is pending.

See [ANDROID-STD-01 validation](_bmad-output/implementation-artifacts/validation-android-std-01.md)
for the verified upstream baseline, emulator evidence, and physical voice and
accessibility gates that remain unverified.

### Which build is installed

Configure Relay -> Troubleshooting shows a read-only, copyable row such as
`Version 0.3.1 (301) · release · 3e10ae2` (test tag `app-version`). It is read
from the installed package, so it cannot drift from the APK that
`scripts/check-apk-metadata.sh` verifies:

- `0.3.1` is `versionName` and `(301)` is `versionCode`; both are unchanged by
  this feature, and `versionCode` stays derived from `versionName`.
- `release` or `debug` is the build type.
- `3e10ae2` is the short git revision the build was made from (`unknown` when
  git is not available, for example a source tarball).

Two rebuilds of the same version have the same code, so they are told apart by
the revision, not the code. The Share diagnostics header carries the same
version, code, build type and revision. The row never includes a serial, an
account or an address. Releases are still a signed APK that you sideload;
Play Store distribution remains outside this repository.

### Repetition gate for lifecycle, reconnect and voice tests

A lifecycle, reconnect or voice test that passes once proves little: the iOS
reconnect race failed 14 of 30 runs. Run the classes you touched many times
before merging:

```bash
scripts/run-flake-gate.sh OkHttpRelaySessionClientTest,AndroidRecoveryControllerTest 30
```

It runs the named JVM test classes N times (default 30) and prints
`N consecutive runs, 0 failures` or the failing iteration counts per class.
Record that line in the validation record of the ticket. A failure that also
occurs on the pre-change code is a baseline flake: record its count rather
than rerunning until it passes. Tests must not wait on the wall clock; inject
`MonotonicClock`/`Sleeper` and the `AndroidPlatform` value instead.

## GitHub actions and releases

Pull requests and pushes to `main` run the JVM, build, lint, and APK metadata
checks. Instrumentation tests remain a local-device/emulator check for Android
16 (API 36) and Android 17 (API 37). Release Please maintains the version and
changelog; merging its release PR creates a `v*` tag and packages a release APK
with a `SHA256SUMS.txt` file.

The release variant is minified with R8 and resource shrinking. Pull requests
build and lint it (`assembleRelease lintRelease`), run
`scripts/check-apk-metadata.sh` on it (backup and data-extraction rules, and
16 KB page-size alignment of the 64-bit native libraries via
`scripts/check_elf_alignment.py`), and fail if R8 emits a non-empty
`missing_rules.txt` or renames `okhttp3.internal.http2.StreamResetException`.
The release workflow uploads `mapping.txt` as the `r8-mapping-<tag>` workflow
artifact (90 days); it is not a release asset. De-obfuscate a stack trace with
`retrace mapping.txt trace.txt` from the Android SDK command-line tools.

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
