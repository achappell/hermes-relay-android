---
id: ANDROID-HOME-09
title: Keep reply audio playing through route changes, focus changes and output restarts
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-05
  - android:ANDROID-HOME-08
ios_reference: 'IOS-HOME-07 (a776a6d, efff91d, 1235243, a7bd524, a147cd5; PR #122)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/73
---

# ANDROID-HOME-09 — Reply audio output resilience

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): PX-04 (audio focus and ducking), PX-05 (route-change and track-death recovery), PX-15 (playback latency), matrix V7/V9/V10 and `ANDROID-BUG-F3`.

Parity with the iOS output fixes shipped in PR #122 (v0.7.0): restart after engine configuration change (coalesced, single restart), never reconfigure a live session, playback cushion with a hold cap, lead sampling, route-loss pause/resume, interruption handling.

## Background (device-observed on iOS)

- Backgrounded playback went silent and the UI stayed "Speaking" with no sound: the output engine had been stopped by a configuration change nobody observed, buffers never played back, and `finish()` waited on the drain forever (2026-10-05). Every re-applied category posted another route change, so the engine restarted three times in run A and twice in run B; the fix coalesces a burst into one restart pass (re-checked, at most three passes) and ends the reply as a playback failure if the restart fails, instead of hanging.
- A stutter at background entry on every playback, and later a one-second pause, were traced to the feed (a main-thread stall meeting a player with zero cushion); a 300 ms lead (500 ms hold cap) plus `lead_ms` sampling (every 250 ms for 5 s after background entry) separated "feed starved" from "OS render pause". Lead is computed from **queued** audio (scheduled minus completed), because the node timeline keeps advancing through silence and read zero after any underrun.
- Headphones/AirPods removed mid-reply must pause output rather than switch to the speaker, and resume when an output returns.

## Android today (verified against `main` at `3e10ae2`)

- `AudioTrackAudioSink` (`AndroidAudioSink.kt`): a streaming `AudioTrack` (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`); playback starts after `STARTUP_BUFFER_MILLIS = 1000` of audio is written, or at stream end; write stall deadline 3 s, drain stall 500 ms, drain timeout 30 s; underruns are counted (`underrunCount`, `failOnUnderrun=false` outside the live gate). Already **stronger than the iOS 300 ms cushion** — but with no hold cap: a stream slower than real time waits for a full second of audio (or the end) before the first sample, and the UI cannot say "buffering" meaningfully. No lead sampling.
- No `AudioManager.requestAudioFocus`, no `OnAudioFocusChangeListener`, no `ACTION_AUDIO_BECOMING_NOISY` receiver, no `AudioDeviceCallback`/routing-changed listener, no `pause()`/`resume()` on `AndroidAudioSink`. Another app (call, navigation, music) can talk over the reply, and unplugging headphones mid-reply is handled by whatever the platform does (typically the `AudioTrack` follows the new route, i.e. the speaker).
- A dead track after an audioserver restart or a route change that invalidates the track shows up as a write error → `AudioSinkFailureKind.WriteFailure`/`PlaybackStalled` → "Response audio playback failed." with no recovery attempt.

## Required behavior

1. **Single restart after output invalidation.** If the `AudioTrack` is invalidated (a write returns `ERROR_DEAD_OBJECT`/`ERROR_INVALID_OPERATION`, the routing-changed listener reports a lost device and the track is no longer usable, or `getState()` is no longer `STATE_INITIALIZED`), the sink recreates the track once with the same format and continues from the unplayed PCM it still holds; a burst of invalidation signals coalesces into one restart pass (re-check after the pass, at most three passes). If recreation fails, the pending `finish()` fails through the existing failure path ("Response audio playback failed. The response text is still available."), never hangs and never stays `Speaking`.
2. **Never reconfigure a live stream.** Focus type and `AudioAttributes` are chosen when the stream starts and never changed while it plays; background entry makes no sink or focus call.
3. **Audio focus.** Request focus when the stream starts (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` in the foreground; `AUDIOFOCUS_GAIN_TRANSIENT` when started or continued while backgrounded — see `ANDROID-HOME-08` D4). Handle: transient loss → pause output and mark paused (not failed); permanent loss → stop and end the reply as stopped, tear down when backgrounded; `CAN_DUCK` → duck (or keep playing at lower volume); gain after transient loss → resume **only** if the reply is unfinished, the transport is alive and the idle timeout has not fired, otherwise tear down if backgrounded. A call/assistant interruption ends any mic session and the mic stays disarmed after the interruption ends (no automatic re-arm). Abandon focus when the reply finishes or is stopped.
4. **Route loss pauses.** `ACTION_AUDIO_BECOMING_NOISY` (and a removed output device reported by `AudioDeviceCallback`) pauses reply output; it does not route to the speaker mid-reply. A paused reply resumes when an output device becomes available or the user presses Play. The reply remembers *why* it paused (focus loss vs route loss vs user) so a focus-gain event never resumes a reply paused by route loss onto the speaker, and returning to the foreground resumes a lock-screen-paused reply (iOS review findings, `validation-ios-home-07.md` triage log).
5. **Cushion with a cap, and honest state.** Playback starts when at least `lead` of audio is queued, when the stream ends first, or after `maximumHold` since the first buffer, whichever comes first. While held the sink reports buffering, not speaking. Defaults to settle on device: the current 1000 ms lead is acceptable, but add a hold cap (propose 1500 ms) so slow streams are not held indefinitely, and measure whether 300–500 ms is enough on Pixel hardware; keep both injectable. Pause keeps the hold; resume or restart starts it; stop cancels the timer.
6. **Lead sampling.** Expose `playbackLead()` = frames written minus `playbackHeadPosition`-derived frames played (queued, not yet rendered). `playbackHeadPosition` stops advancing in an underrun, so unlike the iOS node timeline it should recover correctly after one; prove that with a test. Journal `audio output lead_ms=N t_ms=T state=… playing=…` at background entry and every 250 ms for 5 s (21 lines), and `audio output cushion started lead_ms=N reason=threshold|cap|finish` once per reply. Add `underruns=N` to `voice response ended`.
7. **Media-session writes are cheap.** Pause/Play state writes happen on state change only, off the main thread (see `ANDROID-HOME-08`); never per PCM chunk. `mediasession_writes=N` since background entry is journaled on `voice response ended` and should be a handful, not hundreds.
8. **Feed path.** Do not add main-thread hops per PCM chunk: audio bytes already go from the OkHttp reader through `audioSink.write` on the sink's worker; keep it that way when adding the journal and media-session code.

