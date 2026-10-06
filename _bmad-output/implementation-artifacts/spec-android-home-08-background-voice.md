---
id: ANDROID-HOME-08
title: Keep Home reply and voice conversations running in the background
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-04
  - android:ANDROID-HOME-06
  - android:ANDROID-HOME-07
  - home:HOME-NW-18
ios_reference: 'IOS-HOME-07 (d6dd1db, a147cd5, efff91d, 8482051, 1235243, a7bd524, aabb275; PR #122)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/72
---

# ANDROID-HOME-08 — Background reply and voice retention

Parity with `IOS-HOME-07` and the background-audio fixes in iOS PR #122 (v0.7.0). iOS spec `spec-ios-home-07-background-voice.md` and the follow-up sections of `validation-ios-home-07.md` are the behavioral reference.

## Background (device-observed on iOS)

Locking the phone or switching apps mid-reply cut the spoken answer off: lifecycle teardown stopped playback and closed the Home socket on every non-active phase. After retention was designed, device journals (builds 15–19, 2026-10-04/05) found, in order: (1) an engine stopped by the OS while the app was reconfiguring a live audio session; (2) the whole stack being torn down at `.background` because the lifecycle coordinator was bound to an orphaned voice coordinator (`ANDROID-HOME-07`); (3) a transient stutter at background entry from main-thread contention (Now Playing/MediaPlayer registration plus per-chunk writes) meeting a player with no cushion; (4) a one-second pause caused by the lock-screen registration blocking the main actor even after it was deferred 750 ms. The fixes were: retention that actually sees the in-flight reply; never reconfigure a live audio session at background entry; coalesced single engine restart after a configuration change; a ~300 ms playback cushion (500 ms cap); media-session registration on a private serial queue, deduped and deferred 750 ms; content-free journal lines that name whichever component closed the socket.

## Android today (verified against `main` at `3e10ae2`)

- No foreground service, no `FOREGROUND_SERVICE*` or `POST_NOTIFICATIONS` permission, no `Service` of any kind, no `MediaSession`, no audio focus request (`AudioTrackAudioSink` builds an `AudioTrack` with `USAGE_MEDIA` / `CONTENT_TYPE_SPEECH` and never calls `AudioManager.requestAudioFocus`), no `Application` class (see `ANDROID-HOME-07`).
- PR #61 documents that Android cuts a backgrounded app's network ("Software caused connection abort"); a reply in flight therefore dies shortly after the Activity stops, and the app only reconnects on return.
- Hands-free (`1-A-6`) and capture run inside the Activity (`PlatformSpeechInput`, `AndroidCaptureController`). Android restricts background microphone access; see decision D1.

## Decisions to confirm before implementation (iOS decisions are approved only for iOS)

