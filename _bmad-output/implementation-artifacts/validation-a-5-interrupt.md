---
title: 'Validation — A-5 interrupt an active Android turn'
type: 'validation'
story_id: 'A-5'
created: '2026-09-12'
status: 'done'
---

## Scope

Validate capability-gated interrupt submission, immediate local playback stop,
the distinct `Interrupted` terminal state, retention of the partial response,
and rejection of late events afterwards.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21. 92 unit tests, 0 failures (4 new); debug APK
  assembled; lint clean.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed on
  `hermes-relay-api36` (Android 16 / API 36). 21 instrumentation tests, 0
  failures, 0 skipped.
- Live gate — 3 tests, 0 failures, against
  `wss://voice-amanda.chappell-home.dev/voice-session`, including
  **a real interrupt of a real Hermes turn**: the test asks for a long count,
  waits until Hermes is actually answering, interrupts, and asserts the turn
  settles as `Interrupted` with the partial response retained.
- `scripts/check-apk-metadata.sh`, `git diff --check` — passed.

## Evidence notes

- **The live test found a modeling error that reasoning had missed.** The first
  live run settled as `Unavailable` rather than `Interrupted`. The relay aborts
  its streaming TTS when honouring an interrupt, so `audio_abort` arrives,
  normalizes to `AudioFailed`, and sets the terminal `Unavailable` phase before
  `turn_interrupted` is received. The abort is a consequence of the user's own
  action, so it is now suppressed while an interrupt is pending, and the
  confirmation settles the turn.
- Unit coverage pins both capability paths: a relay advertising `interrupt`
  receives a correctly addressed frame, and a relay advertising only
  `text_stream` receives nothing and reports no support — so the gate cannot
  pass by always refusing or always sending.
- Reducer coverage pins the interrupted turn as terminal with its partial
  response retained, and proves a late delta cannot revive it.
- The normalizer test that previously expected `TurnFailed` for
  `turn_interrupted` was corrected rather than deleted; it now asserts the two
  are distinct.

## Deferred

- Barge-in — interrupting by speaking rather than by pressing a control — is
  `A-6`, still pending the `FR5` amendment.
- The emulator has no audio device, so "playback stopped immediately" is
  verified by the sink being cancelled, not by silence being heard.
