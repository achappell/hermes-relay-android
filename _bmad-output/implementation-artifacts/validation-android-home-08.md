---
story: ANDROID-HOME-08
spec: spec-android-home-08-background-voice.md
build_spec: spec-android-home-08-background-voice-2.md
status: partial
story_status: in-progress
updated: 2026-10-14
---

# ANDROID-HOME-08 validation record

**Current local status (2026-10-14):** 08a and 08b software are implemented; 08b passed reviewed JVM/build/lint and isolated real-service emulator gates. The story remains `in-progress` because physical microphone/recognizer-under-lock and speaker-echo gates remain open. Verification caused a destructive managed-runner incident: profiles and authenticated access were recovered, but newer history was not exactly restored (see incident section). The 08a evidence below is historical and keeps its original environment scope.

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

## 2026-10-13 — phase-neutral notification follow-up (emulator only)

Independent bugfix: `spec-neutral-reply-notification.md`, based on
`fa96838a0e924a1b95f52fe80cb39880e998c247`. The app caption is now **Reply audio**
for plain and media notifications. Real playback state and Pause/Play/Stop
actions remain unchanged. This is not a fix for Android's own media narration
or the separately blocked in-app TalkBack polite-speech queue.

| Gate | Observed evidence |
| --- | --- |
| Red-first platform regression | Real notification test failed before the fix: `Hermes is replying` changed to `Paused`. |
| Build and lint | Full JVM tests, debug app/test APK assembly and `lintDebug` passed; rerun after the reviewed caption refinement also passed. |
| Focused emulator suite | 35 tests passed in 52.001 s: notification lifecycle, accessibility order, main screen, microphone capture/permission and runtime recreation. |
| Native repetition gate | 30 consecutive runs of the real-service notification test, zero failures. This gate preceded the resource-only refinement from “Reply playback controls” to “Reply audio”; the same test passed again on the final APK in 3.426 s. |
| APK metadata | Final debug APK: min SDK 26, version 0.3.1 (301), backup disabled with extraction rules, 16 KB alignment passed. Signing skipped because no expected signer was configured. |
| Actual app smoke | Existing authenticated Standard connection, API 36 emulator. Synthetic story accepted at 02:48:15.316; Home key backgrounded the app; real media session registered at 02:48:16.468. Notification-shade Pause and Play changed the actual session from Playing(3) to Paused(2) to Playing(3), with the same **Reply audio** caption. Screenshot inspected on the resumed card. A redundant shell Play followed the actual Play tap as cleanup protection. |
| Actual completion | Audio drained at 02:49:14.843; retention ended as `workFinished`; foreground service stopped and wake lock released at 02:49:14.849. No Hermes media session remained. Reopening showed Ready, the original selected Standard profile, and an empty draft. |

The actual shade exposed Pause/Play, not a Stop button; the real-service
regression checked that the posted Stop action and session identity persisted.
Do not count this as a manual Stop interaction. Emulator media volume was
already 0 and stayed 0: no human-heard or acoustic acceptance is claimed.
The original no-device checklist above records the earlier run, not this
follow-up. Physical lock-screen, calls/routes and 08b remain unverified.

## 2026-10-13 — shared audio pause-deadline race

Independent bounded bugfix: `spec-audio-pause-deadline.md`, baseline
`a192e0cb25d8024eaadde314f2a2e9ebc8c10686`. This affects the shared AudioTrack sink
used by both connection modes; actual live playback below used Standard.

