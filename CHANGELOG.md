# Changelog

## Unreleased

### Refactoring
- Extract immutable turn, capture, recovery, conversations and approvals state holders from `AndroidClientScreen`, retaining the existing runtime/controller owners and lifecycle-aware rendering. Disposable-emulator surface/lifecycle smoke and 784 JVM tests pass; the unchanged 98-case instrumentation comparison retains the same 18 pre-existing failing test IDs. ARCH-01 remains in local review, not fully accepted.

### Features
- Implement ANDROID-UX-11: replace the settings bottom sheet with a full-screen Settings destination (Done/Back dismiss). Group it into Profiles, Home pairing, Voice (microphone state and Open settings), Advanced and Troubleshooting heading cards. Move Home device administration and the legacy operator handle behind an explained Advanced disclosure. Pick Rooms with chips instead of comma-separated text, and confirm Revoke before it runs. Wrap button rows so nothing overflows at 2.0x. Pairing protocol code is unchanged. Also fix a `ClientHolderLifecycleSmokeTest` race that also failed on main: the test asserted while the keyboard raised after turn settlement was still resizing the rail; it now waits for the keyboard and stable geometry first. 792 JVM tests, focused 55/55 and full non-live 114/114 instrumentation pass on a disposable emulator. Physical Pixel 1.0x/2.0x and manual TalkBack gates remain open.
- Implement the ANDROID-UX-03 SessionHeaderCard: show the current Home conversation title, one-line accessible Profile, state-colored connection dot, status-only polite live region, and a runtime-owned connection duration that survives Activity recreation. Add settings/title actions and retain HOME-12 Disconnect; add JVM state/clock tests and instrumentation coverage. JVM suite passed and instrumentation compiled; on-device tests and HOME-11 matrix remain unrun, and physical-device spoken-announcement/TalkBack gates remain open.
- Protect accepted Home turns with negotiated `turn.alive` keep-alives: a 45-second activity lease, independent 30-minute cap, and 120-second fallback for older Homes. Pending input suspends only idle expiry; a missing reply enters existing uncertain-turn recovery without replay. Heartbeats never enter transcript/history, and text completion leaves the separate audio wait intact. Software and disposable-emulator verification are complete; live Home/physical acceptance remains open.
- Add content-free reply playback diagnostics: current-driver queued response-audio lead, actual native playing state, a bounded background-entry sampling window, the first playback-cushion release reason, and final underrun counts. Preserve the 1000 ms cushion / 1500 ms cap; physical-device tuning remains open.
- Deduplicate native MediaSession playback-state writes on the existing background worker while preserving notification updates and Pause/Play/Stop controls. Response-end diagnostics include successful native writes since the latest background entry, frozen at audio end alongside underruns; service recreation preserves the entry window.
- Recover an invalid reply AudioTrack once, continuing only PCM not yet accepted by Android. Preserve pause/focus/duck safety and release the replacement cushion without a fresh hold. Never replay accepted speech; uncertain loss of Android's old native queue remains a playback failure with response text available.
- Bound slow-stream reply startup with an injectable 1500 ms cap while retaining the 1000 ms audio cushion. HomeBridge and Standard show Buffering until playback actually starts; short-stream completion and explicit resume release queued audio, while pause and stop preserve their safety controls.
- Add independent route-loss reply holds and same-track focus duck/restore. Focus gain cannot resume route-paused audio onto the speaker; explicit Play clears Route/User only. Automatic device return requires verified selected personal output; unknown paused routing on API 26–32 still requires Play. Physical route/acoustic acceptance and the rest of HOME-09 remain open.
- Continue foreground-armed hands-free listening after the app stops under a microphone foreground service; end idle listening and held replies after 60 seconds of monotonic time. Background-only actions never arm capture.
- Suppress hands-free recognition and speech activity throughout a reply and audio drain, then a named injectable 1.5-second provisional tail. Physical microphone/speaker calibration and echo acceptance remain open.
- Add explicit Standard setup alongside HomeBridge, with checked authentication, a separate Keystore credential slot, and mode/endpoint/Hermes-profile-scoped Local History.
- Connect directly to unmodified Standard Hermes for streamed typed turns, on-device voice input, response audio after text completion, and remote interrupt; retain text if response audio fails.
- Add Standard New conversation and no-replay recovery, preserving uncertainty across reconnect and blocking unsafe Profile/mode changes. Keep Home-only controls hidden in Standard and existing unlinked Profiles explicitly Legacy.
- Add paced, lifecycle-aware Home reconnect after Android foregrounds: the first attempt runs at once, retries follow 0.5/1/2/4/8 s, a returning network retries immediately, and background, Disconnect or a Profile switch stop it.
- Close an idle Home transport when the app stops, without ending the conversation, show Disconnected at once, reopen the same conversation on return, and reconnect (never resend) after a submit fails at the transport.
- Keep a Home reply playing after the app stops: a foreground service with a content-free media notification (Pause/Play, Stop) holds the transport and audio until the reply's audio has drained, then parks the conversation; Stop interrupts the reply, and a paused reply ends after 60 seconds while stopped.
- Request audio focus once for active reply/voice-session work, end capture and pause the reply on transient focus loss, and stop on permanent loss; focus gain never re-arms the microphone.
- Request `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` and (Android 13+) `POST_NOTIFICATIONS` for the reply notification; a denied notification permission never blocks a reply.
- Offer Disconnect while Home is reconnecting, and reconnect automatically (never resend) after a transport loss with an unconfirmed turn.
- Add a compact SessionHeaderCard with connection-state indicator and status, accessible Profile name, tappable conversation title, connection elapsed time, and existing Settings action.
- Request the normal `ACCESS_NETWORK_STATE` permission to notice the network returning.
- Add deliberate Home Disconnect with user-only attribution and mid-reply confirmation.
- Add 2-second interrupt acknowledgement and interrupt-and-listen behavior to Android voice controls.
- Tell Home when Interrupt stops speech still buffered on the phone after the text turn ended, so Home releases the reply audio and accepts the next prompt.
- Interrupt speech that starts after Home's text terminal, without treating in-flight PCM or the stopped sidecar's end as an audio failure.
- Let typed Send take ownership from interrupt-and-listen capture, preventing a late transcript from replacing the accepted next turn.
- Add day/night-aware Android splash styling and enable platform predictive-back support for modal sheets.
- Add durable Home claim-management capability detection and an "Open on Home" list/close flow for the conversations sheet.
- Enable R8/resource shrinking for signed release APKs, retain their mapping artifact, and check CameraX libraries for 16 KB page alignment.
- Exclude all app data from cloud backup and device transfer with Android 12+ extraction rules.

