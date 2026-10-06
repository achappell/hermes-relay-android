---
id: ANDROID-DIAG-03
title: Send and receive Home connection-failure diagnostics handshake and request envelopes
status: backlog
product_epic: 6
created: 2026-10-06
depends_on:
  - android:ANDROID-DIAG-02
  - home:HOME-NW-06
  - home:HOME-NW-06-android-platform
ios_reference: 'IOS-DIAG-03 (PRs #119, #120, #121; issue achappell/hermes-relay-ios#118)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/78
---

# ANDROID-DIAG-03 — HOME-NW-06 connection-failure correlation (client side)

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): matrix D3.

Parity with `IOS-DIAG-03` (`spec-ios-diag-03.md`, `validation-ios-diag-03.md`) against Home `hermes-relay-home` PR #71 (`a45f7ef`, HOME-NW-06) and its gap tests (PR #73), deployed on CaticornQueen as part of `0effbf9`. The Home contract is `_bmad-output/implementation-artifacts/ios-handoff-home-nw-06-diagnostics.md` (wire authority: `endpoint.py`, `client_reports.py`).

## Background

When a prompt fails mid-turn, Home's server log and the client's report cannot be joined: each side sees only its half. HOME-NW-06 lets a client opt in per socket, receive a Home-minted per-socket `home_connection_id`, stamp a random `request_id` on each `prompt.submit`, receive Home's `correlation_id` on the response, and emit schema-2 report events that carry those IDs, so the owner can open `/pair` and see an association `state: linked` that ties a phone's failed request to Home's operational record. iOS device evidence (2026-10-04): schema 2 with 7 `request_started`/`request_completed`, 1 `client_response_received`, 1 `client_request_resolved`; Home showed `linked=1` after close.

## Android today (verified against `main` at `3e10ae2`)

- No `X-Hermes-Diagnostics-Version` header, no `diagnostics` envelope key decoding, no `home_connection_id`, no request stamping, no report queue (`ANDROID-DIAG-02`).
- `OkHttpRelaySessionClient` decodes response envelopes with lenient field reads; a top-level `diagnostics` key on a JSON-RPC response is not rejected today (add a regression test; do not assume), and `parseCapabilities` ignores extra keys.

## Required behavior (exact contract; copy the iOS acceptance)

### Decoder and capabilities (before the header is ever sent)

- The JSON-RPC response envelope accepts a top-level `diagnostics` key. Only two exact shapes are accepted: ready `{version, home_connection_id}` and submit `{version, request_id, correlation_id}`, with integer `version` 1 (Booleans and strings rejected) and IDs matching `conn-`/`req-`/`corr-` + 32 lowercase hex. Anything else is dropped; diagnostics never fail the RPC. Notifications are unchanged.
- Capabilities accept `diagnostics_correlation_v1` (Bool) and `client_diagnostic_report_schemas` ([Int]); a malformed value decodes as absent instead of failing ready. These keys stay out of the reconnect-equality/binding identity: gaining or losing diagnostics negotiation is never a conversation mismatch.

### Handshake and negotiation

- The bridge upgrade sets `X-Hermes-Diagnostics-Version: 1` exactly once (Home treats duplicates, `01` or `1.0` as legacy).
- `home_connection_id` is stored per socket from that socket's own ready (`conversation.open` or `conversation.reconnect`) before ready is returned, and cleared on socket install, retire, transport loss and close.
- Negotiated means: `diagnostics_correlation_v1 == true`, `2 ∈ client_diagnostic_report_schemas`, and a valid ready `home_connection_id`.

### Request stamping and events

- On a negotiated socket each `prompt.submit` gets a fresh `req-` + 32 hex (128-bit `SecureRandom`) and the **top-level** `diagnostics {version: 1, request_id}` — never inside `params`. Never reuse a token on a socket.
- Event names are only those Home's schema 2 accepts: the ten schema-1 names plus `client_response_received` and `client_request_resolved`.
- `request_started` is recorded **before** the frame is written, with `home_connection_id`, `request_id`, `leg: client_home`, `phase: submission`, `correlation_state: local_only`, `pending_state: awaiting_write`. A response whose diagnostics echo the same `request_id` records `client_response_received` (`correlation_id`, `response_kind` accepted/rejection by JSON-RPC result/error, `phase: response`); the client's final reading records `client_request_resolved`. `request_completed`/`request_failed` carry the same IDs and the `correlation_id` when known. `correlation_state` stays `local_only` on the client: only Home decides `linked`/`ambiguous`, and only after the carrying socket closes (Home divergence D1).
- Correlation IDs never enter logcat or the diagnostics journal.

