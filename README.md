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

- JDK 17
- Gradle Wrapper 9.3.1
- Android Gradle Plugin 9.1.1
- Kotlin 2.4.20 with the matching Compose compiler plugin
- Compile/target SDK 37; minimum SDK 26
- Compose BOM 2026.08.00

The repository uses the Android Gradle Plugin and a Gradle wrapper so local and
CI builds share the same build version. Do not commit `local.properties`, SDK
paths, tokens, profile files, audio captures, or signing material.

## Local setup

Install a JDK 17 and Android SDK command-line tools, then install platform-tools,
platform 37, and build-tools 36.0.0. Point `JAVA_HOME` and
`ANDROID_SDK_ROOT` at those local installations. The exact SDK path belongs in
the ignored `local.properties` file or the Android CLI environment, never in
the repository.

Run the verification commands from this directory:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

To install the debug shell on a connected device or emulator:

```bash
./gradlew installDebug
```

There is no live Hermes endpoint requirement for the bootstrap tests or build.

## Boundary rules

Hermes remains the session, model, speech, and voice-session protocol authority.
Android presentation code will consume typed adapters and normalized events; it
will not parse Hermes wire frames or silently replay an uncertain turn. Android
and iOS share capability and safety contracts, not source files or credentials.