### Tests
- Add the ANDROID-HOME-11 real-window layout matrix suite: `DoorwayRealWindowMatrixTest` and `DoorwaySplitWindowMatrixTest` run the real `MainActivity` on emulator AVDs. `scripts/run-home11-layout-matrix.sh` applies real font scale, rotation, navigation mode, OS keyboard and WM Shell split-screen per cell, reads each setting back, and the tests fail any cell whose running configuration differs from the declared row. Across its tests the suite asserts the primary controls, menu entries and sheets, the long Profile name and status block, transcript reachability and control-versus-bar clearance; which tests cover which cells, and the per-cell outcomes, are in `validation-android-home-11.md`. Real-window classes now clear any keyboard request before their Activity finishes; before this, a request left behind took the next window's first Back and failed `ModalSheetBackTest` in the full suite. Their real finger strokes now wait for each swipe to complete and run only while the window under test has focus, and the classes grant the notification permission up front so a fresh install's prompt cannot cover the window. No layout defect was found, so production code is unchanged; the physical-Pixel gate remains open.

### Fixes
- Repair all 18 retained instrumentation failures with physical-control reachability, actual runtime/observer readiness, a truthful interrupt fixture, and manual-clock coverage of the unchanged 1500 ms hands-free tail. Fresh-emulator non-LiveRelay discovery is 100: 98 pass, two existing opt-in live-speech cases remain unrequested, and none fail. This supersedes the initial non-green ARCH-01 comparison above without claiming live Home, pairing, or physical audio acceptance.
- Stop displaying Speaking immediately when response audio fails before the text turn ends; continue receiving text and show the existing audio-unavailable warning.
- Keep deliberate interruption distinct from response-audio failure: preserve partial text and actual earlier audio errors without showing a false audio-unavailable warning for stopped playback.
- Preserve rejected Standard authentication as a terminal authorization failure instead of treating a failed WebSocket upgrade as a lost live connection; show credential-repair guidance without an automatic retry cascade.
- Preserve shared Standard Local History and drafts when deleting a duplicate connection; remove them only after the last saved owner of that endpoint/Hermes identity is deleted.
- Freeze audio write/drain deadlines across concurrent Pause/Play transitions, including holds between worker polls, without extending the unpaused failure budgets.
- Preserve Standard interrupted-turn safety across process death with an opaque write-ahead marker; block silent replacement sessions/replay until deliberate New conversation, while retaining completed history, credentials and drafts.
- Reveal clipped TalkBack focus targets above the conversation composer using Compose's native scrolling action, without moving keyboard focus.
- Make ordinary availability, connection/retry and Profile-verification status readable on focus instead of automatically announced, so recovery does not enqueue stale status claims. Preserve microphone-permission guidance, capture/turn feedback and urgent errors.
- Distinguish temporary connection loss from missing or rejected authorization in doorway, typed-send and microphone guidance. Preserve typed session-refusal reasons and deliberate New conversation recovery without suggesting futile Retry or replaying prompts.
- Reconnect the selected Standard connection after configuration changes even when its Profile ID is unchanged, including saving/deleting another connection and reselecting the current one.
- Refresh Standard microphone admission and idle status after uncertain or active turns clear, so successful New conversation and completed replies restore Ready and Tap to speak. Keep shared recovery messages transport-neutral.
- Retry transport failures on a bounded backoff without discarding held claims.
- Await Home's `conversation.close` acknowledgement before recording a claim released; report unacknowledged close truthfully.
- Reflow the conversation header title and Profile block to preserve the accessible overflow action at large font scales.
- Keep the conversation rail reachable when the header and composer exceed a short viewport: scroll the whole doorway rather than overlapping regions, and resize for the software keyboard instead of panning the focused draft under the status bar.
- Treat unbound hands-free listening as terminal for turn actions, so an ended turn is not interrupted or treated as a live reply when capture reopens. A finishing Activity in that window now tears down immediately and releases the microphone instead of waiting on a turn that has already ended.
- Keep reply-notification descriptions phase-neutral across Playing/Paused transitions so delayed announcements do not claim Hermes is still replying. Preserve real media state, controls and service teardown.

