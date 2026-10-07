---
story: ANDROID-HOME-12
spec: spec-android-home-12-disconnect-action.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-06
---

# ANDROID-HOME-12 validation record

Gates are kept separate. Disconnect against a real Home (claim shown closed `client_closed` on Home, Connect again, mid-reply Disconnect) was **not** run: it needs a paired Home and the device holds the owner's pairing, which must not be touched.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | 297 unit tests, 0 failures/errors/skipped (`HomeRuntimeTest` +7, `OkHttpRelaySessionClientTest` +1) |
| Repetition gate | Passed | `scripts/run-flake-gate.sh HomeRuntimeTest,OkHttpRelaySessionClientTest,AndroidRecoveryControllerTest 30`: 30 consecutive runs of 67 tests, 0 failures |
| APK metadata, issue-tracking tests and override check | Passed | `scripts/check-apk-metadata.sh` (min SDK 26, 0.3.1/301, signing skipped: `EXPECTED_SIGNER_SHA256` unset), 18 issue-tracking tests, overrides `--check` exit 0 |
| Build, lint, `compileDebugAndroidTestKotlin` | Passed | `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin` |
| Instrumented on a physical device | Passed | Pixel 6a: `AccessibilityOrderTest` 5/5, including the new overflow-menu/Disconnect tap test (fake port, not a real Home) |
| Device against a real Home | **Not run** | Needs a paired Home; see above |
| TalkBack speech | **Not run** | Role and label are asserted in the semantics tree only |
| CI | Pending the pull request | `ci.yml` |

## What changed

- `AndroidClientPort.endSession()` (default no-op). `OkHttpRelaySessionClient.endSession()` reuses the existing claim release (`releaseHeldClaim`: one `conversation.close`, then a graceful socket close), then `closeTransport()` (audio cancelled, turn observer dropped). Unlike `close()` it is not teardown: observers and the executor survive so Connect works afterwards, and the held claim is gone so the next connect claims afresh (continue-last).
- `AndroidRecoveryController.disconnectDeliberately()`: state `Disconnected`, no ladder, the unconfirmed turn is kept.
- `HomeRuntime`: `userDisconnected`, `canDisconnect`, `disconnectEnabled` (off while a prompt or resend is in flight), `disconnectNeedsConfirmation` (a reply is in flight), `disconnect()` and `connect()`. `recover()` and `switchConversation()` are no-ops while `userDisconnected`, which covers the foreground reconnect, resume and Profile-selection paths. Only the Connect button (`connect()`) clears it. `disconnect()` disarms hands-free and cancels capture before closing; mid-reply it sends the interrupt, then `endSession()`.
- UI: a Disconnect item in the overflow menu (after History), shown only while connected, disabled during a submit, with a button role and label; an `AlertDialog` asks before cutting off a reply. After Disconnect the existing connect button reads "Connect" instead of "Retry".

## Acceptance criteria

- Exactly one `conversation.close`, the socket closed once, the held claim cleared, no reconnect afterwards: `OkHttpRelaySessionClientTest.a_deliberate_disconnect_sends_one_close_ends_the_claim_and_never_reconnects_by_itself` (MockWebServer: counts closes and server-side closings, asserts no second request and no connection-lost event, then a deliberate reconnect opens a second claim).
- No reconnect from the runtime's automatic paths; Connect works: `HomeRuntimeTest.disconnect_ends_the_session_once_and_nothing_reconnects_until_connect`.
- Disabled mid-submit: `disconnect_is_disabled_while_a_prompt_is_being_submitted`. Mid-reply confirmation, interrupt before close: `disconnect_mid_reply_needs_confirmation_then_interrupts_before_closing` (the confirmation dialog itself is Compose and was not driven by a test).
- Unconfirmed turn survives Disconnect and Connect and is never replayed: `an_unconfirmed_turn_survives_disconnect_and_connect_without_being_replayed`.
- Hands-free cleared, capture stopped: `disconnect_stops_capture_and_clears_hands_free`.
- Role, label and menu order: `AccessibilityOrderTest.disconnect_is_a_labelled_button_after_history_in_the_overflow_menu_and_ends_the_session`.

## Deviations and open items

- `ANDROID-HOME-06` (single lifecycle close path) and `ANDROID-HOME-04` (retry logic) do not exist yet. Disconnect reuses the current claim-release path, which is where HOME-06 will converge, and the "no reconnect" guarantee is the runtime's `userDisconnected` gate, not HOME-04's. HOME-04/06 must honour `userDisconnected`.
- The journal line `initiator=user` is not written: `ANDROID-DIAG-01` (the journal) does not exist.
- `ANDROID-UX-03` (session header) does not exist; the action is in the overflow menu as the spec allows until then.
- `userDisconnected` is process-scoped. After a process restart a paired Profile connects automatically on launch, as before.
- `conversation.close` is best effort (existing behaviour): Home still closes the claim after its grace if the frame never arrives.

## Device handling

`adb install -r` and `am instrument` only, one class per invocation, under the device lock. No uninstall, no `pm clear`, no system setting changed.
