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
lead_slice_baseline: aeb470ff656c27b55aed9012b08a2bedcd5e069b
lead_slice_status: software-verified
media_session_slice_baseline: 62987da8d10bde71918a1bd95666a80d502a0caf
media_session_slice_status: software-verified
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

## Approved playback-lead diagnostics slice — 2026-10-15

Fresh baseline: `aeb470ff656c27b55aed9012b08a2bedcd5e069b`, merged PR165
(previous head `bf98fa9d9b80839bdfaf1e890e3b7f00c36f8050`). This slice implements
requirement **6** only. The **1000 ms lead / 1500 ms cap remain unchanged**.
The actual baseline has no `mediasession_writes` or `voice response ended`
summary. The approved correction adds the required underrun summary, not
requirement 7's missing media-session accounting. Existing off-main,
change-driven media updates remain untouched.

### Observation and ownership contract

- `playbackLead()` returns current-driver accepted **response** frames minus
  that driver's playback-head frames, clamped at zero; drain padding and the
  retired driver's lost queue never inflate it. Android's unsigned 32-bit head
  is widened and full wraps are extended within one native driver.
- Native head/playing queries run outside the sink's ownership lock. A versioned,
  generation/driver-owned in-flight marker spans each native response write
  through positive acceptance-counter publication. Queries and publication reject
  overlap or version changes, including the native-accepted-but-not-yet-counted
  interval. Exceptions/cancellation/replacement cannot leave or clear another
  stream's marker. There is no spin/retry loop, PCM copy or per-chunk UI dispatch.
- `state` describes logical buffering/playing/paused intent; `playing` is the
  actual native `AudioTrack.playState == PLAYSTATE_PLAYING`, not an inference
  from prior `play()`. No diagnostic observation changes native controls,
  focus, attributes, media state or notification state.
- Background entry captures the current audio-stream identity without native
  work and schedules observations off-main. A healthy uninterrupted window has
  **21 samples**, deadlines 0, 250, …, 5000 ms. `t_ms` is actual monotonic elapsed
  time at publication. Delayed dispatch skips missed slots: no catch-up burst
  and no fabricated claim of 21 measurements when the executor was starved.
- Pre-final dispatch/query/publication expires after 5000 ms. The final 5000 ms
  slot permits at most one cadence (**250 ms**) of jitter, checked before the
  native query and again before publication. A read blocked past that bound
  produces no stale line. A transient inconsistent snapshot skips only its
  slot, not the rest of an owned window.
- No active audio stream at entry means no sampling window. Duplicate background
  notifications cannot start another loop or adopt a later reply. Foreground
  return, retained-work end and teardown cancel the window; a new stream or
  terminal sink invalidates its ownership. Foreground followed by background
  creates a fresh window for the then-current stream. A native driver replacement
  within the same stream retains the window but invalidates old-driver snapshots.
- `audio output cushion started lead_ms=N reason=…` records only the first
  successful native play of a logical audio reply, not subsequent native
  replacement or resume plays. The user approved the accurate additional reasons
  **resume**, **restart**, and **buffer_full**, alongside **threshold**, **cap**,
  and **finish**. These observe existing release paths; they do not add behavior
  or relabel an early release as a threshold/cap that did not win.
- `voice response ended underruns=N` is emitted once when the admitted audio
  reply completes, fails, is cancelled or is replaced by a new stream. Its final
  native underrun query precedes release, includes retired-driver counts, and
  does not rely solely on the last PCM/drain poll.

### Implementation and permanent verification

Production paths are under `app/src/main/java/com/achappell/hermesrelay/`:

- `AndroidAudioSink.kt`: per-driver lead/head accounting, truthful first-play
  cause, owned publication and final underrun summary.
- `AudioLeadSampler.kt`: the single bounded off-main observation window.
- `AndroidClientPort.kt`, `ModeAwareClientPort.kt`, both OkHttp adapters:
  read-only output facts and stream ownership, not PCM event dispatch.
