---
title: 'Validation — A-9 microphone capture and on-device transcription'
type: 'validation'
story_id: 'A-9'
created: '2026-09-12'
status: 'done-with-environment-limitation'
---

## Scope

Validate the pre-capture gate, the capture state machine, final-transcript
submission, permission and recognizer failure handling, and that typed turns
survive a device with no recognizer. Live participant transcription display is
`2-A-1` and out of scope.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21. 84 unit tests, 0 failures (12 new); debug APK
  assembled; lint clean.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed on
  `hermes-relay-api36` (Android 16 / API 36). 17 instrumentation tests, 0
  failures, 0 skipped (5 new).
- Live gate — passed against
  `wss://voice-amanda.chappell-home.dev/voice-session`, and repeated **three
  consecutive times** after the audio fix below, 2 tests and 0 failures each.
- `scripts/check-apk-metadata.sh`, `git diff --check` — passed.

## Evidence notes

- **The local-STT decision was settled by the relay, not by preference.** Its
  adapter rejects binary frames with "binary ingress is not part of protocol
  v1; send local-STT text", and the `turn` frame carries `stt_source`. Audio
  therefore never leaves the device.
- **Every refusal path asserts the microphone was never opened.** The unit
  tests check `startCount == 0` for a missing permission, an absent recognizer,
  no live Session, and an unverified Profile, so a refusal can never be
  discovered by recording first.
- `PlatformSpeechInput` was exercised against the real platform on device: with
  `RECORD_AUDIO` ungranted it reports `NotDetermined` rather than
  `Unavailable`, which also proves the manifest `<queries>` element lets the
  installed recognizer be seen on Android 11+.
- The Compose tests cover the permission prompt appearing instead of capture
  starting, refusal before a Session exists, a full listen/stop/final-transcript
  submission, and typed turns still working with no recognizer.

## A-8 defect found while verifying this story

The first full run after wiring capture showed the live typed turn timing out
at 120 seconds, having never reached a terminal state. A rerun on a fresh
emulator passed, which made it look like a flake. It was not.

`AudioTrack.write` blocks by default. On a device that stops consuming audio —
which a `-no-audio` emulator does — the worker thread blocks indefinitely
inside `write`, so the queued `finish()` never runs, `AudioEnded` is never
emitted, and the turn stays in `Speaking` forever. The A-8 drain guard could
not help, because the stall happens before draining begins.

Writes are now non-blocking with a bounded stall budget: a device that cannot
keep up reports an audio failure, which the A-2 reducer turns into
`Unavailable` with the response text retained. Three consecutive live turns
passed afterwards.

This is worth recording as a pattern: the A-8 live gate passing once was not
evidence that it passed reliably.

## Environment limitation

The emulator runs `-no-audio` and has no microphone input, so **no real speech
was ever transcribed**. Capture is proven against the platform seam with a
deterministic fake, and `PlatformSpeechInput`'s authorization and recognizer
detection are proven against the real platform. Actual recognition accuracy,
partial-result timing, offline model availability, endpointing behavior, and
the on-device permission dialog remain unverified until a physical Android
device runs the same paths.

## Deferred

- Live partial-transcript display is `2-A-1`.
- Hands-free continuation and barge-in are `A-6`, still pending the `FR5`
  amendment.
- The relay advertises an `interrupt` capability that nothing uses yet; that is
  `A-5`.