### Schema-2 reports and packing

- Every stored event gets `event_id` (`evt-` + 32 hex) and a per-launch monotonic `sequence` once, at observation, and is never mutated (Home rejects conflicting `event_id` content across reports). Context persisted before this ticket gets an identity once on load.
- Each launch's origin is fixed when first observed: current launch uses its app/build/OS versions with `provenance_status: unverified`; a version failing the regex is null and the status `unavailable`; launches with no recorded origin are all-null `unavailable`. `source_revision` and `artifact_sha256` are always null unless the build can prove them (Android may later supply the APK digest; out of scope).
- Report schema is chosen per paired Home from its latest advertised `client_diagnostic_report_schemas` (2 only if advertised). Schema-1 reports keep their exact field set and omit schema-2 event names.
- Packing is deterministic: events in observation order are added greedily, with the origins they newly need, while the report stays ≤ 100 events and ≤ 65,536 body bytes, then it is sealed and the next opens; an event that cannot fit alone is dropped and counted; each report carries only origins its events reference; upload bytes are sorted-key compact JSON so a retry resends identical bytes. Existing bounds (10 queued reports, 1/min, 7-day expiry) are unchanged. The receipt check remains `schema == 1`.

### Legacy behavior

Home without the capability, a non-opted-in socket, or no valid ready ID: no request `diagnostics`, the same request fields as before, schema-1 reports.

## Acceptance criteria

- Decoder strictness (ten malformed ready shapes, malformed submit echo), header exactly once, capability-gated negotiation (five partial/malformed capability sets), reconnect to a legacy Home and to a new opted-in socket (no mismatch; a new socket uses its own ID, the old ID is cleared), fresh unique request IDs over many submissions, `request_started` present before the frame is written (assert write order with a fake socket), correlated accepted and rejected responses, legacy frame byte-for-byte unchanged.
- Reporter tests: schema-2 report field/origin/sequence shape, legacy schema-1 report, packing bounds/determinism/drop accounting/referenced origins, origin null rules, pending-submit socket loss records uncertain correlation (`request_failed` with `pending_state: unknown`, iOS `testSocketLossDuringNegotiatedPromptSubmitRecordsUncertainCorrelation`).
- Device acceptance per Home hand-off §3 against a HOME-NW-06 Home (needs the Android platform change): ready carries `conn-…`, submit carries `req-…`, response returns `corr-…`; **close the carrying socket before checking `/pair`** (associations become `linked` only at socket finalize); a legacy Home shows no decode failures, header errors or reconnect mismatches; a duplicate token shows `ambiguous`.

## Android design notes

- `HomeBridgeSessionClient.handleTextFrame` → Android's `dispatch`/`readOpenResult` in `OkHttpRelaySessionClient`; capabilities → `parseCapabilities`; header → the `Request.Builder` in `reconnectOnce` (next to `Authorization` and the `turn_keepalive` header from `ANDROID-HOME-10`).
- `home_connection_id` belongs in per-socket state next to `connectionId`, never in `HeldClaim`/profile storage (it is per socket and must be cleared with it).
- `SecureRandom` for IDs; format with lowercase hex, no UUID-with-dashes.
- Disclosure copy in the DIAG-02 settings must state that random connection/request identifiers are sent (no content).

## Dependencies

`ANDROID-DIAG-02` (queue, upload, settings); Home PR #71/#73 (deployed); Home accepting `platform: android` (see `ANDROID-DIAG-02`).

## Test notes

Fake socket with scripted ready/submit envelopes; vector tests copied from Home's validator for the exact regexes; no live Home in the JVM suite.

## Device verification and expected journal lines

`home diagnostics negotiated=true|false`, `home request submitted stamped=true|false` (no IDs). Compare Home's `/pair` association `state` with the device timeline.

## References

iOS: `spec-ios-diag-03.md`, `validation-ios-diag-03.md`; Home: `ios-handoff-home-nw-06-diagnostics.md`, `spec-home-nw-06-connection-failure-diagnostics.md`, `_bmad-output/specs/spec-connection-failure-diagnostics/event-contract.md`.
