---
id: ANDROID-HOME-09
title: Keep reply audio playing through route changes, focus changes and output restarts
status: in-progress
baseline_commit: 12c298139c11f2bf5032e0612fdf514999eb0176
route: dispatch
buffering_slice_baseline: 613e740511bd0f166347388b4475074afea2e2b2
buffering_slice_status: software-verified
dead_track_slice_baseline: e0af503e3dbd13b8fc6ef75f14280f482bb72763
dead_track_slice_status: software-verified
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

## Approved bounded-startup slice — 2026-10-14

The user approved requirement **5**, after PR163 merged. This is a bounded
startup and truthful-state slice, not completion of HOME-09. Requirements 1,
6 and remaining physical/API-specific route acceptance are unchanged and open.
The earlier 300–500 ms tuning proposal is not approved without Pixel evidence.

### Timing and ownership contract

- Defaults are an injectable **1000 ms queued PCM lead** and **1500 ms maximum
  hold**. The cap begins with the first successfully accepted response PCM,
  not format admission, synthesis initiation, or an empty packet.
- Start on lead threshold, stream finish, or cap, whichever occurs first.
  Expiry must work without another packet or a call to `finish()`.
- Cap time is **monotonic elapsed time**, including pauses. A pause never
  permits playback, even after expiry. Explicit resume releases the current
  stream's startup cushion, including PCM already submitted but awaiting native
  acceptance behind the hold. It starts that PCM as soon as accepted, without
  another chunk, finish, or fresh cap grant. Resume intent and its generation
  are captured together; an old resume cannot release a replacement stream.
- Cancel, terminal completion/failure, replacement and close cancel the timer.
  An already-dispatched old timer cannot play a replacement. Track recreation
  remains out of scope; a replacement stream owns a new first-buffer deadline.
- Format admission reports `AudioBuffering`. Only the sink's successful first
  native play delivers `AudioStarted`, once and before the terminal callback.
  Both real adapters reject stale binding/stream callbacks; callbacks execute
  outside native and deadline locks. A callback cannot revive ended/cancelled
  audio. Audio admission acquires focus while audio is Buffering, not merely
  because text completion has put the turn phase in Buffering.
- Preserve PR163 route/focus/user holds, pre-audio gain, same-track duck/restore,
  native-control work, atomic terminal ownership and off-main PCM feed.
  No extra per-chunk PCM copies, allocations, UI dispatch, or periodic telemetry.

### Code map and tasks

- [x] `app/src/main/java/com/achappell/hermesrelay/AndroidAudioSink.kt`: injectable
  cushion/cap, shared scheduled native-control worker, cancellation and callback
  ownership. Serialize accepted-frame publication with generation validation
  after native queries so old writes cannot prevent a new first-buffer timer.
- [x] `OkHttpRelaySessionClient.kt` and `OkHttpStandardSessionClient.kt` in that
  directory: preserve format validation, emit Buffering at admission and actual
  Started from the per-stream callback, serialize start/end ownership.
- [x] `HomeRuntime.kt`: request focus for audio Buffering or Speaking; synthesis
  waiting alone does not acquire focus. Reapply owned controls at admission.
- [x] Tests in `app/src/test/java/com/achappell/hermesrelay/`:
  `AudioSinkBufferingTest`, `ManualAudioScheduler`, `HeldAudioAdmissionFixture`,
  both actual protocol-client suites and `HomeRuntimeVoiceRetentionTest`.
- [x] Update changelog and local identity/sprint records without closing parent.

### Acceptance and evidence

