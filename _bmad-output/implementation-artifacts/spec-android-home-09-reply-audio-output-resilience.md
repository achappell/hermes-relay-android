---
id: ANDROID-HOME-09
title: Keep reply audio playing through route changes, focus changes and output restarts
status: in-progress
baseline_commit: 12c298139c11f2bf5032e0612fdf514999eb0176
route: dispatch
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-01
parity_stream: S2
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

## Approved first slice — 2026-10-14

The user approved requirements 3–4 only. HOME-09 remains **in-progress**:
dead-track recreation, cushion cap and lead telemetry are unfinished separate scope.
The HOME-08 implementation merged in PR #162 supersedes the historical
“Android today” focus/pause inventory above.

<frozen-after-approval reason="Explicit user approval of requirements 3–4">

### Intent and constraints

Pause reply output on headphone/output-route loss without automatic speaker
fallback. Preserve independent Route, User and Focus holds; resume only a live,
unfinished, unexpired reply. Foreground return releases User only. Explicit Play
may release User and Route, never Focus. Interruption must not re-arm capture.
Duck and restore the same live track without reconfiguration. Keep one process
runtime and the existing off-main PCM path.

### Acceptance

- Given Route and Focus holds, when focus returns, then Route still prevents output.
- Given User and Route holds, when a selected safe output returns, then User still prevents output.
- Given a route hold, when an unrelated device is added or selection is unknown,
  then output remains paused; explicit Play is the deliberate speaker opt-in.
- Given an expired, stopped or transport-dead reply, when any resume event arrives,
  then playback cannot restart.
- Given ducked output, when gain arrives, then the same track returns to normal
  volume; cancellation/new stream cannot inherit stale callback changes.
- Given a foreground return, when User is released, then Route/Focus remain intact.

</frozen-after-approval>

### Code map and execution

Production paths below are under `app/src/main/java/com/achappell/hermesrelay/`.

- `HomeRuntime.kt`, `BackgroundRetention.kt`: extend existing pause reasons,
  retained-reply timeout and focus lifecycle; guard stale callbacks.
- `AudioRouteEvents.kt`: injected route observation with selected-media-route
  evidence, not a connected-device inventory masquerading as route selection.
- `AndroidAudioSink.kt`: track route observation and same-track volume control.
- `AndroidClientPort.kt`, `ModeAwareClientPort.kt`,
  `OkHttpRelaySessionClient.kt`, `OkHttpStandardSessionClient.kt`: carry output
  controls through both adapters, without per-chunk main-thread dispatch.
- `HermesRelayApplication.kt`: wire the process-owned route controller.
- Existing runtime retention and sink tests plus route-policy regressions:
  meaningful pause combinations, deadlines, stale generations and volume boundaries.
- Existing documentation, story index and sprint status: record partial delivery
  and keep physical headset/Bluetooth/acoustic acceptance open.

### Verification plan

Independent review precedes a local-only commit. Run JVM tests, debug build,
lint, APK metadata and the 30-run race gate after the implementation handoff.
Runtime smoke uses a new empty disposable AVD with explicit serial and direct
`adb shell am instrument`; never Gradle connected tasks or the owner's emulator.
Physical Pixel/headset/Bluetooth/acoustic acceptance remains unavailable and open.

### Implementation notes and bounded acceptance

`AudioRouteChange` captures selection and removed-device evidence before main
dispatch. Production `AudioRouteEvidence` owns removed-ID vetoes, registration
generations and attached-track identity. Android 36+ preserves multiple actual
routed outputs; Android 33+ media-policy selection is the paused-track fallback.
On Android 26–32, a null paused-track route has no equivalent public inactive
media selection query: automatic return in that case remains unsupported, with
explicit Play required. Unknown selection never grants automatic resume.

Noisy is a forewarning, not proof that departure already occurred. Its pending
departure state survives an empty route sample; a duplicate old-route callback
cannot clear it. A confirmed personal replacement is evaluated in the same
event. While Route is held, the existing bounded retention watchdog rechecks
selection so switching to an already-connected device does not require a new
device-added callback.

