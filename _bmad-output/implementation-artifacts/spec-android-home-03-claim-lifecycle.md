---
id: ANDROID-HOME-03
title: Keep Home conversations resumable, stop leaking claims, and let people close open ones
status: review
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-01
parity_stream: S1
created: 2026-10-06
depends_on:
  - home:HOME-NW-17
  - home:HOME-NW-18
ios_reference: 'IOS-HOME-03'
github_issue: https://github.com/achappell/hermes-relay-android/issues/67
---

# ANDROID-HOME-03 — Home claim lifecycle on Android

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): matrix C4 (NW-18 list/close). Fully covered here; no separate ticket.

Parity with `IOS-HOME-03` (spec `spec-ios-home-03-claim-lifecycle.md` on `hermes-relay-ios` main, v0.7.0) and the Home server contract `HOME-NW-18` (`hermes-relay-home` `082e593`, PR #69; deployed on CaticornQueen).

## Background

On 2026-09-27 an iPad leaked seven Home client claims in 19 seconds (each created, never opened, `activity = ready`, expiring 90 s later). With the one live conversation they filled the per-device limit of 8 (`HERMES_HOME_CLIENT_CLAIMS_PER_DEVICE`), and the next `POST /api/v1/client-claims` was refused `409 claim_limit`. The app could not show what was open or let the person clear it.

Android shares the same claim model (`HomeClientClaimProvider`, `POST /api/v1/client-claims`) and has the same exposure. `spec-android-home-02-pair-and-connect.md` handles `claim_limit` as a terminal "specific unavailable state; no retry loop", with no way out for the user.

## Android today (verified against `main` at `3e10ae2`)

- `HomeClientPairing.kt` creates claims and parses the response with lenient field reads, so a `claim_ref` in the response is **ignored, not rejected** (the iOS strict decoder rejected it on deployed Home `082e593`; Android does not have that failure). It never stores the ref.
- No call to `GET /api/v1/client-claims` or `POST /api/v1/client-claims/close` exists (grep for `claim_ref` / `client-claims/close` finds nothing).
- `OkHttpRelaySessionClient.reconnect()` releases a held claim only on a Profile switch (`releaseHeldClaim`) and, on a failed held-claim reuse, drops it and claims afresh once. An unopened claim abandoned by any other path (a second connect while one is in flight, disconnect before open, response arriving after the Profile changed) is not released and waits out Home's 90 s first-open expiry.
- `HomeConversationsSheet.kt` lists stored conversations (`client-sessions/list`) but shows no "open on Home" claims.
- Connect serialization: `recover()` in `MainActivity` is guarded by `recoveryState.isRecovering`, but a claim started from the conversations sheet (`nextConversation`) and a lifecycle reconnect can still be issued independently.

## Required behavior

### A — Resume uses conversation identity and says why it refuses (no Home dependency)

- A reconnect/close/turn operation checks the caller's binding with the same identity rule the store uses (Profile, handle, endpoint/route, household). Capabilities are refreshed from Home's `ready` result and are never part of identity. A reconnect for the same conversation whose ready capabilities differ from the original ready must send `conversation.reconnect` and resume, not report `conversation_mismatch`.
- A reconnect with a genuinely different handle is refused locally, and the refusal records which field names differed (never values) — see `ANDROID-DIAG-01` for the journal.
- A fresh claim after a connect refused locally must not be refused by a stale held binding with no live bridge.
- Verify first: Android's `sameHandshakeBinding` / `canReconnectHeld` logic in `OkHttpRelaySessionClient.reconnectOnce()` may already satisfy this. If it does, mark slice A "already done" in the validation record with the test names that prove it, and do not change it.

### B — Never abandon a claim without releasing it (no Home dependency for the guard)

- Starting or switching a conversation, or disconnecting while a claim is held but not yet opened, releases it by explicit `claim_ref` through `POST /api/v1/client-claims/close` when Home returned one. A claim whose creation response was lost stays ambiguous, is never automatically retried, and is never replayed.
- Connect attempts are serialized per Profile: a connect while one is in progress joins it and never makes a parallel claim.
- A claim response that arrives after disconnect or a Profile change is released by its explicit ref instead of being adopted by the stale operation.
- `claim_ref` values, handles and Session refs stay in memory only and never reach logs or the diagnostics journal.

### C — "Open on Home": see and close this device's open conversations (needs HOME-NW-18)

- Feature detection starts only after a successful create response includes `claim_ref`. Legacy responses without a ref and `404 not_found` from the list/close routes keep this UI hidden.
- The conversations sheet shows "Open on Home (N open · max M)" using Home's `max_claims`, never a hard-coded 8. Each claim shows Profile label, best-effort title (joined from `client-sessions/list` by `grant_id` + `session_ref`; missing titles never block listing or closing), opened time (`opened_at`, falling back to `created_at`), state (`connecting`, `idle`, `replying`, `waiting_to_reconnect`), and marks the current claim.
- **Close** excludes the current claim. **Close all others** submits only explicit `claim_refs` for every other listed claim (1–64 unique). After success or any timeout/error, re-list before deciding what remains open; a `not_open` result is a safe no-op. A close may take longer than 15 s when it waits behind an in-flight open, so the HTTP timeout for this route must exceed the 10 s request default used for the bridge.
- When a connect is refused with `claim_limit`, offer "Manage open conversations", which opens this section directly.
- Closing never deletes the Hermes session; it stays in the conversation list and can be resumed. A confirmed `closed` means Home closed it with reason `client_closed`.

## Acceptance criteria

- **A (fake clients):** open, drop the transport mid-turn, fake Home answers `reconnect_required` with differing capabilities → Android sends `conversation.reconnect`, resumes the same conversation, records no mismatch. A reconnect with a different handle is refused locally with a field-name-only journal line.
- **B (fake clients):** rapid repeated connects with a slow fake claim provider produce exactly one claim. A claim response arriving after disconnect is closed by `claim_ref`. A create response lost before delivery is never automatically retried.
- **C (fake Home service):** list shows the device's claims with the current one marked and the server `max_claims`; tests cover `opened_at: null`, missing titles, a count above `max_claims`, Close and Close all others sending only non-current refs and re-listing after success or failure; a `404 not_found` from list/close hides the section; a delayed list/close response after a newer Profile load cannot clear the current list.
- A create response containing `claim_ref` still parses (regression test); a response without it still parses.
- No handle, `claim_ref`, Session ref, prompt or reply appears in logs or the journal (assert in tests).
- Device: reproduce the pilot — fill a device to `max_claims`, observe `claim_limit` → Manage open conversations → Close all others → connect succeeds. **Waived by owner decision 2026-10-07:** filling 8 claims would leak conversations on the real Home; coverage is the JVM `claim_limit` tests plus the live Open on Home list/close on a paired Spark Profile (see `validation-android-home-03.md`).

## Android design notes

- Put the claim list/close client next to `HomeClientClaimProvider`/`HomeClientService` in `HomeClientPairing.kt`; parse `claim_ref` as an optional field. Keep refs in the held-claim object (`HeldClaim`), not in `RelayProfile` JSON, `FileHomeClientPairingStore` or `rememberSaveable`.
- Serialize with a single-flight guard inside `OkHttpRelaySessionClient` (not in Compose), so the lifecycle reconnect (`ANDROID-HOME-04`), the conversations sheet and the recovery ladder share one guard. `AtomicReference<Job>`-style joins are enough; no coroutine dependency is required.
- UI: extend `HomeConversationsSheet.kt` (Compose bottom sheet). Content descriptions and a polite live region for result changes, following `5-A-2` accessibility rules.
- iOS had a macOS-only Settings sheet layout regression in this story; there is no Android equivalent.

## Dependencies

- `home:HOME-NW-17` (client claims) and `home:HOME-NW-18` (Home `082e593`, deployed). Slice C only runs against Homes that return `claim_ref`; slices A and B do not need Home changes.
- Shares a single-flight guard with `ANDROID-HOME-04`; land that guard once (whichever ticket ships first).

## Test notes

JVM tests with a fake `HomeClientService` and a fake clock; no emulator. Add a table-driven test for every `state` value and for both close results (`closed`, `not_open`).

## Device verification and expected journal lines

Requires `ANDROID-DIAG-01`. Expected content-free lines: `home claim created`, `home claim released reason=switch|disconnect|stale-response`, `home claims listed count=N max=M`, `home claims closed requested=N closed=K`. No refs or handles.

## References

iOS: `spec-ios-home-03-claim-lifecycle.md`, `validation-ios-home-03.md`; Home: `spec-home-nw-18-client-claim-list-and-close.md` (`hermes-relay-home` main).
