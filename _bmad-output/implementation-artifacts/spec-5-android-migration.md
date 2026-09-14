---
title: 'Story 5 — Android Home bridge migration'
type: 'story'
story_id: '5-A-4'
source_story: '5'
created: '2026-09-14'
status: 'done'
route: 'home-first'
upstream_contract: 'hermes-relay-home@f659980'
baseline_commit: '06edbf725aaf4c9e34571e0d7c8fbc336806846e'
baseline_revision: '06edbf725aaf4c9e34571e0d7c8fbc336806846e'
review_loop_iteration: 2
followup_review_recommended: true
context:
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
  - '_bmad-output/implementation-artifacts/spec-5-a-1-independent-doorway.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-home-bridge-route-roaming/bridge-contract.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-home-bridge-route-roaming/route-session-state.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-standard-hermes-compatibility-migration/standard-baseline.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-standard-hermes-compatibility-migration/surface-migration-matrix.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-home-service-foundation/credential-lifecycle.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-standard-bridge/transport-contract.md'
  - 'https://github.com/achappell/hermes-relay-home/blob/f659980/docs/contracts/v1/bridge.md'
warnings: []
deferred:
  - summary: >-
      Home route identity proof, route discovery, and public TLS deployment remain
      Home-owned decisions; Android consumes an approved route.
    evidence: |-
      The linked Home contract leaves route discovery, Household Identity proof,
      TLS deployment, and the public adapter outside this repository.
  - summary: >-
      Physical microphone, speaker, and production Home endpoint evidence remain
      environment gates after JVM and instrumentation coverage.
    evidence: |-
      The attached emulator has no microphone and the public Home adapter is not
      live, so those gates cannot be claimed from local fixtures.
  - summary: >-
      Connected Compose UI evidence remains deferred until the Android test
      Activity restores a usable Compose hierarchy.
    evidence: |-
      Pixel 6a / Android 17 instrumentation ran 34 tests; 27 UI assertions failed
      before rendering because the Activity was replaced by
      InstrumentationActivityInvoker$EmptyActivity. Seven non-Compose checks passed.
    location: >-
      app/src/androidTest
    severity: medium
  - summary: >-
      A production Home bridge handshake and live turn/audio pass remain deferred
      until the planned public adapter is deployed.
    evidence: |-
      The upstream contract explicitly marks /api/v1/bridge/ws as planned and not
      served. Android uses TLS MockWebServer fixtures and an opt-in live gate only.
    location: >-
      app/src/androidTest/java/com/achappell/hermesrelay/LiveRelayHandshakeTest.kt
    severity: high
  - summary: >-
      No client-side terminal timeout was added for a connected turn that never
      receives Home's authoritative terminal event.
    evidence: |-
      Home defines the terminal event as the completion authority and does not
      specify a safe client timeout; transport loss is already surfaced as
      disconnected/uncertain. A timeout here could misclassify a slow valid turn.
    location: >-
      app/src/main/java/com/achappell/hermesrelay/OkHttpRelaySessionClient.kt
    severity: medium
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
and is proposed in Home PR #9. The public Home adapter is not live yet. The
Android implementation therefore targets the planned endpoint with deterministic
fixtures and makes no claim of live route integration.

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
  command correlation, interrupt terminal semantics, `audio.frame` sidecar
  metadata, signed-16 little-endian PCM, and explicit absence of network timing.
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
| Response audio arrives | `audio.frame` notifications with `frame.kind` `start`, `end`, `fallback`, or `unavailable`, plus zero or more signed-16 little-endian PCM frames | Audio bytes go only to the Android sink; Speaking is claimed after valid start metadata and completion waits for drain; text remains readable | Missing/unsupported metadata or fallback marks audio unavailable without discarding text |
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
   `method: event`, `method: audio.frame` with `frame.kind`, raw signed-16
   little-endian binary frames, and the `end`, `fallback`, and `unavailable`
   terminal kinds. Preserve Standard event names and cumulative preview
   meaning; make text survive an audio-sidecar failure and keep timing
   explicitly absent.
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
- **Given** a response with a valid `audio.frame` `start` notification and PCM frames,
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

