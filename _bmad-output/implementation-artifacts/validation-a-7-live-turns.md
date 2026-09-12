---
title: 'Validation — A-7 typed turns and normalized event delivery'
type: 'validation'
story_id: 'A-7'
created: '2026-09-12'
status: 'done'
---

## Scope

Validate typed turn submission on a live Session, frame-to-normalized-event
translation for every frame type the relay emits, streamed-text handling that
renders one response, audio lifecycle projection without retaining bytes,
duplicate/interrupt/error/completion handling, identity rejection of superseded
frames, and a real conversation with Hermes. Microphone capture, transcription,
audio playback, and Local History are out of scope and remain unimplemented.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21. 66 unit tests, 0 failures (19 new: 17 in
  `HermesEventNormalizerTest`, 2 in `OkHttpRelaySessionClientTest`); debug APK
  assembled; lint clean.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed on
  `hermes-relay-api36` (Android 16 / API 36). 11 instrumentation tests, 0
  failures, 0 skipped.
- Live gate — passed, 2 tests, 0 failures. `LiveRelayHandshakeTest` connected to
  the household relay and additionally **submitted a typed turn and received a
  real response**, projected through the A-2 reducer to a terminal state with
  non-blank response text. The token was supplied as an instrumentation
  argument and never written to source or logs.
- `scripts/check-apk-metadata.sh`, `git diff --check` — passed.

## Evidence notes

- **The frame vocabulary was read from the relay, not guessed.** The adapter
  emits `turn_accepted`, `turn_duplicate`, `status`, `text_delta`,
  `text_final`, `audio_start`, `audio_end`, `audio_file_start`,
  `audio_file_end`, `audio_abort`, `turn_interrupted`, `turn_end`,
  `speech_timing`, `error`, and several session/steer/prompt frames. Two of
  these — `turn_accepted` and `turn_duplicate` — have no iOS handling at all.
- **The end-to-end unit test drives the real reducer.** A `MockWebServer`
  WebSocket performs the handshake, receives the `turn` frame, then streams
  status, deltas, audio lifecycle, `text_final`, and `turn_end`. The collected
  events are folded through `AndroidTurnStateReducer`, ending at `Complete`
  with one coherent response and `Delivered` audio.
- **Gating turns on a live connection broke four existing Compose tests**, all
  of which used fakes that never connected. That is the correct production
  behavior — a turn cannot be submitted with no socket — so the tests now
  establish a session first, as the real app does.
- **A self-inflicted bug cost the most time and is worth recording.** While
  patching a test fake, an over-escaped Kotlin interpolation produced the
  literal string `session-$connections` instead of `session-1`. Every event was
  then correctly rejected by the A-2 identity gate, and the symptom — a turn
  visibly accepted but no phase ever rendering — looked exactly like a
  wiring failure in production code. Two intermediate debugging runs were also
  read against stale build artifacts after the same patch broke compilation in
  a second test, which briefly made the evidence misleading. The identity gate
  behaved correctly throughout.
- Normalizer coverage spans delta accumulation, an exact `text_final` repeat
  collapsing to nothing, suffix continuation, rewrite-as-replace, final text
  with no prior stream, per-turn state reset, the three status phases and an
  ignored status, all five audio lifecycle cases, audio abort, duplicate turn,
  accepted turn emitting nothing, interrupt and error, `turn_end`, five
  unowned frame types reported as `Unknown`, malformed JSON, payload-wrapped
  frames, and a stale-identity frame that the reducer rejects.

## Deferred

- Tap-to-speak is refused: microphone capture and on-device transcription are
  not implemented and were not faked.
- Response audio is lifecycle evidence only; no playback exists, so a turn
  whose audio never starts ends `Unavailable` with its text retained, which is
  the A-2 contract.
- `speech_timing`, steering, prompt, and session-management frames are reported
  as `Unknown` rather than handled.
