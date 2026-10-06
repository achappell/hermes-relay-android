---
id: ANDROID-HOME-05
title: Play Home audio for replies slower than any client audio-start wait
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-02
  - home:HOME-NW-18
ios_reference: 'IOS-HOME-05'
github_issue: https://github.com/achappell/hermes-relay-android/issues/69
---

# ANDROID-HOME-05 — Slow replies still get their audio

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): matrix V17 (audio deadline after text; pre-speech tolerance). The audit found no matching iOS constant for ">30 s tolerance"; the requirement here is the arming rule plus the late-audio test.

Parity with `IOS-HOME-05` (`hermes-relay-ios` commit `8c3f163`) and the Home fix in `hermes-relay-home` PR #74 (`9b445bc`, "start the response-audio timeout only once speech is requested"), deployed on CaticornQueen as `0effbf9`.

## Background

On the pilot iPhone a reply that took about 23 s to generate showed "Audio playback failed. The response text is still available." Home relayed the full audio afterward. iOS started a 5 s audio-start deadline at turn acceptance, but Standard only synthesizes audio once text completes, so any reply slower than 5 s was marked failed. The Home side had the mirror bug: `BridgeEndpoint._audio_loop` gave `next_audio()` a full 30 s counted from acceptance, so a reply that thought for more than 30 s got `unavailable / transport_timeout` before speech was ever requested (2026-10-05 13:45–13:49Z, two turns). Home PR #74 now starts the 30 s audio clock only after speech is requested (first text appended, `done`, or `stop`) and polls in 1 s slices with no overall limit before that.

## Android today (verified against `main` at `3e10ae2`)

**The iOS failure mode (a client-side audio-start deadline armed at acceptance) cannot occur: Android has no client-side audio-start or control-terminal deadline.** `OkHttpRelaySessionClient` uses `readTimeout(0)`; the only timers are the 10 s hello/request deadlines for RPC responses (`DEFAULT_HELLO_TIMEOUT_MILLIS`, `DEFAULT_REQUEST_TIMEOUT_MILLIS`), and `AudioTrackAudioSink`'s write-stall (3 s), drain-stall (500 ms) and drain-timeout (30 s from the start of drain). None runs between turn acceptance and first audio. The `audio` frame handler (`handleEvent`) accepts `AudioStarted` only before the control terminal; see the ordering defect under Android design notes.

What is therefore **not** covered, and is the work of this ticket:

1. No regression test proves a reply whose first audio arrives >30 s (and >60 s) after acceptance is played and the turn completes without `AudioFailed`.
2. Nothing prevents the future deadline work (`ANDROID-HOME-10`, idle-based control deadline) from arming an audio-start deadline at acceptance; this ticket records the rule that gates it.
3. The Android tolerance of Home's `audio` frame ordering is untested for the post-#74 shape: control `turn_end` (text complete) may precede `audio start` by several seconds, and audio may start after the text turn completed.

## Required behavior

- Any Android audio-start wait introduced now or later is armed **only when the text turn completes successfully** (`TurnCompleted`) and only if audio has neither started nor ended. It never runs from `prompt.submit` acceptance.
- With no deadline armed, a turn with text complete and no audio yet stays in an honest "waiting for audio" state (not `Complete`, not `Speaking`) until audio starts, Home sends `audio unavailable`, or the control terminal/idle rules of `ANDROID-HOME-10` expire.
- If Home sends `{kind: unavailable}` (Home's own post-speech 30 s timeout, `transport_timeout`) the reply text remains and the audio failure is shown as today (`AudioFailed`); no replay.
- Android tolerates ≥ 60 s from accept to first PCM against a Home with PR #74, and still shows playback failure only on a real Home `unavailable`.

## Acceptance criteria

- Fake Home: `prompt.submit` accepted → no events for 40 s of fake time → text deltas and `turn_end` → `audio start` + PCM + `audio end`. Turn reaches `Speaking` then `Complete`; no `AudioFailed`; audio sink received all PCM; exactly one submission. (Mirrors iOS `testHomeSlowTextReplyStillPlaysAudioThatStartsAfterTheTurnCompletes`.)
- Fake Home: text completes, then 200 ms of fake time (configured post-completion deadline in the test double) with no `audio start` → if a post-text deadline exists it fails as "unavailable"; if none exists the turn stays waiting. Record which in the validation file; do not add a deadline solely to satisfy this test.
- Fake Home: `audio unavailable transport_timeout` after a long pre-speech wait produces `AudioFailed` while text stays available.
- Guard test: any `HomeTurnDeadlines` type added by `ANDROID-HOME-10` has no audio-start timer started by `AudioStarted`-less acceptance (assert via the injected clock).

## Android design notes

- The iOS fix lived in `ConversationStore.finishHomeControlTurn`; Android's equivalent seam is `OkHttpRelaySessionClient.handleEvent`. **Probable real defect, found by reading the code, not yet reproduced:** on `TurnCompleted` with no audio active, the client sets `terminalObserved` and calls `clearTurn`, which nulls `activeTurn`. A later `audio start` is then dropped twice: the `AudioStarted` branch returns early when `terminalObserved` is set (line `if (terminalObserved.get() || interruptRequested.get()) return`), and the inbound path ignores frames tagged with a binding that is no longer active. The 2026-10-04 iPhone evidence shows Home sending the control terminal at 17:41:31.86Z and `audio start` at 17:41:31.93Z, so the slow-reply shape has terminal-before-audio-start. [INFERENCE] On Android that would silently play no audio and show a completed text turn. Write the failing test first; if it fails, the fix is to keep the turn open for audio (state "waiting for audio") after the text terminal until audio starts, ends, or Home reports `unavailable`.
- No Android audio-start deadline should be copied from iOS (`audioStart = 5 s`); only the arming rule matters.

## Dependencies

Home `0effbf9` (PR #74) for the end-to-end behavior beyond 30 s; JVM tests need no Home. Informs `ANDROID-HOME-10`.

## Test notes

JVM: `OkHttpRelaySessionClientTest` with the existing fake WebSocket + a recording `AndroidAudioSink`. Run first against `main` and record the result (if the late-audio-after-terminal test fails, that is the fix to make).

## Device verification and expected journal lines

Against Home `0effbf9`: submit a prompt that takes >35 s to generate (agent thinking time); expect text, then audio, no "Audio playback failed". Journal (`ANDROID-DIAG-01`): `home turn accepted`, `home turn terminal completed`, `home audio started after_text_ms=N`, `home audio completed`. Home diagnostics: no `audio unavailable` before speech.

## References

iOS: `spec-ios-home-05-audio-start-after-turn.md`, `validation-ios-home-05.md`; Home: `spec-pilot-audio-wait-before-speech.md`, PR #74.