- `HomeRuntime.kt`: background/foreground/terminal ownership of observations.
- `HermesRelayApplication.kt`: inject the existing journal into both real sinks.

Permanent `AudioPlaybackLeadTest` and `AudioLeadSamplerTest` cover underrun
refill, unsigned/wrapped head semantics, padding exclusion, replacement-local
lead, truthful release reasons, fresh terminal underruns, stale publication,
delayed/expired scheduling, bounded final jitter, blocked queries and transient
inconsistent snapshots. The real HomeRuntime + sink regression exercises
background entry, foreground cancellation and re-entry without changing the
driver's native play/pause/prepare/volume calls. Existing buffering/recovery
tests remain in the targeted gate.

### Review triage — playback-lead slice

All initial findings are retained separately, including duplicate causes:

| Source | Verdict | Evidence and repair |
| --- | --- | --- |
| Blind 1: delayed callbacks catch up and final slot never expires | medium | Initial dispatch at 1000 ms generated zero-delay samples; final callback could publish at 30000 ms. Skip elapsed slots and apply explicit pre-query/publication expiry with only 250 ms final jitter. |
| Blind 2: old queued counter minus newer head falsely reads zero | medium | Latch-held native query allowed the PCM worker to accept a refill. The old 160-frame counter minus head 240 incorrectly yielded zero despite 240 queued frames. Discard changed-counter snapshots and retry only at future bounded slots. |
| Edge 1: final callback at 30000 ms remains permitted | medium | Same expiry defect as Blind 1, retained separately. Before-query and before-publication checks now enforce the final 5250 ms upper bound. |
| Edge 2: PCM accepted between counter capture and head query | medium | Same observed inconsistent snapshot as Blind 2, retained separately. The worker remains unblocked while the native query is latch-held. |
| Verification gap: no expired pre-final or bounded final-jitter assertions | medium | Added actual 5001 ms pre-final, 5001/5250/5251 ms final, delayed initial catch-up, and blocked-read expiry cases. Assertions inspect query counts and actual journal timestamps, not test names. |
| Blind follow-up: accepted native write precedes counter publication | medium | The first counter-equality repair missed the postwrite underrun-query interval. A second latch regression reproduced false zero with 160 published frames and native head 240 after a 320-frame refill. The owned in-flight/version marker now brackets native acceptance through publication; this completes the original finding rather than widening scope. |
| Full-gate / parent: avoidable pre-first-play head query | medium | The unchanged obsolete-finish test failed in the full suite and all nine prematurely continued repetition runs because its drain-head gate was intercepted before play. Remove the query using the fresh-driver zero-head invariant; this also removes the extra unchecked native-call interval. Original assertions remain unchanged; focused blind review closed the repair. |

Before repair, **20 tests / 5 failures** (`artifact://1690`): four scheduler
boundary regressions and the latch-controlled concurrent-refill snapshot.
After the cohesive repair, targeted JVM plus debug/test-APK build passed
**53 tests / 4 suites / zero failures, errors or skips** (`artifact://1693`).
Original red and green JUnit XML is preserved outside the build directory.

Blind follow-up found that the first consistency repair was incomplete:
**1 test / 1 failure** (`artifact://1702`) with the PCM worker blocked in its
postwrite underrun query. The complete write-interval repair then passed
**54 tests / 4 suites** plus debug/test-APK builds (`artifact://1707`).
`postwrite-red-junit/` and `postwrite-green-junit/` retain those exact results.
The earlier native pass below preceded this final production repair; final
post-repair native/software evidence is recorded separately rather than treating
that earlier run as proof of changed code.

### Actual Android proof and retained failed assumption

Direct serial-targeted instrumentation on a newly created empty API 36 ARM64
AVD passed **1 native test**, using the production factory, sink and sampler:

