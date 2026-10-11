---
story: ANDROID-HOME-04
spec: spec-android-home-04-foreground-reconnect.md
build_spec: spec-android-home-04-foreground-reconnect-2.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-10
---

# ANDROID-HOME-04 validation record

**Local status:** implemented and tested on the host; in review as a draft pull request. Physical Pixel and live Home acceptance are pending device. Approval: director review of the build spec in place of the owner checkpoint (owner authorized); the owner corrects afterward.

## Gates

| Gate | Status | Evidence |
| --- | --- | --- |
| CI recipe | Passed | `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon`: 470 tests, 0 failures/errors/skipped; build and lint passed. |
| APK metadata | Passed | `scripts/check-apk-metadata.sh`: min SDK 26, version 0.3.1 (301); signing skipped because `EXPECTED_SIGNER_SHA256` is unset. |
| Issue-tracking tests | Passed | `python3 -m unittest discover -s tests -p 'test_bmad_issue_tracking*.py'`: 18 tests, 0 failures. |
| Whitespace check | Passed | `git diff --check`. |
| Repetition gate | Passed | `scripts/run-flake-gate.sh HomeRuntimeReconnectTest,ReconnectLadderTest,AndroidRecoveryControllerTest,OkHttpRelaySessionClientTest,LockRecoveryPacerTest 30`: 30 consecutive runs of 105 tests, 0 failures. |
| Red-first on `origin/main` | Partly run | In a temporary worktree at `origin/main`: `a_stale_reconnect_with_an_unconfirmed_turn_does_not_open_a_fresh_claim` and `a_transport_refusal_keeps_the_held_claim_for_the_next_attempt` fail (`ClassCastException`: main replaces the claim on any refusal); a temporary ladder test fails (main burns the attempts without pacing). The two positive stale-claim tests pass on main by design (that behavior pre-exists). The ladder, lifecycle, network and cancel tests use new seams (`ReconnectPolicy`, pacer, `NetworkEvents`, `HomeLifecycleCoordinator`) that do not exist on main, so they cannot compile there; no red result is claimed for them. The `MainActivity` guard removal has no unit seam. |
| Review | Done | Three review layers; verdicts in the build spec's Review Triage Log; deferred items in `deferred-work.md`. |
| Device verification | Pending device | No device or app state was touched. See the checklist below. |
| Pull-request CI | Not run | Reported by the PR. |

## Acceptance coverage

- Fake clock/pacer asserts the schedule (first attempt at 0, retries at 0.5, 1, 2, 4, 8 s), the clamp of each attempt's budget to the overall deadline, early retry on network availability, and cancellation on background, deliberate disconnect and Profile change.
- Runtime lifecycle tests assert START→STOP and STOP→START ordering, that a configuration change is not a stop, that a resume plus a returning network run one ladder, and that only one reconnect/client is active.
- A transport loss with an unconfirmed prompt reconnects without another `prompt.submit`; the prompt remains available for explicit Resend/Discard.
- Adapter tests: stale reconnect opens exactly one fresh claim when no delivery is uncertain (and after Resend/Discard clears it), none while uncertain; a transport refusal keeps the held claim; another terminal refusal still replaces it; the attempt bound cuts the ready wait.
- `stale_conversation` gate: the ticket's `unresolvedTurn == false` is a handshake field a refused reconnect never carries. The client gates on its own `uncertainDelivery` flag, a deliberate narrowing documented in the build spec and deferred-work.

## Device checklist — pending device

- Background the Pixel for more than 120 seconds with Wi-Fi on, return, and confirm automatic reconnect.
- Toggle airplane mode during foreground return and confirm retries stop on background/disconnect and resume on network availability.
- Verify Home reuses the parked claim, or uses a single fresh claim after `stale_conversation`; verify no duplicate `prompt.submit`.
- Capture the expected journal lines from the ticket spec (`app phase=started`, `home connect retry scheduled attempt=N delay_ms=D`, then ready or fresh open).
- The Pixel runs the real app under the debug package id: do not install, uninstall, clear data or run connected tests for this check.
