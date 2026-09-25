---
story: ANDROID-HOME-02
slice: 1 — pair and connect through a client claim
spec: spec-android-home-02-pair-and-connect.md
home_contract: hermes-relay-home 0d345be (HOME-NW-17)
status: done-with-environment-limitation
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