| Given | When | Then | Regression |
| --- | --- | --- | --- |
| Less than 1000 ms queued | 1500 ms cap expires with no further PCM/end | Exactly one native play and Started | `cap_starts_short_queued_audio_without_more_pcm_or_finish` |
| Subthreshold queued PCM | Lead arrives first | Start immediately; cancelled timer cannot replay | `threshold_starts_before_cap_and_cancels_pending_timer` |
| Zero lead | First nonempty buffer arrives | Start then, not at format admission | `zero_lead_starts_first_buffer_not_format_admission` |
| Paused queued PCM | Cap expires, then explicit resume | No paused play; resume starts without another cap | `pause_keeps_audio_held_past_cap_and_resume_starts_it_without_a_new_cap` |
| Short stream | Finish before threshold/cap | Started precedes drained Ended; no hang | `short_finish_starts_and_drains_before_cap_without_late_started_event` |
| Cancel/replacement | Old timer or native play returns | No old callback or new-track play | Cancel/replacement/native-play tests in `AudioSinkBufferingTest` |
| Old postwrite query blocked | Cancel/new start, then old query returns | Only new PCM is counted; new cap still arms | `old_postwrite_query_cannot_pollute_replacement_first_buffer_or_strand_its_cap` |
| Started delivery blocked | Pause/resume and finish race | Controls remain callable; Ended cannot overtake Started | `blocked_started_delivery_holds_terminal_order_but_not_native_pause` |
| Real Home/Standard adapters | Slow PCM then cap; late callback after end | Buffering → actual Started → Ended; stale callback ignored | Both `slow_*_pcm_reports_buffering_until_actual_cap_play_then_ends_in_order` and late-callback tests |
| Text completed but synthesis pending | Audio admission then actual play | Focus only at audio admission, no re-request on play | `audio_admission_acquires_focus_while_buffering_not_while_waiting_for_synthesis` |

Failing-before evidence:

- On untouched PR163 production code, the explicit-resume regression failed
  (`expected play=1, actual=0`): tool receipt `artifact://1476`.
- Before the generation-publication fix, a latch held the old postwrite native
  underrun query across cancel/new start. The new stream never armed its cap:
  `artifact://1482`; preserved JUnit XML
  `/var/folders/cb/3pkc1ncx58j1xq63ktd8xftw0000gn/T/home09-buffering-proof-zs2kprmz/generation-cap-starvation-before.xml`.
- Targeted green after the fixes: `artifact://1485`, covering
  `AudioSinkBufferingTest`, `AudioSinkPauseTest`, `AndroidAudioSinkFramesTest`,
  `OkHttpRelaySessionClientTest`, `OkHttpStandardSessionClientTest`, and
  `HomeRuntimeVoiceRetentionTest`.

Final formal review and software/device-runtime gates are recorded below.
Hardware acoustic and Pixel cushion calibration remain open regardless of
those software results; HOME-09 remains **in-progress**.

### Bounded-startup review triage

All three independent review lenses completed before this repair pass. Findings
are retained individually, including duplicates and the withdrawn edge claim.

