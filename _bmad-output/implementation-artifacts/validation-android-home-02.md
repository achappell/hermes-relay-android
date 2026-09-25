---
story: ANDROID-HOME-02
slice: 1 — pair and connect through a client claim
spec: spec-android-home-02-pair-and-connect.md
home_contract: hermes-relay-home 0d345be (HOME-NW-17)
status: done-with-environment-limitation
story_status: done
updated: 2026-09-25
---

# ANDROID-HOME-02 validation record

This record keeps four gates separate. A gate is complete only when its evidence is recorded here. Session management, owner approvals, and the local "New conversation" divider remain open ANDROID-HOME-02 scope, so this record does not close the story.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (deterministic) | Passed | Below |
| Decision confirmation | Pending | Spec decisions were carried from the approved iOS slice; Amanda has not confirmed them for Android |
| Merge | Not started | Committed on `feat/android-home-02-pair-and-connect`; not pushed, no pull request |
| Emulator against live Home | Partial pass (2026-09-25) | Pairing, claim, connect, response and audible playback against the deployed Home; see below |
| Physical (Pixel) | Waived for slice 1 by Amanda, 2026-09-25 | "The emulator is good enough." The emulator run against the live Home is the accepted evidence for this slice. The camera-opens-QR assumption behind the no-scanner decision remains unverified |

## Local gate — 2026-09-24

- `./gradlew testDebugUnitTest --no-daemon`: 247 tests, 0 failures, 0 errors.
  - `HomeClientPairingTest` (new, 28): link and typed-code parsing, the https/host-name rule, wire bodies for enrollment, consume, device configuration and claim, typed consume and claim outcomes, the pairing coordinator (polling, expiry, cancel, reject, Keystore failure, re-pairing the same Home), secret-free persisted JSON, renewal before claiming, renewal deferral when Home is unreachable, stale-configuration retry, pending and missing grants, route pinning, and credential removal with a Home's last Profile.
  - `OkHttpRelaySessionClientTest` (4 new) against a TLS MockWebServer bridge: a paired Profile claims one handle and opens it with the pairing credential; a transport reconnect reuses the held claim through `conversation.reconnect` without a second claim; `close()` delivers `conversation.close`; a pinned route mismatch fails closed; a denied claim never opens the bridge; a paired Profile without a claim provider reports `NotConfigured`.
- `./gradlew assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon`: passed. Lint reports nothing new; the existing `allowBackup` finding predates this change.
- `scripts/check-apk-metadata.sh`: passed; min SDK 26, version 0.3.1 / code 301; signing skipped because no expected signer was configured.
- `git diff --check`: clean.

### An informative failure

The first transport test run failed: `conversation.close` never reached the bridge. `close()` sent the frame and then `closeTransport()` called `WebSocket.cancel()`, which discards queued frames. The fix detaches the socket after a successful send, closes it gracefully so the queued close request is written first, and cancels it after the request timeout as a bound. Without the test, Home would have held every claim until its 120-second reconnect grace ran out.

## Emulator check — 2026-09-24

The debug APK was installed on the `hermes-relay-api36` emulator (headless, no audio). `am start -a VIEW -d 'hermes-home://pair?home=https%3A%2F%2Fhome.invalid.ts.net&code=K7Q4MX'` opened MainActivity through the new intent filter, which opened the configuration sheet and submitted the link. The sheet then showed "Home could not be reached. Check that this phone is on the tailnet and try again." This proves the link-delivery path and the unreachable-Home state only. No real Home answered, so there was no approval, stored credential, claim, or turn.

## Emulator against the deployed Home — 2026-09-25

Amanda drove the `hermes-relay-api36` emulator, which reached `caticornqueen.taila59979.ts.net` over the host's tailnet (MagicDNS resolved; port 443 open). The deployed Home serves `/pair`.

- Pairing: Amanda created a code on the Home pairing page, entered it in the app, and approved it. The app stored `home-client-pairings.json` and one Profile, `Spark · caticornqueen.taila59979.ts.net`, carrying a client-grant reference.
- Conversation: Local History for that Profile holds two user entries and one assistant entry; entry content was deliberately not read. A client claim, bridge open, and a Hermes response therefore happened on the live Home.
- Audio: an earlier run on the same build delivered 734,688 frames to `AudioTrack` (about 30 s at 24 kHz). It was inaudible at first because the emulator's media volume was 5/15; it was audible after the volume was raised.
- Not established: whether the second user entry was spoken or typed, whether it got its own response, and renewal, reconnect-within-grace, and `conversation.close` observed on Home.

### Follow-up fixes from the emulator run — 2026-09-25

Amanda reported "I can't send a second message". Sending worked (three test prompts each got a reply), but the screen said otherwise:

- **Every reply ended as `Turn phase: Unavailable`.** A temporary content-free event log showed the correct order (thinking, audio started, text, turn completed), then `AudioFailed` about 3.4 s into playback. AudioFlinger logged `BUFFER TIMEOUT … due to underrun` at the same moment. `AudioTrackAudioSink` failed on any underrun, a rule introduced with the live-gate evidence work (`cea500d`), so ordinary playback stopped and marked the completed turn Unavailable. Ordinary playback now counts underruns and keeps playing; the live gate constructs its sink with `failOnUnderrun = true`, and `LiveHomeSafeResult` still requires `underrun_count == 0`. Related to `ANDROID-BUG-F3`.
- **"Turn accepted … Waiting for Home events" stayed after the turn ended.** It is now hidden once the accepted turn is terminal.
- **After launch the app showed "Unavailable … Edit relay settings" with Retry.** This was not a failed connect: the app never connected at launch, which was deliberate for single-use operator handles. A paired Profile now connects when it is selected or the app opens; operator-handle Profiles keep the manual connect.

Evidence after the fixes, on the emulator against the live Home: the app connected at launch without a tap; a short typed turn and a long spoken reply (1,635,296 frames, about 68 s) both ended `Turn phase: Complete` with no stale status line. Neither turn underran, so tolerant underrun handling is proven by `ordinary_playback_counts_an_underrun_and_still_drains` only, not live. Unit suite: 248 tests, 0 failures; build, lint, instrumentation compile and APK metadata check pass. The diagnostic logs were removed before commit.

### Emulator handling errors (not app defects)

Two pairings were lost to emulator lifecycle, not the app: `-no-snapshot-save` reloaded a pre-pairing snapshot (which also reverted the installed APK to v0.1.0, with no pairing entry point), and a later cold boot after enabling `hw.keyboard` came up without the app. Home still holds those orphaned emulator devices until they are revoked on the pairing page.

## Not verified

- Pairing, approval, claim, and a typed or voice turn on a physical phone.
- Whether the Pixel's system camera opens a `hermes-home://` QR payload directly. The no-scanner decision depends on it.
- Renewal against a real Home near expiry, and Home's handling of the best-effort `conversation.close` on Profile switch.
- TalkBack reading of the pairing status live region.

## Known limitations in this slice

- After pairing succeeds, the sheet closes because a Profile is now selected, so the "waiting for the owner to approve" note for `pending_owner` grants is rarely seen. A grant that later becomes active does not get a Profile until the same Home is paired again; a refresh action is deferred.
- Leaving the sheet during approval cancels polling. A request approved afterwards is abandoned, and Home expires it after five minutes.

## In-app QR scanner — 2026-09-25

Built after Amanda reversed the no-scanner decision. CameraX preview and analysis with ZXing core decoding. ML Kit's bundled model was tried first and dropped because it grew the debug APK from 12.9 MB to 39.6 MB; with ZXing it is 17.8 MB, most of that CameraX. `CAMERA` is requested only when the scanner opens, and `android.hardware.camera.any` is optional.

- Unit (252 tests, 0 failures): only a valid `hermes-home://pair` payload is accepted (other codes, and plain-http links, show "not a Home pairing code"). A ZXing-encoded pairing QR rendered as a padded camera Y plane decodes through the scanner's own row-copy and decode path to the accepted link; another QR decodes but is not accepted; a blank frame decodes to nothing.
- Emulator (virtual-scene camera): the camera permission, the camera opening, and a live preview inside its square frame were observed. The first build let the preview paint over the text above and below it; it is now clipped to its frame. The virtual camera was not steered to the QR poster, so a live camera-to-pairing scan was not performed.
- Not verified: scanning on a physical camera, and the denied-permission and no-camera messages on a device.

## Slice 2 — conversations (2026-09-25)

Spec: `spec-android-home-02-conversations.md`. Depends on Home PR #60 (claim session lookup, and rename through `session.title`), which is not yet deployed.

- Unit (262 tests, 0 failures), plus build, lint, instrumentation compile and APK metadata. New coverage: continue-last resumes the remembered conversation; no remembered conversation starts new; `session_busy` and `session_unavailable` fall back to new and report why (busy keeps the reference for the next launch, gone forgets it); a deliberate new conversation forgets it; learning the current reference persists it in the pairing record; the list, claim-session and resume wire shapes; current/in-use row classification; dividers never lead the history; and on the transport, a requested new conversation closes the current claim (`conversation.close` observed) and opens the new claim with `conversation.open`.
- Informative defect: the transport chose `conversation.reconnect` for a just-made claim when its handle matched the previous one. A fresh claim now always opens.
- Emulator against the live Home:
  - The Conversations sheet listed the Spark Profile's real conversations with Hermes titles, dates and message counts.
  - Resuming "Say one short sentence" restored Hermes's memory: asked "what did I ask you to do in my previous message", it described the earlier ten-sentence story request. Its message count rose from 4 to 6, and the sheet marked it "Current conversation".
  - Leaving with Back and reopening continued the last resumed conversation with no fallback, and it stayed marked current.
  - Local History recorded the "Resumed: <title>" dividers.