| # | iOS decision (2026-10-04, approved by Amanda) | Android default proposed here |
|---|---|---|
| D1 | Continue only a turn or hands-free session started in the foreground; never start the mic from the background. | Same. Reply retention is required. Background hands-free needs a foreground service of type `microphone` that must be started while the app is visible, and the recognizer may still refuse to run from the background. [INFERENCE — device-gate it.] If it proves infeasible, hands-free disarms on stop and the UI says so; this does not block the reply-retention acceptance. |
| D2 | 60 s idle timeout after the last voice activity (hands-free armed), injected clock. | Same; applies only if D1 background hands-free ships. A paused reply is also covered by the timeout. |
| D3 | Lock-screen card titled from the conversation with Pause/Play and Stop. Never display the user's prompt. | Media notification with Pause/Play and Stop; title from Home's current conversation title, else neutral "Hermes conversation". |
| D4 | Other audio: duck in foreground; non-mixable only for backgrounded voice work. | Audio focus: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` in the foreground; `AUDIOFOCUS_GAIN_TRANSIENT` when the reply starts or continues while stopped. Chosen when audio starts and never changed mid-playback (see "never reconfigure a live session"). |
| D5 | Interruption (call) began → end the mic session; ended + should-resume → resume the reply only if transport alive and timeout not fired. | Same, mapped to audio-focus loss/gain events (`ANDROID-HOME-09`). |
| D6 | (macOS closes the window → teardown.) | Swiping the task away (`Service.onTaskRemoved`) tears down: park the claim, stop the service, no replay. |

## Required behavior

1. **Retention policy.** One injected predicate answers `none | reply | voiceSession` (iOS: `backgroundRetention`). `reply`: a Home turn is submitted, streaming, or its audio is pending or playing, including the post-text wait for audio (`ANDROID-HOME-05`). `voiceSession`: hands-free armed or capture live (D1). Everything else is `none`.
2. **Stop with `none`:** teardown exactly as `ANDROID-HOME-06` (marks disconnected, parks the claim). **Stop with `reply`/`voiceSession`:** keep the Home socket, audio output and turn pipeline alive; when the work ends (reply finished, audio drained, deadline expired, playback failed, interruption that cannot resume, notification Stop, idle timeout), run the normal teardown through one serialized request if the app is still not foreground.
3. **`onPause` is not background.** Notification shade, dialogs, permission prompts, multi-window focus loss and PiP never stop playback, capture or transport. Only `ON_STOP` (and task removal) counts.
4. **Foreground service.** While retention != none the runtime runs a foreground service of type `mediaPlayback` (plus `microphone` only if D1 ships) with a low-importance notification. The service is started **while the app is still foreground** (at turn submit or at the first audio start in the foreground), not at `ON_STOP`, because starting a foreground service from the background is restricted on current Android. [INFERENCE; verify on the targetSdk 37 device matrix.] It stops itself the moment retention returns to `none`.
5. **Socket stays open through the reply.** No `lifecycle deactivate`, no `home client close`, no socket cancel between `app phase=stopped` and the end of the reply. If the transport drops anyway, follow `ANDROID-HOME-06`: mark disconnected, no replay, keep the uncertain turn for Resend/Discard; a background reconnect is allowed only in `reply`/`voiceSession`, uses the `ANDROID-HOME-04` ladder, and never resends.
6. **Return to foreground** with a kept live transport is a no-op apart from leaving background mode (no reconnect, conversation intact, UI shows the full reply and is not stuck "Speaking").
7. **Never reconfigure a live audio session.** Backgrounding must not create a new `AudioTrack`, change `AudioAttributes`, or re-request focus with different options while a reply plays. Focus type is decided at stream start.
8. **Media session / notification.** Register once, deduped (write state only when it changes, never per PCM chunk), on a private serial thread, deferred ~750 ms after background entry and cancelled if the app returns first, so a quick peek never registers a card. Pause/Play control the reply output; Stop ends the session, tears down and clears the notification. Cleared on every teardown and on foreground return.
9. **Notification permission.** On API 33+ without `POST_NOTIFICATIONS`, retention still works (the foreground service runs); only the lock-screen card/controls are absent. Request the permission in context (first reply, with a one-line rationale), not at launch. Coordinate with `ANDROID-NOTIFY-01`, which owns opt-in alerts; this ticket owns only the ongoing playback notification.
10. **Content-free diagnostics** (below), so the next device journal names the closer if the socket ever closes under a reply.

## Acceptance criteria

- Given a Home reply is playing, when the screen locks or the app stops, then the whole reply plays and afterwards the store shows disconnected and Home sees the claim parked (`client_disconnected`).
- Given the app is idle, when it stops, then behavior and tests match `ANDROID-HOME-06`.
- Given only `ON_PAUSE` occurs during voice work, then nothing stops (no deactivate, no close).
- Given a reply whose control turn completes before its audio ends (the common slow-reply shape), then retention stays `reply`, the socket stays open, and teardown runs only after the audio terminal and drain. (Mirrors iOS `testControlTurnCompletingWhileAudioStreamsKeepsReplyRetentionAndTheSocket`.)
- Given retention ends while stopped, then the notification and foreground service are removed and the socket closes exactly once.
- Given backgrounded voice work, the media notification shows a title with Pause/Play and Stop; Stop ends the session and clears the card; the card never contains the user's prompt.
- Given notification permission is denied, the reply still plays through lock.
- Given the transport drops during a background reply, then the uncertain prompt is not resubmitted and stays offered; retry exhausted → idle teardown.
- Given the app is returned to the foreground mid-reply, then playback continues without a gap, no reconnect, no duplicate submit.
- Given Activity recreation (rotation) or swipe-away, then rotation changes nothing (`ANDROID-HOME-07`) and swipe-away tears down once (D6).
- If D1 ships: hands-free armed in the foreground continues after stop for up to 60 s idle, then disarms, capture stops and the normal teardown runs; a follow-up within 60 s submits exactly once and restarts the window; the mic is never started from the background.

## Android design notes (iOS mechanism → Android)

| iOS | Android |
|---|---|
| `UIBackgroundModes` = `audio` | `<service android:foregroundServiceType="mediaPlayback">`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (API 34+) permissions; declare the type in Play Console for store builds. |
| `AppleLifecycleCoordinator` scene phases, serialized chained task | `HomeLifecycleCoordinator` in the runtime (`ANDROID-HOME-07`) fed by `ProcessLifecycleOwner`/Activity `ON_START`/`ON_STOP` and `Service.onTaskRemoved`; one serialized executor; superseded inputs skipped. |
| `VoiceSessionCoordinator.backgroundRetention` | Predicate on the runtime's turn state (`responseTask` equivalent: accepted turn not terminal **or** audio active/draining). |
| Now Playing + `MPRemoteCommandCenter` | `MediaSession` + `MediaStyle` notification (androidx.media3 session or `MediaSessionCompat`) with a hand-published `PlaybackState`, because the stream is a raw `AudioTrack`, not a Player. |
| Registration off the main actor, deduped, deferred 750 ms | Build/post the notification and set `PlaybackState` on a private `HandlerThread`; compare-and-skip identical state; `postDelayed` 750 ms from `ON_STOP`, cancelled on `ON_START`. Never update per chunk. |
| `AVAudioSession` interruption/route notifications | Audio focus callbacks and `ACTION_AUDIO_BECOMING_NOISY` (`ANDROID-HOME-09`). |
| Idle timeout via injected `HomeMonotonicClock` | Injected monotonic clock in the runtime; only counts while armed with no capture or reply in flight, restarts after each activity. |
| Lead sampling (`lead_ms`) | `ANDROID-HOME-09`. |

## Journal lines to add (content-free; reuse iOS grammar so one grep works on both platforms)

`app phase=started|stopped`, `runtime created`, `lifecycle input=<x> active=<b> retaining=<b>` (and `superseded`), `lifecycle deactivate trigger=stopWithoutRetention|backgroundWorkEnded|taskRemoved|notificationStop reply=<b>`, `lifecycle closing Home client trigger=<t>`, `voice background enter retention=reply|voiceSession`, `voice background retention ended reason=workFinished|interruption|notificationStop|idleTimeout`, `voice background exit`, `voice response started path=<p>`, `voice response ended path=<p> state=<s> audio_stream=<b> paused=<b> backgrounded=<b> mediasession_writes=<n>`, `store Home sendTurn returning completed=<b> audio_terminal=<b> audio_requested=<b>`, `home client close socket=<b> turn_audio=<b>`, `websocket cancel initiator=close|send-task-cancelled`, `websocket closed by peer code=<n>`, `websocket task completed error=<class>`, `fgs started type=mediaPlayback`, `fgs stopped reason=<r>`, `mediasession registered took_ms=<n> thread=background`. Never prompts, replies, titles, handles, claim refs or audio.

## Dependencies

`ANDROID-HOME-04`, `ANDROID-HOME-06`, `ANDROID-HOME-07`. Related: `ANDROID-HOME-05` (post-text audio wait must count as `reply`), `ANDROID-HOME-09`, `ANDROID-DIAG-01` (journal), `ANDROID-NOTIFY-01` (permission UX only).

## Test notes

- JVM: retention predicate matrix, lifecycle serialization, teardown-after-work-ends ordering, idle timeout on a manual clock, interruption → no mic restart, transport drop in background with no replay, "control terminal before audio end keeps retention and socket".
- Seams for platform calls: `ForegroundServiceController`, `MediaSessionPublisher`, `AudioFocusController` as small interfaces with fakes so nothing needs Robolectric or a device.
- Instrumented smoke on API 26 and the newest emulator: service starts from foreground with the right type; stops on retention end. See `ANDROID-TEST-01` for destination independence.

## Device verification (physical Pixel, release-signed build, device-only checks adapted from iOS 1–10)

1. Submit a 30+ s prompt; lock the screen 10 s into the audio; stay locked through the reply. Whole reply plays without a gap; unlock: transcript complete, UI not stuck "Speaking".
2. Pull the notification shade and open Quick Settings during playback and during hands-free capture: nothing stops.
3. (D1) Hands-free armed, lock, speak within 60 s → one new turn; idle 60 s → mic indicator clears.
4. Notification/lock-screen Pause/Play and Stop: Pause/Play control output; Stop ends and clears the card.
5. Play a podcast, start a reply from the background-capable path: the podcast pauses and resumes after Hermes abandons focus.
6. Phone call during a reply: capture ends; reply resumes only when focus returns and the transport/timeout allow.
7. Disconnect Bluetooth/headphones mid-reply: playback pauses, does not switch to the speaker; resumes when an output returns.
8. Swipe the app from Recents mid-reply: teardown once; Home parks the claim.
9. Share diagnostics (`ANDROID-DIAG-01`): healthy journal shows `voice background enter retention=reply`, no `lifecycle deactivate`/`home client close`/`websocket cancel` before the reply ends, then `voice response ended …`, `voice background retention ended reason=workFinished`, `lifecycle deactivate trigger=backgroundWorkEnded`, `home client close`. If it fails, the line immediately before the first `home client close`/`websocket cancel` names the initiator.
10. Home logs show no duplicate submit or replay.

## Known iOS residual risk to carry

iOS PR #122 recorded that the final Now Playing off-main change and the ~1 s stutter at background entry were verified by CI and the device journal only, not by a clean ear test; engine-restart coalescing was not stress-tested on device. Treat the same areas as unproven on Android and gate them with the `lead_ms` sampling in `ANDROID-HOME-09`.

## References

iOS: `spec-ios-home-07-background-voice.md`, `validation-ios-home-07.md`, PR #122 (`e7859db`).
