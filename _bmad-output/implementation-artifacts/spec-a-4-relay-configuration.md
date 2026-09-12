---
title: 'A-4 — Relay configuration, Keystore credentials, and live transport'
type: 'story'
story_id: 'A-4'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: 'db95d34'
context:
  - 'docs/plans/2026-09-12-a-4-relay-configuration-design.md'
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
  - '../hermes-relay-tui/_bmad-output/planning-artifacts/epics.md'
  - '../hermes-relay-ios/_bmad-output/implementation-artifacts/spec-5-1-ios-independent-conversation-doorway.md'
---

## Intent

**Problem:** `A-1` through `A-3` are verified entirely against deterministic
fakes. `BootstrapClientPort` rejected every request with `SessionUnavailable`
and no Android code had ever opened a socket. `2-A-*` and `3-A-*` cannot be
validated honestly against that: participant timing proved against a fake only
re-states our own assumptions, and a fail-closed revocation path cannot be
demonstrated on a port that was never open.

**Approach:** Implement the configuration and handshake half of
`AndroidClientPort` — `snapshot()` and `reconnect()` — over an OkHttp
WebSocket, with the relay collection stored as non-secret JSON and each bearer
token sealed separately under an Android Keystore key. Turn submission and
normalized event delivery remain deliberately absent; they are `A-7`.

## Boundaries & Constraints

**Always:** Require `wss://` with a host name; store each token under its own
Keystore-sealed record; read a token only in the transport; adopt the Session
identity the relay returns in `hello_ack`; map every transport failure onto the
`AndroidReconnectOutcome` that `A-3`'s bounded ladder expects; report an
unconfigured relay as `NotConfigured` rather than as a failure; delete a
profile's credential when the profile is deleted.

**Never:** Accept `ws://` or a bare IP address; write a token to the profile
file, UI state, a saved instance bundle, or a log; retry a credential or
certificate failure inside the ladder; report an off-tailnet failure as a bad
credential; resume the prior Session after a reconnect; invent turn submission,
normalized events, audio, or Device operations in this slice.

## Acceptance Criteria

- Given a selected profile with a stored credential, when the Client connects,
  then the upgrade carries `Authorization: Bearer <token>`, the `hello` frame
  carries `protocol_version`, `client_id`, `device_id`, and `session_id`, and
  the acknowledged Session identity comes from the relay.
- Given no profile, or a profile with no stored credential, then `snapshot()`
  reports `NotConfigured` and no socket is opened.
- Given the relay rejects the credential with 401 or 403, then the outcome is
  `Unrecoverable` and the bounded ladder abandons that attempt.
- Given the relay host cannot be resolved, then the outcome is `Unrecoverable`
  and the reported reason names the Tailscale network rather than the
  credential.
- Given a certificate cannot be verified, then the outcome is `Unrecoverable`.
- Given the relay reports a different protocol version, or sends an `error`
  frame, then the outcome is `Unrecoverable` and names the cause.
- Given the connection is refused, reset, or the acknowledgement does not
  arrive within the timeout, then the outcome is `Retryable`.
- Given an endpoint that is not `wss://`, or is a bare IP address, then saving
  is refused with field-level guidance and nothing is stored.
- Given a profile is deleted, then its credential is deleted with it and the
  selection is cleared if it named that profile.

## Code Map

- `RelayProfile.kt` — profile model, save-time validation, collection with
  add/select/remove, and token-free JSON serialization.
- `RelayCredentialStore.kt` — `RelayCredentialStore` seam, the Keystore AES-GCM
  implementation, and an in-memory store for tests.
- `RelayProfileStore.kt` — file-backed persistence and
  `RelayConfigurationController`, which keeps credential and profile lifetimes
  together.
- `OkHttpRelaySessionClient.kt` — live `snapshot()` and `reconnect()`, the
  `hello`/`hello_ack` handshake, and the failure-to-outcome mapping.
- `RelayConfigurationScreen.kt` — profile list, write-only token field, and
  field-level validation.
- `MainActivity.kt` — builds the stores and hosts the configuration surface.
- `AndroidManifest.xml` — `INTERNET` permission.

## Implementation Notes

- **One port, not two.** The design proposed selecting between
  `OkHttpRelaySessionClient` and `BootstrapClientPort`. The implementation uses
  the OkHttp client alone: it reports `NotConfigured` until a profile with a
  credential exists, which is the same honest state with less machinery.
  `BootstrapClientPort` remains for tests.
- **Validation is a save-time concern.** The transport does not re-check the
  scheme, so deterministic tests can drive it against a local `MockWebServer`
  without a certificate. The `wss://` rule is enforced where a profile is
  written, which is where a user can act on it.
- **Credentials are hand-rolled over the Keystore.** An AES-GCM envelope with a
  per-record IV is a small amount of code and avoids taking a dependency for
  it. Key material never leaves the Keystore; only ciphertext and IV persist.
- **`UnknownHostException` is `Unrecoverable` by design.** It is the
  off-tailnet case, and `A-3` explicitly requires the ladder not exhaust itself
  against a condition that retrying cannot fix.

## Verification

See `validation-a-4-relay-configuration.md`, including the live handshake
against the household relay.

## Closure

Android holds a real, authenticated Hermes Session for the first time. The
configuration surface, credential storage, and handshake are complete and
verified live; turn submission and normalized event delivery remain absent and
are `A-7`'s work. No protocol, audio, or Device behavior was fabricated.
