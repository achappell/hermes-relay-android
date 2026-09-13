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

## Real-device finding — 2026-09-12

With hands-free armed, the microphone reopened after a turn settled while the
phase line still read `Complete`. Both labels were on screen at once: the
capture line said `Listening...` and the phase line said `Complete`.

Strictly the previous turn *had* completed, so the phase was not lying about
the turn. But the most prominent state line read as terminal while the
microphone was live, and Amanda noticed capture was active in spite of the UI
rather than because of it. For a story whose subject is honest phases, an
inaccuracy pointing that direction is the wrong one.

`beginCapture()` never touched turn state, so the settled phase persisted until
the next turn's first relay event arrived -- the entire hands-free listening
window.

**Change.** When hands-free reopens capture, the phase becomes `Listening`.
Only a terminal turn (`Complete`, `Unavailable`, `Disconnected`, `Interrupted`)
is replaced; an active turn is never overwritten, and manual mode is unchanged.
Verified on a Pixel 6a.

**Consequence to watch.** A turn now begins at the microphone rather than at the
first relay event, so there is a window where the phase is `Listening` with no
`binding` -- no session or turn identity behind it. Nothing reads `binding`
during that window today and `A-3`'s ladder keys off connection state, but
recovery or interruption edge cases while hands-free is armed should be checked
here first.
