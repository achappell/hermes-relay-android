---
title: 'Story 5 — Android Home bridge migration'
type: 'story'
story_id: '5-A-4'
source_story: '5'
created: '2026-09-14'
status: 'ready-for-dev'
route: 'home-first'
upstream_contract: 'hermes-relay-home@f659980'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
  - '_bmad-output/implementation-artifacts/spec-5-a-1-independent-doorway.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-standard-hermes-compatibility-migration/SPEC.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-standard-hermes-compatibility-migration/standard-baseline.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-home-service-foundation/credential-lifecycle.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-home-bridge-route-roaming/SPEC.md'
  - 'https://github.com/achappell/hermes-relay-home/blob/f659980/docs/contracts/v1/bridge.md'
warnings: []
deferred:
  - 'Home route identity proof, route discovery, and public TLS deployment remain Home-owned decisions; Android consumes an approved route.'
  - 'Physical microphone, speaker, and production Home endpoint evidence remain environment gates after JVM and instrumentation coverage.'
---

## Intent

**Problem:** Android currently connects to the fork-only `/voice-session` route,
sends a personal Hermes bearer, and expects response PCM on that gateway socket.
That is the wrong boundary for the next migration wave. Story 5 must move the
client behind the paired Home service without losing the local Profile,
Profile-scoped history, capture and playback lifecycle, interruption,
reconnect, or honest unavailable state.

**Decision:** Android is Home-first. The endpoint opens the versioned Home
bridge at `/api/v1/bridge/ws` with `Authorization: Device <device-credential>`.
Home alone uses the server-held Hermes credential against Standard Hermes
`/api/ws` and `/api/audio/speak-stream`. Android receives only an opaque
`conversation_handle`, Home-owned `turn_id`, safe route/capability state, and
normalized Standard event meaning. It never becomes a direct Standard client.