- Deliberately withholding source PCM caused an **actual native underrun count
  of 1**. The playback head stayed **16000 → 16000** while lead read zero.
  Refilling restored **15637 queued response frames**. This is controlled
  source-starvation/native-API evidence, not acoustic hardware acceptance or an
  injected fake underrun count.
- Native pause reported `playing=false`. Entry head **16363**, then final head
  **16726**, yielded **15274** queued frames, exactly **32000 − 16726**.
  The observed additional **363 frames after pause** is retained without
  claiming its mechanism or an instantaneous hardware-pause guarantee.
- **21** journal samples had actual `t_ms` values
  **0, 251, 500, 750, 1000, 1251, 1500, 1750, 2001, 2250, 2501, 2751,
  3001, 3250, 3503, 3750, 4000, 4251, 4502, 4751, 5005**.
  The fixture asserted strictly advancing timestamps, every expected cadence
  slot, the final bounded jitter, and **zero additional native
  play/pause/prepare/volume calls** during sampling.
- First-play journal: `audio output cushion started lead_ms=1000 reason=threshold`,
  once. Terminal journal: `voice response ended underruns=1`.
- The first native attempt failed **1 test** because its fixture incorrectly
  expected the paused entry head 16363 to remain exactly frozen; Android returned
  16726 after the window. This was not discarded or rerun to confirm. The revised
  proof checks head-derived queued arithmetic and actual playing state instead
  of that disproved assumption. Both fixture versions and the failure are saved.
- No UI changed, so this slice claims native/runtime diagnostics proof, not an
  additional rendered UI acceptance run. No Gradle `connected*` task ran.

Evidence root:
`/var/folders/cb/3pkc1ncx58j1xq63ktd8xftw0000gn/T/home09-lead-proof-vmt237xz/`

- `initial-review.diff`, `review-repaired.diff`: reviewed snapshots.
- `targeted-junit/`, `review-red-junit/`, `review-green-junit/`: preserved XML.
- `android-smoke-initial.log`, `Home09LeadSmokeTest-initial.kt`: failed assumption.
- `android-smoke-repaired.log`, `Home09LeadSmokeTest-final.kt`: complete native
  proof and final off-tree fixture.

The temporary repository fixture was removed after proof. The exclusively owned
`emulator-5556` exited with code 0; AVD `hermes-home09-lead-20261015`, its newly
created empty AVD home and the port claim were deleted. Emulator 5554, owner
AVD/userdata, other worktrees and protected HOME-08 recovery copies were untouched.
Physical/OS-death/Pixel tuning acceptance, API-specific unknown-route return,
and requirement 7's absent `mediasession_writes` accounting remain open.
HOME-09 stays **in-progress**.

### Final write-interval repair and native proof

All three independent formal review lenses closed after the complete write
interval repair. Blind and edge follow-ups explicitly verified the marker
precedes the native write, positive-counter publication closes it atomically,
and zero/error/exception/cancel/recovery cleanup owns generation, driver and
version. Coverage follow-up closed the scheduling-boundary gaps.

Final focused tests and debug/test APK build passed **54 tests / 4 suites,
zero failures/errors/skips** (`artifact://1711`), including the explicit
cancellation-marker invalidation. These checks and the second native proof
preceded the full-gate incident and final query-removal repair recorded below.

Because the first owned AVD had already been deleted before the follow-up
finding arrived, the final production proof used a **second newly created empty**
AVD, `hermes-home09-lead-final-20261015`, not a clone or restored snapshot.
Direct instrumentation passed **1 test / zero failures** on `emulator-5556`.
`android-smoke-write-interval-final.log` retains complete output:

- Controlled source starvation again produced an actual native underrun of **1**
  and stalled head **16000 → 16000**; refill lead was **15637** response frames.
- Held entry head/lead were **16363 / 15637**; final native head/lead were
  **16726 / 15274**, with native `playing=false` and correct `32000 − 16726`
  queued-frame arithmetic. The previously observed post-pause head advance is
  not hidden or replaced by a pause/acoustic guarantee.
