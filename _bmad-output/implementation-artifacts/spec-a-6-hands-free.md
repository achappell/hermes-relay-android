---
title: 'A-6 — Hands-free continuation and echo-safe capture'
type: 'story'
story_id: 'A-6'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: '13054c5'
context:
  - '_bmad-output/implementation-artifacts/spec-a-9-microphone-capture.md'
  - '_bmad-output/implementation-artifacts/spec-a-8-response-audio-playback.md'
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
  - '../hermes-relay-tui/_bmad-output/implementation-artifacts/spec-continuous-wake-free-follow-ups.md'
---

## Intent

**Problem:** `A-9` gave Android one spoken turn per tap. Holding a conversation
meant tapping before every sentence, which is not a conversation. `A-6` was
blocked on `FR5`, which described a single bounded follow-up window and did not
match the delivered contract on any surface.

**Approach:** Implement the amended `FR5`: after a completed response, reopen a
bounded capture window without re-triggering, and keep doing so until an
explicit exit.

## What the FR5 amendment settled

The working assumption had been that "bounded no longer applies". Reading the
TUI's continuous-follow-up spec showed otherwise, and the amendment says so:
**each window is still bounded by silence; what continues is the reopening.**

That distinction shaped this story directly. Android implements a repeating
bounded window, not an unbounded open microphone — a mistake this slice would
otherwise have inherited.

## Echo safety comes free

The TUI contract requires follow-up only after a *completed* response. `A-8`
already made `Complete` mean the response audio has finished draining.

Reopening only on `Complete` therefore guarantees capture never starts while
the device is still speaking. Echo safety is structural here rather than a
timing heuristic, which is the strongest form it can take.

## Boundaries & Constraints

**Always:** Reopen only after a completed turn; bound each window by the
recogniser's silence endpointing; close silently on exactly "stop"; end the
conversation on silence, failure, transport loss, or disarm; record why it
ended.

**Never:** Reopen after a turn that failed, was interrupted, or lost transport;
submit "stop" as a turn; reopen while audio is still playing; stay armed when
capture is refused; narrate a deliberate disarm back to the user.

## Acceptance Criteria

- Given hands-free is armed and a turn completes, then a new window opens with
  no further user action, repeatedly.
- Given the user says exactly "stop", then the conversation ends silently, and
  "stop" is never submitted as a turn.
- Given an utterance merely contains "stop", then it is submitted normally.
- Given a turn ends `Unavailable`, `Interrupted`, or `Disconnected`, then the
  window does not reopen and the reason is recorded.
- Given transport is lost, then the conversation ends rather than listening
  into nothing.
- Given silence or a recogniser failure, then the conversation ends.
- Given hands-free is armed while capture is blocked, then it fails closed and
  does not stay armed.
- Given a turn settles while hands-free was never armed, then nothing happens.

## Code Map

- `AndroidCaptureController.kt` — `armHandsFree`, `disarmHandsFree`,
  `onTurnSettled`, the exact-"stop" gate, and `AndroidHandsFreeExit`.
- `MainActivity.kt` — the toggle, the active notice, the exit explanation, and
  the turn-settled hook.

## Implementation Notes

- **Exactly "stop" is a command to the doorway, not a turn**, so it is never
  sent to Hermes. Matching is on the whole utterance: "stop the timer" is a
  normal turn.
- **A deliberate disarm is not narrated back.** The user pressed the control;
  telling them they said "stop" would be a small lie.
- **Focus restoration is suppressed while hands-free is active**, because
  pulling focus to the composer mid-conversation fights the user.
- Android has no wake phrase; the toggle is the arming action, matching how iOS
  arms hands-free. The amended `FR5`'s "without requiring another wake phrase"
  reads here as "without another tap".

## Verification

See `validation-a-6-hands-free.md`.

## Closure

Android holds a continuous spoken conversation: speak, hear the answer, and
speak again without touching the device, ending when you say so.
