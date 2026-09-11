---
title: 'Validation — A-1 authorized typed or tap-to-speak initiation'
type: 'validation'
story_id: 'A-1'
created: '2026-09-11'
status: 'done-with-environment-limitation'
---

## Scope

Validate the Android-owned Profile and authorization gate, exactly-once typed
adapter invocation, tap-to-speak request shape, honest unavailable behavior,
and the launchable Compose surface. No live Hermes endpoint, credentials,
microphone, response audio, or Device is used.

## Checks

- `./gradlew testDebugUnitTest` — passed under JDK 21; 6 A-1 tests and the
  existing bootstrap test passed (7 tests total).
- `./gradlew assembleDebug` — passed under JDK 21; debug APK produced.
- `./gradlew lintDebug` — passed under JDK 21 with no blocking findings.
- `scripts/check-apk-metadata.sh` — passed; APK declares min SDK 26.
- `scripts/check-missing-sdk.sh` — passed; Gradle reported an explicit missing
  SDK failure. The ignored local `local.properties` was temporarily moved and
  restored for this check.
- `./gradlew connectedDebugAndroidTest --no-daemon` — instrumentation sources
  and APKs compiled under JDK 21, but execution stopped with `No connected
  devices!`. The existing `hermes-relay-api36` AVD cannot start because its
  Android 16 system image is not installed.
- `git diff --check` — passed before the final verification pass.

## Evidence Notes

- The initial focused run under JDK 17 passed after the implementation import
  correction; the final focused run was repeated under JDK 21 after the
  project baseline moved.
- The connected test's compile phase passed; only device execution remains
  unverified until an API 36 or API 37 system image is installed.
