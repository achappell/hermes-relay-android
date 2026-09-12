---
title: 'A-8 — Response audio playback'
type: 'story'
story_id: 'A-8'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: '7d37238'
context:
  - '_bmad-output/implementation-artifacts/spec-a-2-honest-phases.md'
  - '_bmad-output/implementation-artifacts/spec-a-7-live-turns.md'
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
---

## Intent

**Problem:** `A-7` delivered normalized audio lifecycle events, but nothing
played them. A frame probe against the household relay showed it streams real
audio for every turn — `audio_start`, twenty-odd binary PCM frames, `audio_end`
— so the A-2 reducer walked `Speaking`, then `Delivered`, then `Complete`.
**The Client was claiming it was speaking while producing no sound, and
claiming the audio had been delivered when it had been discarded.** A-2's own
spec forbids claiming `Speaking` after audio delivery has failed; this was
worse, because delivery had never been attempted.

**Approach:** Add an audio sink and make the Client's audio claims conditional
on it. `Speaking` is reported only once playback actually starts, and
`AudioEnded` only once the buffer has drained, so `Complete` means the response
has genuinely finished speaking.

## Boundaries & Constraints

**Always:** Report `Speaking` only after playback starts; report `AudioEnded`
only after the queued audio has drained; report an unsupported format as an
audio failure that retains the response text; pass audio bytes straight to the
sink; cancel playback when a turn fails, is interrupted, or the Session ends.

**Never:** Retain audio bytes in UI state, Local History, or a log; claim
delivery for audio that was discarded; reach `Complete` while audio is still
playing; guess at a format the device cannot play.

## Acceptance Criteria

- Given `audio_start` announcing `pcm_s16le` at 24 kHz mono, when playback
  starts, then `Speaking` is reported and subsequent binary frames are written
  to the sink.
- Given `audio_end`, then `AudioEnded` is reported only after the queued audio
  has drained, and the turn reaches `Complete` only then.
- Given a format the device cannot play, then an audio failure is reported
  instead of `Speaking`, the response text is retained, and the turn ends
  `Unavailable`.
- Given `audio_end` with no playback ever started, then a failure is reported
  rather than a silent success.
- Given a turn fails, is interrupted, or the Session disconnects, then playback
  is cancelled.
- Given a new turn begins, then any prior playback is cancelled first.

## Code Map

- `AndroidAudioSink.kt` — `AndroidAudioFormat` with its support test, the sink
  seam, the `AudioTrack` implementation with drain-aware `finish`, and a
  recording sink for deterministic tests.
- `AndroidTurnState.kt` — `AudioStarted` now carries the announced format.
- `HermesEventNormalizer.kt` — parses `sample_rate`, `channels`,
  `sample_width`, and `encoding`, defaulting to the relay's documented PCM.
- `OkHttpRelaySessionClient.kt` — routes binary frames to the sink, substitutes
  a failure when a format is refused, and defers `AudioEnded` until drained.
- `MainActivity.kt` — wires the real `AudioTrackAudioSink`.

## Implementation Notes

- **The format was measured, not assumed.** The relay announces
  `encoding: pcm_s16le`, `sample_rate: 24000`, `channels: 1`,
  `sample_width: 2`, and `audio_focus: exclusive`, and advertises `pcm_s16le`
  in its `hello_ack` capabilities. Roughly 131 KB arrives per short turn in
  8 KB frames.
- **`AudioStarted.format` is nullable with a default** so the A-2 event
  vocabulary stays source-compatible with tests written before this slice.
- **Draining is bounded.** `finish` waits for the playback head to reach the
  written frame count, with an iteration guard so a stalled device reports a
  failure instead of hanging a turn in `Speaking` forever.
- **`audio_file_*` is still only lifecycle.** The buffered-file fallback path
  is not played; the relay uses the streamed PCM path and that is what this
  slice supports.
- Playback needs no Android permission, so no runtime permission flow was
  introduced.

## Verification

See `validation-a-8-response-audio-playback.md`, including the honest limit on
what the emulator can prove.

## Closure

`Speaking` and `Delivered` are now true statements. A completed turn means the
response finished speaking, and a device that cannot play the format says so
while keeping the answer readable.