| Source | Verdict / disposition | Evidence and action |
| --- | --- | --- |
| Blind F1: admission with no first PCM can wait | Outside approved scope | The cap is explicitly measured from first accepted PCM. No pre-first-PCM timeout is approved; adding a transport/synthesis timeout would broaden scope. |
| Blind F2: no pre-first-PCM timeout test | Outside approved scope | Same contract boundary as F1, retained separately. Zero-lead test already proves format admission alone does not start playback. |
| Blind F3: Recording sink terminal retains callback | Low / fixed | No actual play means `finish()` must not invent Started. However the stored callback survived terminal delivery and a late write invoked it. Clear it on finish; failing-before `terminal_recording_stream_cannot_deliver_a_late_started_callback` proves the repair. Runtime composition injects AudioTrack sinks; Recording remains a client-constructor/test default. |
| Blind F4: optional callback permits omission | False / rejected | Low-level driver tests may omit observations. Both actual adapters supply callbacks and direct socket integration tests assert Buffering → Started → Ended. No unmigrated production consumer was identified. |
| Blind F5: duplicate late Buffering | False for this change / rejected | Suggested live path uses legacy normalization disabled in production Relay. The changed adapters serialize admission before callback delivery. No newly reachable late Buffering path was shown; do not alter unrelated reducer semantics. |
| Blind F6: adapter held-past-cap gap | Medium / fixed | Both real protocol adapters now have manual-cap integration cases: accepted subthreshold PCM, Pause, cap expiry without play/Started, Resume, actual sink play/Started and ordered Ended. |
| Blind F7: observer replacement strands drain | Medium / fixed | `observeTurn` publicly supports same-binding replacement independently of current HomeRuntime usage. Cleanup now belongs to stream generation/binding/drain ownership, not observer identity. Both success and failure completion tests failed before the fix; notifications still use current-observer guards. |
| Blind F8: sink invokes Started synchronously in start | False / rejected | A conforming sink only admits format in start; no PCM has played. Both supplied sinks invoke after PCM/actual play, so the hypothetical path is not a supported contract. |
| Blind F9: playback-state fixture models old sequence | Low / fixed | `ResponseAudioPlaybackTest` now translates format admission into Buffering and emits Started through the sink callback, asserting the actual public event sequence and final delivered state. |
| Verification-gap: runtime holds at Buffering admission | Medium / fixed | Added real sink/driver runtime test: retained User hold survives sink-local cleanup through Buffering admission, stays inaudible beyond cap time, then explicit resume starts at ducked gain. Assertions inspect actual play and gain, not forwarding counts. |
| Edge finding: no Started at zero-PCM finish | False / withdrawn by reviewer | No native play occurred, so fabricating Started would be wrong. Real AudioTrack sink fails zero-PCM finish. Callback retention is separately handled in Blind F3. |
| Self review: observer-owned terminal cleanup | Medium / fixed | Duplicate of F7 retained individually. Stream cleanup must occur even when the original observer is replaced. |
| Self review: resume generation captured too late | Medium / fixed | A latch held resume control dispatch while cancel/new start admitted replacement PCM. Old resume then skipped the new cushion. Capture generation atomically with hold release and pass it to both control tasks. |
| Parent/self review: pending-PCM resume intent | Medium / fixed | Resume could run before held worker accepted its submitted PCM, see zero accepted frames and lose the release. Persist a generation-owned startup-release intent consumed by acceptance. Corrected the implementer-authored narrower appendix sentence above. |
| Parent review: old postwrite native query publishes new counters | High / fixed before formal snapshot | Genuine failing-before latch test stranded replacement cap. Generation-check and accepted-counter/timer publication now share the existing deadline lock after native work. |

Review repair red receipt: `artifact://1569` — **5 tests failed** for pending-PCM
resume, stale-resume generation, observer replacement after successful and failed
drain, and retained Recording callback. Original JUnit XML is preserved under
`/var/folders/cb/3pkc1ncx58j1xq63ktd8xftw0000gn/T/home09-buffering-proof-zs2kprmz/review-before/`.

### Final bounded-startup verification — 2026-10-14

- **Formal review closed:** blind, edge-case and verification-gap lenses all
  completed independent follow-up review with no actionable finding remaining.
  The final runtime coverage case raises Duck before sink-local cleanup and
  Buffering admission, then captures **0.2 gain at first native play**, before
  Started can reapply controls. It proves restoration of already-owned gain,
  not merely a later focus callback.
- **Full JVM:** **680 tests in 49 suites**, zero failures/errors/skips.
  Final full `testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest`
  passed (`artifact://1593`). Full XML was copied before the repetition gate
  overwrote Gradle reports.
- **Repetition:** **30 × 216 = 6480 test executions**, zero failed iterations.
  The eight suites were `AudioSinkBufferingTest`, `AudioSinkPauseTest`,
  `AndroidAudioSinkFramesTest`, `OkHttpRelaySessionClientTest`,
  `OkHttpStandardSessionClientTest`, `HomeRuntimeRetentionTest`,
  `HomeRuntimeVoiceRetentionTest`, and `ResponseAudioPlaybackTest`.
