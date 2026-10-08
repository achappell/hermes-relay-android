---
story: ANDROID-HOME-12
spec: spec-android-home-12-disconnect-action.md
status: done-with-environment-limitation
story_status: done
updated: 2026-10-08
---

# ANDROID-HOME-12 validation record

The pre-ACK active-reply attempt closed transport before Home's close work completed and later produced `client_disconnected`, not `stopped`; that result motivated the ACK fix. The corrected active smoke passed 2026-10-08: one prompt tap at 04:41:59.421Z, UI `Thinking` at 04:42:01.809Z, and the `Disconnect while Hermes is replying?` dialog confirmed with `Stop and disconnect` at 04:42:36.288Z. The Android journal recorded `home disconnect initiator=user` at 04:42:37.843Z and `home claim released reason=disconnect` at 04:42:38.167Z after the validated Home close acknowledgement. The read-only exact-claim witness confirmed `status=closed`, `activity=closed`, `close_reason=stopped`, `idle_deadline=null`; tombstone update 04:42:36.560Z preceded WebSocket peer close 04:42:36.607Z and an exact-ref reread at 04:43:26.368Z remained closed/stopped. No connection-open/ready or prompt-submit was observed in the bounded 34.359-second Home window through 04:43:10.647Z; UI remained Disconnected/Connect at 04:43:24.567Z and 04:43:28.762Z. An earlier post-fix driver attempt selected Local History and is excluded; it was not a product result and issued no close. Home v1 contract README lines 528–530 and 546 reserve literal `client_closed` for REST closure and prefer bound-WebSocket `conversation.close`, whose tombstone reason is `stopped`.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | Targeted red/green plus final `testDebugUnitTest` passed after the close-ACK fix |
| Repetition gate | Historical pass; not rerun after close-ACK change | `scripts/run-flake-gate.sh HomeRuntimeTest,OkHttpRelaySessionClientTest,AndroidRecoveryControllerTest 30`: 30 consecutive runs of 67 tests, 0 failures, before this change |
| APK metadata, issue-tracking tests and override check | Historical pass; not rerun | `scripts/check-apk-metadata.sh` (min SDK 26, 0.3.1/301, signing skipped: `EXPECTED_SIGNER_SHA256` unset), 18 issue-tracking tests, overrides `--check` exit 0 |
| Build, lint, `compileDebugAndroidTestKotlin` | Passed | `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon` |
| Instrumented on a physical device | Prior pass; fake Home port | Pixel 6a: `AccessibilityOrderTest` 5/5, including the overflow-menu/Disconnect tap test; not a real-Home test |
| Device against a real Home | Passed | Retained idle Disconnect→Connect evidence: user event 03:38:44.010Z, fresh `conversation.open` 03:39:27.149Z. Corrected active claim persisted exact `closed/stopped` at 04:42:36.560Z before peer close 04:42:36.607Z; no reconnect/replay observed in 34.359s; UI remained Disconnected/Connect |
| Physical TalkBack speech, focus, gestures | Waived for HOME12 only | Not run and not claimed; role/label and menu-order automated checks remain required |
| CI | Pending pull request | `ci.yml`; no CI watch |

## What changed

- `OkHttpRelaySessionClient.endSession()` sends `conversation.close` through the existing pending-RPC mechanism and waits off-main up to `requestTimeoutMillis` for a valid `status=closed` acknowledgement before recording `home claim released` and closing the socket. Timeout/error records `home claim release unacknowledged`; transport is still cleaned up without retry or fallback after an attempted close. Programmatic teardown remains non-blocking and logs a close request rather than a confirmed release.
- `AndroidRecoveryController.disconnectDeliberately()`: state `Disconnected`, no ladder, the unconfirmed turn is kept.
- `HomeRuntime`: a confirmed user Disconnect journals `home disconnect initiator=user`; cancellations and other non-user teardown do not receive that attribution. `userDisconnected`, `canDisconnect`, `disconnectEnabled` (off while a prompt or resend is in flight), `disconnectNeedsConfirmation` (a reply is in flight), `disconnect()` and `connect()`. `recover()` and `switchConversation()` are no-ops while `userDisconnected`, which covers the foreground reconnect, resume and Profile-selection paths. Only the Connect button (`connect()`) clears it. `disconnect()` disarms hands-free and cancels capture before closing; mid-reply it sends the interrupt, then `endSession()`.
- UI: a Disconnect item in the overflow menu (after History), shown only while connected, disabled during a submit, with a button role and label; an `AlertDialog` asks before cutting off a reply. After Disconnect the existing connect button reads "Connect" instead of "Retry".

## Acceptance criteria

- Deliberate close acknowledgement precedes transport closure and the released journal event: `OkHttpRelaySessionClientTest.end_session_waits_for_home_to_confirm_close_before_closing_the_socket` models Home persisting `stopped` before its delayed `status=closed` response.
- Rejected/malformed response and bounded timeout do not claim release, retry, or fall back to REST: `end_session_does_not_claim_release_when_home_rejects_close_acknowledgement`, `end_session_times_out_close_without_fallback_or_false_release`.
- Exactly one `conversation.close`, socket closed once, held claim cleared, no reconnect afterwards: `OkHttpRelaySessionClientTest.a_deliberate_disconnect_sends_one_close_ends_the_claim_and_never_reconnects_by_itself`.
- No reconnect from runtime automatic paths; Connect works: `HomeRuntimeTest.disconnect_ends_the_session_once_and_nothing_reconnects_until_connect`.
- Disabled mid-submit; mid-reply confirmation interrupts before close: `disconnect_is_disabled_while_a_prompt_is_being_submitted`, `disconnect_mid_reply_needs_confirmation_then_interrupts_before_closing`.
- Unconfirmed turn survives Disconnect and Connect without replay: `an_unconfirmed_turn_survives_disconnect_and_connect_without_being_replayed`.

## Deviations and open items

- `ANDROID-HOME-06` (single lifecycle close path) and `ANDROID-HOME-04` (retry logic) do not exist yet. Disconnect reuses the current claim-release path, which is where HOME-06 will converge, and the "no reconnect" guarantee is the runtime's `userDisconnected` gate, not HOME-04's. HOME-04/06 must honour `userDisconnected`.
- All nonwaived HOME12 acceptance gates passed. Physical TalkBack speech/focus/gesture checks are waived only for HOME12 and remain unverified; CI is pending the draft PR and was not watched.
- `ANDROID-UX-03` (session header) does not exist; the action is in the overflow menu as the spec allows until then.
- `userDisconnected` is process-scoped. After a process restart a paired Profile connects automatically on launch, as before.
- Timeout/error teardown cleans transport but does not imply logical Home closure; it records an unacknowledged release and does not retry/fall back.

## Device handling

Use only `adb install -r`; never uninstall, clear app data, re-pair, change system settings, or run connected Android tests. Preserve a fresh snapshot of the current private files and fresh pairing bytes/hash before install, retain legitimate history, and restore the original compatible APK and baseline pairing bytes afterward.