Sink controls are retained before audio starts. `prepareTurn()` ends prior live
output but preserves already-held pending-turn controls when no prior stream is
live; both clients use that admission boundary. `start()` preserves those
controls before PCM can play. Cancellation and terminal drain reset them.
Native pause/play/volume/release work uses a serialized control worker rather
than blocking the UI behind native teardown. Stream generations reject stale
control tasks; returned platform volume errors enter the existing failure path.

### Review triage log — first formal pass

Every formal finding is recorded separately; shared causes refer to the same fix.
All 17 rows below are closed: 16 repaired/verified, one parent claim rejected as
false. The three independent formal review lenses and their targeted follow-ups
were cleared by the parent after the final ownership/admission/privacy repairs.

| Finding | Verdict | Evidence and repair |
| --- | --- | --- |
| Blind 1 | medium | Resampling after a second main post could erase removal. Immutable event selection/removal capture plus queued removal/reconnection regression now preserves it. |
| Blind 2 | medium | A→B personal replacement took only the loss branch and stayed held. The same event now evaluates safe replacement after adding Route, preserving other causes. |
| Blind 3 | high | `start()` cleared a pre-audio hold before queued `AudioStarted` delivery. Red sink regression observed; pending-turn hold/gain now survives admission/start, with real sink + delayed runtime-main regression. |
| Blind 4 | medium | Already-connected selected-device changes could produce no callback. Existing retained-work timer now rechecks only a held Route, bounded by its existing expiry. |
| Blind 5 | medium | UI controls could wait on native release under the volume lock. Atomic intent publication plus serialized off-UI native controls and blocked-release regression remove that wait. |
| Blind 6 | medium | Platform volume return status was discarded. Driver returns status; sink checks `AudioTrack.SUCCESS`; returned-error regression proves failure propagation. |
| Blind 7 | medium | Fake route callbacks bypassed production evidence logic. Injected `AudioRouteQueries` and production `AudioRouteEvidence` now test tombstones, null-route fallback, unknown selection, stale registration, detached tracks and immutable snapshots. |
| Edge 1 | high | Same pre-audio leak as Blind 3. Shares the admission/start control-state fix and delayed-main real-sink regression. |
| Edge 2 | high | Noisy with empty current selection allowed the still-old route to resume. Red runtime regression observed; explicit pending-departure state now survives an empty sample. |
| Edge 3 | medium | Same single-event safe-replacement failure as Blind 2. Red A→B runtime regression observed; same-event replacement now releases Route only. |
| Verification Gap 1 | medium | Same production-evidence gap as Blind 7. Production evidence tests and evidence-to-runtime queued delivery regression now cover it. |
| Parent follow-up: terminal reset absent | false | The pre-repair source already called `endHold()` and cleared duck at lines 405–407, omitted by the parent's partial read. No claim of a plain sequential-drain red was made. Sequential success/failure turn regressions now preserve this behavior. |
| Parent follow-up: terminal generation ownership | high | Check/reset/detach could interleave with newer controls. Completion, admission and cancellation now detach/reset only within the short generation/state lock; native release remains outside it. Stale-finish/new-held-turn and cancel-during-native-prepare regressions cover ownership. |
| Repair regression: duplicate initial play | medium | The real-sink/delayed-main test failed when queued Resume arrived after the PCM worker's first play (two plays). Native paused state now distinguishes actual resume from a not-yet-started hold; the same test passes. |
| Recheck: old native play publication | medium | Blocked old `play()` returned after cancel/new-start and published `started=true` for the new track. The blocked-old-play/concurrent-new-start regression failed before repair. Post-native publication now checks generation and driver identity atomically. |
| Recheck: actual adapter admission coverage | medium | Direct sink tests did not exercise either protocol client's admission. Replaced the Relay forwarding-count test and added Standard admission coverage using real clients, mock servers and the real sink. Temporarily restoring admission cancellation made both tests fail; restored production admission passes. |
| Recheck: unknown-noisy unrelated removal | high | Empty candidate IDs treated any removed sink as departure evidence. Production-evidence→runtime regression failed for unknown/noisy, selected old personal ID 1, unrelated removal ID 99. First personal evidence is now retained as a candidate, and only correlated removal, verified replacement/public transition or explicit Play can release it. |