- All **21** native sample timestamps were asserted in their expected slots:
  **0, 250, 500, 750, 1000, 1250, 1500, 1750, 2000, 2250, 2500, 2750, 3000,
  3250, 3502, 3752, 4000, 4251, 4505, 4750, 5002** ms. Native control-call
  delta remained **0**. First cushion remained a single threshold/1000 ms line;
  the once-per-reply terminal summary reported **underruns=1**.
- The repository fixture was removed again before the fixture-free gates.
  The second owned emulator exited **0**, its AVD definition, isolated empty
  home and port claim were deleted. Both disposable environments were created
  independently; the protected owner emulator/data/recovery copies stayed
  untouched throughout.

### Full-gate incident: avoidable pre-first-play query

The first fixture-free full run observed **721 tests / 52 suites / 1 failure**:
`AudioSinkPauseTest.obsolete_finish_cannot_clear_the_next_turn_preaudio_hold_or_duck`
at line 245 expected **1** native play but observed **0**. Exact XML is retained
in `full-jvm-final/testDebugUnitTest/TEST-com.achappell.hermesrelay.AudioSinkPauseTest.xml`.
The verification worker incorrectly continued into repetition instead of
stopping at this failure. The parent stopped that run after **9 failed
iterations**. All nine preserved XML files contain this same exact test and
assertion; they are retained under `final-repetition/flake-gate/`, with
`prior-full-and-repetition-failure-attribution.json`. This is a deterministic
regression, **not flakiness**, and these failed gates are not superseded out of
the record by subsequent success.

Cause: the new cushion diagnostic queried the native playback head immediately
before the first native `play()`. The existing test deliberately blocks the
first drain-head query while a newer held/ducked turn is admitted. The added
query intercepted that gate **before** the old native play, while the native
control lock was held; cancellation waited until the five-second gate timeout
and the assertion correctly exposed zero old plays. The query also introduced
an unnecessary native-call interval after the last pre-play ownership check.

The repair removes that native query entirely. Production admission and
replacement each construct a fresh AudioTrack, `preparePlayback` does not play,
and `startPlayback` excludes already-started drivers. Therefore the native
head is zero at this first-play boundary and the cushion lead is exactly
current-driver response frames divided by sample rate. No test assertion,
gate, expected play count or timeout was changed. The focused blind follow-up
verified these invariants and closed the finding.

After repair, **73 tests / 5 suites / zero failures/errors/skips** and APK
builds passed (`artifact://1730`); XML is in `cushion-query-fix-junit/`.
The third independent empty AVD, `hermes-home09-lead-cushion-20261015`, provided
the post-fix native run: **1 test passed**, exact output
`android-smoke-cushion-query-final.log`. It again observed actual native underrun
**1** under controlled source starvation; stalled head **16000**, refill lead
**15637**, final paused head **16726** and response lead **15274**, with
**zero extra native control calls**. Its 21 asserted actual timestamps were
**0, 250, 500, 751, 1009, 1250, 1511, 1751, 2000, 2250, 2500, 2751, 3000,
3250, 3501, 3754, 4003, 4250, 4500, 4750, 5001** ms. The first cushion event
and final underrun summary were each emitted once.

The temporary repository fixture was removed before the post-fix full gate.
The final repetition procedure runs the existing evidence-retaining script
one iteration at a time, preserving each result off-tree, and stops immediately
on any failed iteration rather than allowing its ordinary N-run loop to continue.

### Post-incident fixture-free full verification

- Full `testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest`
  passed (`artifact://1735`): **721 tests / 52 suites, zero
  failures/errors/skips**. Complete XML and `counts.json` were copied into
  `full-jvm-after-cushion-query-fix/` **before** the repetition gate cleaned
  Gradle's reports.
