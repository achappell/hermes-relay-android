---
title: '2-A-2 — Honest Android disconnected and unavailable state without stale-turn replay'
type: 'story'
story_id: '2-A-2'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: '2294a04'
context:
  - '_bmad-output/implementation-artifacts/spec-a-2-honest-phases.md'
  - '_bmad-output/implementation-artifacts/spec-a-3-fresh-recovery.md'
  - '_bmad-output/implementation-artifacts/spec-a-7-live-turns.md'
  - '../hermes-relay-ios/_bmad-output/implementation-artifacts/spec-2-2-ios-disconnected-state.md'
---

## Intent

**Problem:** `2-A-2` requires the Android doorway to show an honest
disconnected or unavailable state, retain only safe cached context, and never
replay an uncertain turn. `A-2`, `A-3`, and `A-7` already deliver most of that
contract, so this slice had to prove the guarantees are visible rather than
merely implicit — and fix whatever the audit exposed.

**Approach:** Audit the Android doorway against the same contract iOS answered
in `2-I-2`, whose own audit found three gaps rather than none. Make the
smallest production change for each user-visible gap found, and pin the
remaining guarantees with deterministic coverage.

## Boundaries & Constraints

**Always:** Keep disconnected, reconnecting, and unavailable states visible
with an explicit connect path; retain cached response text and the unsent draft
while clearly marking them as not live; reach `Connected` only after a fresh
handshake succeeds; require an explicit action to resend an uncertain turn;
discard superseded Session and turn events without mutating current state.

**Never:** Clear retained conversation context merely because transport is
unavailable; present retained text as a live conversation; allow a turn to be
submitted with no live Session; silently resume a prior turn; let a superseded
socket's late frames reach the recovered Session.

## Audit findings

The audit mirrored iOS's and found the same shape of problem — two user-visible
gaps and one coverage gap.

| Finding | Severity | Resolution |
|---|---|---|
| Retained response text read as a live conversation during an outage. | Medium | Labelled `Cached conversation. Saved locally; not live while Hermes is unavailable.` whenever the connection is not `Connected`. |
| An unsent draft gave no indication it was held locally and could not be sent. | Medium | Labelled `Draft saved locally. Connect before sending.` while disconnected. |
| Stale-frame coverage proved the reducer's identity gate, but nothing proved a *replacement socket* negotiates a different Session and that the superseded socket's late frames cannot reach it. | Medium | Added a transport-level test that connects twice against a `MockWebServer`, asserts distinct Session identities, then has the first socket speak after replacement and asserts the recovered turn is untouched. |

Already satisfied, and left unchanged:

- Disconnected, `Reconnecting(attempt of n)`, and `Failed(reason)` are visible
  with an explicit connect action (`A-3`).
- Turn controls are disabled without a live Session (`A-7`).
- An uncertain turn is retained and resent only by explicit action, exactly
  once (`A-3`).
- Superseded Session and turn events leave phase, response, and terminal state
  unchanged (`A-2` identity gate).

## Acceptance Criteria

- Given transport is lost, then the state is visibly disconnected with an
  explicit connect path, and no turn can be submitted.
- Given retained response text and an unsent draft during an outage, then both
  remain visible and both are labelled as saved locally and not live.
- Given the connection is live, then no cached labelling is shown.
- Given a reconnect succeeds, then the Session identity is genuinely fresh.
- Given the superseded socket sends a frame after replacement, then the
  recovered turn's response and phase are unchanged.

## Code Map

- `MainActivity.kt` — cached-response and cached-draft labelling, gated on the
  live connection state.
- `strings.xml` — the two cached-context strings.
- `OkHttpRelaySessionClientTest.kt` —
  `a_replacement_session_ignores_late_frames_from_the_superseded_socket`.
- `MainActivityTest.kt` —
  `retained_context_is_labelled_as_cached_while_disconnected`.

## Implementation Notes

- The labelling is deliberately gated on connection state rather than on turn
  phase. A turn can end `Unavailable` while the Session is still live, and that
  text is not cached — it is a completed answer whose audio never arrived.
- No production change was needed for capture, because Android has no
  microphone path yet. iOS's equivalent finding — capture lingering in
  `Listening` during an outage — cannot occur here, and was not simulated.

## Closure

The Android doorway's disconnected behavior is honest and proven rather than
assumed. Retained context stays available without pretending to be live, and a
dead Session cannot speak into a recovered one.
