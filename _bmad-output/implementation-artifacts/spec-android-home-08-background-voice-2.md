---
title: 'ANDROID-HOME-08: keep a Home reply and a hands-free voice session alive after the app stops'
type: feature
created: '2026-10-11'
status: in-review
route: dispatch
baseline_commit: e8b59f36450eb99cb776e8c860e35d28a6d4a8d2
review_loop_iteration: 0
context:
  - '_bmad-output/implementation-artifacts/spec-android-home-08-background-voice.md'
  - '_bmad-output/implementation-artifacts/spec-android-home-06-backgrounded-transport-disconnected-2.md'
  - '_bmad-output/implementation-artifacts/spec-android-home-09-reply-audio-output-resilience.md'
  - '_bmad-output/implementation-artifacts/spec-android-watch-f1-listening-without-binding.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** On `main` nothing keeps a reply alive once the app stops: no foreground service, no `MediaSession`, no audio focus, no wake lock, and playback is a bare `AudioTrack` (`AndroidAudioSink.kt`). Locking the phone mid-reply killed the reply (owner device test, 2026-10-11). `ANDROID-HOME-06` now closes an idle transport on stop, so in-flight work has to be kept alive explicitly. Parity with `IOS-HOME-07` (issue #72); decisions D1 (background hands-free) and D2 (60 s idle) are already decided in the ticket spec.

**Approach:** One runtime-owned retention predicate (`none | reply | voiceSession`) decides what a stop does. While it is not `none`, a foreground service keeps the process, socket and audio alive and shows a content-free media notification with Pause/Play and Stop. When it returns to `none` while the app is stopped, one idempotent end path runs the `ANDROID-HOME-06` teardown and releases everything, including the microphone. Delivered as two stacked PRs from one spec: **08a** reply retention, **08b** voice-session (hands-free) retention.

## Boundaries & Constraints

**Always:** The service is started while the app is visible, never from the stop callback; a `microphone` type is added only while hands-free is armed or capture is live and `RECORD_AUDIO` is granted, and dropped the moment neither holds. The mic is never started from the background. `onPause`, dialogs, the shade, multi-window and a configuration change never count as a stop; only `ON_STOP` and task removal do. Backgrounding makes no sink, focus or `AudioAttributes` change while a reply plays. One end path, idempotent, journaled once, releases: capture and hands-free, service and notification, `MediaSession`, wake lock, audio focus. No replay or resubmit under any path; an unconfirmed turn stays offered. Media notification and journal carry no prompt, reply, handle or claim ref. Platform calls sit behind small interfaces with fakes; all timers use injected clocks.

**Never:** No `ExoPlayer`/Media3 or change to the `AudioTrack` feed path. No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, no silent-audio keep-alive, no push/CallKit-style wake, no alarm or WorkManager. No route-change, ducking, track-recreation, cushion or lead-sampling work (`ANDROID-HOME-09`). No Play Console declaration (`ANDROID-REL-02` declined). Do not touch `build.gradle.kts` beyond what a new dependency would need (none expected), proguard, `AndroidTurnState`, release PR #27, or the `ANDROID-HOME-04/06` ladder pacing.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Stop, nothing retained | `ON_STOP`, retention `none` | exactly `ANDROID-HOME-06` (close idle transport, `Disconnected`) | n/a |
| Stop mid-reply | reply streaming or audio pending/playing, incl. text terminal before audio end | socket, audio and turn pipeline stay; service up; whole reply plays | transport drop: see below |
| Reply ends while stopped | audio drained or failed, retention → `none` | end path once: park claim, `Disconnected`, service and card removed | notification already gone: no-op |
| Return to foreground, retained | `ON_START` with live transport | no reconnect, no resubmit, UI shows full reply, playback uninterrupted | n/a |
| `ON_PAUSE` / config change | shade, dialog, rotation | nothing stops | n/a |
| Notification Pause / Play | reply playing / paused | output only pauses/resumes; Home keeps streaming; paused hold ≤ 60 s | 60 s paused: idle end |
| Notification Stop | any retained state | interrupt (best effort) then park; hands-free disarmed, capture cancelled, card cleared | interrupt unsent: still tears down |
| Hands-free armed, stop | armed, capture or between windows | stays up ≤ 60 s idle; follow-up within 60 s submits once; mic never started from background | permission revoked/denied: disarm |
| Idle 60 s | armed, no capture, no reply (or reply paused) | disarm, capture stopped, indicator clears, end path | n/a |
| Focus loss or call | transient/permanent loss while retained | mic session ended, capture cancelled, reply paused/stopped; resume only if transport live and timeout not fired; mic never re-armed | gain denied: end path |
| Transport drops while retained | socket lost | retention ends (hands-free disarmed, capture cancelled), unconfirmed turn offered, no resubmit, end path | foreground: HOME-04 ladder as today |
| Task removed | swipe from Recents | interrupt best effort, park, end path once | already ended: no-op |
| Conversation switch while armed | New/Resume while mic live | hands-free disarmed and capture cancelled before the switch | n/a |
| Own voice | hands-free armed during/after a reply | no capture start until output drained plus tail; echo never submits a turn | n/a |

