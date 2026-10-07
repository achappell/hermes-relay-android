---
id: ANDROID-HOME-06
title: Treat a backgrounded Home transport as disconnected, and close or reconnect it exactly once
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-01
parity_stream: S1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-04
  - home:HOME-NW-18
  - android:ANDROID-HOME-07
ios_reference: 'IOS-HOME-06'
github_issue: https://github.com/achappell/hermes-relay-android/issues/70
---

# ANDROID-HOME-06 — Backgrounded Home transport is disconnected

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): matrix V18; PX-09 transport liveness is owned by `ANDROID-NET-01` and consumed here.

Parity with `IOS-HOME-06` (`hermes-relay-ios` commit `feeb475`), plus the idempotent-close and stale-retry fixes from `b46e8fc` and `aabb275` (PR #122, v0.7.0).

## Background

The pilot iPhone was backgrounded for about two minutes. Home parked the claim as `client_disconnected` and closed it. Back in the foreground the app still showed "Connected"; tap-to-talk failed and left the app Disconnected, and Home saw no open, no fresh claim and no submission. Cause: the lifecycle teardown closed the Home client but never cleared `connectionState`; the foreground short-circuit and the turn binding both trusted the stale `.connected`, and a submit that failed on a dead transport never retried.

A related production race (`b46e8fc`): an uncertain submission armed a 500 ms connect retry, and a following transport loss started the transport-loss reconnect loop (also 500 ms) without cancelling it. Two recoveries then ran against the same conversation and the retry's outcome could land after the loop's, leaving the store "reconnecting (1 of 5)". It reproduced in 14 of 30 iterations of the iOS test before the fix and 0 of 30 after.

## Android today (verified against `main` at `3e10ae2`)

- There is no `ON_STOP`/background handling. The Home socket stays open in the Activity's `OkHttpRelaySessionClient` until Android freezes the process or cuts its network (`Software caused connection abort`, per the PR #61 commit).
- UI state is `recoveryState.connection`, updated only through `AndroidRecoveryController.transportLost(...)` when `observeConnection` reports a drop. If the process was frozen and the drop callback has not yet run when the Activity resumes, the UI still shows `Connected`, and `latestResumeConnection` deliberately skips reconnecting because `connection == Connected`.
- `beginTurn` on a dead socket returns `uncertain(TransportUnavailable)`; `reconnectIfForeground` then refuses to reconnect while an unconfirmed turn exists (fixed by `ANDROID-HOME-04` item 5).
- `closeTransport()` is already idempotent in effect (`getAndSet(null)`), and `releaseHeldClaim()` sends `conversation.close` (ends the claim) — that is correct for a Profile switch but **wrong** for a background teardown, which must park the claim, not close it.
- Two overlapping recoveries are partly guarded (`recover()` returns when `isRecovering`; `transportLost` while recovering does not start a second ladder). Any new retry from `ANDROID-HOME-04` needs the same single-owner guarantee.

## Required behavior

1. **Teardown marks disconnected.** When the app is stopped (Activity `ON_STOP`, with no retained work per `ANDROID-HOME-08`), the Home client is closed **without** `conversation.close` (claim stays held and Home parks it), the held claim is kept for `reconnect`, and the visible connection state becomes `Disconnected` immediately — never left showing `Connected`. A `Failed`/`Unavailable` reason already shown is preserved; only live or pending states become `Disconnected`.
2. **Liveness before trust.** On `ON_START`/`ON_RESUME`, `Connected` is trusted only when a live transport exists: a ready socket, a bound conversation, and a recent successful read or ping. Otherwise treat as disconnected and reconnect (`ANDROID-HOME-04`). A cached `Connected` with no live client reconnects.
3. **No turn over a dead transport.** `beginTurn`/tap-to-speak is refused (or queued behind the reconnect) without a live client and binding; an uncertain turn is never created just because the UI state was stale.
4. **Submit failure reconnects.** A submit that fails at the transport (`transport_unavailable`) schedules the paced reconnect of `ANDROID-HOME-04`, which reopens the held claim and **never resends**; the prompt stays offered for Resend/Discard.
5. **One owner of recovery.** At most one recovery runs per Profile at any time. A transport loss that arrives while a connect retry is pending cancels that retry before starting its own loop; a retry never lands after a newer loop's outcome (generation check).
6. **Idempotent teardown.** Lifecycle close, `onDispose`, explicit disconnect and Profile switch all route through one close path that records its initiator once; calling it twice closes the socket once and emits one journal line.

## Acceptance criteria

- (a) Backgrounding with nothing in flight leaves the client disconnected, a `conversation.close` is **not** sent, and the next start reopens the held claim via `conversation.reconnect`.
- (b) A cached `Connected` UI state with a dead/absent socket reconnects on resume instead of short-circuiting.
- (c) With no live client/binding, starting a turn is not possible (no `prompt.submit` frame is written).
- (d) A submit failing `transport_unavailable` reaches Connected without user action, sends exactly one `prompt.submit` overall, and the unconfirmed prompt is still offered.
- (e) Uncertain submission followed by a transport loss produces one recovery: assert a single reconnect outcome and a final `Connected` state over 30 repeated runs with 0 failures.
- (f) Lifecycle close, dispose and disconnect called back to back produce one socket cancel and one journal initiator line.
- Write each test first and record that it fails on `main`.

## Android design notes

- iOS: `takeHomeClientForLifecycle` + `hasLiveTransport` in `ConversationStore`. Android: add `fun hasLiveTransport(): Boolean` to `AndroidClientPort` (ready socket + binding + last-frame age) and a `closeForLifecycle()` that is `closeTransport()` **without** `releaseHeldClaim()`. Do not change `releaseHeldClaim()` semantics for Profile switch.
- Liveness probe: Home advertises `heartbeat: true`; use OkHttp's `pingInterval` (and a one-shot `send` of a ping on resume) rather than trusting `ready`. [INFERENCE] OkHttp pings need the process unfrozen; a ping on resume that fails within ~1–2 s is sufficient to mark the transport lost before the user can tap.
- iOS had the close issued by a SwiftUI `scenePhase` handler; on Android use a lifecycle observer on the runtime (`ANDROID-HOME-07`), not a `DisposableEffect` in the screen, so recreation (rotation, font-scale change) is **not** treated as background (it must keep the socket; see `ANDROID-HOME-07`).
- Doze/network cut: `ConnectivityManager` default-network loss while foreground should flow into the same `transportLost` path with reason code `transport_unavailable`.

## Dependencies

`ANDROID-HOME-04` (retry/pacing and lifecycle serialization); `ANDROID-HOME-07` (single runtime) for the lifecycle owner; `ANDROID-HOME-08` changes the stop-with-work-in-flight branch and must not regress (a) here.

## Test notes

JVM: fake `AndroidClientPort`, fake `HomeClientService`, manual clock. Keep race tests deterministic with an injected executor and generation counters rather than `Thread.sleep` (see `ANDROID-TEST-01`).

## Device verification and expected journal lines

Background 2+ minutes with Wi-Fi on; return; tap-to-talk. UI must not show Connected before Ready. Journal (`ANDROID-DIAG-01`): `app phase=stopped`, `lifecycle deactivate trigger=stopWithoutRetention reply=none`, `home client close socket=true initiator=lifecycle`, `app phase=started`, `home connect reconnect result=ready unresolved_turn=false`. Home: claim parked `client_disconnected`, then re-adopted; no second claim; no duplicate submit.

## References

iOS: `spec-ios-home-06-backgrounded-transport-disconnected.md`, `validation-ios-home-06.md`, `validation-ios-home-07.md` ("Follow-up: PR #122 CI failure").