The upstream contract is committed at `hermes-relay-home@f659980` in
[`docs/contracts/v1/bridge.md`](https://github.com/achappell/hermes-relay-home/blob/f659980/docs/contracts/v1/bridge.md)
and is proposed in Home PR #9. The Android implementation must use that
contract even when a sibling checkout has not yet pulled the PR.

**Approach:** Keep the existing typed `AndroidClientPort`, reducer, Keystore,
Profile history, capture, and audio seams. Replace the fork wire adapter and
credential assumptions behind those seams. Treat migration as an approved
pairing transition, not as a cryptographic conversion of a personal bearer:
the old credential remains rollback-only until retirement, the new Home
credential is stored separately, and the target binding becomes usable only
after Home authorization and `conversation.open` succeed.

## Boundaries & Constraints

**Always:**

- Use Home-first transport at `wss://<approved-home-route>/api/v1/bridge/ws`.
  Send the Device credential only in the WebSocket upgrade header; never put
  it in a URL, profile JSON, event, log, error, or Compose state.
- Treat a production Home credential as 32 random bytes encoded with unpadded
  base64url (43 ASCII characters). Android stores the opaque value in the
  platform secure store and never derives it from the old Hermes bearer.
- Preserve `RelayProfile.id`, display name, device label, and Profile-scoped
  Local History. Store the opaque Home conversation handle as binding metadata,
  never a runtime Hermes Session ID or internal Profile ID.
- Keep JSON parsing, request correlation, event normalization, binary PCM
  handling, reconnect, and credential access below the typed Android port. UI
  code receives normalized events only.
- Preserve cumulative Standard text-preview semantics, event order, prompt and
  command correlation, interrupt terminal semantics, separate audio metadata,
  signed-16 little-endian PCM, and explicit absence of network timing.
- Fail closed on a missing, malformed, unprotectable, expired, revoked, or
  unauthorized Home binding. Do not capture, submit, switch route, or claim
  readiness until the binding is valid.
- Preserve an uncertain turn after transport loss and reconnect the same opaque
  conversation handle without automatically resending the prompt or replaying
  an old response. A fresh user action is required for a resend.

**Never:**

- Connect Android directly to Standard `/api/ws` or
  `/api/audio/speak-stream`, fall back to `/voice-session` during a Home turn,
  or switch routes while a turn is active.
- Send a personal Hermes bearer, runtime Session ID, local Profile ID, raw
  device credential, or arbitrary Profile/Session selector across the Home
  bridge.
- Invent a Home envelope, silently translate cumulative previews into repeated
  messages, treat an interrupt acknowledgement as completion, or claim
  Speaking before the PCM sink has accepted valid audio metadata.
- Declare authorization from credential presence alone. `hasToken()` must not
  outrank a successful secure read and Home bridge authorization.
- Delete the rollback credential before the explicit migration retirement gate,
  create a second Profile/history, or retry a request whose delivery is
  uncertain without explicit user action.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Legacy Profile migration succeeds | Existing local Profile and history; approved Home pairing returns route, Device credential, and opaque conversation handle | Same Profile ID, label, display name, and history remain; Home credential is stored in a distinct secure slot; target binding is usable only after authorization and `conversation.open` returns `ready` | Source credential stays rollback-only until retirement; no duplicate Profile, history, or credential |
| Pairing or secure migration fails | Pairing is cancelled, credential is malformed/unprotectable, or secure write fails | Target binding remains unavailable; source Profile/history and rollback credential remain intact | No Home turn, microphone capture, route switch, or automatic retry |
| Home bridge opens | Approved route, valid Device credential, opaque conversation handle | Upgrade uses `/api/v1/bridge/ws` and `Authorization: Device`; Android sends `conversation.open`; `ready` exposes only safe capabilities and route label | HTTP 401 or typed unavailable reason maps to `Unavailable`; no Standard request is attempted by Android |
| Home cannot bind conversation | Handle is stale/mismatched, Home authorization is revoked, or Hermes is unavailable | Doorway shows a typed unavailable state with a safe reason | No Profile/session substitution, capture, prompt, or guessed capability |
| Typed turn is accepted | `ready` binding and non-empty prompt | Android sends `prompt.submit` with the opaque handle and text; Home returns its opaque `turn_id`; UI enters the existing in-flight state | `request_rejected` is known non-delivery; timeout/transport failure is uncertain and is never auto-resubmitted |
| Cumulative text events arrive | Standard `message.delta`/completion events are forwarded by Home | Normalizer preserves event type/correlation and replaces a cumulative preview or appends only a verified suffix | A malformed, stale, or uncorrelated event is ignored or typed as protocol failure; it is not rendered as a new answer |
| Response audio arrives | `audio.start`, zero or more signed-16 little-endian PCM frames, and `audio.end` or `audio.fallback` | Audio bytes go only to the Android sink; Speaking is claimed after valid start metadata and completion waits for drain; text remains readable | Missing/unsupported metadata or fallback marks audio unavailable without discarding text |
| User interrupts | Active Home turn and advertised `interrupt` capability | Android sends `session.interrupt` with handle and Home turn ID; playback stops locally, then the reducer waits for the matching terminal event | Acknowledgement alone does not settle the turn; connection loss leaves delivery/terminal state honest |
| Transport loss | Socket ends before or after prompt acceptance | Current turn becomes disconnected/uncertain; reconnect re-authenticates and opens the same handle | No automatic prompt replay, old-response replay, Profile switch, or mid-turn route change |
| Credential or identity leak attempt | Profile serialization, diagnostics, error, or bridge event includes secret-shaped data | Serialized output contains no Hermes bearer, Device credential, runtime Session ID, or internal Profile ID | Redact/reject the frame and expose only a safe typed reason |

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/AndroidClientPort.kt:1-110` —
  replace `AndroidTurnBinding.sessionId` and reconnect results that expose a
  runtime Session with an opaque conversation/connection binding; keep Compose
  independent of wire frames.
- `app/src/main/java/com/achappell/hermesrelay/OkHttpRelaySessionClient.kt:30-432` —
  primary transport seam. Build the approved Home bridge URL, send Device auth,
  correlate JSON-RPC requests, open/resume the opaque handle, map notifications,
  accept same-bridge binary PCM, and retain no direct Standard or fork target.
- `app/src/main/java/com/achappell/hermesrelay/HermesEventNormalizer.kt:1-220` —
  isolate the Home event envelope and preserve Standard event identity,
  cumulative text replacement/suffix rules, terminal outcomes, prompt/command
  correlation, and audio framing without exposing JSON to the UI.
- `app/src/main/java/com/achappell/hermesrelay/RelayCredentialStore.kt:1-128` —
  add typed Home and rollback-only credential slots; make presence checks prove
  secure readability; never serialize either secret.
- `app/src/main/java/com/achappell/hermesrelay/RelayProfile.kt:1-160` and
  `RelayProfileStore.kt:1-100` — version the non-secret binding metadata for the
  approved Home route and opaque conversation handle while preserving legacy
  JSON reads, local IDs, labels, and selected Profile behavior.
- `app/src/main/java/com/achappell/hermesrelay/AndroidTurnState.kt:1-300`,
  `AndroidRecovery.kt:1-220`, and `AndroidDoorwayState.kt:1-90` — preserve the
  reducer and no-replay recovery rule while adding safe Home reasons and
  connection/turn bindings that cannot contain runtime Session identity.
- `app/src/main/java/com/achappell/hermesrelay/AndroidAudioSink.kt:1-230` and
  `MainActivity.kt:44-220` — keep PCM and lifecycle ownership on Android;
  explicitly tear down observation, capture, playback, and sockets when the
  screen/activity leaves the active lifecycle.
- `app/src/test`, `app/src/androidTest`, and the existing live handshake tests —
  add contract fixtures and record JVM, instrumentation, and live evidence in
  `validation-5-a-4-home-bridge-migration.md`.

## Tasks & Acceptance

**Execution:**

1. **Home bridge transport** — update the client port and OkHttp adapter to use
   `/api/v1/bridge/ws` with `Authorization: Device`, `schema: 1` JSON-RPC,
   `conversation.open`, `prompt.submit`, `session.interrupt`, and bounded
   request/readiness waits. The adapter must bind every event to the local
   opaque handle and Home turn, never to a runtime Hermes Session ID.
2. **Normalized events and audio** — update the normalizer and sink path for
   `method: event`, `audio.start`, raw signed-16 little-endian binary frames,
   `audio.end`, and `audio.fallback`. Preserve Standard event names and
   cumulative preview meaning; make text survive an audio-sidecar failure and
   keep timing explicitly absent.
3. **Secure Home pairing transition** — add a typed pairing/configuration seam
   that accepts an approved route, newly issued Device credential, and opaque
   conversation handle. Version local binding metadata, preserve Profile and
   history identity, retain the legacy credential only in a rollback-only slot,
   and make repeat migration idempotent.
4. **Fail-closed authorization** — replace presence-only credential checks with a
   secure-read plus Home authorization/readiness check. Map HTTP 401 and the
   contract's safe unavailable reasons without leaking secrets or silently
   falling back to the fork.
5. **Lifecycle and recovery** — keep capture/playback/observation ownership
   inside the Android adapter/controller, close sockets and audio on lifecycle
   teardown, preserve uncertain turns, reconnect the same opaque handle, and
   require an explicit user action before any resend.
6. **Evidence and local records** — add JVM fixtures for JSON-RPC, migration,
   cumulative text, PCM framing, interrupt, no-replay, and redaction; add
   instrumentation coverage for doorway/unavailable/history behavior; run the
   repository verification commands and record exactly what live hardware could
   and could not prove.

**Acceptance Criteria:**

- **Given** a legacy Android Profile with existing local history and a valid
  approved Home pairing, **when** migration completes, **then** the same local
  Profile ID, label, selected state, and history remain, the new 43-character
  Home Device credential is stored in a separate secure slot, and the target is
  usable only after Home authorization and `conversation.open` return `ready`.
- **Given** a malformed, expired, revoked, or unprotectable source or Home
  credential, **when** migration or doorway readiness is attempted, **then**
  Android shows a typed unavailable state before microphone capture or prompt
  submission, preserves the source/rollback slot, and emits no turn.
- **Given** a ready approved route, **when** Android connects, **then** the
  WebSocket URL ends in `/api/v1/bridge/ws`, the upgrade uses exactly the
  `Authorization: Device` form, and no endpoint-facing frame contains a Hermes
  bearer, runtime Session ID, internal Profile ID, or raw Device credential.
- **Given** `conversation.open` returns `ready`, **when** a non-empty typed
  prompt is submitted, **then** Android sends `prompt.submit` with only the
  opaque conversation handle and text, binds the returned Home turn ID, and
  maps forwarded Standard events without renaming or duplicating cumulative
  previews.
- **Given** a response with valid `audio.start` metadata and PCM frames,
  **when** the frames arrive, **then** Android delivers signed-16 little-endian
  PCM only to the audio sink, claims Speaking only after sink acceptance, waits
  for drain before completion, and does not claim network timing.
- **Given** an active turn and advertised interrupt capability, **when** the
  user interrupts, **then** Android sends `session.interrupt`, stops local
  playback promptly, and settles only after the matching terminal event; an
  acknowledgement alone never becomes Complete or Interrupted.
- **Given** transport loss before or after prompt acceptance, **when** Android
  reconnects, **then** it re-authenticates and reopens the same opaque handle,
  preserves uncertainty, sends no automatic replacement prompt, replays no old
  response, and requires a fresh user action for a resend.
- **Given** the same Profile is migrated twice, **when** the second approved
  pairing is applied, **then** the existing Profile/history and binding are
  updated in place with no duplicate identity, credential, turn, or replay.

## Design Notes

The Home bridge intentionally multiplexes endpoint-facing audio on the
authenticated Home WebSocket even though Home uses Standard's separate audio
sidecar internally. This keeps the Android adapter on one endpoint boundary;
it does not change Standard's `/api/ws` or `/api/audio/speak-stream` semantics.

Pairing UI, QR transport, approved-route discovery, same-household identity
proof, and public TLS deployment are not Android-owned by this story. Android
must consume their approved result through a typed seam and report failure when
that result is absent or invalid. The absence of network timing is a capability
fact, not a permission to estimate word offsets from arrival timestamps.

## Verification Plan

Run the repository gate from the Android root:

```sh
./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon
scripts/check-apk-metadata.sh
```

With an available emulator or device, also run:

```sh
./gradlew connectedDebugAndroidTest
```

Instrumentation and live-relay evidence must identify the environment and
limitations. The emulator's `-no-audio` configuration cannot prove a real
microphone, speaker, network permission, or recognizer timing; a green JVM
suite is necessary evidence, not a substitute for those gates.

## Build Auto Handoff

Status: ready-for-dev

The former intent gap is resolved by Home PR #9 (`f659980`). The strategic
choice is Home-first, the endpoint path and envelope are versioned, and the
credential boundary is explicit. No Android implementation or Android test
claim is made by this handoff; those are the next auto-loop stages.
