---
title: 'A-9 — Microphone capture and on-device transcription'
type: 'story'
story_id: 'A-9'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: '7d37238'
context:
  - '_bmad-output/implementation-artifacts/spec-a-1-authorized-initiation.md'
  - '_bmad-output/implementation-artifacts/spec-a-7-live-turns.md'
  - '_bmad-output/implementation-artifacts/spec-a-8-response-audio-playback.md'
---

## Intent

**Problem:** Tap-to-speak has been refused since `A-1` named it. `A-7` made the
refusal explicit rather than faked, but it left the Android Client a chat box
with a microphone button that did nothing. No capture, no transcription, and no
permission story existed.

**Approach:** Capture speech on device, transcribe it there, and submit only
the final transcript as a turn. A speech seam mirrors the iOS `SpeechInput`
protocol so the capture state machine is testable without a microphone, and a
capture controller enforces the pre-capture gate.

## Why transcription is local

The relay settles this, not preference. Binary ingress is refused outright:

> `binary ingress is not part of protocol v1; send local-STT text`

Its `turn` frame carries `stt_source`, and its own documentation describes
inbound text as a local-STT transcript. Audio therefore never leaves the
device, which is also the better privacy position.

## Boundaries & Constraints

**Always:** Refuse capture before opening the microphone when permission,
recognizer, Profile, or Session is missing; report `Started` only when the
recognizer confirms it is listening; submit only a final transcript; keep typed
turns working when no recognizer exists; distinguish a denied microphone from
an absent recognizer.

**Never:** Upload, retain, or log audio; submit a partial transcript; open the
microphone to discover it is not authorized; submit a transcript into a Session
that ended while the recognizer was finishing; fake a transcript.

## Acceptance Criteria

- Given microphone permission, a recognizer, a verified Profile, and a live
  Session, when capture finishes, then the recognizer's final transcript is
  submitted as one turn.
- Given a partial transcript, then nothing is submitted.
- Given no permission, no recognizer, no Session, or no verified Profile, then
  capture is refused before the microphone opens, with a reason naming the
  actual obstacle.
- Given the Session ends while the recognizer is finalizing, then the
  transcript is reported and not submitted.
- Given an empty or whitespace-only final transcript, then no turn is sent.
- Given a recognizer error, then it is reported and no turn is sent.
- Given capture is cancelled, then nothing is submitted and the doorway returns
  to idle.
- Given capture is already running, then a second start does not open a second
  recognizer.
- Given no recognizer exists, then typed turns still work.

## Code Map

- `AndroidSpeechInput.kt` — authorization, failure, and event vocabulary; the
  capture seam; a deterministic fake.
- `PlatformSpeechInput.kt` — `SpeechRecognizer` implementation, offline
  preferred, with platform error codes mapped to the failure vocabulary.
- `AndroidCaptureController.kt` — the pre-capture gate, capture state machine,
  and final-transcript submission.
- `MainActivity.kt` — capture controls and the runtime permission flow.
- `AndroidManifest.xml` — `RECORD_AUDIO` plus the `<queries>` element that
  makes an installed recognizer visible on Android 11+.

## Implementation Notes

- **`Unavailable` and `Denied` are kept apart.** A refused microphone and a
  device with no recognizer need different guidance, and collapsing them would
  produce advice that cannot be acted on.
- **The gate runs before `start`.** Tests assert `startCount == 0` on every
  refusal path, so a failure can never be discovered by opening the microphone
  first.
- **`EXTRA_PREFER_OFFLINE` is set**, matching the local-STT contract; a
  recognizer that can only work online reports `NetworkUnavailable` rather than
  silently sending audio elsewhere.
- **A-8 defect found while verifying this story.** `AudioTrack.write` blocks by
  default, so a device that stops consuming audio strands the worker thread and
  `finish()` never runs, leaving a turn in `Speaking` forever. Writes are now
  non-blocking with a bounded stall budget that reports failure instead. See
  the validation record.

## Verification

See `validation-a-9-microphone-capture.md`, including what a `-no-audio`
emulator cannot prove.

## Closure

Tap-to-speak works: speech is captured and transcribed on device, and only the
final text reaches Hermes. Audio never leaves the phone. Live participant
transcription display remains `2-A-1`.