- Found live, fixed or routed:
  - Rename was rejected (`request_rejected`) because Standard Hermes's `command.dispatch` refuses `title`, which is renamed through `session.title`. The fix is in Home PR #60; Android rename is unverified live until it deploys.
  - After a force-stop, Home still held the old claim for its 120 s reconnect grace, so continue-last fell back to new and forgot the conversation, with a message that blamed "another device". Busy now keeps the reference, and the message explains the restart case.
- Not verified live until Home PR #60 is deployed: learning a brand-new conversation's reference (the route returns 404 on the current Home, so continue-last only works after an explicit resume), and rename.

### After Home PR #60 deployed (2026-09-25)

The deployed Home answers `/api/v1/client-claims/session` with 401 when unauthenticated (404 before the update).

- Continue-last for a brand-new conversation: New conversation, then a turn ("remember the code word …"). The pairing record then held the learned `session_ref`. After Back and relaunch there was no fallback notice, and "what is the code word" was answered correctly in the continued conversation (checked by keyword, without reading the reply).
- Rename: renaming the current conversation reported "Renamed.", and the list showed the new title marked "Current conversation".
- Both slice-2 items previously marked "not verified live" are now observed on the emulator. Physical-device checks remain waived as for slice 1.

## Slice 3 — owner approvals (2026-09-25)

Spec: `spec-android-home-02-approvals.md`.

- Unit (265 tests, 0 failures), plus build, lint, instrumentation compile and APK metadata. New coverage: pending and holder parsing against Home's actual item shape; the approve, reject and revoke paths with `{"schema":1}`; the mapping of `not_found`, `unauthorized`, `forbidden`, `service_unavailable` and other errors to Gone, NotAllowed, Unreachable and Failed; per-pairing loading and deciding; holder grouping by Profile.
- Emulator against the live Home: the sheet listed no waiting requests and the holders of Amanda, Jensen and Spark. Hermes TUI refresh is marked "First device" for the owned Profiles, and this phone is "This phone". Remove on the TUI opened "Remove Hermes TUI refresh from Amanda?", and Keep dismissed it without a change.
- Not verified live: approving or declining a real request (it needs a second device to request an owned Profile this phone holds), the banner appearing for a real request, and a confirmed removal.

## Story closure — 2026-09-25

All three slices are merged: #56 and #57 (pairing and the QR scanner), #58 (conversations; Home #60), and #59 (owner approvals). Amanda marked ANDROID-HOME-02 done with these gaps recorded, not closed:

- A physical Pixel pass was waived. All live evidence comes from the `hermes-relay-api36` emulator against the deployed Home.
- ~~A live owner approve or decline, the banner for a real request, and a confirmed removal were not exercised.~~ Exercised on 2026-09-25; see below.
- A live camera scan was not exercised; decoding is covered by unit tests.
- A spoken turn after pairing was not explicitly confirmed; response audio playback was.

Android Epic 1 stays in progress until a short emulator pass covers the voice and approval gaps.

### Live owner approval and voice attempt — 2026-09-25

- **Owner approval, end to end:** a throwaway "Approval test device" (type `tui`) enrolled with a code from the pairing page. Amanda approved it with Amanda ticked, and Home reported its grant as `pending_owner`. When the emulator returned to the foreground the banner appeared ("A device is asking to use one of your Profiles."). Review showed "Approval test device wants to use Amanda". Approve reported "Approved.", and the test device's own configuration then showed Amanda `active`.
- **Removal:** Remove opened "Remove Approval test device from Amanda?"; confirming reported "Removed.", and the test device's configuration then listed no grants. Its credential was deleted locally. The device record still exists on Home without grants and can be revoked from the pairing page.
- **Voice not exercised:** tap-to-speak with the Mac speaking into the host microphone failed with the recognizer's `LANGUAGE_PACK_ERROR` (13). The emulator has no English on-device speech pack. It was requested (English (US), 93 MB) but Google's background downloader had not fetched it, even with charging simulated and its jobs forced. The app's "Speech capture failed, so no turn was sent." is the correct handling. Response audio playback remains verified.
- Found while testing and fixed in PR #61: the reconnect after Android cuts a background app's network, and recovery once Home's reconnect grace has closed the held claim.