- **Confirmed red:** block the drain worker's second head read, after its pause check; Pause and advance a manual clock by 5 s before releasing that read. The unchanged sink settles as failed instead of returning to its held poll. One selected test failed before the production fix. The earlier intermittently failing regression used wall-clock sleeps; its diagnostic history remains in `deferred-work.md`.
- **Fix:** a private pause-aware elapsed clock excludes exact held intervals from write-stall, drain-stall and overall drain deadlines. Pauses entirely between worker polls are included. Resume preserves the remaining unpaused budget; genuine stalls/timeouts still fail. No threshold, buffering, transport or notification-policy changes.
- **Host verification:** final full JVM suite passed; debug app/test APK assembly and lint passed. Final four-class audio/lifecycle repetition gate passed 30 consecutive runs of 58 tests (1,740 executions). Ten pause tests use worker barriers and manual time, not scheduling sleeps. Metadata passed min SDK 26, version 0.3.1 (301), backup/extraction rules and 16 KB alignment; expected signer comparison remained unset/skipped.
- **Native instrumentation:** 18 tests passed in 35.489 s across `HomeVoiceNotificationTest`, `MainActivityTest`, `HomeRuntimeRecreationTest` and `AudioOutputPreflightTest` (explicit `AUDIO_PREFLIGHT=PASS`). Direct installation/instrumentation retained the configured main app and data. The silent preflight does not establish human audibility or physical-speaker acceptance.
- **Actual API 36 emulator:** controlled typed turn accepted at 21:04:48.622Z; background retention/media session established and audio focus granted. Native notification Pause at 21:05:10.701Z put AudioTrack 809/session 6593 in P state. Its server frame position stayed `00045FE0` over a measured 32.036 s hold; Play was tapped at 21:05:45.953Z (35.252 s total journaled pause). The same track returned to A, advancing to `0006D4E0` with zero underruns in the observed snapshots. Native paused notification screenshot inspected.
- **Completion:** `standard audio result=drained` at 21:06:19.758Z; background retention ended, transport closed with uncertain=false, foreground service stopped and wake lock released. Foreground return showed Ready/Verified. Final wake-lock count zero, no Hermes media session, original draft/settings restored. Only test APK and owned captures removed.
- **Limits:** output was already port-muted and remained so; this is AudioTrack control/drain evidence, not an audible-playback claim. No Pixel, unattended microphone, app reset or server change. Physical checklist and 08b remain open; story stays in-progress.
- **Review:** independent production review found no supported defect; coverage review found a removed playing-driver Pause assertion, restored with a stable worker barrier and repeated-Pause check before final full/repetition gates. No new deferred code finding.

## 2026-10-14 — HOME-08b software and emulator-only scope