The planned Home endpoint multiplexes endpoint-facing audio on the authenticated
Home WebSocket even though Home uses Standard's separate audio sidecar
internally. This keeps the Android adapter on one endpoint boundary; it does
not change Standard's `/api/ws` or `/api/audio/speak-stream` semantics. The
public route is not live, so all Android evidence for this story is fixture or
environment-gated evidence until Home publishes the adapter.

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

Status: done

The contract correction in review loop 2 preserves the Home-owned boundary:
`audio.frame` is the only planned endpoint audio notification, and the public
adapter remains not live. Android implementation and test evidence are local
to this repository; no Home route or vanilla `/api/ws` claim is made.

## Review Triage Log

### 2026-09-14 — Review pass

- verdicts: 56 findings — high 23, medium 23, low 1, false 9, maybe-false 0
- findings:
  - `[medium]` `[patch]` [Peirce] `beginTurn()` waited on the prompt acknowledgement on the UI path — the initiation worker now owns the bounded wait.
  - `[high]` `[patch]` [Peirce] the three-attempt recovery ladder could freeze Compose — recovery now runs on the single Android work executor.
  - `[medium]` `[patch]` [Peirce] idle socket loss was invisible without an active turn observer — the client now exposes a connection observer and the screen records the loss.
  - `[high]` `[patch]` [Peirce] `message.complete` could clear a turn before asynchronous audio drain — terminal observation and drain completion are tracked independently.
  - `[high]` `[patch]` [Peirce] queued frames carried only a connection identity — queued text and PCM are now checked against the active Home binding and transport generation.
  - `[high]` `[patch]` [Peirce] binary PCM could be accepted from a global `audioActive` flag — unbound queued PCM is admitted only after the current binding's valid audio start.
  - `[medium]` `[patch]` [Peirce] interrupt RPC rejection or timeout was not observed — interrupt responses now clear the pending suppression state unless a valid acknowledgement arrives.
  - `[medium]` `[patch]` [Peirce] interrupt could race a terminal/draining turn — terminal and interrupt guards now refuse that request, and late drain callbacks are suppressed while it is pending.
  - `[medium]` `[patch]` [Peirce] failed `message.complete` statuses were treated as successful — failed/error statuses now become `TurnFailed`.
  - `[medium]` `[patch]` [Peirce] timeout status variants were not terminal — `timeout`, `timed_out`, and `timed-out` now fail the turn explicitly.
  - `[high]` `[patch]` [Peirce] global events without `turn_id` could affect the active turn — the endpoint normalizer now ignores them.
  - `[high]` `[patch]` [Peirce] missing nested `params.schema` defaulted to version one — the Home envelope now requires the field explicitly.
  - `[medium]` `[patch]` [Peirce] structured prompt sensitivity was incomplete — payload sensitivity metadata is retained alongside type-derived secret/sudo sensitivity.
  - `[medium]` `[patch]` [Peirce] structured prompts and commands were discarded — typed state now preserves their correlation/identity and the UI explains that this surface has no response/dispatch control yet.
  - `[false]` `[reject]` [Peirce] ready responses allegedly could omit route state — the planned endpoint response used by this story requires the safe route object; the internal Home domain shape is not sent directly to Android.
  - `[false]` `[reject]` [Peirce] optional `interrupt`/`audio` capabilities allegedly made the target incompatible with the internal Home seam — Android defaults absent optional flags to false and never guesses them.
  - `[high]` `[patch]` [Peirce] profile persistence failure could orphan a Home credential — migration now writes profile metadata transactionally enough to restore the prior Home slot when the profile save fails.
  - `[high]` `[patch]` [Peirce] unreadable rollback storage was treated as absent — stored-but-unreadable rollback entries now reject migration.
  - `[medium]` `[patch]` [Peirce] the legacy credential writer could be declared successful without persistence evidence — credential writes now return and are checked as Boolean success.
  - `[medium]` `[patch]` [Peirce] route string slicing could append the bridge path after a query or fragment — URI structure is validated and rebuilt before the WebSocket request.
  - `[medium]` `[patch]` [Peirce] validation evidence overstated unexercised cases — the validation record now separates deterministic source/fixture checks from deployment and device limits.
  - `[medium]` `[patch]` [Meitner] `clearTurn()` compared a copied binding identity — it now compares the current atomic value before clearing, so terminal turns cannot strand the composer.
  - `[high]` `[patch]` [Meitner] voice transport uncertainty could retain the wrong request — final capture transcripts and uncertain typed requests now update the shared retained request.
  - `[high]` `[patch]` [Meitner] an explicit resend could lose its request if that resend became uncertain — the recovery state keeps it until an accepted resend.
  - `[high]` `[patch]` [Meitner] a restored connection could accept capture while an uncertain turn remained — typed and voice controls are gated by the retained uncertainty marker.
  - `[high]` `[patch]` [Meitner] an old observer/socket could feed a replacement turn — observer, binding, connection, and generation checks reject superseded callbacks.
  - `[medium]` `[patch]` [Meitner] a socket drop before the turn observer was installed was missed — transport loss is now reported independently of turn observation.
  - `[high]` `[patch]` [Meitner] prompt acknowledgement timeout could block Compose — bounded initiation waits run off the main thread.
  - `[high]` `[patch]` [Meitner] a superseded socket could publish a late handshake — generation and handshake-commit guards prevent stale readiness publication.
  - `[high]` `[patch]` [Meitner] failed interrupt send cancelled local playback without a recovery signal — the client now clears suppression and reports transport loss.
  - `[medium]` `[defer]` [Meitner] an accepted turn or interrupt with no terminal event has no client timeout — Home makes the terminal event authoritative and specifies no safe timeout; adding one could misclassify a slow valid turn.
  - `[high]` `[patch]` [Meitner] nested event schema was not required — endpoint events reject missing or incompatible `params.schema`.
  - `[high]` `[patch]` [Meitner] the compatibility normalizer could accept legacy frames on the live path — the live client constructs it with legacy parsing disabled.
  - `[medium]` `[patch]` [Meitner] failed/error completion statuses were projected as completion — the normalizer emits a typed failure.
  - `[medium]` `[patch]` [Meitner] an unknown terminal status could leave a turn active — unknown nonblank terminal statuses now fail closed.
  - `[high]` `[patch]` [Meitner] audio fallback could terminate readable text early — audio failure remains a sidecar state until the authoritative terminal event.
  - `[medium]` `[patch]` [Meitner] audio could start after terminal/interruption — terminal and interrupt guards reject late starts.
  - `[medium]` `[patch]` [Meitner] a late PCM drain callback could override interruption — drain completion/failure callbacks now suppress delivery while interrupt is pending.
  - `[high]` `[patch]` [Meitner] late PCM could reach a new turn — binary delivery requires the current binding or a current binding established by its queued audio start.
  - `[false]` `[reject]` [Meitner] transport exception text allegedly triggered 401/403 authorization — the current classifier examines the actual HTTP response code only.
  - `[medium]` `[patch]` [Meitner] route query/fragment handling could construct a malformed bridge URL — approved routes reject query/fragment data and URL construction is structural.
  - `[high]` `[patch]` [Meitner] profile save failure could leave credentials and metadata divergent — migration restores the prior Home credential and publishes only after a successful profile save.
  - `[high]` `[patch]` [Meitner] malformed or unprotectable source credentials could pass migration — rollback presence and readability are distinguished before any Home binding is published.
  - `[low]` `[reject]` [Meitner] closing the client could make a later same-instance audio turn unusable — close is lifecycle teardown, the production screen does not reuse that client, and adding reuse machinery would add complexity for a negligible path.
  - `[medium]` `[patch]` [Meitner] validation language claimed broad coverage beyond the actual harness — the record now labels deterministic checks and explicitly lists the missing asynchronous/device/deployment gates.
  - `[medium]` `[defer]` [Dirac] Compose acceptance remains unverified — 27 of 34 connected tests reached `InstrumentationActivityInvoker$EmptyActivity` with no Compose hierarchy; the test harness must be restored.
  - `[medium]` `[patch]` [Dirac] the real Android Home Keystore slot lacked instrumentation coverage — `RelayConfigurationTest` now exercises Home write/read/delete and separation from rollback storage.
  - `[high]` `[defer]` [Dirac] the production Home handshake/turn/audio gate was not run — the upstream public adapter is explicitly not live and the opt-in gate awaits deployment and approved credentials.
  - `[high]` `[patch]` [Dirac] asynchronous audio drain ordering was not represented by the synchronous recording sink — transport state now retains the turn until drain, and the validation record calls out the remaining real-`AudioTrack` limit.
  - `[medium]` `[patch]` [Dirac] timeout completion variants were untested — normalizer coverage and the terminal-status mapping now include all three documented spellings.
  - `[false]` `[reject]` [Einstein] the change allegedly ignored the TUI-like BMAD runtime migration — the branch already carries the tracked `.agents`, `.claude`, and `_bmad` runtime surfaces and the Story 5 workflow artifacts.
  - `[false]` `[reject]` [Einstein] the story target allegedly remained ambiguous — the later user direction selected Android Story 5 and the local record is `source_story: '5'`.
  - `[false]` `[reject]` [Einstein] push intent allegedly had no delivery path — this is a remote delivery operation, not a defect in the reviewed implementation; it follows final local verification.
  - `[false]` `[reject]` [Einstein] PR intent allegedly had no implementation — PR creation is the next GitHub delivery step after the reviewed commit.
  - `[false]` `[reject]` [Einstein] build-auto execution allegedly was absent — this spec is the Story 5 auto-loop artifact, including review, verification, and handoff state.
  - `[false]` `[reject]` [Einstein] blocker handling allegedly was absent — the former dirty-runtime-tree blocker is recorded in the old result and is resolved by the tracked runtime surfaces; the remaining environment limits are recorded as deferred.