- **APK metadata:** pass, min SDK 26, version 0.3.1 (301), backup disabled with
  extraction rules, 16 KB aligned. Signer comparison was **not exercised**:
  `EXPECTED_SIGNER_SHA256` was unset.
- **Actual Android runtime:** direct serial-targeted instrumentation passed
  **4 tests** on a newly created empty API 36 ARM64 AVD. Production AudioTrack
  factory/sink and real HomeRuntime were used, not native play counters alone:
  750 ms PCM stayed Buffering with playback head 0, then started at **1504 ms**
  with no further PCM or finish and head **544**. AudioFlinger track 56/session
  217 went inactive/server `00000000` → active/server `00000220`.
  A paused stream stayed inactive beyond cap, then resumed in **4 ms** with
  advancing frames on the same track 55/session 209. A 100 ms short stream
  started in **2 ms** at finish and drained. Stop before cap remained Stopped
  with zero starts after **1703 ms**.
- **Rendered existing Home UI:** an additional direct instrumentation test
  passed on the production `AndroidClientScreen` surface backed by that runtime
  and real sink. It asserted displayed **“Turn phase: Buffering”** at head 0
  and **“Turn phase: Speaking”** after the cap (**1503 ms**, head **4896**);
  both screenshots are retained. The first attempt found no visible Compose
  hierarchy because the fresh install's notification permission dialog covered
  the Activity. The logcat identified the permission Activity; granting
  `POST_NOTIFICATIONS` on the disposable emulator only resolved the obstruction.
  This failed attempt is retained, not hidden as an application defect.
- **Cleanup:** temporary instrumentation source was removed; final debug and
  Android-test APKs were rebuilt fixture-free. Scanning all APK DEX entries
  confirmed `Home09BufferingSmokeTest` absent. The owned `emulator-5556` exited
  cleanly and AVD `hermes-home09-buffering-20261014` plus its owned empty userdata
  directory were deleted. No Gradle `connected*` task ran. Emulator 5554,
  its original AVD/userdata and every owner recovery copy were untouched.
- **Scope remains partial:** no dead-track recreation, periodic lead telemetry,
  300–500 ms tuning, physical headset/Bluetooth routing or acoustic/Pixel
  acceptance was claimed. No remote issue hook, push or PR was run.

Private evidence root:
`/var/folders/cb/3pkc1ncx58j1xq63ktd8xftw0000gn/T/home09-buffering-proof-zs2kprmz/`

- `full-jvm-final/counts.json` and `TEST-*.xml`: complete suite evidence.
- `flake-final-summary.txt`, `flake-gate-final/iteration-{1..30}.log` and
  `flake-final-junit/`: repetition receipts.
- `android-smoke-initial.log`: four native/runtime scenarios.
- `home09-precap.audio_flinger:69`, `home09-postcap.audio_flinger:67`,
  `home09-paused-cap.audio_flinger:65`, `home09-resumed.audio_flinger:65`:
  exact native track/state/frame evidence.
- `android-ui-smoke.log`, `home09-ui-buffering.png`, `home09-ui-speaking.png`:
  rendered UI assertions and screenshots. `android-ui-permission-obstructed.log`
  and `ui-first-attempt-logcat.txt` retain the initial obstruction.
- `metadata-final.txt`, `fixture-free-apks.json`: metadata, final APK hashes and
  temporary-class absence. `Home09BufferingSmokeTest.kt` is the off-tree proof
  fixture only, not shipped source.

## Approved dead-track slice — 2026-10-14

Baseline: `e0af503e3dbd13b8fc6ef75f14280f482bb72763` (merged PR164).
This implements requirement 1 only; HOME-09 remains in progress.

### Bounded recovery contract

- One actual replacement per admitted stream, not a three-attempt retry loop.
  Repeated signals for the obsolete driver coalesce; invalidation of its
  replacement fails through the existing playback-failure path.
