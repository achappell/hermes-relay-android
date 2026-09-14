---
title: 'Story 5 — Android Standard Hermes migration'
type: 'feature'
created: '2026-09-14'
status: 'blocked'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
  - '_bmad-output/implementation-artifacts/spec-5-a-1-independent-doorway.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-standard-hermes-compatibility-migration/SPEC.md'
  - '../hermes-relay-home/_bmad-output/specs/spec-standard-hermes-compatibility-migration/standard-baseline.md'
warnings: []
deferred: []
---

## Intent

**Problem:** The Android client still connects through the fork-only
`/voice-session` route, sends a personal bearer token, and receives response
PCM on the gateway socket. Migration story 5 requires the approved Home or
Standard boundary without losing local Profile identity, history, lifecycle
audio, interruption, reconnect, or honest unavailable behavior.

**Approach:** Put the target adapter behind the existing typed
`AndroidClientPort`, preserve the Android reducer, Keystore, history, capture,
and audio seams, and retain the fork path only as an explicit rollback until
the local gate passes.

## Boundaries & Constraints

**Always:** Keep `RelayProfile.id` as the local credential/history key; store
only the approved endpoint credential in Android secure storage; fail closed on
conversion, authorization, readiness, or capability failure; preserve
normalized event identity and order; use the Standard `/api/ws` plus separate
`/api/audio/speak-stream` contract when that is the selected boundary; treat
timing as absent unless an authoritative playback contract exists; reconnect
without replaying an uncertain turn.

**Never:** Put wire parsing in Compose; expose a Hermes bearer to Android or a
Home endpoint; invent a Home WebSocket envelope or credential encoding; switch
routes during an active turn; delete the rollback path before evidence; claim
audio, timing, prompt, command, or interruption support that the selected
boundary did not advertise and prove.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Conversion succeeds | Existing legacy Profile and valid source credential | Same local Profile/history identity, new approved credential state, no source deletion until verified | Idempotent repeat produces no second identity |
| Conversion fails | Malformed, expired, revoked, or unprotectable source | Profile is unavailable before capture or submission | No usable credential, no turn, legacy source preserved |
| Boundary not ready | Route or `gateway.ready` absent | Doorway shows typed unavailable state | No capture, route switch, or resend |
| Turn becomes uncertain | Transport fails after submission may have been accepted | Existing unconfirmed-turn state remains visible | Reconnect only; fresh user action required |
| Audio unavailable | Missing/invalid sidecar metadata or unsupported PCM | Text remains usable and Speaking is not claimed | Typed audio-unavailable outcome |

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/AndroidClientPort.kt:56-98` — typed snapshot, turn, observation, reconnect, and interrupt boundary; keep UI independent of wire frames.
- `app/src/main/java/com/achappell/hermesrelay/OkHttpRelaySessionClient.kt:30-325` — current fork transport, bearer header, `hello`/`turn`/`interrupt`, same-socket binary PCM, and reconnect ownership; primary migration seam.
- `app/src/main/java/com/achappell/hermesrelay/HermesEventNormalizer.kt:36-209` — fork event normalization and text accumulation; replace or isolate behind the selected target adapter.
- `app/src/main/java/com/achappell/hermesrelay/RelayCredentialStore.kt:21-109` — Android Keystore/AES-GCM storage; `hasToken()` currently checks presence rather than decryptability.
- `app/src/main/java/com/achappell/hermesrelay/RelayProfileStore.kt:49-97` and `RelayProfile.kt:117-158` — local Profile identity and unversioned legacy JSON; preserve identity while versioning conversion.
- `app/src/main/java/com/achappell/hermesrelay/AndroidLocalHistory.kt:19-199` — per-Profile history and draft persistence; must survive migration unchanged.
- `app/src/main/java/com/achappell/hermesrelay/AndroidTurnState.kt:52-294`, `AndroidRecovery.kt:62-189`, and `AndroidDoorwayState.kt:3-77` — reducer, no-replay recovery, and fail-closed doorway state to reuse and extend only with typed contract reasons.
- `app/src/main/java/com/achappell/hermesrelay/AndroidAudioSink.kt:44-225` and `MainActivity.kt:44-149` — PCM playback/drain and lifecycle observation; retain platform ownership and add explicit teardown where the target contract requires it.

## Tasks & Acceptance

**Execution:**
- `OkHttpRelaySessionClient.kt` and `HermesEventNormalizer.kt` — implement the selected versioned Home/Standard adapter and normalized event mapping after the upstream endpoint contract is fixed.
- `RelayCredentialStore.kt`, `RelayProfileStore.kt`, and `RelayProfile.kt` — implement idempotent secure credential conversion with rollback-preserving failure behavior after credential representation is fixed.
- `AndroidTurnState.kt`, `AndroidRecovery.kt`, `AndroidAudioSink.kt`, and `MainActivity.kt` — preserve turn/reconnect/audio semantics and close lifecycle gaps without moving ownership into the UI.
- `app/src/test` and `app/src/androidTest` migration fixtures — prove conversion failure, no replay, readiness, sidecar PCM, interruption, and unavailable states at JVM and instrumentation boundaries.

**Acceptance Criteria:**
- Given the approved endpoint and credential contracts are versioned, when Android migrates a legacy Profile, then the same local Profile/history identity remains usable and no bearer credential crosses the endpoint boundary.
- Given conversion, authorization, readiness, or capability validation fails, when the doorway is opened or a turn is requested, then Android fails closed before capture/submission and preserves the legacy source for rollback.
- Given a Standard/Home turn may have reached Hermes, when transport fails, then Android reconnects without resending and requires a fresh user action.

## Design Notes

The fork transport is a rollback implementation, not a target contract. The
Standard baseline separates JSON and response audio, while Android's current
client assumes one socket; retaining the typed Android seams prevents that
transport correction from leaking into Compose or local history.

## Auto Run Result

Status: blocked

Blocking condition: intent gap

Unanswered questions and evidence:

- The story permits either Home-first or direct Standard, but neither is chosen
  for Android. The migration SPEC leaves that choice open.
- The public Home conversation WebSocket path and endpoint envelope are not
  versioned. `../hermes-relay-home/docs/standard-bridge.md:59-62` explicitly
  says that route remains open.
- Home's endpoint credential representation and conversion payload are not
  fixed. `../hermes-relay-home/_bmad-output/specs/spec-home-service-foundation/credential-lifecycle.md:39-42`
  requires secure opaque storage but leaves representation to later work.

Android cannot implement these observably different choices without inventing
protocol or security policy. No Android files were changed and no build was
run.