## 0.4.0 (2026-10-19)

<!-- Release notes generated using configuration in .github/release.yml at main -->

## What's Changed
### Dependencies
* chore(deps): bump androidx.compose:compose-bom from 2026.08.00 to 2026.09.00 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/49
* chore(deps): bump com.android.application from 9.4.0 to 9.4.1 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/48
* chore(deps): bump okhttp from 4.12.0 to 5.5.0 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/47
* chore(deps): bump actions/setup-python from 6.3.0 to 7.0.0 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/45
* chore(deps): bump org.json:json from 20240303 to 20260814 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/46
* chore(deps): bump android-actions/setup-android from 4.0.1 to 4.0.4 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/62
* chore(deps): bump gradle/actions/setup-gradle from 6.3.0 to 6.4.0 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/63
* chore(deps): bump gradle-wrapper from 9.7.1 to 9.8.0 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/64
* chore(deps): bump androidx.core:core-ktx from 1.19.0 to 1.19.1 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/65
* chore(deps): bump androidx.core:core-splashscreen from 1.0.1 to 1.2.0 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/143
* chore(deps): bump actions/upload-artifact from 7.0.1 to 7.0.2 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/144
* chore(deps): bump actions/setup-java from 6.0.1 to 6.1.0 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/170
* chore(deps): bump gradle-wrapper from 9.8.0 to 9.8.1 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/171
* chore(deps): bump org.jetbrains.kotlin.plugin.compose from 2.4.20 to 2.4.21 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/172
### Other Changes
* Fix/stereo drain frames by @achappell in https://github.com/achappell/hermes-relay-android/pull/26
* 5-A-3: Night Console palette and doorway decomposition by @achappell in https://github.com/achappell/hermes-relay-android/pull/28
* chore: make Android BMad delivery status local by @achappell in https://github.com/achappell/hermes-relay-android/pull/29
* feat: migrate Android client to Home bridge by @achappell in https://github.com/achappell/hermes-relay-android/pull/36
* feat: complete Android 5-A-3 doorway pass by @achappell in https://github.com/achappell/hermes-relay-android/pull/30
* chore: standardize project-local worktrees by @achappell in https://github.com/achappell/hermes-relay-android/pull/37
* chore: register Android next-wave aliases by @achappell in https://github.com/achappell/hermes-relay-android/pull/38
* feat(android): align conversation navigation with iOS by @achappell in https://github.com/achappell/hermes-relay-android/pull/39
* docs(android): defer manual TalkBack validation by @achappell in https://github.com/achappell/hermes-relay-android/pull/40
* chore: configure BMAD issue tracking by @achappell in https://github.com/achappell/hermes-relay-android/pull/41
* fix(android): resume Home turns after reconnect by @achappell in https://github.com/achappell/hermes-relay-android/pull/43
* Harden Android BMAD issue tracking by @achappell in https://github.com/achappell/hermes-relay-android/pull/42
* fix(android): complete signed live Home gate on Pixel by @achappell in https://github.com/achappell/hermes-relay-android/pull/44
* feat(android): add Home device administration lifecycle by @achappell in https://github.com/achappell/hermes-relay-android/pull/50
* chore(status): sync Android sprint status with merged PRs by @achappell in https://github.com/achappell/hermes-relay-android/pull/51
* docs(planning): apply approved nine-epic course correction by @achappell in https://github.com/achappell/hermes-relay-android/pull/55
* ANDROID-HOME-02 slice 1: pair with Home and claim per connect by @achappell in https://github.com/achappell/hermes-relay-android/pull/56
* ANDROID-HOME-02: in-app pairing QR scanner and decision review by @achappell in https://github.com/achappell/hermes-relay-android/pull/57
* ANDROID-HOME-02 slice 2: list, resume, rename, and continue Home conversations by @achappell in https://github.com/achappell/hermes-relay-android/pull/58
* ANDROID-HOME-02 slice 3: owner approvals and Profile holders by @achappell in https://github.com/achappell/hermes-relay-android/pull/59
* Mark ANDROID-HOME-02 done; Android Epic 1 stays open by @achappell in https://github.com/achappell/hermes-relay-android/pull/60
* Reconnect a paired Profile after Android cuts it off by @achappell in https://github.com/achappell/hermes-relay-android/pull/61
* docs: approve ANDROID-STD-01 readiness review by @achappell in https://github.com/achappell/hermes-relay-android/pull/66
* docs(android): ticket the iOS 0.7.0 parity work (ANDROID-PARITY-01) by @achappell in https://github.com/achappell/hermes-relay-android/pull/82
* docs(android): ticket functional and design/polish parity with iOS by @achappell in https://github.com/achappell/hermes-relay-android/pull/111
* feat(planning): structure Android parity epics and streams by @achappell in https://github.com/achappell/hermes-relay-android/pull/114
* docs(planning): decide HOME-08 background hands-free and HOME-10 idle control lease by @achappell in https://github.com/achappell/hermes-relay-android/pull/115
* test(android): platform seam, injected time and repetition gate (ANDROID-TEST-01) by @achappell in https://github.com/achappell/hermes-relay-android/pull/116
* refactor(android): one process-scoped Home runtime (ANDROID-HOME-07) by @achappell in https://github.com/achappell/hermes-relay-android/pull/117
* feat(android): microphone permission rationale and Open settings (ANDROID-VOICE-02) by @achappell in https://github.com/achappell/hermes-relay-android/pull/118
* fix(android): stale Device-administration state no longer blocks a paired Profile by @achappell in https://github.com/achappell/hermes-relay-android/pull/119
* feat(android): content-free diagnostics journal and Share diagnostics (ANDROID-DIAG-01) by @achappell in https://github.com/achappell/hermes-relay-android/pull/120
* feat(android): deliberate Disconnect from Home (ANDROID-HOME-12) by @achappell in https://github.com/achappell/hermes-relay-android/pull/121
* feat(android): recognizer language-pack handling and explicit online retry (ANDROID-VOICE-01) by @achappell in https://github.com/achappell/hermes-relay-android/pull/123
* feat(android): app version and build identity in settings and diagnostics (ANDROID-REL-01) by @achappell in https://github.com/achappell/hermes-relay-android/pull/124
* fix(android): keep late Home reply audio playable (ANDROID-HOME-05) by @achappell in https://github.com/achappell/hermes-relay-android/pull/126
* fix(android): restore main build after HOME-12/DIAG-01 merge by @achappell in https://github.com/achappell/hermes-relay-android/pull/131
* feat(android): Paired Homes Refresh Profiles and Unpair this Home (ANDROID-HOME-13) by @achappell in https://github.com/achappell/hermes-relay-android/pull/122
* feat(android): silence endpointing and auto-send for tap-to-talk (ANDROID-VOICE-03) by @achappell in https://github.com/achappell/hermes-relay-android/pull/125
* docs(bmad): feedback parity tickets ANDROID-UX-13..15, ANDROID-VOICE-06 by @achappell in https://github.com/achappell/hermes-relay-android/pull/132
* feat(android): implement UX-09 splash and back behavior by @achappell in https://github.com/achappell/hermes-relay-android/pull/128
* feat(home): manage Home client claims (ANDROID-HOME-03) by @achappell in https://github.com/achappell/hermes-relay-android/pull/130
* fix: quote apostrophes in Android story index by @achappell in https://github.com/achappell/hermes-relay-android/pull/133
* ANDROID-UX-03: keep session header text and overflow menu reachable at large font scales by @achappell in https://github.com/achappell/hermes-relay-android/pull/134
* ANDROID-UX-01: Night Console token foundation (in progress) by @achappell in https://github.com/achappell/hermes-relay-android/pull/127
* feat(android): implement VOICE-04 interrupt behavior by @achappell in https://github.com/achappell/hermes-relay-android/pull/129
* docs: close ANDROID-HOME-03 and ANDROID-UX-09 on owner acceptance by @achappell in https://github.com/achappell/hermes-relay-android/pull/135
* docs: HOME-13 Pixel render proof and refresh feedback defect by @achappell in https://github.com/achappell/hermes-relay-android/pull/136
* fix: keep Paired Homes refresh feedback visible by @achappell in https://github.com/achappell/hermes-relay-android/pull/138
* docs: close HOME-07, REL-01 and TEST-01 with runtime evidence by @achappell in https://github.com/achappell/hermes-relay-android/pull/139
* docs: close VOICE-04; retain UX-01 outdoor acceptance gate by @achappell in https://github.com/achappell/hermes-relay-android/pull/140
* docs: batch Android owner-accepted status closeouts by @achappell in https://github.com/achappell/hermes-relay-android/pull/141
* fix(home): await close acknowledgement on deliberate disconnect by @achappell in https://github.com/achappell/hermes-relay-android/pull/142
* feat(android): R8 shrinking, 16 KB check, data-extraction rules (ANDROID-REL-03) — PENDING DEVICE by @achappell in https://github.com/achappell/hermes-relay-android/pull/145
* feat(home): pace and cancel Home reconnect across Android lifecycle (ANDROID-HOME-04) by @achappell in https://github.com/achappell/hermes-relay-android/pull/147
* feat(home): park an idle Home transport when the app stops (ANDROID-HOME-06) by @achappell in https://github.com/achappell/hermes-relay-android/pull/148
* fix(android): partial: narrow state fix + tests for hands-free armed with no binding (ANDROID-WATCH-F1) by @achappell in https://github.com/achappell/hermes-relay-android/pull/146
* feat(home): keep a Home reply playing after the app stops (ANDROID-HOME-08a) by @achappell in https://github.com/achappell/hermes-relay-android/pull/149
* feat(android): add explicit Standard setup (ANDROID-STD-01) by @achappell in https://github.com/achappell/hermes-relay-android/pull/150
* fix(android): refresh Standard capture readiness after recovery by @achappell in https://github.com/achappell/hermes-relay-android/pull/151
* fix(android): reconnect Standard after configuration changes by @achappell in https://github.com/achappell/hermes-relay-android/pull/152
* fix(android): reveal clipped TalkBack focus by @achappell in https://github.com/achappell/hermes-relay-android/pull/153
* fix: keep reply notification captions phase neutral by @achappell in https://github.com/achappell/hermes-relay-android/pull/154
* fix: keep conversation reachable in short windows and above the keyboard by @achappell in https://github.com/achappell/hermes-relay-android/pull/155
* fix(a11y): read recovery status on focus by @achappell in https://github.com/achappell/hermes-relay-android/pull/156
* fix: distinguish connection recovery from authorization repair by @achappell in https://github.com/achappell/hermes-relay-android/pull/157
* fix: preserve Standard interrupted-turn safety across restart by @achappell in https://github.com/achappell/hermes-relay-android/pull/158
* fix: exclude paused time from audio playback deadlines by @achappell in https://github.com/achappell/hermes-relay-android/pull/159
* fix(android): preserve Standard history and authentication recovery by @achappell in https://github.com/achappell/hermes-relay-android/pull/160
* fix: distinguish stopped playback from audio failure by @achappell in https://github.com/achappell/hermes-relay-android/pull/161
* feat: retain foreground-armed hands-free conversations in background by @achappell in https://github.com/achappell/hermes-relay-android/pull/162
* feat: add safe audio-route pause and ducking by @achappell in https://github.com/achappell/hermes-relay-android/pull/163
* feat: cap reply audio startup buffering by @achappell in https://github.com/achappell/hermes-relay-android/pull/164
* Recover invalid reply AudioTracks without replay (HOME-09) by @achappell in https://github.com/achappell/hermes-relay-android/pull/165
* feat: observe coherent reply playback lead and underruns by @achappell in https://github.com/achappell/hermes-relay-android/pull/166
* feat: account for change-only native media session writes by @achappell in https://github.com/achappell/hermes-relay-android/pull/167
* feat: protect accepted Home turns with keepalive control leases by @achappell in https://github.com/achappell/hermes-relay-android/pull/168
* [ANDROID-ARCH-01] Extract lifecycle-aware client state holders by @achappell in https://github.com/achappell/hermes-relay-android/pull/169
* HOME-11 emulator layout matrix by @achappell in https://github.com/achappell/hermes-relay-android/pull/173
* feat: complete Android session header card (ANDROID-UX-03) by @achappell in https://github.com/achappell/hermes-relay-android/pull/174
* feat: full-screen grouped Settings with Advanced administration (ANDROID-UX-11) by @achappell in https://github.com/achappell/hermes-relay-android/pull/175