**Decisions (director review in place of owner checkpoint; owner to correct):** (1) One spec, two stacked PRs: 08a reply retention (this PR), 08b hands-free voice session. (2) The D1 feasibility check opens 08b. (3) Platform `MediaSession` plus `Notification.MediaStyle`, no new dependency. (4) The card is content-free: neutral title, never the prompt; showing the conversation title when unlocked is deferred. (5) Approved deviation: the notification and foreground service stay until retention ends, because an FGS must show one; the full card is published after a 750 ms off-main deferral from `ON_STOP`. (6) `POST_NOTIFICATIONS` is asked once at the first reply on API 33+ via `RuntimePermission` and never blocks the reply; the one-line rationale is deferred. (7) Stop and idle end are interrupt best effort, flushed with a graceful close, then park via the HOME-06 close; Stop is not Disconnect. (8) Pause is output-only with a reason set (`user`, `focus`); sink deadlines freeze while paused; a pause held 60 s while stopped ends the reply. (9) HOME-08 ships a minimal audio-focus controller; HOME-09 keeps ducking, delayed gain, route events, track recreation, cushion, lead sampling. (10) Approved deviation: no background reconnect; a transport loss ends retention. (11) Partial wake lock, 10 min timeout, released by the end path; no `WifiLock`. (12) `Service.onTaskRemoved` forces the end path; Back/finish keeps a retained reply. (13) 08b owns: own-voice tail (500 ms placeholder), the conversation-switch-while-armed fix, the `microphone` type and hands-free retention. (14) Device: `adb install -r` only after a read-only signature check; never uninstall, `pm clear` or connected tests.

</frozen-after-approval>

## Code Map