- The two new permanent suites contain **24 tests**. Subtracting those from
  the observed full result gives the unchanged baseline inventory of
  **697 tests / 50 suites**; this is an inventory derivation, not a claimed
  rerun of the historical baseline or its unattributed iteration-23 failure.
- APK metadata passed: min SDK **26**, version **0.3.1 (301)**, backup disabled
  with extraction rules and **16 KB alignment**. Signer comparison was not
  exercised because `EXPECTED_SIGNER_SHA256` was unset.
- Final debug and Android-test APKs were rebuilt fixture-free. Every DEX entry
  was scanned (**7** debug, **4** Android-test); `Home09LeadSmokeTest` is absent
  from both. `fixture-free-apks-final.json` records their SHA-256 hashes;
  `metadata-after-cushion-query-fix.log` contains the metadata receipt.
- Final fail-fast repetition passed **30 consecutive runs × 270 tests =
  8100 executions**, zero failures/errors/skips across **12 suites**. Every
  iteration's full XML, Gradle output and counts are preserved in
  `flake-after-cushion-query-fix/iteration-01/` through `iteration-30/`;
  `summary.json` records all iterations and the exact class filter.
- The final third owned AVD was retained until the full suite and repetition
  passed, then explicitly stopped on `emulator-5556` (exit **0**) and deleted
  with only its isolated empty AVD home and port claim. All three temporary
  AVDs were newly created, never cloned from owner state.
- All formal review findings are closed. Requirement **6** is software/native-
  emulator verified; parent HOME-09 remains **in-progress**. Requirement 7's
  missing media-session accounting, physical/acoustic/Pixel tuning, natural
  OS-death and API-specific unknown-route acceptance remain open. The historical
  unattributed Standard iteration-23 failure is still unattributed; this clean
  gate does not rewrite that history or the current slice's preserved failures.

## Approved media-session accounting slice — 2026-10-15

Requirement **7** only. Playback-state writes remain on `hermes-voice-card`;
notification-only changes do not rewrite the unchanged native playback state.
Initial publication and each actual Pause/Play transition retain the existing
Play/Pause/Stop actions. Buffering defaults remain **1000/1500 ms**.

### Observation window and terminal ordering

- One application-owned monotonic counter increments only after a successful
  native `MediaSession.setPlaybackState` return, never for UI requests,
  notifications, metadata, PCM chunks or lead-sampling ticks.
- Actual background entry captures a counter baseline before entry-triggered
  service work. A native write queued before entry but completed afterward
  counts. Duplicate stop and configuration recreation do not reset the baseline.
- Foreground return retains the last entry's observation window. Re-entry resets
  its baseline; a new response admitted while backgrounded inherits the current
  entry baseline. Counts are cumulative within a background stay, not per reply;
  arbitrarily many manual state changes need not produce only a handful of writes.
  A foreground-only response has no observation window and reports zero scoped
  writes; this differs from a background observation window with zero writes.
- Service recreation does not reset the process counter. A fresh native session
  requires its first state write, which counts naturally if within the window.
- The audio sink freezes the delta at its existing completion/cancellation
  boundary. It explicitly **excludes** a later adapter/runtime `AudioEnded`
  terminal service update and native writes completing after that boundary.
  There is no service barrier that could incorrectly precede that update.
  Final underrun collection stays on the existing sink control worker and emits
  the same single `voice response ended underruns=N mediasession_writes=N` line.
- Per-response handles own baselines and frozen results, not write admission.
  Old finalization cannot reset a new response. No main/audio/feed thread waits
  for the service or a native call; counter monitors protect scalar state only.

### Implementation and acceptance

- `MediaSessionWrites.kt`, `HermesRelayApplication.kt`: injected process counter,
  response-owned baseline/finalization and read-only native-write/thread evidence.
- `HomeRuntime.kt`: baseline before background service dispatch, foreground and
  duplicate/configuration lifecycle behavior.
