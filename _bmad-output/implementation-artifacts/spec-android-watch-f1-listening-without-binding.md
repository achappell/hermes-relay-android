---
id: ANDROID-WATCH-F1
title: Verify recovery and interruption while hands-free is armed with no turn binding
type: 'bugfix'
status: in-review # draft | ready-for-dev | in-progress | in-review | done (workflow state; ticket delivery status lives in sprint-status.yaml)
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 601800ec084d57ff78b62bfb210b6b4ade3b7506
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S3
created: 2026-10-06
depends_on: []
source_defect: 'android-open-defects.md ANDROID-WATCH-F1 (2026-09-12 Pixel 6a session)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/113
validation: '_bmad-output/implementation-artifacts/validation-android-watch-f1.md'
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/android-open-defects.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Since PR #25 a hands-free reopen sets the turn phase to `Listening` before Home binds any turn, so `phase` can be non-terminal while `binding` is null. In that window `isTerminal` is false, so the runtime treats the previously accepted turn as live (`hasAcceptedTurn`), would offer Disconnect confirmation and interrupt a turn that already ended.

**Approach:** Classify `Listening` with a null `binding` as terminal for turn actions (the microphone still listens), and pin each reachable path with JVM tests: stale interrupt event, reconnect without replay, and disconnect without interrupt.

## Boundaries & Constraints

**Always:** Keep the diff narrow (`AndroidTurnState.isTerminal` plus tests); `Listening` with a binding stays non-terminal; no turn is replayed from an unbound microphone; record device checks as pending.

