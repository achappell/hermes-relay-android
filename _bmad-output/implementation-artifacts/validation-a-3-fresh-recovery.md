---
title: 'Validation — A-3 fresh recovery without replaying an uncertain turn'
type: 'validation'
story_id: 'A-3'
created: '2026-09-12'
status: 'done'
---

## Scope

Validate the Android-owned bounded reconnect ladder, visible connection state,
fresh-Session adoption, unconfirmed-turn retention, the no-automatic-replay
rule, exactly-once explicit resend, repeated-loss ladder isolation, and
superseded-Session event rejection. No live Hermes endpoint, credentials,
microphone, PCM, speaker, or Device is used.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21; 24 unit tests across four classes passed (10 new A-3
  recovery tests), the debug APK assembled, and lint reported no blocking
  findings.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed under explicit
  JDK 21 on `hermes-relay-api36` (Android 16/API 36); all 5 instrumentation
  tests passed, including the new lost-turn recovery and explicit-resend path.
- `scripts/check-apk-metadata.sh` — passed; APK declares minimum SDK 26.
- `scripts/check-missing-sdk.sh` — passed; Gradle reported an explicit missing
  SDK failure. The ignored local `local.properties` was temporarily moved and
  restored for this check.
- `git diff --check` — passed before commit.

## Evidence Notes

- `AndroidRecoveryControllerTest` covers ladder progress with a fresh Session,
  exhaustion followed by a successful later attempt, immediate abandonment on
  an unrecoverable failure, no submission during recovery, exactly-once
  explicit resend, retention after a rejected resend, refusal while
  disconnected, a second loss not stacking ladders, discard, and stale
  `session-1` events failing to mutate a recovered `session-2` turn.
- The Compose test drives a real disconnect through the A-2 reducer, asserts
  the visible `Disconnected` state and unconfirmed-turn card, confirms the
  adapter received no request during reconnect, and confirms the explicit
  resend submits the identical request once and clears the marker.
- JDK 21 remains the repository execution baseline; the API 26 minimum and API
  37 compile/target configuration are unchanged.
