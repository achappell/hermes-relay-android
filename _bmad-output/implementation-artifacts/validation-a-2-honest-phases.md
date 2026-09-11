---
title: 'Validation — A-2 honest phases with response text/audio delivery'
type: 'validation'
story_id: 'A-2'
created: '2026-09-11'
status: 'done'
---

## Scope

Validate the Android-owned normalized-event boundary, canonical phase
projection, coherent streamed response text, audio delivery ordering,
text-preserving audio failure, stale-event rejection, and disconnected state.
No live Hermes endpoint, credentials, microphone, PCM, speaker, or Device is
used.

## Checks

- `./gradlew testDebugUnitTest --tests
  com.achappell.hermesrelay.AndroidTurnStateTest --tests
  com.achappell.hermesrelay.AndroidInitiationControllerTest --no-daemon` —
  passed under explicit JDK 21; focused reducer and A-1 contract tests passed.
- `./gradlew compileDebugAndroidTestKotlin --no-daemon` — passed under
  explicit JDK 21; Compose instrumentation sources compiled. The installed
  Compose test API emits a deprecation warning for the existing
  `createAndroidComposeRule` API.
- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21; repository unit tests, debug APK assembly, and lint
  passed.
- `scripts/check-apk-metadata.sh` — passed; APK declares minimum SDK 26.
- `scripts/check-missing-sdk.sh` — passed; missing SDK configuration reports
  explicitly rather than silently using a machine path.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed under explicit JDK
  21 on `hermes-relay-api36` (Android 16/API 36); all 4 instrumentation tests
  passed.
- `git diff --check` — passed before commit.

## Evidence Notes

- The reducer covers `Listening → Transcribing → Thinking → Buffering →
  Speaking → Complete`, late processing-event suppression, streamed text
  append/replace behavior, completion-before-audio ordering, audio failure,
  stale Profile/Session/turn identity, unknown events, and disconnect.
- The Compose tests exercise an authorized fake port, visible lifecycle
  phases, one response surface, and text retention when normalized audio
  delivery is unavailable.
- JDK 21 is the repository execution baseline; Java 17-compatible Android
  bytecode and the API 26 minimum remain unchanged for Android 16/API 36 and
  Android 17/API 37 support.
