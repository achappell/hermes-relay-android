---
story: ANDROID-HOME-02
slice: 1 — pair and connect through a client claim
spec: spec-android-home-02-pair-and-connect.md
home_contract: hermes-relay-home 0d345be (HOME-NW-17)
status: local-evidence
updated: 2026-09-24
---

# ANDROID-HOME-02 validation record

This record keeps four gates separate. A gate is complete only when its evidence is recorded here. Session management, owner approvals, and the local "New conversation" divider remain open ANDROID-HOME-02 scope, so this record does not close the story.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (deterministic) | Passed | Below |
| Decision confirmation | Pending | Spec decisions were carried from the approved iOS slice; Amanda has not confirmed them for Android |
| Merge | Not started | Committed on `feat/android-home-02-pair-and-connect`; not pushed, no pull request |
| Physical/live | Not run | HOME-NW-17 deployment and a physical pairing were not exercised |

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

## Not verified

- Pairing, approval, claim, and a typed or voice turn against a deployed HOME-NW-17 Home on a physical phone.
- Whether the Pixel's system camera opens a `hermes-home://` QR payload directly. The no-scanner decision depends on it.
- Renewal against a real Home near expiry, and Home's handling of the best-effort `conversation.close` on Profile switch.
- TalkBack reading of the pairing status live region.

## Known limitations in this slice

- After pairing succeeds, the sheet closes because a Profile is now selected, so the "waiting for the owner to approve" note for `pending_owner` grants is rarely seen. A grant that later becomes active does not get a Profile until the same Home is paired again; a refresh action is deferred.
- Leaving the sheet during approval cancels polling. A request approved afterwards is abandoned, and Home expires it after five minutes.
