---
title: 'A-3 — Fresh recovery without replaying an uncertain turn'
type: 'story'
story_id: 'A-3'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: '9318d11'
context:
  - '../hermes-relay-tui/_bmad-output/implementation-artifacts/surface-coverage-matrix.md'
  - '../hermes-relay-tui/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '../hermes-relay-tui/_bmad-output/planning-artifacts/epics.md'
  - '../hermes-relay-ios/_bmad-output/implementation-artifacts/spec-1-4-recover-without-replaying-an-uncertain-ios-turn.md'
---

## Intent

**Problem:** A-2 projects an honest `Disconnected` phase, but Android has no
recovery boundary behind it. Without one, a future adapter could silently
resume a dead Session, re-submit a turn the Client cannot prove was unsent,
retry forever behind a spinner, or let a superseded socket's buffered frames
mutate a recovered turn.

**Approach:** Add an Android-owned recovery controller and a typed reconnect
seam on `AndroidClientPort`. The controller exposes visible connection state,
runs a bounded reconnect ladder that always adopts the adapter's fresh Session
identity, retains a lost turn as an explicitly marked unconfirmed turn, and
sends it only on a deliberate user action, exactly once. Stale-frame isolation
is the existing A-2 identity gate: a superseded Session's events cannot match
the recovered binding.

## Boundaries & Constraints

**Always:** Show connection state as `Connected`, `Reconnecting(attempt of n)`,
`Disconnected`, or `Failed(reason)`; bound the reconnect ladder; adopt a fresh
Session on success; retain an in-flight turn that loses transport as an
unconfirmed turn; keep prior response text and the typed draft visible; require
an explicit user action to resend; clear the marker only when the adapter
accepts the resend.

**Never:** Resend a turn the Client cannot prove was unsent; start a second
ladder while one is running; resume the prior Session after a reconnect; retry
an unrecoverable configuration or authorization failure; let a superseded
Session's events mutate the recovered turn; add credentials, audio transport,
Local History, or Device administration to A-3.

## Acceptance Criteria

- Given an unexpected transport loss, when the bounded ladder succeeds, then
  connection state moves `Disconnected → Reconnecting(n of m) → Connected` with
  a fresh Session identity, and no turn is submitted during recovery.
- Given the bounded ladder is exhausted, then connection state settles on
  `Failed` with the observed reason, and a later explicit attempt can still
  succeed.
- Given the adapter reports an unrecoverable failure, then the ladder is
  abandoned on that attempt rather than exhausting the remaining attempts.
- Given a turn loses transport mid-flight, then it is retained as an
  unconfirmed turn, is never resent automatically, and is resent exactly once
  by an explicit user action.
- Given an explicit resend is rejected by the adapter, then the unconfirmed
  turn is retained, the rejection is reported once, and no automatic retry
  occurs.
- Given transport is not connected, when a resend is requested, then it is
  refused and the unconfirmed turn is retained.
- Given a second loss is reported while a ladder is already running, then the
  existing ladder continues and no second ladder is started.
- Given an event arrives for a Session superseded by a reconnect, then the
  recovered turn's phase, response text, and terminal state are unchanged.

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/AndroidRecovery.kt` — connection
  state, unconfirmed-turn marker, typed reconnect and resend outcomes, and the
  bounded-ladder controller.
- `app/src/main/java/com/achappell/hermesrelay/AndroidClientPort.kt` —
  `reconnect()` seam, defaulting to an honest unconfigured-transport failure.
- `app/src/main/java/com/achappell/hermesrelay/MainActivity.kt` — connection
  state, reconnect action, unconfirmed-turn card, and explicit resend/discard
  controls.
- `app/src/main/res/values/strings.xml` — resource-backed recovery copy.
- `app/src/test/java/com/achappell/hermesrelay/AndroidRecoveryControllerTest.kt`
  — deterministic ladder, no-replay, exactly-once resend, repeated-loss, and
  stale-Session coverage.
- `app/src/androidTest/java/com/achappell/hermesrelay/MainActivityTest.kt` —
  Compose recovery presentation and the explicit resend path.

## Implementation Notes

- The controller is synchronous and transport-agnostic. Backoff timing,
  credentials, and socket teardown belong to the future adapter; this slice
  owns visible state and the no-automatic-replay rule.
- `AndroidResendResult.NotConnected` keeps the marker rather than queuing a
  deferred send, so a resend is always a decision made against a live Session.
- Stale-frame isolation reuses the A-2 binding gate rather than adding a
  separate generation counter: a reconnect yields a new `sessionId`, so a
  superseded Session's events fail the existing identity check.
- `BootstrapClientPort` still reports unconfigured transport. Nothing in this
  slice pretends a Hermes endpoint exists.

## Verification

See `validation-a-3-fresh-recovery.md` for command evidence.

## Closure

A-3 completes the Epic 1 Android triad. Recovery is visible and bounded, an
uncertain turn is never replayed by the Client, and an explicit resend happens
exactly once. Live transport, credentials, microphone, and speaker work remain
with their own stories.