- `HomeVoiceService.kt`: native success recording and playback-state-only cache.
- `AndroidAudioSink.kt`: admission ownership and frozen count on its existing
  once-only underrun summary; no new summary or per-chunk work.
- Given queued pre-entry work, when it successfully writes after entry, then its
  native write counts. Given re-entry, only writes after the newest baseline count.
- Given metadata-only updates, when the card changes, then controller state and
  actions remain valid without a playback-state write.
- Given cancellation or completion, when later terminal service work and a new
  response arrive, then the old summary remains frozen and the new count is owned
  independently. Underruns remain present exactly once.
- Permanent JVM boundary/sink/runtime tests and native production-service
  instrumentation exercise these consumers. Emulator evidence is controlled
  input, not physical headset, Bluetooth, acoustic or Pixel tuning acceptance.

### Formal review triage — media-session slice

| Finding | Disposition | Repair and evidence |
| --- | --- | --- |
| Blind review | No actionable finding | Parent reviewed the initial complete diff. |
| Edge-case review | No actionable finding | Parent reviewed the initial complete diff. |
| Coverage 1: counter-only dedup oracle | Medium; repaired and verified | The native test independently reads the stored **Paused** `PlaybackState.lastPositionUpdateTime` after the changed notification is observed, crossing the OS clock's millisecond boundary before the update. A mutation moving only the native call outside the guard while keeping accounting guarded failed this independent oracle. Playing getters synthesize timestamps and are deliberately not used as a publication oracle. |
| Coverage 2: service recreation not exercised | Medium; repaired and verified | Native instrumentation stops and recreates the actual service with the same response handle and entry baseline, checks a fresh controller/token and initialized actions/state, and includes both sessions' initial successful writes in the one final summary (`mediasession_writes=4`). Controlled lifecycle input is not natural OS-death recovery proof. |

The proposed delayed-card `shown == latest` guard was removed from this slice:
it addresses a pre-existing lifecycle race rather than the measured identical
native projection writes, and no new production behavior is justified by the
two accepted coverage findings.

### Observed verification chronology and receipts

All receipts below are under the private evidence directory
`/var/folders/cb/3pkc1ncx58j1xq63ktd8xftw0000gn/T/home09-media-evidence-lptzkq3j/`.
The fresh baseline was PR166 merge `62987da8d10bde71918a1bd95666a80d502a0caf`;
GitHub confirmed its merged head `0bf19fdc6e662f4413df1de8601a0bf008dc2106`.

1. Initial focused JVM verification passed **39 tests / 3 suites** (`focused/`);
   the later queued-summary regression brought focused coverage to **40 tests**.
   `coverage-repairs/xml/` retains those full XML results.
2. Initial actual production-service native proof passed **1 test**
   (`native-initial.log`), with the real controller Playing→Paused→Playing,
   notification actions/token, metadata-only dedup, zero main-thread writes and
   `voice response ended underruns=0 mediasession_writes=3`.
3. Deliberately restoring the old unconditional setter and counter call failed
   the metadata-only count: **expected 1, actual 2**, one native test failure
   (`native-unconditional-mutation/instrument.log`). The condition was restored.
4. The first independent oracle attempt failed on a **Playing getter timestamp**
   (`coverage-repairs/instrument.log`, expected 247873, actual 247904).
   Android 16 `MediaSessionRecord.getStateWithUpdatedPosition()` synthesizes a
   current timestamp for Playing getters; it returns stored native state for
   Paused. The exact framework source is saved as
   `MediaSessionRecord-android16.java:971-1003`, fetched from the
   [Android 16 framework source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/core/java/com/android/server/media/MediaSessionRecord.java).
   This was a test-oracle assumption, not a production defect. The corrected
   Paused oracle plus real service recreation passed (`paused-native-oracle/`).