## New Contributors
* @dependabot[bot] made their first contribution in https://github.com/achappell/hermes-relay-android/pull/49

**Full Changelog**: https://github.com/achappell/hermes-relay-android/compare/v0.3.1...v0.4.0

## 0.3.1 (2026-09-12)

<!-- Release notes generated using configuration in .github/release.yml at main -->

## What's Changed
### Other Changes
* fix: pass secrets to the reusable release workflow by @achappell in https://github.com/achappell/hermes-relay-android/pull/23
* fix: clear the Speaking phase when response audio ends by @achappell in https://github.com/achappell/hermes-relay-android/pull/25


**Full Changelog**: https://github.com/achappell/hermes-relay-android/compare/v0.3.0...v0.3.1

## 0.3.0 (2026-09-12)

<!-- Release notes generated using configuration in .github/release.yml at main -->

## What's Changed
### Other Changes
* feat: sign release APKs with a developer keystore by @achappell in https://github.com/achappell/hermes-relay-android/pull/19
* fix: derive versionCode from the release version name by @achappell in https://github.com/achappell/hermes-relay-android/pull/21
* fix: repair the release version check and assert APK metadata by @achappell in https://github.com/achappell/hermes-relay-android/pull/22


**Full Changelog**: https://github.com/achappell/hermes-relay-android/compare/v0.2.0...v0.3.0

