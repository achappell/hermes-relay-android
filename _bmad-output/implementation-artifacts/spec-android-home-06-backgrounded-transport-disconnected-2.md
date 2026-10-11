---
title: 'ANDROID-HOME-06: a backgrounded Home transport is disconnected, parked and reopened once'
type: feature
created: '2026-10-10'
status: done
route: dispatch
baseline_commit: 07df74344e3a349ad4894f9801f95a9b92f84554
review_loop_iteration: 0
context:
  - '_bmad-output/implementation-artifacts/spec-android-home-06-backgrounded-transport-disconnected.md'
  - '_bmad-output/implementation-artifacts/spec-android-home-04-foreground-reconnect-2.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Backgrounding leaves the Home socket open; Home parks the claim, yet on return the UI still shows `Connected`, a tap fails, and a failed submit never reconnects. Teardown paths also close through different routes, and an uncertain submit plus a transport loss can start two recoveries. Parity with `IOS-HOME-06` (issue #70).

**Approach:** On Activity stop with nothing in flight, close the transport without `conversation.close` (the claim stays held), show `Disconnected` at once, and reopen the held claim on start through the `ANDROID-HOME-04` ladder. Never trust a cached `Connected` without a live transport; a submit that fails at the transport reconnects and never resends. The ticket spec above is the approved scope.

## Boundaries & Constraints

**Always:** Background close keeps `heldClaim` and its opened state so the next start sends `conversation.reconnect`, never a second claim. Only live or pending states become `Disconnected`; an existing `Failed` reason is kept. A configuration change is not a stop. One close path records its initiator once and is idempotent. At most one recovery runs; a late retry never lands after a newer outcome. `prompt.submit` is never sent without a live client and binding, and never resent automatically. Journal lines carry no content.

**Never:** Do not change `releaseHeldClaim()` semantics for Profile switch or Disconnect. Do not add OkHttp `pingInterval`, `bridge.ping`, `ConnectivityManager` state or off-tailnet handling (`ANDROID-NET-01`), foreground services or retained-work handling (`ANDROID-HOME-08`), or lease/keepalive changes (`ANDROID-HOME-10`). Do not touch build files, proguard, `AndroidTurnState`, release PR #27, or the `ANDROID-HOME-04` ladder pacing.