Baseline: `13962b88845961491369026b23193adbda9b53b1` (merged PR #161).
Isolated branch: `feat/home-08b-background-voice`. No owner checkout changes,
push or pull request are part of this work.

Implementation covers foreground-armed `VoiceSession` retention, microphone
foreground-service acknowledgement before capture, background-only admission
refusal, monotonic 60-second idle/paused shutdown, and reply/drain-derived wake
suppression for both results and recognizer speech activity. Recognizer silence
reopens without extending idle; genuine activity and a completed reply restart
the window. Disarm downgrades the service when a reply remains.

`RetentionTimings.outputTailMillis = 1500` is a **provisional injectable software
default**, not an acoustic measurement or a hardware-accepted value. Physical
speaker/microphone and locked-screen recognizer acceptance remain open.

The owned temporary `Home08bOwnedSmokeTest` fixture exercises production
`HomeRuntime`, real Android `HomeVoiceService` microphone type and Activity
stop/return, with explicitly simulated speech and Home transport. It does not
write saved profile, credential or history records. Its evidence cannot establish
real recognizer capture, acoustic echo rejection or live-server claim behavior.
No assumption about emulator audio flags is made. The first Gradle-managed
attempt caused the separate destructive runner incident documented below;
subsequent smoke uses only direct instrumentation on an isolated AVD clone.

Baseline API status: voice-service admission, `VoiceSession`, speech-activity and
injected deadline APIs did not exist on the baseline (`compile-red`). Independent
verification subsequently proved isolated red regressions for both review fixes:
removing only the idle-expiry predicate guard fails the queued-speech regression;
removing only the activity resets from close/disarm fails both exact-stop and
recognizer-failure re-arm regressions. The patched targeted suite passed **30
consecutive runs of 111 tests** before the later smoke-discovered lifecycle race.
Final fixture-free full-build and refreshed 120-test repetition evidence follow.

### Review regression: speech activity versus an already-due idle callback

Independent inspection found that checking only the timer generation/deadline
could expire idle listening after the controller had already received speech,
but before its posted runtime refresh ran. The expiry path now rechecks the
controller's current activity/armed state, current retention and active client
turn. The regression holds the runtime callback queue, delivers speech activity,
then advances the monotonic clock to the idle deadline before releasing that
queue. The verifier observed its isolated failure with the guard removed and the
patched repetition gate passing. A second review finding cleared sticky speech
activity on every capture terminal/disarm path; its two re-arm regressions likewise
failed with only those resets removed.


### Verification incident and bounded recovery — not a complete data restoration

The verification agent ran the single-class Gradle command
`./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.achappell.hermesrelay.Home08bOwnedSmokeTest --no-daemon`
on the configured original emulator. After the fixture failed a dumpsys parser
assertion, the Gradle-managed runner cleanup removed the target package **and
its app data**. `pm path`, `run-as`, `pm list packages -u` and both app-data paths
were absent. This violated the task's preservation requirement. An APK and
file hashes had been saved, but no raw app-data backup had been captured first.

Recovery preserved the paused complete AVD in a private incident-recovery
directory before loading `default_boot`; its absolute machine-local location is
in the delivery handoff, not this portable record. Both userdata and
encryption-key qcow2 images contained the matching snapshot dated
2026-10-12 22:38:35 (host-local snapshot listing). The original `default_boot`
was not overwritten. Named `home08b_postincident_20261014` and
`home08b_restored_20261014` checkpoints and a second complete
`restored-good.avd` copy are retained; do not delete these incident-recovery copies
as routine test cleanup. A private raw restored-app tar was also captured before
opening the app.

Observed recovery:

- The original app package and `run-as` returned.
- `files/relay-profiles.json` exactly matches the preincident SHA-256:
  `ff4142f575ed175206a796810821cc08945451e834745b0e4e61f3132d0df66d`.
- Original Standard credentials/Keystore are usable: the restored app showed
  **Ready**, and the content-free journal recorded
  `2026-10-14T15:58:54.740Z standard session method=session.resume result=connected uncertain=false`.
- History is **not** an exact restoration. The preincident SHA-256 for
  `files/history-standard_aa76f566c7f66631b5231df8e28601d0216504393bc7479e775faee.json`
  was `f2e9305c9673cb1e0ede328a8491078e66c453a7d2a496f268e64036f8df31c1`;
  the restored older file hashes to
  `3195995167067a420777795b2997f0c9371b48e4ef7ef040c91828d7d44a8c6d`.
  It contains 19 entries dated 2026-10-13 01:28:50.176 through
  03:16:03.930 UTC and an empty draft. The exact newer preincident history and
  number/content of missing or changed entries are unknown: only its hash was
  saved. No record or timestamp was fabricated to make this appear restored.

Targeted searches for owned `/tmp` recovery artifacts found no newer raw history
backup. A read-only scan of the preserved postincident qcow2 copy found neither
the known history filename nor the restored plaintext prefix. The forensic copy
is retained for possible future recovery; these bounded checks do not prove
forensic recovery impossible.

The original `emulator-5554` was left in this authenticated, partially restored
state. Further smoke is isolated to owned API 36 `home08b-smoke`,
`emulator-5556`, cold-booted from a private copy with snapshot loading/saving
disabled. `AGENTS.md` now prohibits Gradle `connected*` on configured devices
even with a class filter, and requires private raw data/permission backups and
a whole-AVD/Keystore recovery path before testing configured state.

### Additional race found by the isolated OS smoke

The first isolated direct-instrumentation attempt correctly disarmed after the
fixture emitted a completed turn without any audio-delivery events; the reducer
reports that as unavailable audio, so the fixture was corrected to supply a
controlled successful audio-terminal sequence. This does not claim real playback.

The next run reached the real 60-second idle deadline. Its journal at
11:09:41.757 and 11:09:41.759 showed **two** lifecycle deactivations: queued
capture notifications briefly re-read the stale UI hands-free mirror and
resurrected `VoiceSession`. Before changing production, the new deterministic
`queued_capture_callbacks_cannot_resurrect_retention_after_idle_teardown` test
failed with `expected:<1> but was:<2>`. Retention, microphone service state and
focus ownership now use the capture controller's arming state directly. The
focused voice-retention suite and app/test APK builds then passed.

The smoke's initial cleanup parser also matched historical ACQ/REL entries in
`dumpsys power` even though its **active** wake-lock section had size zero. The
corrected temporary fixture checks only that active section, checks notification
7401 absence, and checks exactly one runtime journal park as well as one
controlled-transport close. Both the original journal and deterministic red
evidence are retained privately under the incident-recovery directory.

### Successful isolated runtime smoke — direct ADB only

After the source-of-truth fix and fixture-parser correction, direct
`adb -s emulator-5556 shell am instrument -w -e class com.achappell.hermesrelay.Home08bOwnedSmokeTest com.achappell.hermesrelay.test/androidx.test.runner.AndroidJUnitRunner`
passed **1 test in 109.302 seconds** on API 36. The owned temporary source was
then removed from the repository and retained privately with the evidence.

Observed OS/runtime evidence, not a hardware or live-server claim:

- Actual `mediaPlayback|microphone` foreground service was installed before
  controlled speech capture started. Activity `ON_STOP` retained `VoiceSession`.
- A two-second injected wake-lock lease remained **actually held after 2536 ms**;
  the service worker emitted 64 renewal events across this run.
- One controlled background speech follow-up submitted once. Controlled reply
  audio-terminal events allowed capture to reopen after the suppression tail.
- Default monotonic idle shutdown was observed after **60244 ms**. Both the
  controlled transport and actual runtime journal recorded **exactly one** park.
- Journal order: microphone FGS at 11:14:01.307, background voice retention at
  11:14:01.869, idle expiry at 11:15:04.409, lifecycle park at 11:15:04.410,
  wake-lock release at 11:15:04.414 and service stop at 11:15:04.442.
- Android reported no remaining app service, notification 7401 was absent, and
  the active OS wake-lock count was zero. Historical ACQ/REL entries are not held
  locks. Background arm/tap/Play attempts did not restart capture.
- Unlike the managed-runner incident, direct instrumentation left the clone
  package installed. Its profile and restored-history hashes were unchanged,
  and `RECORD_AUDIO`/`POST_NOTIFICATIONS` remained granted as before the smoke.

The original owner emulator was not used for these attempts. Controlled speech
and controlled Home/audio events do not prove real recognizer-under-lock,
physical microphone/speaker echo, audible playback, or server-side claim release.

### Final fixture-free verification

| Gate | Observed result |
| --- | --- |
| Full JVM, debug app/test APKs and lint | `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --no-daemon` passed; **623 tests across 47 suites**, zero failures/errors/skips. |
| Refreshed lifecycle/voice repetition gate | **30 consecutive runs of 120 tests**, zero failures, including the new queued-callback exactly-once regression. Classes: `HomeRuntimeVoiceRetentionTest`, `HomeRuntimeRetentionTest`, `HomeRuntimeInterruptTest`, `HomeRuntimeBackgroundTransportTest`, `AndroidCaptureControllerTest`, `AndroidHandsFreeTest`, `AndroidEndpointingTest`, `AndroidWatchF1Test`. |
| APK metadata | Min SDK 26, version 0.3.1 (301), backup disabled with extraction rules, 16 KB alignment passed. Debug signer verified against the preserved installed APK certificate; this is not release-signing acceptance. |
| Manifest and fixture removal | Final APK declares microphone FGS permission and service type `0x82` (`mediaPlayback|microphone`). Final test APK contains no `Home08bOwnedSmokeTest` class. |
| Original permission restoration | Read-only inspection after recovery: `RECORD_AUDIO` and `POST_NOTIFICATIONS` both `granted=true`, both exactly `[USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]`, matching the verifier's baseline; no permission mutation needed. |
| Owned process cleanup | Only owned `emulator-5556` was stopped after evidence capture; its emulator process exited 0. Original `emulator-5554` was not stopped. Private recovery copies remain retained. |

The initial host run's five failures were not ignored: obsolete silence-disarm
expectations were replaced with no-partial-submit/continued-listening coverage;
unbound-capture and typed-send fixtures now cross the actual output tail before
asserting their original consumer behavior; the background-only submit test
retains its zero-service-start invariant across audio start and watchdog checks,
without requiring an incidental exact diagnostic count.

**Remaining acceptance:** physical microphone/recognizer continuation under
lock, speaker echo and acoustic tail calibration, calls/routes and release-signed
Pixel checks. **Remaining incident gap:** the exact newer preincident history
bytes are unavailable; profile/auth restoration is not full data restoration.
