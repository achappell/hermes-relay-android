---
title: '2-A-1 — Capture acknowledgement and live Transcription participant state'
type: 'story'
story_id: '2-A-1'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: '24e3115'
context:
  - '_bmad-output/implementation-artifacts/spec-a-9-microphone-capture.md'
  - '_bmad-output/implementation-artifacts/spec-2-a-2-disconnected-state.md'
  - '../hermes-relay-ios/_bmad-output/implementation-artifacts/spec-2-1-ios-capture-acknowledgement-and-live-transcription.md'
---

## Intent

**Problem:** `A-9` produces live partial transcripts, but the doorway showed
only a `Transcribing…` label. The participant could not see their own words
forming, and capture was not correlated to the Session it began in — so an
utterance spoken across a recovery could be attached to a Session the user was
never having.

**Approach:** Render the provisional transcript as the participant's own text,
and bind capture to its originating Session. iOS ran its equivalent story as
verify-only because its HUD already showed `provisionalText`; Android needed
both the presentation and the identity rule.

## Boundaries & Constraints

**Always:** Acknowledge capture the moment the recognizer confirms listening;
update the provisional transcript live as partials arrive; label it as the
participant's own provisional text; clear it on cancel; refuse to submit an
utterance whose originating Session has been replaced.

**Never:** Show a provisional transcript as a finished turn; leave a cancelled
utterance on screen; submit a partial; attach an utterance to a Session that
replaced the one which heard it; invent a second capture affordance when the
capture state already acknowledges it.

## Acceptance Criteria

- Given the recognizer confirms listening, then capture is acknowledged before
  any words exist.
- Given partial transcripts arrive, then the participant's provisional text
  updates live, labelled as theirs and as in-progress.
- Given no words yet, then no provisional text is shown.
- Given capture is cancelled, then the provisional text disappears and no turn
  is submitted.
- Given the Session that heard the utterance is replaced before the transcript
  is final, then nothing is submitted and the reason says the Session changed.
- Given the Session is unchanged, then the final transcript submits normally.

## Code Map

- `AndroidCaptureController.kt` — records the originating Session at capture
  start, refuses submission when it has been replaced, and clears provisional
  text on cancel.
- `MainActivity.kt` — renders the participant label and live provisional
  transcript, and supplies the live Session identity.
- `strings.xml` — participant label and the replaced-Session guidance.

## Implementation Notes

- **The capture state is the acknowledgement.** Following iOS's decision, no
  second affordance was added; `Listening…` appearing the moment the recognizer
  confirms is the acknowledgement.
- **`SessionReplaced` is its own block reason.** Collapsing it into
  `NotConnected` would be wrong: the Client *is* connected, just not to the
  Session that heard the words, and the remedy is to speak again rather than to
  reconnect.
- **The identity rule is deliberately narrow.** It compares the Session id
  captured at `beginCapture` with the live one at submission, and only when
  both are known — so tests and builds without a recovery controller are
  unaffected.

## Verification

See `validation-2-a-1-live-transcription.md`.

## Closure

Epic 2 is complete on Android. The participant sees their own words forming,
a cancelled utterance leaves nothing behind, and an utterance cannot be
attached to a conversation the user was not having.
