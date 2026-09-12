---
title: 'Validation — A-8 response audio playback'
type: 'validation'
story_id: 'A-8'
created: '2026-09-12'
status: 'done-with-environment-limitation'
---

## Scope

Validate that the Client plays streamed response audio, that `Speaking` and
`Delivered` are conditional on real playback, that an unsupported format fails
honestly while retaining the response text, and that a completed turn means the
response finished speaking. Microphone capture and buffered-file audio are out
of scope.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21. 72 unit tests, 0 failures (6 new); debug APK
  assembled; lint clean.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed on
  `hermes-relay-api36` (Android 16 / API 36). 12 instrumentation tests, 0
  failures, 0 skipped.
- Live gate — passed, 2 tests, 0 failures, against
  `wss://voice-amanda.chappell-home.dev/voice-session`. The typed turn now also
  asserts the turn reaches `Complete` with audio `Delivered`, which is reached
  only after the real `AudioTrack` buffer drained.
- `scripts/check-apk-metadata.sh`, `git diff --check` — passed.

## Evidence notes

- **The defect was found by probing, not by reasoning.** A frame probe against
  the relay returned
  `hello_ack -> turn_accepted -> status -> audio_start -> text_delta ->
  text_final -> speech_timing -> audio_end -> turn_end` with 20 binary frames.
  That disproved an earlier assumption — recorded in this session — that turns
  were ending `Unavailable` for want of audio. They were ending `Complete` with
  `Delivered`, which was a false claim: the bytes were being counted as
  lifecycle evidence and dropped.
- **The format was measured.** `audio_start` announces
  `pcm_s16le`/24000/1/2 with `audio_focus: exclusive`, and `hello_ack`
  advertises `pcm_s16le` among its capabilities. About 131 KB arrives per short
  turn in 8 KB frames.
- Unit coverage pins format parsing from a real `audio_start` frame, the
  support test rejecting `opus`, a 4-byte sample width, 7 channels, and an
  out-of-range sample rate while accepting 48 kHz stereo and mixed-case
  encoding; the observed frame order reaching `Complete`/`Delivered` with the
  bytes handed to the sink; a refused format ending `Unavailable` with the text
  retained and `Speaking` never claimed; and `finish` without a `start`
  reporting failure rather than silent success.

## Real-device finding — 2026-09-12

Amanda ran a turn on a physical device. The audio finished, and the phase
stayed in `Speaking` for roughly thirty seconds before clearing.

The drain guard compares `AudioTrack.playbackHeadPosition`, which counts
frames, against a `framesWritten` total that divided bytes by a fixed two. That
is only correct for mono. The sink configures `CHANNEL_OUT_STEREO` whenever the
format reports two channels, where a frame is four bytes, so `framesWritten`
was double the true count and the guard's condition could never be satisfied.
It ran its full `DRAIN_GUARD_ITERATIONS` (1,500 x 20 ms = 30 s) and then
reported drained anyway, which is why the turn recovered rather than hanging.

`channels` was validated as `1..2` and used to select the channel mask, but --
unlike `sampleRate` -- was never stored, so the sink could not compute a
correct frame size. No test covered a stereo stream; 88 unit tests and a
passing live gate all missed it.

Fixed by storing the channel count and deriving bytes-per-frame from it, with
unit coverage for both channel counts. **The fix is not yet verified on
hardware** -- the emulator cannot show this, for the same `-no-audio` reason
recorded below.

This is the first defect found by real-device use rather than by the gate, and
it is exactly the class the limitation below predicted.

## Environment limitation

The emulator runs with `-no-audio`, so there is **no audio device to hear**.
The live gate proves the sink initialized, accepted the stream, and drained to
the written frame count before the turn completed — it does **not** prove sound
was audible. Audible playback, output routing, focus behavior against
`audio_focus: exclusive`, and interaction with other media on the device remain
unverified until a physical Android device runs the same gate.

This is recorded rather than glossed: the story's claim is that the Client no
longer reports speaking when it is not, and that claim is supported. "The user
heard the response" is not yet evidenced.

## Deferred

- Buffered-file audio (`audio_file_start`/`audio_file_end`) is still lifecycle
  only and is not played.
- `speech_timing` is still reported as `Unknown`; word-level highlighting is
  not implemented.
- Audio focus is requested implicitly through `AudioAttributes` rather than by
  taking explicit focus, so behavior alongside other playing media is
  unverified.