5. The stronger deliberate mutation moved **only** the native setter outside the
   dedup guard, keeping accounting/cache guarded. The independent Paused oracle
   failed **expected 376973, actual 376990**, while preceding counter assertions
   passed (`native-counter-blindspot-mutation/instrument.log`). The native setter
   was restored inside the guard. Both deliberate mutation failures are evidence,
   not shipped changes or a fabricated API compile-red claim.
6. A subsequent native run failed because its pause barrier accepted the previous
   **Listening** notification after native state had already changed to Paused
   (`full-final/instrument.log`). The predicate now requires the new notification's
   expected text and actions as well as controller state; all original
   post-barrier assertions remain. No production behavior was changed for either
   test-observation failure. Every failure stopped further Gradle work until its
   actual cause was diagnosed and the test/mutation repaired.
7. Final fixture-free full JVM/build/lint/Android-test APK verification passed:
   **729 tests / 53 suites, zero failures/errors/skips** (`full-final/gradle.log`,
   `full-final/xml/`). After the notification-test repair, the full command
   passed again with unchanged JVM results retained in
   `final-barrier-repair/xml/` and `counts.json` **before** the repetition gate.
   Metadata passed: min SDK26, version0.3.1 (301), backup/extraction rules and
   16KB alignment. Signer comparison was not exercised because
   `EXPECTED_SIGNER_SHA256` was unset.
8. Final restored native proof passed **1 test** on the exclusively owned new
   empty API36 AVD, serial `emulator-5560`
   (`final-barrier-repair/instrument.log`). The actual diagnostic receipt
   `final-barrier-repair/native-diagnostics.log:18` is
   **`voice response ended underruns=0 mediasession_writes=4`**; lines6/16 show
   both native session registrations. Assertions independently preserve Paused
   native state on notification-only changes, verify real Pause/Play/actions,
   fresh session/controller initialization, four successful writes and zero
   main-thread writes across controlled service recreation.
9. The fail-fast gate passed **30 consecutive single-iteration invocations ×
   265 tests = 7950 executions**, zero failures/errors/skips. Every invocation
   used `scripts/run-flake-gate.sh … 1`; all XML, Gradle logs and counts were
   archived before the next invocation, under `flake-final/iteration-01/`
   through `iteration-30/`. `flake-final/summary.json` retains the exact filters.
   The **12 exercised JVM suites** were `MediaSessionWritesTest`,
   `AudioPlaybackLeadTest`, `AudioLeadSamplerTest`, `AudioSinkBufferingTest`,
   `AudioSinkRecoveryTest`, `AudioSinkPauseTest`, `AndroidAudioSinkFramesTest`,
   `ResponseAudioPlaybackTest`, `HomeRuntimeRetentionTest`,
   `HomeRuntimeVoiceRetentionTest`, `OkHttpRelaySessionClientTest` and
   `OkHttpStandardSessionClientTest`. The additional requested
   `HomeRuntimeRecreationTest` filter names an instrumentation class, so it
   contributed no JVM suite and is not claimed as repetition coverage.
10. No temporary repository fixtures were introduced; permanent native behavior
    coverage remains. Both mutation edits were removed before the final build.
    `final-apks.json` records both fixture-free APK hashes. After production,
    native and all gates passed, the owned AVD
    `hermes-home09-media-20261015210903` was stopped (exit0), deleted (exit0),
    and only its isolated empty home and `/tmp/hermes-emulator-5560.claim` removed.
    No Gradle connected task was used. Owner emulator5554, original AVD/data,
    recovery copies and other worktrees were untouched.

All three formal review lenses and the coverage follow-ups are closed.
Requirement7 is software/native-emulator verified; HOME09 remains **in-progress**
for physical/acoustic/Pixel tuning, natural OS-death and API-specific unknown-route
acceptance. Historical Standard iteration23 remains unattributed. This gate
does not reinterpret that history or the preserved PR166 preplay-query evidence;
the fresh-driver zero-head fix and original regression assertions are unchanged.