## 0.2.0 (2026-09-12)

<!-- Release notes generated using configuration in .github/release.yml at main -->

## What's Changed
### Other Changes
* feat: recover an Android turn without replaying it (A-3) by @achappell in https://github.com/achappell/hermes-relay-android/pull/4
* feat: connect Android to a live Hermes relay (A-4) by @achappell in https://github.com/achappell/hermes-relay-android/pull/6
* feat: hold a live Hermes conversation on Android (A-7) by @achappell in https://github.com/achappell/hermes-relay-android/pull/7
* feat: honest Android disconnected state without stale-turn replay (2-A-2) by @achappell in https://github.com/achappell/hermes-relay-android/pull/9
* docs: front the Hermes relays with Caddy instead of tailscale serve by @achappell in https://github.com/achappell/hermes-relay-android/pull/10
* feat: actually play Hermes response audio on Android (A-8) by @achappell in https://github.com/achappell/hermes-relay-android/pull/11
* feat: capture and transcribe speech on device for tap-to-speak (A-9) by @achappell in https://github.com/achappell/hermes-relay-android/pull/12
* feat: show the participant's live transcript while speaking (2-A-1) by @achappell in https://github.com/achappell/hermes-relay-android/pull/13
* feat: give the Android doorway an accessible reading order (5-A-2) by @achappell in https://github.com/achappell/hermes-relay-android/pull/14
* feat: keep a deliberate per-Profile conversation on the device (5-A-1) by @achappell in https://github.com/achappell/hermes-relay-android/pull/16
* feat: let the user stop a running Hermes answer (A-5) by @achappell in https://github.com/achappell/hermes-relay-android/pull/15
* feat: palette, app icon, transcript export, and prompt recall (local tickets) by @achappell in https://github.com/achappell/hermes-relay-android/pull/17
* feat: hold a continuous hands-free conversation on Android (A-6) by @achappell in https://github.com/achappell/hermes-relay-android/pull/18


**Full Changelog**: https://github.com/achappell/hermes-relay-android/compare/v0.1.0...v0.2.0

## 0.1.0 (2026-09-12)

<!-- Release notes generated using configuration in .github/release.yml at main -->

## What's Changed
### Other Changes
* ci: add Android actions and release automation by @achappell in https://github.com/achappell/hermes-relay-android/pull/1
* fix: bootstrap Release Please without a phantom tag by @achappell in https://github.com/achappell/hermes-relay-android/pull/2

## New Contributors
* @achappell made their first contribution in https://github.com/achappell/hermes-relay-android/pull/1

**Full Changelog**: https://github.com/achappell/hermes-relay-android/commits/v0.1.0

## Changelog

All notable changes to Hermes Relay Android will be documented here by
Release Please.
