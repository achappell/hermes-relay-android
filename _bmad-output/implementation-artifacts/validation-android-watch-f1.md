---
spec: spec-android-watch-f1-listening-without-binding.md
status: partial
story_status: in-progress
updated: 2026-10-10
---

# ANDROID-WATCH-F1 validation

> Status note: **partial** — a narrow state fix plus JVM tests; the ticket stays open. Approval: director review of the spec in place of the owner checkpoint (owner authorized); owner to correct. Draft PR only.

## Host behavior and evidence

`AndroidTurnState.isTerminal` now classifies `Listening` with `binding == null` as terminal for **turn actions** (the microphone may still be listening). This makes the existing `HomeRuntime.hasAcceptedTurn` projection false during the hands-free reopen window even though the prior accepted binding remains in initiation state. A late `TurnInterrupted` for that prior binding is ignored. Recovery's existing no-replay behavior is pinned for the same unbound state; no `AndroidRecovery.kt` or `HomeRuntime.kt` behavior was changed.

Focused JVM coverage in `AndroidWatchF1Test` (5 tests) verifies: bound `Listening` stays in flight; unbound Listening is terminal for turn actions and the reducer ignores a prior-turn interrupt event (a reducer guard that already existed; the user-visible interrupt control is pinned by `hasAcceptedTurn` being false); a connection loss driven through `HomeRuntime` while armed with no binding reconnects with no unconfirmed turn and no `beginTurn`; the runtime requires no Disconnect confirmation and sends no interrupt for the old binding; and a finishing Activity in that window tears down at once, closes the client, cancels capture and clears hands-free.

## Explicitly unresolved / pending

- **PENDING DEVICE:** No device interaction was performed. Recheck hands-free connection loss/recovery, interrupt, stop, background/foreground, and microphone denial/revocation after HOME-07, HOME-08 and HOME-14 land, as the spec requires. Record actual microphone-indicator and phase outcomes; do not infer these from JVM fakes.
- **Owner decision / not fixed here:** The `HomeRuntime` connection-loss callback still calls `transportLost` and schedules foreground recovery without cancelling or disarming a hands-free capture that has no binding. The microphone may remain open through the recovery attempt until the recognizer returns/fails. This ticket changes neither that callback nor `AndroidRecovery`; decide separately whether capture should stop immediately on loss or continue until the recognizer result, then verify on device. The no-replay test does not claim microphone release on this unexpected-loss path.
- Background retention (`Activity.onStop`) remains governed by the not-yet-landed HOME-08 behavior. No lifecycle callback was edited, but the `isTerminal` change alters one lifecycle outcome: `HomeRuntime.activityDestroyed` used to defer teardown (`hasAcceptedTurn` true) in the armed window, when no later turn event would ever settle it, and now tears down immediately. That is pinned by the Activity-finish test and is an improvement, not a regression.
- **Open finding (deferred, medium):** `hasAcceptedTurn` being false in the armed window re-enables the conversation New/Resume actions, the composer and tap-to-speak. `HomeRuntime.switchConversation` does not disarm hands-free or cancel capture, so a switch while armed leaves the microphone live while the phase reads Idle. The same hole already exists for manual (non-hands-free) capture; this change only widens its reachability. Fixing it needs a `HomeRuntime`/`MainActivity` edit, which this ticket excludes because the HOME-04 stack owns them. Recorded in `deferred-work.md`; verify on device.

Verification (2026-10-10): `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` BUILD SUCCESSFUL, 437 JVM tests, 0 failures; `AndroidWatchF1Test` 5/5. With `AndroidTurnState.kt` reverted to baseline, the Activity-finish, Disconnect and reducer tests fail (3 of 5); the connection-loss and bound-`Listening` tests pass either way and pin existing behavior. `scripts/check-apk-metadata.sh` passes on the debug APK; the `test_bmad_issue_tracking*` suite passes (18).

## Ticket paths not pinned by this change

The ticket asks that every enumerated path be pinned with a JVM test that also asserts microphone release. This change pins only the armed-no-binding turn actions above. Not covered here: connection-loss microphone release (open decision above); Activity stop and destroy; the `ANDROID-HOME-06` backgrounded-transport close and `ANDROID-HOME-08` background teardown (not landed); and microphone release assertions for the A-3 ladder and A-5 interrupt paths. User stop, recognizer failure, permission denial and cancel are pinned at controller level by `AndroidHandsFreeTest` and `AndroidCaptureControllerTest`, not for the armed-no-binding window specifically. `HomeRuntime` `disconnect()` and `tearDown()` already disarm and cancel capture; only `disconnect()` is exercised here.