- `.../HomeRuntime.kt` -- `appBackgrounded` (HOME-06 `idle` expression, ~255) becomes `retention == None`; `hasAcceptedTurn` (183, covers a text terminal waiting on audio because the client keeps the turn until audio settles); `handsFree` state (155); `disconnect` (479) is the interrupt-then-close order to copy; `switchConversation` (506); `activityDestroyed`/`tearDown` (559/582). Add `retention`, the end path, idle timer, task-removed entry, audio-interruption entries.
- `.../HomeReconnect.kt` -- `HomeLifecycleCoordinator`: reintroduce a read-only `isForeground` (removed in HOME-04 review for lack of a reader); `appBackgrounded` generation guards the end path.
- `.../AndroidCaptureController.kt` -- `armHandsFree` (143) opens capture at once; `onTurnSettled` (168) reopens after `Complete`; add output-aware wake suppression plus tail; `disarmHandsFree` and `cancelCapture` (258) are the mic-release calls; `PlatformSpeechInput.cancel`/`release` destroy the recognizer.
- `.../AndroidAudioSink.kt` -- `AudioTrackAudioSink` write loop (`writeStallDeadlineMillis` 3 s), `drainQueued` (500 ms stall, 30 s timeout): add `pause()/resume()` that freeze those deadlines. Interface `AndroidAudioSink` and `RecordingAudioSink` gain the same.
- `.../AndroidClientPort.kt`, `OkHttpRelaySessionClient.kt` -- port `pauseOutput()/resumeOutput()` (defaults `Unit`); reuse `hasActiveTurn`, `interruptTurn`, `closeForLifecycle`, `hasLiveTransport` from HOME-06. Do not change `releaseHeldClaim`.
- `.../AndroidPlatform.kt` -- `notificationsPermitted` and `foregroundServiceSupported` already exist and are unused: reuse. `RuntimePermission.kt`/`PermissionPrompt.kt` for the `POST_NOTIFICATIONS` ask.
- `.../HermesRelayApplication.kt` -- wire the real platform controllers into `HomeRuntime` (`createHomeRuntime`).
- `.../MainActivity.kt` -- contextual notification prompt; no lifecycle `DisposableEffect`.
- New: `BackgroundRetention.kt` (enum, predicate, once-only end path, idle timer), `HomeVoiceService.kt` (thin FGS shell; every `onStartCommand` calls `startForeground`; actions START/PAUSE/PLAY/STOP; `onTaskRemoved`), `VoiceNotification.kt` (channel `voice_session`, low importance, immutable `PendingIntent`s to the service), `MediaSessionPublisher.kt` (private `HandlerThread`, write on change only), `AudioFocusController.kt`, `ForegroundServiceController.kt` (seam), `PartialWakeLock` wrapper.
- `AndroidManifest.xml` -- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`; `<service android:exported="false" android:foregroundServiceType="mediaPlayback|microphone" android:stopWithTask="false">`. `compileSdk`/`targetSdk` are 37, `minSdk` 26; FGS type rules apply from API 34.
- Tests: `BackgroundRetentionTest` (predicate matrix, end path once, idle timer on a manual clock), `HomeRuntimeBackgroundVoiceTest` (stop ordering with `QueuedExecutorService`/`ManualPacer`, switch while armed, focus entries, own-voice tail), sink pause tests in `AndroidAudioSinkFramesTest` style, fakes for the four platform seams; extend `HomeRuntimeBackgroundTransportTest`. Run `scripts/run-flake-gate.sh` for the ordering tests (30 runs).

## Tasks & Acceptance

**Execution (08a):**
- [x] `BackgroundRetention.kt`, `HomeRuntime.kt`, `HomeReconnect.kt` -- predicate, end path, stop/resume/task-removed ordering -- replaces the HOME-06 idle test
- [x] `AndroidAudioSink.kt`, `AndroidClientPort.kt`, `OkHttpRelaySessionClient.kt` -- output pause/resume with frozen deadlines
- [x] `HomeVoiceService.kt`, `VoiceNotification.kt`, `MediaSessionPublisher.kt`, `AudioFocusController.kt`, wake-lock wrapper, `AndroidManifest.xml`, `HermesRelayApplication.kt`, `MainActivity.kt` -- service, card, controls, focus, permission flow
- [x] Tests above; `CHANGELOG.md`; red-first against the HOME-06 branch; device journal per the ticket's device list

**Execution (08b):**
- [ ] Device feasibility check first (Open Question 2), then `microphone` type handling, hands-free retention and 60 s idle timer, interruption entries, wake suppression plus tail in `AndroidCaptureController.kt`/`VoiceTimings.kt`, `switchConversation` fix, mic-release assertions on every end reason

**Acceptance Criteria:**
- Given a reply playing, when the screen locks or the app stops, then the whole reply plays and afterwards Home shows the claim parked (`client_disconnected`) and the app `Disconnected`.
- Given a reply whose text completes before its audio, then retention stays `reply` and the socket stays open until the audio terminal and drain.
- Given only `ON_PAUSE`, a dialog or rotation, then nothing stops.
- Given retention ends while stopped, then the service, card, wake lock, focus and recognizer are released and the socket closes once (journal shows one end line).
- Given hands-free armed then stopped, then it continues up to 60 s idle, a follow-up within 60 s submits once, and at 60 s the mic indicator clears; the mic is never started from the background.
- Given notification Stop, Pause or Play, then Stop ends and clears the card, Pause/Play toggle output only; the card never shows the prompt.
- Given a call or focus loss, then capture ends, the reply pauses, and nothing re-arms the mic.
- Given the transport drops while retained, then no resubmit occurs and the unconfirmed turn is offered.
- Given hands-free armed while Hermes speaks, then no capture starts until drain plus tail.
- Given a conversation switch with the mic live, then the mic is released first.
- Given notification permission denied, then the reply still plays through lock.

## Implementation Notes

08a only; 08b is not started. Retention is `reply` only (`BackgroundRetention` has no voice-session value yet). Deviations from the provisional design, all recorded in the PR: the card title is always neutral; no notification-permission rationale line; the foreground service and notification stay until retention ends (approved); no background reconnect (approved); `retention` also requires `Connected` and latches a stopped binding so late events cannot re-enter it. A 1 s recheck while retained covers the unordered terminal event and the client clearing its turn. Pause is re-applied by the runtime when a held reply's next stream starts. Stop with an interrupt closes gracefully (`closeAfterInterrupt`) so the queued frame is flushed. `HomeLifecycleCoordinator.isForeground` is back, now with readers, and defaults to true. No device was attached for this work, so every device check is pending.

## Spec Change Log

## Review Triage Log

Three review layers. `patch` rows are fixed; `defer` rows are in `deferred-work.md`.

| Finding | Verdict | Evidence / route |
|---------|---------|------------------|
| A stopped reply re-entered retention on the next turn event (service restart, second end line, second close) | high, patch | Stopped binding latched; two tests. |
| Deferred runtime teardown never ran after a background stop (turn reset to Idle is not terminal) | high, patch | `tearDownIfDue` also treats a cleared turn as settled; test. |
| Stop path closed the socket right after queuing the interrupt; `cancel()` discards queued frames | medium, patch | `closeAfterInterrupt` closes gracefully first; adapter test sees a close frame. |
| Stopped prompt stayed in `lastRequest` and could be offered as unconfirmed | medium, patch | Cleared with the reset; test. |
| New active state sent to a service that was ending was dropped | medium, patch | Controller starts a new service for an ending instance; a restart cancels the pending stop. |
| A control tap replaced the media card with a bare notification | medium, patch | Re-assert uses the card when published. |
| Sink pause could race `startPlayback` | low, patch | Pause re-checked after `play()`. |
| Drain-loop pause branch, client delegation, in-flight-submit stop, superseded end, teardown while retained untested | medium, patch | Tests added for each. |
| Delayed card publish ignored `ended` | low, patch | Checked. |
| Foreground-service start failure is not fed back to the runtime | medium, defer | Logged as `fgs start failed`; device check shows whether it occurs. |
| A held reply's new stream plays until the queued re-pause runs | low, defer | Covered by the 1 s startup buffer unless the worker is busy; revisit with HOME-09's sink changes. |
| Denied audio focus plays on | low, defer | Delayed focus gain is HOME-09. |
| `outputPaused` unread by the UI | low, defer | No in-app pause indicator in 08a. |

## Design Notes

- Retention: `reply` = `hasAcceptedTurn || initiationInFlight || resendInFlight`; `voiceSession` = hands-free armed or capture live; `none` otherwise and whenever the user disconnected. Service type is `mediaPlayback`, plus `microphone` only for `voiceSession`; recompute on turn, initiation, capture, hands-free and connection changes, deduped.
- Service shell: runtime-owned state flows to the service through intent extras (retention, paused, neutral title) written only on change; the service never owns runtime objects.
- Starting a foreground service, especially a `microphone` one, from the background is restricted on current Android [INFERENCE; verify on the Pixel's release]; hence start at turn submit, first audio or arming, never at `ON_STOP`.

## Verification

**Commands:**
- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` -- expected: success
- `scripts/run-flake-gate.sh BackgroundRetentionTest,HomeRuntimeBackgroundVoiceTest,HomeRuntimeBackgroundTransportTest 30` -- expected: 0 failures
- `scripts/check-apk-metadata.sh`; `python3 -m unittest discover -s tests -p 'test_bmad_issue_tracking*.py'`; `git diff --check` -- expected: pass

**Manual checks (Pixel; install -r only after a read-only signature check, never uninstall/`pm clear`/connected tests):**
- The ticket's device list 1-10 (lock mid-reply, shade, hands-free 60 s, own voice on speaker, notification controls, focus, call, headphones, swipe-away, journal order), plus: mic indicator clears at every end reason; media controls with notifications denied.