**Decisions (director review in place of owner checkpoint; owner to correct):** (1) Close on stop only when idle; with a reply, capture or submit in flight keep the HOME-04 behavior (cancel ladder, leave the socket) and leave it to `ANDROID-HOME-08`. (2) Liveness is structural only (ready socket, selected Profile, binding); ping and last-frame probing belong to `ANDROID-NET-01`. (3) Idempotency lives at the transport close; claim-releasing paths are not unified. (4) Journal lines: `home client close socket=true initiator=lifecycle` and `lifecycle deactivate trigger=stopWithoutRetention reply=none`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Stop, nothing in flight | `ON_STOP`, Connected, no turn/capture/submit | socket cancelled, no `conversation.close`, claim kept, state `Disconnected`, one journal line | `Failed`/already `Disconnected` unchanged |
| Start after stop | `ON_START`/`ON_RESUME`, paced reconnect | one `conversation.reconnect` on the held claim, no new claim | stale claim: HOME-04 rules |
| Stop with work in flight | accepted reply, capture or submit in flight | unchanged from HOME-04: ladder cancelled, socket left to `ANDROID-HOME-08` | n/a |
| Configuration change | rotation, font scale | nothing closed, nothing cancelled | n/a |
| Stale `Connected` | UI `Connected`, no ready socket/binding | treated as disconnected; reconnect starts | single recovery |
| Turn without live transport | no live client or binding | refused, no `prompt.submit` frame | `SessionUnavailable` |
| Submit fails at transport | `transport_unavailable` | unconfirmed prompt kept, paced reconnect starts, exactly one `prompt.submit` overall | prompt offered for Resend/Discard |
| Uncertain submit then loss | both within one attempt | one recovery, one reconnect outcome, final `Connected` (30 repeated runs) | later retry dropped by generation |
| Close called repeatedly | lifecycle close, dispose, Disconnect, Profile switch back to back | one socket cancel, one initiator line | second call is a no-op |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/AndroidClientPort.kt` -- add `hasLiveTransport()` and `closeForLifecycle()` (defaults keep fakes compiling).
- `.../OkHttpRelaySessionClient.kt` -- `closeForLifecycle()` = `closeTransport()` without `releaseHeldClaim()`, keeps `heldClaim`, `hasOpenedConversation`, handshake memory, observers and unconfirmed-turn flags; `hasLiveTransport()` = ready socket + active Profile + binding; initiator journaled once; `closeTransport` already `getAndSet(null)`. `beginTurn` already refuses without a live socket (~340-350): keep and pin with a test. Do not touch `releaseHeldClaim`, `endSession`, `close`.
- `.../AndroidRecovery.kt` -- controller method marking a live/pending state `Disconnected` for a lifecycle close while keeping `Failed`, `unconfirmedTurn` and the held-claim path.
- `.../HomeRuntime.kt` -- `appBackgrounded(false)`: after cancelling the ladder, if idle (no accepted turn, capture, submit or resend in flight, not user-disconnected) run `closeForLifecycle()` on the work executor and mark `Disconnected`; resume path distrusts `Connected` without `hasLiveTransport()`; on an `Uncertain` initiation with a transport reason start the paced reconnect through `recover()` (generation-guarded, coalesced as in HOME-04). Reuse `appBackgrounded`, `recover()`, `lifecycle`.
- `.../MainActivity.kt` -- `latestResumeConnection` also reconnects when `Connected` but the client is not live (`runtime` supplies the answer); no new `DisposableEffect`.
- Tests: new `HomeRuntimeBackgroundTransportTest`; extend `OkHttpRelaySessionClientTest` (close keeps claim, reopen sends `conversation.reconnect`, no `conversation.close`, no frame without live transport) and `AndroidRecoveryControllerTest`. Reuse `ReconnectTestSupport` (`ManualPacer`, `QueuedExecutorService`, `FakeNetworkEvents`). Run `scripts/run-flake-gate.sh` for the race test (30 runs).

## Tasks & Acceptance

**Execution:**
- [x] `AndroidClientPort.kt`, `OkHttpRelaySessionClient.kt` -- `hasLiveTransport()`, `closeForLifecycle()` with once-only initiator journaling -- park the claim, never close it
- [x] `AndroidRecovery.kt` -- lifecycle-disconnect state transition preserving `Failed` and the unconfirmed turn -- never show a stale `Connected`
- [x] `HomeRuntime.kt`, `MainActivity.kt` -- idle-stop close, liveness check on resume, reconnect after a transport-failed submit, single recovery owner -- ticket items 1-5
- [x] Tests listed in the Code Map; I/O matrix rows; write each test first and record red results against the `ANDROID-HOME-04` branch; `CHANGELOG.md` entry

**Acceptance Criteria:**
- Given an idle Connected app, when it stops, then the client is disconnected, no `conversation.close` is sent, and the next start sends `conversation.reconnect` on the held claim.
- Given cached `Connected` with a dead or absent socket, when the app resumes, then it reconnects instead of short-circuiting.
- Given no live client or binding, when a turn starts, then no `prompt.submit` frame is written.
- Given a submit failing `transport_unavailable`, then it reaches `Connected` without user action, sends one `prompt.submit` overall, and the unconfirmed prompt is still offered.
- Given an uncertain submit followed by a transport loss, then one recovery runs and the final state is `Connected` over 30 repeated runs.
- Given lifecycle close, dispose and Disconnect called back to back, then one socket cancel and one initiator journal line result.

## Implementation Notes

Stacked on `feat/android-home-04-foreground-reconnect` (`07df743`). The resume gate is `HomeRuntime.needsReconnect` (state not `Connected` or no live transport), used by `latestResumeConnection`; `recover()` also demotes a cached `Connected` without a live transport. The stop close and the `Disconnected` state change run together on the worker, so a queued reconnect never sees the stale state. A failed submit (`transport_unavailable`) calls `recover()`, which the HOME-04 coalescing collapses with a simultaneous loss event to one ladder. The initiator line is written by the call that takes the socket in `closeTransport(initiator)`; lifecycle, disconnect and dispose pass their own. Red-first: see the PR.

## Spec Change Log

## Review Triage Log

Three review layers. All `patch` rows were fixed in place.

| Finding | Verdict | Evidence / route |
|---------|---------|------------------|
| Liveness clause landed in the Profile effect, not `latestResumeConnection` | high, patch | Moved into `HomeRuntime.needsReconnect`, used by the resume lambda; the test fixture uses the same property. |
| Failed-submit reconnect went through the stale Compose-captured state | medium, patch | The `Uncertain` branch now calls `recover()` directly. |
| Posted `Disconnected` could overwrite a newer `Connected` | medium, patch | State change now runs on the worker right after the close, behind the generation check. |
| Initiator guard only on the lifecycle path, check-then-act | medium, patch | Guard is the `getAndSet` result in `closeTransport(initiator)`; mixed back-to-back test added. |
| Double recovery asserted (2) for submit failure plus loss | medium, patch | Test now drops the transport during the submit and asserts one reconnect. |
| Deactivate line logged before the supersede check | low, patch | Moved after it. |
| No tests for `disconnectForLifecycle`; no CHANGELOG | low, patch | Controller tests and CHANGELOG entry added. |

## Verification

**Commands:**
- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` -- expected: success
- `scripts/run-flake-gate.sh HomeRuntimeBackgroundTransportTest,HomeRuntimeReconnectTest,OkHttpRelaySessionClientTest 30` -- expected: 0 failures
- `scripts/check-apk-metadata.sh`; `python3 -m unittest discover -s tests -p 'test_bmad_issue_tracking*.py'`; `git diff --check` -- expected: pass

**Manual checks (if no CLI):**
- Pending device: background 2+ minutes with Wi-Fi on, return, tap to talk; journal `app phase=stopped`, `lifecycle deactivate trigger=stopWithoutRetention reply=none`, `home client close socket=true initiator=lifecycle`, `app phase=started`, `home connect reconnect result=ready unresolved_turn=false`. No install/clear/connected tests on the Pixel.
