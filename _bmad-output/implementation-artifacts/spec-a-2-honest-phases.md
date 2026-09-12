---
title: 'A-2 — Honest phases with response text/audio delivery'
type: 'story'
story_id: 'A-2'
created: '2026-09-11'
status: 'done'
route: 'dispatch'
baseline_commit: 'f81dcec'
context:
  - '../hermes-relay-tui/_bmad-output/implementation-artifacts/surface-coverage-matrix.md'
  - '../hermes-relay-tui/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '../hermes-relay-tui/_bmad-output/planning-artifacts/epics.md'
  - '../hermes-relay-ios/_bmad-output/implementation-artifacts/spec-1-2-render-honest-ios-turn-phases.md'
---

## Intent

**Problem:** A-1 accepts an authorized Android turn but leaves the UI waiting
for a lifecycle boundary. Without an Android-owned projection, a future
adapter could let late status events regress the visible phase, duplicate
streamed response text, claim audio is speaking before delivery, or discard a
completed answer when audio is unavailable.

**Approach:** Add a pure Android reducer for normalized session events and a
typed observation seam on `AndroidClientPort`. The reducer binds every event
to the accepted Profile/Session/turn identity, projects the observed phase and
one coherent response, and keeps audio lifecycle evidence separate from text.
Compose renders that state without parsing Hermes frames or retaining PCM.

## Boundaries & Constraints

**Always:** Consume normalized events for the accepted `AndroidTurnBinding`;
advance through observed `Listening`, `Transcribing`, `Thinking`,
`Buffering`, and `Speaking` phases; preserve one coherent streamed response;
wait for in-flight audio delivery before `Complete`; preserve response text
when audio is unavailable; and expose a visible disconnected/unavailable
state without replay.

**Never:** Parse Hermes wire frames in Compose; invent response text, audio,
credentials, or protocol operations; let stale Session/turn events mutate the
current state; store PCM/audio chunks in UI state or Local History; or claim
`Speaking` after audio delivery has failed.

## Acceptance Criteria

- Given an accepted A-1 turn and normalized lifecycle events, when capture,
  transcription, processing, response, audio, and completion events arrive,
  then Android exposes only the corresponding observed canonical phase and
  does not regress from output phases on late processing hints.
- Given streamed response text and normalized audio lifecycle events, when
  deltas and chunks arrive, then Android renders one coherent response for the
  bound turn and represents audio delivery from that same response without
  storing audio bytes.
- Given response text completes but audio never starts or reports failure,
  when the turn reaches its terminal event, then the completed text remains
  visible, audio is reported unavailable, and the UI never claims `Speaking`.
- Given audio delivery is in flight when turn completion is observed, then
  Android remains in the output phase until audio ends and only then reaches
  `Complete`.
- Given an unknown, stale, differently identified, or late event, when it is
  received, then the active phase, response, and terminal state remain
  unchanged.
- Given the active Session disconnects, then Android exposes `Disconnected`,
  marks audio unavailable, and does not replay or silently resume the turn.

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/AndroidClientPort.kt` — typed
  normalized-event observation seam and cancellable subscription.
- `app/src/main/java/com/achappell/hermesrelay/AndroidTurnState.kt` — Android
  phase/audio state, normalized event types, identity gate, and pure reducer.
- `app/src/main/java/com/achappell/hermesrelay/MainActivity.kt` — observes
  the accepted turn and renders phase, response, unavailable, and disconnected
  presentation.
- `app/src/main/res/values/strings.xml` — resource-backed lifecycle copy.
- `app/src/test/java/com/achappell/hermesrelay/AndroidTurnStateTest.kt` —
  deterministic phase, response, audio, stale-event, and disconnect tests.
- `app/src/androidTest/java/com/achappell/hermesrelay/MainActivityTest.kt` —
  authorized Compose projection and audio-failure presentation tests.

## Implementation Notes

- The event types are adapter-facing normalized events. The Android surface
  does not know how Hermes frames are decoded.
- Audio chunks are lifecycle evidence only. The actual audio adapter owns PCM
  delivery; this repository does not invent a transport or fake playback.
- `Complete` is reached only after a completion event and delivered audio.
  Text-only completion becomes `Unavailable` with the text retained.
- The bootstrap adapter remains unavailable by default. This slice verifies
  the Android boundary with deterministic fakes and leaves live endpoint,
  credential, microphone, and speaker work to their own stories.

## Closure

A-2's Android normalized-event projection and Compose presentation slice is
implemented and independently verifiable. The future Hermes/audio adapter is
still intentionally absent; no protocol or audio behavior was fabricated to
make the bootstrap shell appear connected.