## Acceptance criteria

- Sink tests (fake `AudioTrackDriver`): invalidation mid-stream → one recreation, playback continues, queued PCM not lost or duplicated; burst of five invalidation signals → one restart; failed recreation → `onFailure` fires and the turn ends as a playback failure (not `Speaking`).
- Focus tests (fake `AudioFocusController`): transient loss pauses and resumes; permanent loss stops; duck; resume denied when transport dead or timeout fired; foreground return resumes a lock-screen pause; route-loss pause is not resumed by focus gain.
- BECOMING_NOISY pauses; device-added resumes; Pause/Play from the media notification toggles.
- Cushion tests with a manual clock: threshold start while `Buffering`, short stream starts at `finish()`, cap start, pause keeps hold, stop cancels, `lead = 0` configuration starts on first buffer, a sub-cushion stream cannot hang `finish()`.
- Lead test: after an induced underrun the lead recovers to the true queued amount (the iOS bug that read 0 for seconds must not exist here).
- No sink call, focus request or `AudioAttributes` change occurs at `ON_STOP` while a reply plays (assert via the fakes).

## Android design notes

- iOS `AVAudioEngineConfigurationChange` restart has no direct Android twin; the closest signals are `AudioTrack.OnRoutingChangedListener`, write error codes, and `AudioDeviceCallback`. Treat "track unusable" as the single trigger and recreate; do not try to keep a stopped track alive.
- iOS `playerNode.play()` cushion → `AudioTrack.play()` after `preparePlayback` (existing). Keep `startPlayback` the only place that calls `play()`.
- Keep platform types behind the existing `AudioTrackDriver` seam and add `AudioFocusController` / `AudioRouteEvents` interfaces so the JVM suite stays device-free.
- `AudioAttributes` usage: `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH` today. Whether `USAGE_ASSISTANT`/`USAGE_ASSISTANCE_ACCESSIBILITY` would be a better fit for talk-over behavior is a design question for the story spec; it must not change at background entry either way.
- The 1-second wall-clock pause iOS found was an Android-agnostic lesson: any main-thread work at background entry (notification build, MediaSession registration, journal writes) can starve a feed that shares that thread. The OkHttp reader and the sink worker are off the main thread today; keep new work off the thread that feeds PCM.

Audit detail to apply in the story spec: request focus per reply with `setAcceptsDelayedFocusGain` and `setWillPauseWhenDucked(false)`; recreate the track on `ERROR_DEAD_OBJECT` and resume from the last written frame; re-measure the `ANDROID-BUG-F3` underruns on a device after the cushion change; capture-side focus (`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`) is owned by `ANDROID-VOICE-05`. Target values for the cushion are 300 ms lead with a 500 ms maximum hold (about 0.7 s lower first-audio latency than the current 1000 ms); keep them injectable and prove them with `lead_ms` before changing the default.

## Dependencies

`ANDROID-HOME-08` (service, media session, `pause/resume` plumbing and the retention predicate), `ANDROID-HOME-05` (audio start after text). Journal needs `ANDROID-DIAG-01`; without it use unit assertions only and defer the device lines.

## Test notes

JVM with fakes, manual clock, no `Thread.sleep` (`ANDROID-TEST-01`). `AudioOutputPreflightTest` (instrumented) already exercises the real `AudioTrack`; add a device-gated test that invalidates the route via `AudioManager` where feasible, otherwise leave to the manual gate. The emulator has no audio hardware path for route changes; do not claim route behavior from emulator runs.

## Device verification and expected journal lines

On a physical Pixel with Bluetooth earbuds: start a long reply; (1) lock the screen at 10 s — no gap, `audio output lead_ms` samples near the cushion value and about 250 ms apart in wall clock with no 1 s hole; (2) disconnect the earbuds — `audio route event reason=becoming_noisy`, `audio output paused reason=route`, no speaker output; reconnect — `audio route event reason=device_added`, `audio output resumed`; (3) start a phone call — `audio focus event change=loss_transient`, mic never re-arms; hang up — resume only when allowed; (4) healthy background entry shows **no** `audio output track restart` line. If a stutter remains with evenly spaced samples and non-zero lead, the cause is OS render timing, not feed latency; capture a system log (`adb logcat -b all` with the audio tags) before changing code.

## References

iOS: `validation-ios-home-07.md` follow-ups "background reply stays speaking with no sound", "background during speech stops audio…", "stutter at background entry on build 18", "one-second pause at background entry on build 19"; `AudioOutputTests` (`testBurstOfConfigurationChangesRestartsTheEngineOnce`, `testLeadCountsQueuedAudioNotTheNodeTimelineSoItRecoversAfterAnUnderrun`).
