---
story: ANDROID-VOICE-03
spec: spec-android-voice-03-endpointing.md
status: done
story_status: done
updated: 2026-10-08
---

# ANDROID-VOICE-03 validation record

**Done under owner acceptance (2026-10-07 CDT / 2026-10-08 UTC).** Retained deterministic and real-recognizer evidence is now complemented by the actual spoken Home turn below. Earlier measured endpoint latency used a recording fake port; those measurements are not attributed to this Home turn.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | 322 unit tests, 0 failures/errors/skipped (this ticket adds 14: `AndroidEndpointingTest`) |
| Repetition gate | Passed | `scripts/run-flake-gate.sh AndroidEndpointingTest,AndroidCaptureControllerTest,AndroidHandsFreeTest 30`: 30 consecutive runs, 0 failures |
| Real recogniser, silence-only send, one request | Passed | `LiveEndpointingTest` on the Pixel 6a (Android 17 / API 37), on-device recogniser: 1 request, sent with no screen interaction |
| Real recogniser, 1 s mid-sentence pause | Passed | same test; the whole 4-word phrase was sent once; the pause did not cut it off |
| Through Home | **Passed: owner-confirmed, journal/UI corroborated** | Actual spoken turn, roughly 1 s pause preserved, automatic send without Send tap, reply worked |
| Historical implementation CI note | Pending at original validation | Not current-head CI evidence; no CI watch for this status-only update |

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

## Actual spoken Home turn — 2026-10-08 UTC

Existing Pixel 6a / Android 17 (API 37), debug 0.3.1 (301), embedded revision `b865c0b`, APK SHA-256 `5692a179b2347214b7e31cc1010f650ec8a224fe00439b8533ecbb5437ddeb0b`. Source ancestry includes VOICE-01 merge `688bbf2` and VOICE-03 merge `64f12cb`. Microphone permission was already granted; the app was Ready with Tap to speak and Start hands-free (hands-free off). No install or assistant-triggered capture occurred.

Amanda reported “voice turn worked,” then explicitly answered yes to pausing about one second mid-sentence, finishing speech, and automatic submission **without tapping Send**. These pause/interaction observations are **owner-reported**, not instrumented timing.

Existing content-free journal from this launch records **one** successful `prompt.submit` response at `03:17:55.324Z` (149 ms RPC duration), **one** text terminal at `03:17:57.817Z`, and **one** audio completion at `03:18:08.623Z`. UI showed Ready and Turn phase: Complete. This corroborates one successful actual Home turn; it is not an independently instrumented controller auto-submit counter.

The user completed the turn before observability preparation finished. Initial journal lookup used the wrong `files/` location; the retained journal was recovered from `no_backup/diagnostics/connection-journal.jsonl`. No partial-to-submit latency, current recognizer package or acoustic pause duration was captured; the historical recognizer measurements above are not substituted. No repeat prompt or rerun was performed.

Safe conclusion: Hermes stopped, phone returned to launcher, temporary UI dump removed; all captured settings and three profile/pairing/credential baseline files restored exactly. Only this launch's removed optional pairing capability flag was restored after confirming it was the sole difference. Legitimate new turn history was retained. No language-pack, permission, network, account or APK changes occurred. No TalkBack waiver extends from VOICE-04.