**Never:** Change `HomeRuntime.kt` or `AndroidRecovery.kt` (another worker's HOME-04 stack owns them); change connection-loss microphone behavior; touch the Pixel 6a (no install, `pm clear`, or connected tests); claim device acceptance from JVM fakes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Armed, no binding | Prior turn complete, hands-free reopens capture | `isTerminal`, not `isInFlight`; `hasAcceptedTurn` false; no Disconnect confirmation | N/A |
| Stale interrupt | `TurnInterrupted` for the prior binding arrives | State unchanged | N/A |
| Reconnect | Transport lost while armed, no in-flight turn | Ladder reconnects; `beginTurn` never called; no unconfirmed turn | N/A |
| User Disconnect | Disconnect while armed | `endSession` only; no interrupt of the old binding; capture cancelled, hands-free off | N/A |
| Bound Listening | `Listening` with a binding | Remains non-terminal | N/A |

**Decisions (provisional — made under owner authorization with director review; Amanda corrects afterward):**
- This change is **partial**: a narrow state fix plus JVM tests for three paths and one invariant. The ticket's enumerated paths (connection loss/A-3 ladder, A-5 interrupt, user stop, mic end/fail, Activity stop/destroy, HOME-06/08 closes) are not all pinned and mic release is not asserted for all of them, so the ticket stays open and its tracker is not `done`.
- Whether the microphone should stop immediately or run until the recognizer returns when the transport drops while armed with no binding: unresolved and intentionally unchanged (today: mic may stay open through the recovery attempt); separate ticket. This spec claims no mic release on unexpected loss.
- `HomeRuntime.kt` and `AndroidRecovery.kt` are untouched because the HOME-04 stack (another worker) owns them; small merge conflicts are possible.

</frozen-after-approval>

## Original ticket scope

Verbatim from the ticket on `origin/main` (preserved; this change covers only part of it — see Intent and Tasks).

### Background

Since PR #25, a hands-free reopen sets the turn phase to `Listening` before any turn exists. The phase can therefore be non-terminal while `binding` is null. Nothing reads `binding` in that window today, and the `A-3` recovery ladder keys off connection state, but the defect file says recovery and interruption while hands-free is armed should be checked here first.

### Required behavior

1. Enumerate every reader of the turn phase and `binding` and every path that can run while the phase is `Listening` with a null `binding`: connection loss and the `A-3` ladder, `A-5` interrupt, user stop, microphone capture ending or failing, Activity stop and destroy, and (once they exist) the `ANDROID-HOME-06` backgrounded-transport close and the `ANDROID-HOME-08` background teardown.
2. Pin the intended behavior of each path with a JVM test: no null dereference, no stale non-terminal phase left over after the path completes, the microphone is released, and no turn is replayed.
3. Fix any path the tests show to be wrong. If a path is correct but fragile, make it explicit rather than leaving it to the current absence of a reader.

### Acceptance criteria

- JVM tests cover each enumerated path while hands-free is armed with no turn.
- On a physical device: arm hands-free, then lose and regain the connection, press interrupt, stop, background and foreground the app, and deny or revoke the microphone; the phase ends in a truthful terminal or ready state each time and the microphone indicator clears.
- Re-run the device check after `ANDROID-HOME-07`, `ANDROID-HOME-08` and `ANDROID-HOME-14` land, because they move where the phase and `binding` live; record the result in the validation record of whichever ticket lands last, or in this ticket's own record.

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/AndroidTurnState.kt` -- `isTerminal`/`isInFlight`; reducer ignores events when `binding == null` or terminal.
- `app/src/main/java/com/achappell/hermesrelay/HomeRuntime.kt` -- `hasAcceptedTurn`, the hands-free reopen (`updateTurn(Listening)` runs on each Starting/Listening/Transcribing capture change while the phase is terminal, which now includes unbound `Listening`), `disconnect()` and `tearDown()` disarm and cancel capture; reference only.
- `app/src/main/java/com/achappell/hermesrelay/DoorwayZones.kt`, `MainActivity.kt` -- readers of `isTerminal`/`hasAcceptedTurn` (Interrupt control, composer block, conversation sheet `actionsEnabled`, doorway state); behavior changes through them are listed in Design Notes; reference only.
- `app/src/test/java/com/achappell/hermesrelay/AndroidWatchF1Test.kt` -- 5 JVM tests (reducer, bound-`Listening` invariant, and three `HomeRuntime` paths: connection loss, Disconnect, Activity finish); existing `AndroidHandsFreeTest`/`AndroidCaptureControllerTest` already cover stop, permission denial, recognizer failure and cancel at controller level.

## Tasks & Acceptance

**Execution:**
- [x] `AndroidTurnState.kt` -- treat `Listening` with null `binding` as terminal for turn actions.
- [x] `AndroidWatchF1Test.kt` -- pin stale-interrupt, reconnect-no-replay and disconnect-no-interrupt for the armed no-binding window.
- [x] `CHANGELOG.md`, `validation-android-watch-f1.md` -- record the fix and what stays pending.
- [ ] Device recheck after HOME-07/08/14 land -- pending device, owner-approved only; not in this PR.

**Acceptance Criteria:**
- Given hands-free armed with no binding, when a prior-turn interrupt or Disconnect occurs, then no stale turn is interrupted and no turn is replayed.
- Given a bound `Listening` turn, when events arrive, then it is still non-terminal.
- Given a physical device, when hands-free is armed and connection loss, interrupt, stop, background/foreground and mic denial are exercised, then the phase is truthful and the mic indicator clears — PENDING DEVICE, so not device-accepted.

## Implementation Notes

- Implemented before this spec was reconciled. Step-04 review (2026-10-10) rewrote the reconnect test to drive `HomeRuntime`, added Activity-finish and bound-`Listening` tests, removed a tautological counter assertion, and recorded the conversation-switch exposure as deferred. Validation status is `partial`; the tracker is `in-progress`; device checks and the mic-on-loss decision are not resolved here.

## Spec Change Log

## Review Triage Log

| Finding | Verdict | Evidence and route |
|---|---|---|
| Step-04 (2026-10-10): finishing Activity while armed now tears down at once instead of deferring (verification-gap; blind) | medium | Confirmed in `activityDestroyed`: `hasAcceptedTurn` was true so teardown deferred and no later event settled it. Improvement, previously untested. Route: patch (Activity-finish test; validation and spec text corrected). |
| Step-04: reconnect test passed `inFlightTurn = null` straight to `AndroidRecoveryController` and never used `HomeRuntime` (verification-gap; edge-case; blind) | medium | Test could not fail on the fix. Route: patch (replaced with a `HomeRuntime` connection-loss test). A transport loss after hands-free Submitted but before Accepted legitimately retains an unconfirmed turn; that is not the armed-no-turn case. |
| Step-04: conversation switch while armed leaves the microphone live with an Idle phase (edge-case; blind) | medium | Confirmed: `switchConversation` does not call `disarmHandsFree`/`cancelCapture`; `actionsEnabled = !hasAcceptedTurn` is now true in the window. The hole already exists for manual capture, and the fix lives in `HomeRuntime`/`MainActivity`, excluded by the frozen block. Route: defer (`deferred-work.md`, PR body). |
| Step-04: stale-interrupt test passes without the fix because the reducer already guards `binding == null` (blind) | low | Confirmed. Route: patch (test renamed to say it pins the reducer; interrupt control pinned by `hasAcceptedTurn` false and `interruptCount == 0`). |
| Step-04: `beginTurnCount = 1` hand-set then asserted (blind) | low | Tautological. Route: patch (counter starts at 0; asserts no submission). |
| Step-04: validation verdict `done-with-environment-limitation` overstated a partial change (blind) | low | Route: patch (`partial`). |
| Step-04: ledger says nothing reads `binding`; Code Map omitted new `isTerminal` readers (blind) | low | Route: patch (`android-open-defects.md` note and Code Map). |

## Design Notes

Unbound `Listening` is terminal for turn actions, not for the microphone: the capture controller still owns the mic. Not covered by new tests: Activity stop (not destroy), HOME-06/08 backgrounded close (owned by those tickets) and mic release on unexpected connection loss (decision recorded in the frozen block).

Behavior changes the one-line fix causes through `hasAcceptedTurn` while armed with no binding: Interrupt control hidden, `activityDestroyed` tears down at once instead of deferring (pinned by a test; previously nothing would ever settle the deferred teardown), and conversation New/Resume, composer and tap-to-speak become enabled. `switchConversation` does not disarm or cancel capture, so a switch while armed leaves the mic live with an Idle phase; this also happens today with manual capture and is deferred, not fixed, here (`HomeRuntime`/`MainActivity` belong to the HOME-04 stack).

Release scope `migration` (2026-10-06): hands-free (`A-6`) is part of the migration voice conversation; an armed microphone with an inconsistent phase is a correctness and privacy risk. Dependencies: none hard; re-verify on device after `ANDROID-HOME-07`, `ANDROID-HOME-08` and `ANDROID-HOME-14`, which move where phase and `binding` live.

## Verification

**Commands:**
- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` -- expected: success.
- `python3 -m unittest discover -s tests -p 'test_bmad_issue_tracking*.py'` -- expected: pass.
- `git diff --check` -- expected: clean.

**Manual checks (PENDING DEVICE):**
- Arm hands-free, then drop and restore the connection, interrupt, stop, background/foreground, deny the mic; expect a truthful phase and cleared mic indicator.