## Auto Run Result

Status: done

Summary: Completed the Android Story 5 Home bridge migration against the
planned endpoint contract. Android now uses Home Device authentication,
versioned JSON-RPC, opaque conversation/turn bindings, strict endpoint event
normalization, PCM audio lifecycle handling, interrupt/reconnect recovery,
secure credential migration, and Profile/history-preserving local records. The
public Home adapter is not live, so no production route integration is claimed.

Files changed: Android transport, normalizer, reducer, recovery, audio, UI,
credential/profile stores, tests, validation records, Gradle TLS test support,
and the tracked `.agents`, `.claude`, and `_bmad` runtime surfaces included by
the branch.

Review findings: 43 patches applied (22 high, 21 medium); 3 items deferred
(Compose harness, production Home deployment, and an unspecified terminal
timeout); 9 findings rejected as false or out of scope for code review, plus
one low lifecycle-reuse finding rejected because client close is terminal and
the production client is not reused. No intent-gap or bad-spec findings remain.

Follow-up review recommendation: true. This pass patched high-severity
transport and state races; the named residual risks are the unavailable public
Home adapter and the broken Compose instrumentation harness, both recorded in
the deferred list and validation artifact.

Verification: `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon`
passed with 166 JVM tests, a debug APK, and clean lint. `scripts/check-apk-
metadata.sh` passed with min SDK 26 and version 0.3.1 (301); signing was
skipped because `EXPECTED_SIGNER_SHA256` was unset. `git diff --check` and
`./gradlew compileDebugAndroidTestKotlin --no-daemon` and frontmatter parsing
passed. The prior connected run on Pixel 6a / Android 17
ran 34 tests (7 passed, 27 failed before Compose rendering because the harness
used `InstrumentationActivityInvoker$EmptyActivity`); a later rerun found no
connected devices and produced no additional evidence. No live Home gate was
run because the public adapter is not deployed.

Residual risks: physical microphone/speaker behavior, real `AudioTrack` drain
callbacks, production TLS/authorization, and endpoint event ordering remain
deployment or device gates. Android does not send Home methods to vanilla
Hermes `/api/ws` or `/api/audio/speak-stream`.
