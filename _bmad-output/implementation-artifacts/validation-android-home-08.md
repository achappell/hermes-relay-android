---
story: ANDROID-HOME-08
spec: spec-android-home-08-background-voice.md
build_spec: spec-android-home-08-background-voice-2.md
status: partial
story_status: in-progress
updated: 2026-10-11
---

# ANDROID-HOME-08 validation record

**Local status:** **08a (reply retention) is implemented and tested on the host and is in review as a draft pull request** stacked on `ANDROID-HOME-06` (base `feat/android-home-06-backgrounded-transport`). **08b (hands-free voice session: `microphone` service type, 60 s idle, interruption mic-end, own-voice tail, the conversation-switch fix) is not started**, so the story stays `in-progress`. No device was attached during this work: every device check below is pending. Approval: director review of the build spec in place of the owner checkpoint (owner authorized); the owner corrects afterward.

## Gates (08a)

| Gate | Status | Evidence |
| --- | --- | --- |
| CI recipe | Passed | `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon`: 523 tests, 0 failures/errors/skipped; build and lint passed. |
| APK metadata | Passed | `scripts/check-apk-metadata.sh`: min SDK 26, version 0.3.1 (301); signing skipped (`EXPECTED_SIGNER_SHA256` unset). Checked on the debug APK, not the minified release build. |
| Issue-tracking tests | Passed | 18 tests, 0 failures. |
| Whitespace check | Passed | `git diff --check`. |
| Repetition gate | Passed | `scripts/run-flake-gate.sh HomeRuntimeRetentionTest,AudioSinkPauseTest,HomeRuntimeBackgroundTransportTest,OkHttpRelaySessionClientTest 30`: 30 consecutive runs of 111 tests, 0 failures. |
| Red-first | Partly run | Against the HOME-06 commit with the new types and entry points stubbed to old behavior (retention always `none`, no pause, no focus, no service): 27 of 29 new runtime and sink tests fail. The two that pass pin unchanged behavior (an idle stop is exactly the HOME-06 close; a cancel ends a sink hold). Tests added after review (stop latch, graceful close, teardown, supersession, drain-loop pause) were written against the final code and not re-run red. |
| Review | Done | Three layers; triage in the build spec; deferred items in `deferred-work.md`. |
| Device verification | Pending device | See below. |

## What the JVM tests pin

- Retention is `reply` from a submit until the audio has drained, including a text terminal before the audio and a stop during a submit in flight; a configuration change, a user Disconnect and a transport loss end or ignore it.
- The foreground service is requested only while the app is visible, with state pushed only on change; a request from the background is skipped and journaled.
- One end path: the service state goes inactive, focus is abandoned, the transport is parked once (one close, one `lifecycle deactivate` line), and a newer start suppresses the close.
- Stop interrupts then closes gracefully, resets the display, clears the stopped prompt, and a stopped binding never re-enters retention.
- Pause/Play are output-only with a reason set; a focus gain never resumes a user pause; a pause held 60 s while stopped ends the reply; a foreground pause never times out; the foreground return resumes a lock-screen pause.
- Focus: one request per reply at the first audio, gain type chosen then (`MAY_DUCK` in front, `TRANSIENT` when stopped), transient loss pauses, permanent loss stops, abandoned at the end.
- Sink: a hold freezes the write-stall, drain-stall and drain-timeout deadlines, starts nothing until resumed, and a cancel ends it.

## Device checklist — pending device

No device was attached. Before any install: read-only signature comparison (`dumpsys package`/`apksigner`); the phone runs the minified release build (REL-03), so a debug build goes on only if signatures match. Back up the installed APK to `/tmp/hermes-relay-backup/` first; never uninstall, `pm clear` or run connected tests.

1. Submit a 30 s+ prompt, lock the screen 10 s into the audio, stay locked: the whole reply plays; unlock shows the full transcript and the UI is not stuck on Speaking.
2. Shade and Quick Settings open during playback: nothing stops.
3. Notification Pause/Play and Stop: Pause/Play hold and resume output only; Stop interrupts and clears the card; the card never shows the prompt.
4. Lock-screen visibility of the card with a secure lock screen; media controls with notifications denied.
5. A phone call or another app's audio mid-reply: transient loss pauses and gain resumes; a permanent loss stops.
6. Swipe the app away mid-reply: the reply ends once; Home parks the claim; no replay on reopen.
7. Journal order: `voice background enter retention=reply`, `fgs started type=mediaPlayback`, `mediasession registered`, `voice background retention ended reason=workFinished`, `lifecycle deactivate trigger=backgroundWorkEnded reply=none`, `home client close socket=true initiator=lifecycle`, `fgs stopped`, `wakelock released`.
8. Whether the wake lock is needed (screen-off slow reply); drop it if the journal shows none.
9. Whether `startForegroundService` ever fails from a visible Activity (deferred item).
10. 08b's gate (D1: `SpeechRecognizer` under a `microphone` service while stopped) is its own first step.
