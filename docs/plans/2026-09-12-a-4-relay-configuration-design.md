# A-4 — Relay configuration, Keystore credentials, and live transport

**Date:** 2026-09-12
**Status:** designed; prerequisites verified
**Story:** `A-4` (proposed; see `_bmad-output/planning-artifacts/android-ios-parity-audit.md`)

## Problem

Every Android story delivered so far — `A-1` initiation, `A-2` honest phases,
`A-3` recovery — is verified entirely against deterministic fakes.
`BootstrapClientPort` rejects everything with `SessionUnavailable`, and no
Android code has ever opened a socket. `2-A-*` and `3-A-*` both need a live
relay to be validated honestly: participant timing cannot be proven against a
fake that emits our own assumptions back at us, and a fail-closed revocation
path cannot be validated against a port that was never open.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Scope | Configuration and handshake only | Makes `snapshot()` and `reconnect()` live; leaves `beginTurn()`/`observeTurn()` on fakes for `A-7`. Sixteen inbound event types are their own slice. |
| Profile model | Collection from the start | iOS had to write a `migrateLegacyProfile` path after starting single-profile. Storage schema is the expensive thing to change later. |
| Transport | `wss://` via `tailscale serve` | Real Let's Encrypt certificate on the MagicDNS name. Default Android trust, no network security config, no cleartext exception, no pinning. |
| Relay bind | `host: 0.0.0.0` | Lets `serve` proxy through loopback while existing direct tailnet clients keep working. |
| Live gate | Emulator on the tailnet | Verified working; no physical device required. |

## Architecture

Five new pieces. Nothing already built changes — the reducer, the recovery
controller, and all 24 existing tests keep guarding the boundary.

- **`RelayProfile` / `RelayProfileCollection`** — the non-secret half. Endpoint,
  `clientId`, `deviceId`, `displayName`, stable `id`, and a `selectedId`
  pointer, as JSON in app-private storage. Endpoint validation lives here and
  rejects `ws://` and bare `100.x` addresses: neither can match a certificate.
- **`RelayCredentialStore`** — the secret half. One token per profile keyed by
  profile id, in `EncryptedSharedPreferences` under a Keystore master key. It
  never returns a token to the UI layer; only the transport reads it, so a
  token cannot reach Compose state, logs, or a saved instance bundle.
- **`OkHttpRelaySessionClient`** — implements `snapshot()` and `reconnect()`.
  Opens the WebSocket with `Authorization: Bearer <token>` on the upgrade,
  sends `hello` with `protocol_version: 1`, awaits `hello_ack`, and surfaces
  the negotiated `sessionId`.
- **`RelayConfigurationScreen`** — Compose list with add, delete, select, and
  field-level validation.
- **Wiring** — `MainActivity` selects `OkHttpRelaySessionClient` when a profile
  is configured and keeps `BootstrapClientPort` when none is, so the shell
  stays honest about being unconfigured rather than pretending to fail.

## Error taxonomy

`A-3` treats the outcomes very differently: `Retryable` continues the bounded
ladder, `Unrecoverable` abandons it on that attempt. Mapping real failures
onto them is the substance of this story.

| Failure | Outcome | Why |
|---|---|---|
| DNS fails for the `.ts.net` name | `Unrecoverable` — "not on the tailnet" | Retrying three times in eight seconds will not summon a VPN. `A-3` explicitly requires the ladder not exhaust itself here. |
| TLS or certificate failure | `Unrecoverable` | Configuration, not weather. |
| HTTP 401/403 on upgrade | `Unrecoverable` | Bad or revoked token; send the user to configuration. |
| `hello_ack` reports protocol mismatch | `Unrecoverable` | Version skew needs a human. |
| Connection refused, timeout, reset | `Retryable` | Genuine transient loss — what the ladder exists for. |
| `hello_ack` never arrives in time | `Retryable` | Could be a slow host. |

Unconfigured is not failure: with no profile saved, `snapshot()` reports
`NotConfigured` and the UI leads with *Configure relay*, not a retry button.
That is iOS's `IOS-UX-F1` finding, inherited rather than rediscovered.

## Testing

**Deterministic, runs in CI.** OkHttp's `MockWebServer` serves a genuine
WebSocket, so the handshake is tested for real with no network:

- The upgrade carries `Authorization: Bearer <token>` and correct `hello` fields.
- A well-formed `hello_ack` yields `Connected` with the server's `sessionId`.
- Each failure row above maps to its stated outcome, each as its own test.
- Credential round-trip, and profile deletion removing its token.
- Endpoint validation rejects `ws://`, a bare `100.x` host, and malformed URLs.

**Instrumentation.** The configuration screen: add, select, delete, field-level
errors, and an assertion that a token never appears in rendered state.

**Live gate.** Connect to the real relay from the emulator and observe a
`hello_ack`.

## Prerequisites — verified 2026-09-12

Both were unknowns when the design started. Both are now settled by
measurement, not assumption.

### Relay reachable over TLS — done

The relay had no TLS terminator; port 443 was refused. The first `serve`
attempt proxied to the node's own tailnet IP and **hung on every request**,
which ruled out the HTTP/2-versus-upgrade theory — a plain GET hung too. The
pre-existing `8443 -> 127.0.0.1:5001` mapping answered in 0.2s, isolating the
cause to the non-loopback target.

Fix applied: `voice_session.extra.host` changed from `100.90.186.57` to
`0.0.0.0` in the `amanda` profile on `media-server` (backed up first, YAML
validated, gateway restarted via `launchctl kickstart`), then
`tailscale serve --bg --https=443 http://127.0.0.1:8792`.

Verified end to end:

- Let's Encrypt certificate for the MagicDNS name; `SSL certificate verify ok`.
- ALPN negotiates `http/1.1` on request, which is what OkHttp needs for WebSockets.
- Plain GET through the proxy: `401` in 57ms from aiohttp.
- WebSocket upgrade through the proxy: reaches the backend and returns `401`
  rather than hanging.

`401` is the correct result for an unauthenticated probe. A `101` still needs a
valid token and is part of the live gate.

### Emulator reaches the tailnet — done, better than predicted

The parity audit predicted the emulator could not resolve MagicDNS and that a
physical device would be needed. That was wrong. On a plain launch with no
`-dns-server` flag, the emulator resolves
`media-server.taila59979.ts.net -> 100.90.186.57` and opens TCP to both 8792
and 443 by name. No hardware required.

## Open items for the spec

- **`allowed_users` allowlist.** `voice_session.extra.allowed_users` is
  currently the scalar `amanda-laptop`, and the home channel `chat_id` is
  `amanda-laptop:iphone`. If the allowlist matches on `client_id`, an Android
  client using `client_id: amanda-laptop` with its own `device_id` may be
  admitted with no server change. Unverified — resolve it against a real
  `hello` before assuming either way.
- **Token provenance.** How an Android token is minted and rotated is not yet
  described. `A-4` stores and sends one; it does not invent an issuance flow.
- **`0.0.0.0` widens exposure** from the tailnet to the home LAN as well.
  Authentication still rejects unauthenticated requests with `401`, but the
  posture change is deliberate and recorded here. Tightening to `127.0.0.1`
  later would require moving iOS to the `wss://` endpoint too.
