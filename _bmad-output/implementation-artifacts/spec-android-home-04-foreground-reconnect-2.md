---
title: 'ANDROID-HOME-04: paced, lifecycle-aware Home reconnect after a long background'
type: feature
created: '2026-10-10'
status: done
route: dispatch
baseline_commit: 601800ec084d57ff78b62bfb210b6b4ade3b7506
review_loop_iteration: 0
context:
  - '_bmad-output/implementation-artifacts/spec-android-home-04-foreground-reconnect.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** After a long background the app's single foreground reconnect fails at the transport layer while the radio wakes. `AndroidRecoveryController.recover()` burns three attempts in milliseconds and lands in `Failed`; nothing cancels a ladder on background, disconnect or Profile change; lifecycle inputs are unordered; an unconfirmed turn blocks automatic reconnect. Parity with `IOS-HOME-04` (issue #68).

**Approach:** Pace the existing recovery ladder on the iOS schedule, wake it when the default network returns, cancel it on every stop condition, order lifecycle inputs by a generation counter, and let the transport reconnect (never resend) with an unconfirmed turn. The ticket spec above is the approved scope.

## Boundaries & Constraints

**Always:** Retry only `transport_unavailable`/`transport_timeout`, reusing the held claim, on 0.5/1/2/4/8 s delays, 10 s per attempt, 60 s overall; all injectable (`ReconnectPolicy`, `MonotonicClock`, `RecoveryPacer`). A cancelled ladder ends `Disconnected`, never `Reconnecting`. Only the newest lifecycle input acts. The unconfirmed prompt stays offered for explicit Resend/Discard; exactly one `prompt.submit`. Journal lines carry no content.

**Never:** No `AlarmManager`/`WorkManager`; no background retry. Do not close the socket on stop (`ANDROID-HOME-06`), add liveness probing or off-tailnet classification (`ANDROID-NET-01`), or change lease/keepalive (`ANDROID-HOME-10`). No new permission beyond `ACCESS_NETWORK_STATE`. Do not touch build files, proguard, `AndroidTurnState`, or release PR #27.

**Decisions (director review in place of owner checkpoint; owner to correct):** (1) The merged backlog ticket spec is the approved scope. (2) `ON_STOP` cancels the ladder but does not close the socket; `ANDROID-HOME-06` owns that. (3) `canDisconnect` is true while connected or recovering. (4) The 10 s per-attempt bound covers connect+handshake only, not claim creation.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Resume after >120 s | held claim, first attempt `transport_unavailable` | retries at 0.5, 1, 2, 4, 8 s until Connected | ends `Failed` with last real reason at schedule/60 s end |
| Network returns mid-wait | resumed, ladder waiting | next attempt at once, not next backoff step | listener registration failure: journal, keep backoff |
| Stop condition | ON_STOP (not config change), Disconnect, Profile switch, newer generation, teardown | no further attempt; state `Disconnected` | attempt already connected is reported as is |
| Non-retryable refusal | any other reason | ladder ends at once | surfaced as today |
| STOP/START interleave | START→STOP or STOP→START | end state follows newest input; one live reconnect | superseded work drops itself |
| Transport loss, unconfirmed turn | `uncertainDelivery` true | automatic reconnect, no resubmit | prompt still offered |
| `stale_conversation` on reused claim | no uncertain delivery | release claim, open one fresh claim in same attempt | with uncertain delivery: no replace |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/HomeReconnect.kt` (new) -- `ReconnectPolicy.default` (first attempt at once, then retries after 0.5/1/2/4/8 s), `RecoveryPacer`/`LockRecoveryPacer` (interruptible wait), `NetworkEvents`/`NetworkListener`, `HomeLifecycleCoordinator` (generation counter).
- `.../ConnectivityNetworkEvents.kt` (new) -- default-network `onAvailable` callback; failure journaled, backoff kept.
- `.../AndroidRecovery.kt` -- `AndroidRecoveryController`: policy/clock/pacer ladder, `cancelRecovery()`, `isLadderLive`; transport-class `Unrecoverable` retried unless `ambiguousClaim` (a lost claim POST must never be repeated). Replaces `DEFAULT_MAX_ATTEMPTS`.
- `.../HomeRuntime.kt` -- owns coordinator and network observation; `appStarted/appForegrounded/appBackgrounded(isChangingConfigurations)/profileSelected`; cancels ladder on disconnect/teardown; `canDisconnect` includes recovering; reconnect guard drops superseded generations and coalesces requests queued under one input.
- `.../MainActivity.kt` -- forwards lifecycle and Profile inputs; drops the `!hasUnresolvedTurn` resume guard.
- `.../OkHttpRelaySessionClient.kt`, `.../AndroidClientPort.kt` -- `reconnect(attemptTimeoutMillis)` (default delegates; bound = min(attempt, `helloTimeoutMillis`), clock starts after claim creation, a stale replacement shares the attempt's budget); `shouldReplaceReusedClaim`: never for transport failures or `reconnect_required`, for `stale_conversation` only without uncertain delivery, any other refusal as before.
- `.../HermesRelayApplication.kt`, `AndroidManifest.xml` -- wire `ConnectivityNetworkEvents`; `ACCESS_NETWORK_STATE`.
- Tests: `ReconnectLadderTest`, `HomeRuntimeReconnectTest`, `ReconnectTestSupport` (new); `AndroidRecoveryControllerTest`, `OkHttpRelaySessionClientTest` (extended).

## Tasks & Acceptance

**Execution:**
- [x] `HomeReconnect.kt`, `ConnectivityNetworkEvents.kt` -- policy, pacer, network events, lifecycle coordinator -- injectable seams
- [x] `AndroidRecovery.kt` -- paced, cancellable ladder with overall deadline and journaled waits -- stops the sub-second burn
- [x] `HomeRuntime.kt`, `MainActivity.kt`, `HermesRelayApplication.kt`, `AndroidManifest.xml` -- serialized lifecycle, network wake, stop conditions, Disconnect while recovering
- [x] `OkHttpRelaySessionClient.kt`, `AndroidClientPort.kt` -- per-attempt bound; stale-conversation and transport claim handling
- [x] Tests listed in the Code Map plus `LockRecoveryPacerTest` -- fake clock/pacer, scripted outcomes; I/O matrix rows; `CHANGELOG.md` entries

**Acceptance Criteria:**
- Given resume after >120 s with the claim intact, when the first attempt fails `transport_unavailable`, then attempts follow the schedule (asserted by fake clock) until Connected, deadline, or a stop; Home-ended claim shows a fresh conversation.
- Given connectivity returns after resume, when `onAvailable` fires, then a reconnect starts without waiting for the next backoff step.
- Given a stop condition, when it occurs mid-wait or mid-attempt, then no further attempt runs and the state is `Disconnected`; a configuration change is not a stop.
- Given interleaved lifecycle inputs, then the end state matches the newest and one client is live.
- Given an unconfirmed turn and a transport loss, then the transport reconnects, one `prompt.submit` total, prompt still offered.

## Implementation Notes

Reconciled with the existing uncommitted implementation on `feat/android-home-04-foreground-reconnect` (base `601800e`): all five ticket deltas are built. The ticket's `unresolvedTurn == false` is a Home handshake field that a refused reconnect never carries, so the client gate is its own `uncertainDelivery` flag (set by an uncertain submit or a transport loss mid-turn; cleared by explicit Resend/Discard, accepted turn, resumed turn, close). This is a deliberate narrowing: it also withholds a fresh claim when Home never saw the prompt, and Resend is `NotConnected` until Discard (see deferred-work). Red-first against `main` is recorded in the PR.

## Spec Change Log

## Review Triage Log

Three review layers (blind, edge-case, verification-gap). Verdicts below; `patch` rows were fixed in place, `defer` rows are in `deferred-work.md`.

| Finding | Verdict | Evidence / route |
|---------|---------|------------------|
| Stale replacement ran a second full attempt budget | medium, patch | `reconnect()` now gives the replacement the attempt's remaining budget. |
| Attempt timer counted claim creation (contradicts decision 4) | medium, patch | Timer now starts right before the socket opens. |
| `helloTimeoutMillis` silently dead | medium, patch | Bound is `min(attempt, helloTimeoutMillis)`; existing 250 ms silent-bridge test keeps meaning. |
| Non-stale terminal refusals no longer replaced the claim | medium, patch | Restored prior behavior; only transport and uncertain-stale keep the claim; test added. |
| First attempt waited 0.5 s; matrix says retries follow the first attempt | medium, patch | First attempt immediate, `ReconnectPolicy.maxAttempts = delays + 1`; ladder and runtime tests rewritten. |
| Two queued recoveries ran two ladders (resume + network) | medium, patch | One queued request per lifecycle generation; test added. |
| Lost claim POST retried as a second claim | medium, patch | `Unrecoverable.ambiguousClaim` ends the ladder; test added. |
| `isForeground` dead state | low, patch | Removed. |
| Missing tests: pacer, budget clamp, adapter attempt bound, other refusals | medium, patch | `LockRecoveryPacerTest` and new ladder/adapter tests added. |
| Validation record, CHANGELOG stale | low, patch | Updated. |
| Stale + uncertain delivery: Resend stays `NotConnected` until Discard | medium, defer | Follows the ticket's own rule; alternative is to drop the uncertain guard. Decision recorded for the owner. |
| Ladder holds the single worker thread up to ~65 s | medium, defer | Spec requires reusing `workExecutor`; Disconnect and stop cancel it at once. |
| `switchConversation` calls `recover()` without the generation guard | low, defer | Pre-existing direct call. |
| Config change bumps the generation and can drop a just-queued reconnect | low, defer | Needs a rotation within the worker queue delay; resume reconnects paired Profiles anyway. |
| MainActivity wiring and `ConnectivityNetworkEvents` untested | medium, defer | Needs instrumented tests outside the CI recipe; device checks pending. |
| Profile switch during an attempt leaves old Profile connected | false | The adapter drops a handshake whose Profile is no longer selected (`staleHandshake`). |
| Policy validation, null-manager journal line, cancel inside stale branch | low, rejected | Fix adds guards for situations the code cannot reach in production. |
| `__pycache__` in diff | false | Untracked artifacts of local test runs; deleted, never committed. |

## Verification

**Commands:**
- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` -- expected: success
- `scripts/check-apk-metadata.sh` -- expected: success
- `python3 -m unittest discover -s tests -p 'test_bmad_issue_tracking*.py'` -- expected: pass
- `git diff --check` -- expected: clean

**Manual checks (if no CLI):**
- Pending device: Pixel background >120 s and airplane toggle on return; journal lines per the ticket spec. No install/clear/connected tests.
