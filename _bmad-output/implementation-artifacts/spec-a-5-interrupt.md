---
title: 'A-5 — Interrupt an active Android turn'
type: 'story'
story_id: 'A-5'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: '091ae50'
context:
  - '_bmad-output/implementation-artifacts/spec-a-7-live-turns.md'
  - '_bmad-output/implementation-artifacts/spec-a-8-response-audio-playback.md'
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
---

## Intent

**Problem:** The relay advertises an `interrupt` capability and accepts an
`interrupt` frame, but Android had no way to send one. A long answer had to be
waited out, and `A-8` made that literal: the response speaks to the end.

**Approach:** Send the `interrupt` frame, stop playback locally at once, and
model an interrupted turn as its own terminal state rather than as a failure.

## Boundaries & Constraints

**Always:** Offer the affordance only when the relay advertised `interrupt`;
stop playback immediately on request rather than waiting for confirmation;
retain whatever the response had already said; treat the relay's confirmation
as the turn's terminal state.

**Never:** Describe a user's own interrupt as a failure or as "unavailable";
present an interrupt control that might silently do nothing; discard the
partial response; let a late event revive an interrupted turn.

## Acceptance Criteria

- Given the relay advertised `interrupt` and a turn is in flight, then the
  affordance is offered and sends an `interrupt` frame naming that turn and
  Session.
- Given the relay did not advertise `interrupt`, then no frame is sent and the
  affordance is not offered.
- Given an interrupt is requested, then playback stops immediately.
- Given the relay confirms with `turn_interrupted`, then the turn is terminal
  in an `Interrupted` state with the partial response retained.
- Given a late event arrives for an interrupted turn, then nothing changes.

## Code Map

- `AndroidTurnState.kt` — `AndroidTurnPhase.Interrupted` and the
  `TurnInterrupted` event, distinct from `TurnFailed`.
- `HermesEventNormalizer.kt` — `turn_interrupted` maps to the interrupt event.
- `OkHttpRelaySessionClient.kt` — capability capture from `hello_ack`,
  `supportsInterrupt`, `interruptTurn`, and suppression of the self-inflicted
  audio abort.
- `MainActivity.kt` — the capability-gated affordance and interrupted notice.

## Implementation Notes

- **An interrupt is not a failure.** iOS models `.interrupted` separately, and
  telling someone their turn is "unavailable" when they stopped it themselves
  misdescribes their own action. `Interrupted` is its own terminal phase.
- **The affordance is gated on the advertised capability**, read from the
  `hello_ack` capabilities array, rather than assumed — a control that might
  silently do nothing is worse than no control.
- **The self-inflicted audio abort is suppressed.** This was found by the live
  test, not by reasoning: the relay aborts its TTS stream when honouring an
  interrupt, which arrives as `audio_abort` → `AudioFailed` → `Unavailable`,
  settling the turn terminally *before* `turn_interrupted` arrives. While an
  interrupt is pending, that abort is not reported, because it is the
  consequence of the user's own action rather than an audio failure.

## Verification

See `validation-a-5-interrupt.md`, including the live interrupt of a real turn.

## Closure

A running answer can be stopped, it stops speaking at once, and what it had
already said is kept. The turn reports what actually happened: the user stopped
it.
