# Hermes Relay Android

Native Android delivery repository for the Hermes Relay Client. This is the
feature-parity sibling of the SwiftUI iOS Client; it owns Android lifecycle,
permissions, accessibility, presentation, audio, secure Profile storage, and
deliberate per-profile Local History.

## Current state

The Compose client supports HomeBridge setup and an explicit Standard-only
typed-chat setup. Standard slice 1 uses its own credential slot and talks to
Hermes at `/api/ws`; Home pairing, household administration and voice are not
offered in Standard mode by this slice. Implementation, live-baseline and device
acceptance are separate: see the local story index and validation records below.

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

### Standard-only setup (slice 1)

Choose **Standard** in Configure Relay, enter an `https://` or `wss://` endpoint
ending in `/api/ws`, a Standard token, and optionally a Hermes Profile (blank
means `default`). A successful session-creation check is required before saving.
The configured endpoint cannot contain a token, userinfo, query or fragment.
The token uses a separate encrypted credential slot; the request adds it as an
in-memory URL-encoded query parameter, matching the existing Home/TUI adapter
boundary rather than assuming an Authorization header is accepted.

Local history stays scoped to the mode, endpoint and Hermes Profile. Changing
the endpoint requires re-entering credentials and never reuses a prior session
or sends old history. Pre-Home profiles remain **Legacy — needs setup**; they
are not silently converted to Standard.

Typed responses stream without response audio in this slice. Stop is local:
until Hermes ends the previous response, sending stays blocked with
“Hermes is finishing the previous response.” After uncertain delivery, reconnect
does not replay or clear the uncertain turn. **New conversation** deliberately
creates a fresh session; only success clears uncertainty. Active, uncertain,
finishing and session-creation transitions block profile switching.

The always-running `StandardBaselineProbeTest` exercises the real adapter
against a local TLS/WebSocket fixture. `StandardBaselineProbeLiveTest` is inert
unless explicitly enabled with `HERMES_STANDARD_PROBE_LIVE=1` and endpoint/token
environment variables. Its redacted report is written to
`app/build/standard-baseline-probe.md`; never share raw request URLs or tokens.
Neither local fixture success nor instrumentation compilation establishes
household Hermes 0.21.5 or device acceptance. See
[`validation-android-std-01.md`](_bmad-output/implementation-artifacts/validation-android-std-01.md)
for the exact exercised and unrun gates.


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
