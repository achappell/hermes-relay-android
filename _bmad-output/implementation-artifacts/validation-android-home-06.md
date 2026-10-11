---
story: ANDROID-HOME-06
spec: spec-android-home-06-backgrounded-transport-disconnected.md
build_spec: spec-android-home-06-backgrounded-transport-disconnected-2.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-10
---

# ANDROID-HOME-06 validation record

**Local status:** implemented and tested on the host; in review as a draft pull request stacked on `ANDROID-HOME-04` (base `feat/android-home-04-foreground-reconnect`). Physical Pixel and live Home acceptance are pending device. Approval: director review of the build spec in place of the owner checkpoint (owner authorized); the owner corrects afterward.

## Gates

| Gate | Status | Evidence |
| --- | --- | --- |
| CI recipe | Passed | `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon`: 483 tests, 0 failures/errors/skipped; build and lint passed. |
| APK metadata | Passed | `scripts/check-apk-metadata.sh`: min SDK 26, version 0.3.1 (301); signing skipped (`EXPECTED_SIGNER_SHA256` unset). |
| Issue-tracking tests | Passed | 18 tests, 0 failures. |
| Whitespace check | Passed | `git diff --check`. |
| Repetition gate | Passed | `scripts/run-flake-gate.sh HomeRuntimeBackgroundTransportTest,HomeRuntimeReconnectTest,AndroidRecoveryControllerTest,OkHttpRelaySessionClientTest 30`: 30 consecutive runs of 101 tests, 0 failures. |
| Red-first | Partly run | New tests were run against the `ANDROID-HOME-04` commit (`07df743`) with the two new port methods and `needsReconnect` stubbed to the old behavior: the idle-stop, next-start, stale-Connected and failed-submit runtime tests and both adapter tests fail; the configuration-change, in-flight, superseded-stop and user-disconnected tests pass by design (they pin behavior that must not change). |
| Review | Done | Three layers; triage in the build spec; deferred items in `deferred-work.md`. |
| Device verification | Pending device | No device or app state touched. |

## Acceptance coverage

- (a) idle stop: `closeForLifecycle` once, no `conversation.close`, `Disconnected`; next start sends `conversation.reconnect` on the held claim (adapter test: one claim, methods `open`, `reconnect`).
- (b) cached `Connected` with no live transport reconnects on resume (`needsReconnect`).
- (c) no live client: `beginTurn` refused, no frame written (adapter test).
- (d) a submit failing `transport_unavailable` reconnects, one `prompt.submit`, unconfirmed prompt kept.
- (e) uncertain submit plus loss in one attempt: one reconnect (included in the repetition gate).
- (f) lifecycle close, disconnect and dispose back to back: one initiator line.

## Device checklist — pending device

- Background 2+ minutes with Wi-Fi on, return, tap to talk; UI must not show Connected before Ready.
- Journal: `app phase=stopped`, `lifecycle deactivate trigger=stopWithoutRetention reply=none`, `home client close socket=true initiator=lifecycle`, `app phase=started`, `home connect reconnect result=ready unresolved_turn=false`. Home: claim parked, re-adopted, no second claim, no duplicate submit.
- The Pixel runs the real app under the debug package id: no install, uninstall, data clear or connected tests.
