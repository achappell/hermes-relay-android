---
id: ANDROID-VOICE-03
title: Endpointing parity and auto-send on tap-to-talk
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S3
created: 2026-10-06
depends_on:
  - android:ANDROID-VOICE-01
parity_source: 'PX-14 (matrix V1)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/87
---

# ANDROID-VOICE-03 — Endpointing parity and auto-send on tap-to-talk

Source: PX-14 (matrix V1) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Background

On iOS a tap-to-talk capture ends on a 1.5 s silence endpoint with a 2 s final-result wait and auto-sends the turn; a cancel button abandons it. Android starts capture, then the person must press "Stop and send" (`ActiveCaptureZone`).

## Android today (checked against `main` unless marked [INFERENCE])

- `PlatformSpeechInput` uses `LANGUAGE_MODEL_FREE_FORM` with partial results and no silence-length extras; the endpoint is the recognizer default (Appendix A of the audit: none set).
- There is no final-transcript wait deadline; the controller waits for `onResults`.
- Hands-free re-opens the recognizer after each completed turn (`AndroidCaptureController.armHandsFree`).

## Required behavior

- Set `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` and `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS` (and the minimum length extra) so a pause of about 1.5 s ends capture; note that several recognizers ignore these extras, so also enforce the endpoint locally from partial-result silence.
- Tap-to-talk auto-sends the final transcript when capture ends by silence; the explicit Stop/Cancel actions remain and Stop still sends.
- Add a 2 s final-result wait after the endpoint: if no final arrives, send the last partial only if the existing no-partial-send rule allows it, otherwise show the no-speech state.
- Never send an empty or whitespace transcript; hands-free exit phrases keep working.

## Acceptance criteria

- Fake recognizer: silence for 1.5 s after a partial ends capture and submits once; a pause shorter than 1.5 s does not; a late final within 2 s wins over the last partial; after 2 s with no final the documented fallback runs.
- Explicit Stop/Cancel unit tests still pass unchanged.
- No double submit when silence-end and Stop race (assert single `beginTurn`).

## Android design notes

- Keep numbers in one `VoiceTimings` value shared with the hands-free path.
- Measure real endpoint latency on a Pixel and record the values the recognizer actually honors.

## Dependencies

`ANDROID-VOICE-01` (a working recognizer). Related: `ANDROID-VOICE-04`.

## Test notes

JVM with a manual clock and a scripted recognizer callback sequence.

## Device verification

Pixel: tap, speak a sentence, stop talking; the turn sends about 1.5 s later without touching the screen; speak with a 1 s mid-sentence pause; it does not cut off.