### Observed verification chronology

- Initial noisy-forewarning regression: **1 test failed** before its first fix.
- First formal review regressions: **3 tests failed** before fixes (pre-audio
  hold/gain, noisy/null-old-route, direct personal A→B replacement).
- Before formal repairs, full fixture-free JVM/build/lint passed: **633 tests,
  47 suites, zero failures/errors/skips**. APK metadata passed; signer checking
  was skipped because `EXPECTED_SIGNER_SHA256` was not configured.
- Before formal repairs, direct instrumentation on a newly created empty API 36
  AVD (`hermes-home09-route-safety-20261014`, serial `emulator-5556`) passed
  **2 tests**. One used actual Android focus requests and the production focus
  adapter, observing Duck/Gain callbacks. The other used controlled route/focus
  events with the real runtime and production AudioTrack sink: real paused/play
  states and one unchanged track/session. Actual platform route query identified
  the emulator speaker. AudioFlinger track 55/session 129 showed L/R gain
  **−14 dB → 0 dB**, unchanged port gain −33 dB and advancing server frames.
  This is OS/software evidence, not headset/Bluetooth/acoustic acceptance.
- Temporary instrumentation fixtures were removed after proof. The owner
  emulator, original AVD, user data and recovery copies were not accessed.
- Final fixture-free full JVM/build/lint: **655 tests in 48 suites, zero
  failures/errors/skips** (`artifact://1453`). APK metadata passed: min SDK 26,
  version 0.3.1 (301), backup disabled/extraction rules and 16 KB alignment.
  Signer comparison remained skipped because `EXPECTED_SIGNER_SHA256` was unset.
- Final expanded race gate: **30 consecutive runs × 164 tests = 4,920 test
  executions, zero failures**, covering runtime retention, sink controls,
  production route evidence and both actual protocol adapters (`bg_148`).
- Final repaired Android build and direct instrumentation passed **2 tests**
  on the same exclusively owned empty API 36 AVD. Actual Android focus requests
  produced Duck/Gain callbacks. Controlled route evidence exercised the real
  runtime and native AudioTrack: pre-audio held start stayed inactive, explicit
  Play resumed, same-track duck/gain restored volume, route/User/Focus holds
  remained independent, and a subsequent naturally completed turn started at
  normal gain. This is controlled-route OS evidence, not physical route proof.
- Final private instrumentation receipt:
  `/var/folders/cb/3pkc1ncx58j1xq63ktd8xftw0000gn/T/home09-final-instrument-fb0z68x_.log`.
  AudioFlinger evidence in that directory:
  `home09-final-preaudio_held-mq54khzs.audio_flinger:64-66` shows track 56/session
  137 inactive with zero server frames;
  `home09-final-ducked-2kpi87lw.audio_flinger:67-69` and
  `home09-final-restored-s7d8jh5y.audio_flinger:67-69` show the same track/session
  at **−14 dB → 0 dB** L/R, unchanged −33 dB port gain and advancing frames;
  `home09-final-next_turn_normal-x0lkzqe4.audio_flinger:67-70` shows new track
  57/session 145 at normal 0 dB gain.
- Temporary smoke source was removed; debug and Android-test APKs were rebuilt
  fixture-free successfully (`artifact://1462`). No Gradle connected task was
  used. Owner emulator 5554, original AVD/data and recovery copies remained
  untouched. Physical headset/Bluetooth/acoustic acceptance, API 26–32
  unknown-route automatic return and the remaining HOME-09 requirements stay
  open; the parent story remains **in-progress**.
