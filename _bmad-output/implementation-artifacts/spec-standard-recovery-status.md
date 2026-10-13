---
title: 'Refresh Standard capture readiness after recovery'
type: 'bugfix'
created: '2026-10-12'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

Fix the observed Standard unavailable status after successful recovery. When a connected uncertain turn is cleared by a successful New conversation, or an active turn completes, idle status and speech admission must reflect the current state. Preserve no-replay/uncertainty guards; failed recovery must not enable speech. Shared recovery and turn messages must not describe Standard as a Home bridge.

</frozen-after-approval>

## Implementation Notes

- Isolated worktree approved by Amanda; existing main-checkout evidence and Gradle changes remain untouched.
- Root cause: MainActivity remembers captureController.blockingReason() using authorization/connection/capture/permission keys, but Standard's capture connection predicate also depends on unresolved and active turns. Clearing these while connection remains Connected leaves a cached NotConnected block, affecting both the status banner and microphone control.
- Add unresolved/active-turn dependencies to the existing memoization, retaining the controller's admission authority. Use transport-neutral language only in shared connection/turn messages; leave Home administration-specific labels intact.
- Add behavioral Compose regressions for connected uncertainty becoming a new conversation and active-turn completion. Run the regression against pre-fix code, then verify the fix, existing relevant tests and live emulator network recovery without wiping data.
- No protocol, credential, persistence, reconnect-policy or server changes; no irreversible operation or unresolved user choice.

- Both `MainActivity` and `IdleCaptureZone` independently cache the admission check; both now include unresolved and accepted/active-turn dependencies. `IdleCaptureZone` also keys its controller identity. The first patch covered only the banner cache; the failing UI regression exposed the second cache before completion.
- Shared connection, accepted-turn, uncertainty and capture messages now use transport-neutral language. Removed three old wording-only assertions without re-pinning them; retained behavioral assertions.
- Reproduced before the fix: `new_conversation_restores_capture_without_a_connection_transition` failed because Tap to speak remained disabled. The active-turn case initially passed; it protects the related dependency boundary rather than being claimed failing-before.
- Final verification: 556 JVM tests passed, zero failures/errors/skips; debug app/test APK build and lint passed; direct instrumentation passed all 23 tests across StandardCaptureRecoveryTest, MainActivityTest and MicrophoneCaptureTest. The two Standard regressions additionally passed 30 consecutive direct-instrumentation runs (60 executions).
- Live fixed-app smoke: at 20:28:51 network loss retained uncertainty; 20:29:04 resume preserved it with no observed automatic submission. New conversation at 20:29:12 cleared uncertainty; visible status changed to Ready. Tap to speak reached real Listening at 20:29:16 and returned idle without a speech submission. Explicit typed turn returned STATUS_RECOVERY_OK, Complete, and audio drained at 20:29:50. Ready and the enabled microphone control were verified visually afterward.
- **Test-runner incident:** the initial Gradle connected-test invocation removed the app during cleanup. Reinstalling revealed cleared profiles/history. This violated the intended preservation constraint and was disclosed to Amanda. Restored Amanda Standard through its checked setup using the existing protected Standard credential, and granted microphone/notification permissions; no credential was logged. HomeBridge enrollment and old local history were not recovered. A fresh Home enrollment code is required to recreate that pairing; no server grants were changed. Subsequent verification used direct instrumentation and retained the restored Standard profile across 30 runs and removal of only the test APK.
- Added README guidance: Gradle connected tests belong on a disposable AVD; direct instrumentation avoids its uninstall cleanup but does not make destructive tests safe. Both emulator radios restored enabled; temporary captures and the test APK removed. Physical-device gates unchanged.

## Review Triage Log

- Inline review only; no independent reviewer claim. The small, coupled fix did not justify delegation.
- Medium, patched: the independent IdleCaptureZone cache also omitted turn-state dependencies, leaving the microphone disabled after fixing only the banner. Regression failed until both sites were updated.
- Medium, patched in test setup: fresh installation could show the notification permission dialog over the test Activity. The regression now grants notification permission explicitly on API 33+ and injects granted microphone permission.
- False: a suggested missing success-state assignment in HomeRuntime was already present at the merged baseline: `recoveryState = recoveryController.acceptNewConversation(outcome)`. It was not the defect and was left unchanged.
- Merge/publication remains separate: this fix is local until a PR is requested; ANDROID-STD-01's broader physical/live acceptance is not marked done.
