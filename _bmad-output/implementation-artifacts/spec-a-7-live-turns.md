---
title: 'A-7 — Typed turns and normalized event delivery'
type: 'story'
story_id: 'A-7'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: '0448248'
context:
  - '_bmad-output/implementation-artifacts/spec-a-4-relay-configuration.md'
  - '_bmad-output/implementation-artifacts/spec-a-2-honest-phases.md'
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
---

## Intent

**Problem:** `A-4` made `snapshot()` and `reconnect()` real, but `beginTurn()`
still rejected everything and `observeTurn()` delivered nothing. The Android
Client could authenticate and hold a Session it could not use. The A-2 reducer,
built to project normalized events, had never seen one from a relay.

**Approach:** Complete `AndroidClientPort`. Submit typed turns as `turn` frames
on the live socket, and translate the relay's voice-session frames into the
normalized events the reducer already consumes. The normalizer is the only
place that sees a Hermes frame; nothing above it changes.

## Boundaries & Constraints

**Always:** Take turn and Session identity from the frame, so the A-2 identity
gate can reject a superseded turn; collapse a `text_final` that merely repeats
the stream; reset streamed-text state at the start of each turn; treat audio
frames as lifecycle evidence only; report a `turn_duplicate` as a failed turn;
report frames this slice does not own as `Unknown`.

**Never:** Retain audio bytes; submit a turn with no live Session; fake
microphone capture or transcription for tap-to-speak; render a response twice;
let a frame type this slice does not understand mutate turn state; expose a
Hermes frame above the normalizer.

## Acceptance Criteria

- Given a live Session, when a typed turn is submitted, then a `turn` frame
  carries `protocol_version`, the generated `turn_id`, the Session's
  `session_id`, and the prompt text, and the accepted binding names that turn.
- Given no live Session, when a turn is submitted, then it is rejected and no
  frame is sent.
- Given tap-to-speak, then it is refused, because no microphone capture exists.
- Given streamed `text_delta` frames followed by a `text_final` repeating them,
  then the response renders once.
- Given a `text_final` that continues the stream, then only the suffix is
  emitted; given one that rewrites it, then the response is replaced.
- Given `audio_start`, `audio_file_start`, `audio_end`, `audio_file_end`, and
  binary frames, then audio lifecycle is projected without retaining bytes.
- Given `turn_duplicate`, then the turn is reported failed rather than replayed.
- Given `turn_interrupted` or `error`, then the turn fails with the stated
  reason; given `turn_end`, then the turn completes.
- Given a frame for a superseded Session or turn, then turn state is unchanged.
- Given the socket fails or closes after a Session exists, then a
  `Disconnected` event is delivered and recovery state reflects it.

## Code Map

- `HermesEventNormalizer.kt` — frame-to-event translation and the streamed-text
  state that prevents a duplicated response.
- `OkHttpRelaySessionClient.kt` — `beginTurn()` frame submission,
  `observeTurn()` registration, streaming dispatch, and disconnect reporting.
- `AndroidRecovery.kt` — `AndroidRecoveryState` now starts `Disconnected`.
- `MainActivity.kt` — turn controls gated on a live connection; an explicit
  connect action.

## Implementation Notes

- **`text_final` is the subtle frame.** The relay streams deltas and then
  repeats the whole answer. A naive reader renders the response twice, so the
  normalizer tracks what has been rendered: an exact repeat collapses to
  nothing, a continuation becomes a delta, a rewrite becomes a replace.
- **`turn_duplicate` is surfaced, not swallowed.** The relay proving a turn was
  already processed is exactly the condition `A-3` exists to handle, and the
  Client should show the turn did not run again.
- **`AndroidRecoveryState` now starts `Disconnected`.** Defaulting to
  `Connected` claimed a Session before one existed. One `A-3` test asserted the
  old default's visible progress and was corrected: the ladder's first visible
  state is now `Reconnecting(1 of n)`, because the loss no longer changes
  anything.
- **Tap-to-speak is refused.** Microphone capture and transcription are not
  implemented and are not faked. This is a deliberate, visible gap.
- **`beginTurn` does not wait for `turn_accepted`.** It returns as soon as the
  frame is sent, so the UI thread never blocks on the relay. `turn_accepted`
  carries no presentation change; `turn_duplicate` arrives as a failure event.

## Verification

See `validation-a-7-live-turns.md`, including a live conversation with Hermes.

## Closure

Android holds a conversation. A typed prompt reaches Hermes over the tailnet
and its streamed response is projected through the A-2 reducer to a terminal
state. Microphone capture, response audio playback, and Local History remain
absent and belong to their own stories.