- Recover returned `ERROR_DEAD_OBJECT`, returned `ERROR_INVALID_OPERATION`,
  and observed noninitialized state. Route changes alone are not invalidation.
  Keep the admitted format, buffer policy and AudioAttributes unchanged.
- Android documents that a positive write result transfers that prefix even
  when death is reported on the next call. Retry only the app-owned, unaccepted
  suffix. Never retain/replay accepted PCM or infer acoustic delivery from its
  acceptance count. Native-accepted but unplayed PCM cannot safely be recovered.
  If the last native playback head cannot establish that all accepted response
  frames played, continue the remaining app-owned PCM but finish through the
  existing failure path. A provably drained prefix permits ordinary success.
  Reference: [AudioTrack.write](https://developer.android.com/reference/android/media/AudioTrack#write(byte[],%20int,%20int,%20int)).
- Replacement releases the startup cushion immediately for pending PCM, not
  the independent User/Route/Focus pause holds. No fresh 1500 ms cap is granted;
  defaults remain 1000 ms lead / 1500 ms hold. Preserve duck intent. Report
  Started at most once per stream, only after actual native play.
- Native controls, deadlines, write/head results and recovery publication must
  own both the stream generation and the driver identity. Cancellation and a
  newer stream cannot inherit old callbacks, failure, controls or replacement.
- Failed creation, unusable replacement or repeat invalidation must terminate
  pending finish as failure, never hang or leave the turn Speaking.

### Code map and acceptance

`AndroidAudioSink.kt` owns replacement and frame accounting; reuse its worker,
native-control serialization, deadline lock and existing failure callback.
Permanent fake-driver tests exercise partial acceptance/no replay, both native
error codes, state invalidation, bounded replacement, creation failure, native
queue uncertainty, pause/duck/cap/callback ownership and cancellation races.
Existing real-adapter/runtime tests retain truthful state and hold semantics.
`AndroidTurnState.kt` projects pre-terminal audio failure as nonterminal Thinking
with audio Unavailable, not Speaking; subsequent text remains receivable.
`OkHttpRelaySessionClientTest` exercises the real adapter and real sink across
that boundary before delivering the text terminal.
No periodic telemetry, cushion tuning, new error copy or PCM retention is added.

Verification requires pre-fix regression evidence, full JVM/build/lint/metadata,
the repetition gate and actual Android native/UI smoke on an exclusively owned
new empty AVD. Controlled injected invalidation is not OS audioserver death or
physical/acoustic proof. Preserve complete JUnit XML before the repetition gate.
After smoke, remove its temporary fixture, rebuild fixture-free APKs, remove only
the owned AVD, and update partial-status records. No push, PR or remote hook.

### Dead-track review triage and regression evidence

The parent coordinated independent blind, edge-case and verification-gap
reviews, then focused follow-ups on the repaired snapshot. All three follow-ups
closed with no actionable findings. The blind review found no actionable issue.

| Finding | Verdict | Evidence and repair |
| --- | --- | --- |
| Edge: underruns use max rather than sum across native tracks | medium | Existing exported stream telemetry undercounted old 5 plus replacement 2 as 5. Regression failed before repair; the retired-track count now carries into replacement deltas and the test observes 7. No periodic telemetry added. |
| Parent: cumulative underrun accounting | medium | Same demonstrated defect as the edge review; kept as an individual triage row and repaired by the same retired-track accounting change. |
| Verification gap: padding masks wrong replacement drain origin | medium | New regression uses a proved-played 2,000-frame old prefix and an independent replacement head of exactly 2 response frames. It passes with the local target and fails when deliberately mutated to the cumulative stream target. Padding cannot satisfy the assertion. |
| Parent/verification mapping: failed recreation can leave Speaking before text completion | high | Real Home adapter, real sink and reducer regression failed with Speaking after AudioFailed. The reducer now shows nonterminal Thinking plus the existing audio-unavailable warning, retains subsequent text and reaches Unavailable at the later text terminal. Actual Android rendering also verifies this boundary. |
| Parent: native head/underrun query exception after state check needs recovery | false | Withdrawn after tracing AOSP Java/JNI: these query exceptions require a null native pointer, not ordinary audioserver death. Cancellation/release already invalidates ownership. No speculative exception-recovery branch was added. |

Observed red/green evidence:

- Before recovery implementation: **7 tests, 6 failures**, including native
  invalidation, suffix preservation, creation failure and the bounded retry.
  Preserved `red-junit/`; build receipt `artifact://1614`.
- Review regression run: **17 tests, 2 failures** for cumulative underruns and
  pre-terminal consumer phase. Preserved `review-red-junit/`;
  receipt `artifact://1626`. Both repaired regressions and the response-audio
  suite passed afterward (`artifact://1629`).
- Wrong-target mutation: **1 test, 1 failure** (`artifact://1631`,
  `drain-mutation-red-junit/`). The mutation was immediately removed.

### Dead-track actual Android smoke — 2026-10-14

Direct `adb -s emulator-5556 shell am instrument` passed **3 tests, zero
failures/ignored**, using a newly created empty API 36 AVD. The temporary test
decorated the production factory with controlled `ERROR_DEAD_OBJECT`,
noninitialized-state and replacement-construction-failure signals; Android
itself did not kill audioserver. All successful drivers were real AudioTracks.

- Native old prefix reached **16,000 playback-head frames**. Recovery created
  one replacement with the same format and its head advanced to **362**;
  the stream emitted Started once and finished successfully. AudioFlinger
  recorded track **56/session 137 → 57/session 145**, not a reused fake track.
- A paused/ducked replacement stayed at **0 frames / 0 plays**, native track
  **59/session 161** inactive at **−14 dB**. Resume advanced its head to
  **362**; restoring volume advanced to **725**, with native L/R **0 dB**.
  Port gain remained **−33 dB**. This proves native software state, not audible
  sound or physical focus/headset/Bluetooth behavior.
- Actual production `TurnZone` rendered **Thinking**, retained response text
  and the existing audio-unavailable warning after failed recreation and
  before TurnCompleted. Further text remained visible; the later terminal
  rendered Unavailable. This is a real Compose component/native sink proof,
  not a full socket/Home session or a physical acoustic acceptance run.

Private evidence root:
`/var/folders/cb/3pkc1ncx58j1xq63ktd8xftw0000gn/T/home09-dead-track-proof-3q1gar26/`

- `android-native-ui-smoke.log`: TestRunner **3/0/0** and actual native head counts.
- `home09-old-prefix.audio_flinger:69`,
  `home09-replacement.audio_flinger:67-68`: old/replacement native identity.
- `home09-replacement-held.audio_flinger:68`,
  `home09-replacement-ducked.audio_flinger:68`,
  `home09-replacement-restored.audio_flinger:68`: held/ducked/restored evidence.
- `home09-failed-before-terminal.png`: rendered pre-terminal audio failure.
- `Home09DeadTrackSmokeTest.kt`: off-tree proof fixture only; removed from the
  repository before final fixture-free verification.

The owned AVD `hermes-home09-dead-track-20261014` used an isolated temporary
AVD home and an exclusively claimed free port. It was stopped with explicitly
targeted `emu kill`, exited cleanly, and deleted with its own isolated AVD home;
the claim was removed. Emulator 5554, owner AVD/userdata, earlier worktrees and
the protected HOME-08 recovery copy were untouched. No connected Gradle task,
push, PR or remote issue hook was run. Physical/acoustic, naturally occurring
OS track-death and remaining HOME-09 acceptance stay open.

### Repetition-gate incident and repair

The first independent final full suite/build/lint/test-APK run passed
**697 tests in 50 suites**, but its subsequent **30 × 246** repetition gate
had **one failure in iteration 23**, with 29 iterations passing. The failing
suite was `OkHttpStandardSessionClientTest`. This was not dismissed as noise.

The original gate retained its quiet console logs and suite summary, but the
next iteration's `cleanTestDebugUnitTest` deleted the failed JUnit XML. Searching
the original Gradle daemon log, surviving binary results, HTML report and XML
did not recover the exact assertion. **The original failure remains
unattributed.** Evidence is retained in `flake-final/summary.json`,
`flake-final/iteration-23.log` and `original-flake-residual/`.

The authorized diagnostic work, distinct from a confirmation rerun:

1. `scripts/run-flake-gate.sh` now copies failed-iteration JUnit XML to a unique
   `app/build/flake-gate/failed-iteration-N.XXXXXX` directory before any later
   task clean and prints that location. An off-tree stub generated a known
   failed XML then a passing iteration; the exact failed assertion survived.
   Evidence: `/var/folders/cb/3pkc1ncx58j1xq63ktd8xftw0000gn/T/home09-flake-retention-proof-ebx6v892/`.
   This is not general archival storage: a full `app/build` clean can still
   remove it, and existing per-iteration console-log filenames are unchanged.
2. One bounded Standard-only diagnostic campaign retained verbose logs and
   every iteration's XML, stopping on failure. All 30 passed; this did **not**
   establish that the original failure was resolved. See `standard-diagnostic/`.
3. A temporary deterministic probe used the actual Standard observer: it held
   delivery after AudioEnded woke the test, began the test's event iteration,
   then allowed the real TurnCompleted append. This produced
   **ConcurrentModificationException**, **1 test / 1 failure**
   (`artifact://1658`, `standard-iterator-red-junit/`).
4. The two affected audio-test collectors now use the existing
   `CopyOnWriteArrayList` convention rather than iterating a synchronized
   ArrayList without its monitor. Assertions, timings and production behavior
   were not weakened. The same forced schedule and the full Standard class
   passed (**29 tests**, including the temporary probe; `artifact://1661`,
   `standard-iterator-green-junit/`).
5. The temporary collection probe was removed before final gates; its before
   and after source remains off-tree in `standard-iterator-before.kt` and
   `standard-iterator-after.kt`. Existing behavior tests remain permanent.
   The focused independent coverage review approved the two-file repair.

This establishes and repairs a real test-observation race, **not the historical
identity of the lost iteration-23 failure**. No new production change followed
the native/UI smoke, so no additional native run was represented as necessary.

### Final dead-track software verification

- Fixture-free `testDebugUnitTest assembleDebug lintDebug
  assembleDebugAndroidTest` passed (`artifact://1664`):
  **697 tests / 50 suites, zero failures/errors/skips**. Complete XML and counts
  were copied to `full-jvm-after-flake-repair/` before the repetition gate.
- The post-repair, evidence-retaining gate passed **30 consecutive runs ×
  246 tests = 7,380 executions, zero failed iterations**. Its ten classes and
  every console log are retained in `flake-after-repair/`; final XML is under
  `flake-after-repair/last-iteration-junit/`. This clean result does not erase
  the initial failed gate or upgrade its unknown failure identity.
- APK metadata passed: min SDK **26**, version **0.3.1 (301)**, backup disabled
  with extraction rules, **16 KB aligned**. Signer comparison was **not
  exercised** because `EXPECTED_SIGNER_SHA256` was unset. The first metadata
  invocation failed to locate build tools; explicitly setting the project SDK
  root and SDK command-line-tool PATH corrected that environment failure
  without source changes. Receipt:
  `metadata-after-flake-repair.txt`.
- Both final APKs were scanned across every DEX entry: the temporary
  `Home09DeadTrackSmokeTest` was absent. Hashes and absence results:
  `fixture-free-after-flake-repair.json`.
- HOME-09 remains **in-progress**. This bounded slice is software verified;
  periodic lead telemetry, physical/acoustic/Pixel acceptance, API-specific
  unknown-route return and naturally occurring OS track-death proof remain open.
