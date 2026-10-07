---
story: ANDROID-VOICE-03
spec: spec-android-voice-03-endpointing.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-06
---

# ANDROID-VOICE-03 validation record

Gates are kept separate. Endpoint latency was measured on a physical Pixel with the real recogniser and a host-spoken neutral phrase; the turn was sent to a recording fake port, **not** to a Home (the device was not paired).

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | 322 unit tests, 0 failures/errors/skipped (this ticket adds 14: `AndroidEndpointingTest`) |
| Repetition gate | Passed | `scripts/run-flake-gate.sh AndroidEndpointingTest,AndroidCaptureControllerTest,AndroidHandsFreeTest 30`: 30 consecutive runs, 0 failures |
| Real recogniser, silence-only send, one request | Passed | `LiveEndpointingTest` on the Pixel 6a (Android 17 / API 37), on-device recogniser: 1 request, sent with no screen interaction |
| Real recogniser, 1 s mid-sentence pause | Passed | same test; the whole 4-word phrase was sent once; the pause did not cut it off |
| Through Home | **Not run** | Device not paired |
| CI | Pending the pull request | `ci.yml` |

## Measured on the device (content-free)

Time from the last transcript change (partial) to the controller reporting `Submitted`:

| Run | Last partial to submit |
| --- | --- |
| Phrase, then silence | 1283 ms |
| Phrase with a 1 s pause inside | 841 ms (phrase complete, one request) |

Both are **under** the 1500 ms local endpoint: the on-device recogniser honoured the silence-length extras and delivered its own final before the local timer fired. The local timer is therefore a backstop for recognisers that ignore the extras, and was not exercised on this device. Partials are not emitted continuously, so these figures are "last partial update to submit", not "last sound to submit"; the true silence length the recogniser used is not observable from the API.

## Behaviour (`VoiceTimings`: 1.5 s silence endpoint, 2 s final wait, 0.5 s minimum utterance)

- Silence is measured from the last *change* in the partial transcript; a repeated identical partial does not postpone it; nothing is measured before anything was heard (the recogniser's own timeout owns an empty window).
- Silence and explicit Stop both go through one `endCapture()`: only the first stops the recogniser, and the recogniser is stopped once; late `Final`/`Partial` after a capture ended are ignored, so racing Stop and silence send one turn.
- After the endpoint the controller waits 2 s for the final. A late final within 2 s wins over the last partial.

### Decision recorded (owner may overrule)

If no final arrives within 2 s, the controller shows the **no-speech state** and sends nothing. The spec allows sending the last partial "only if the existing no-partial-send rule allows it"; that rule (a turn uses only the recogniser's final transcript, enforced by `a_partial_transcript_is_never_submitted`) does not. iOS differs: `endCaptureAndSend` uses `finalText ?? provisionalText`. Aligning with iOS would be a one-line change in `finalResultOverdue` plus relaxing that rule, and needs an owner decision.

## What changed

- `VoiceTimings.kt`: `VoiceTimings`, `VoiceTimers` seam, `MainLooperVoiceTimers`. `ManualVoiceTimers` is the test double.
- `AndroidCaptureController`: takes `timers` (no default, per `ANDROID-TEST-01`) and `timings`; endpoint, final wait, one-shot end, late-event guard.
- `PlatformSpeechInput`: passes `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`, `..._POSSIBLY_COMPLETE_...` and `..._MINIMUM_LENGTH_MILLIS` from the same `VoiceTimings`.
- `HomeRuntime` passes `MainLooperVoiceTimers` (one line).
- `FakeSpeechInput.stopCount`.
- `LiveEndpointingTest` (instrumented, skipped unless `-e liveSpeech true`).
