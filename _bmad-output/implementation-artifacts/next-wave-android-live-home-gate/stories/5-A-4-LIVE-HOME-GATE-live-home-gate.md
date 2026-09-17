---
title: '5-A-4 follow-up — Prove Android against the live Home bridge'
type: 'chore'
story_id: '5-A-4-LIVE-HOME-GATE'
parent_story: '5-A-4'
created: '2026-09-15'
status: 'done-with-environment-limitation'
review_loop_iteration: 9
authority_revision: 5
followup_review_recommended: true
route: 'dispatch'
warnings:
  - oversized
baseline_commit: '3808052c52f7f729b3b37da62bab668728559e8e'
parent_spec: '_bmad-output/implementation-artifacts/spec-5-android-migration.md'
validation: '_bmad-output/implementation-artifacts/validation-5-a-4-live-home-gate.md'
canonical_spec: '_bmad-output/implementation-artifacts/spec-5-a-4-live-home-gate.md'
dispatch_copy: '_bmad-output/implementation-artifacts/next-wave-android-live-home-gate/'
canonical_spec_sha256: '33be6b4416a787032aa731436ee0d95751acbc59eb92f8b9ae564728c893a1cb'
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-5-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-5-android-migration.md'
  - '{project-root}/_bmad-output/implementation-artifacts/validation-5-a-4-home-bridge-migration.md'
  - '{project-root}/_bmad-output/implementation-artifacts/validation-5-a-4-live-home-gate.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Authority and scope

This root file is the one registered Android authority for the additive
follow-up. Its implementation status is `done-with-environment-limitation`
after the completed independent plan gate and local build validation;
the delivery row remains
`5-a-4-live-home-gate: backlog` until evidence passes. The story index points
here and to the companion validation record. The supervised stories-mode run
materializes a working copy under
`_bmad-output/implementation-artifacts/next-wave-android-live-home-gate/` for
the plan checkpoint; that copy is dispatch machinery, not a second product
specification. If BMAD rewrites dispatch frontmatter, the copy must retain
this authority binding in its body: story key `5-A-4-LIVE-HOME-GATE`, parent
`5-A-4`, canonical spec path above, and validation path
`_bmad-output/implementation-artifacts/validation-5-a-4-live-home-gate.md`.
The story file that carries this projection is
`_bmad-output/implementation-artifacts/next-wave-android-live-home-gate/stories/5-A-4-LIVE-HOME-GATE-live-home-gate.md`;
the directory and story file must both exist for dispatch verification.
The reviewer compares the dispatch body to this registered file; it never
promotes a dispatch-only status or an unlinked copy. Before implementation,
`scripts/verify-story-dispatch.sh` must compute the SHA-256 of this canonical
file and compare it with the `canonical_spec_sha256` recorded in the dispatch
frontmatter, as well as require `authority_revision: 5`. A missing child,
mismatched body, missing hash, or stale revision blocks dispatch. The
canonical file is the only source for the schema and proof contract; the
dispatch copy is a generated projection and may vary only in engine-owned
frontmatter. The completed parent story, parent validation, and `5-a-4: done`
status remain unchanged.

This work is an evidence gate, not a new product feature. It may add test-only
telemetry or focused contract fixtures when those are needed to prove the
existing adapter. It may not add pairing UI, route discovery, TLS deployment,
Home runtime/factory code, or a direct Standard Hermes client.

## Intent

The Android Home migration has deterministic TLS-fixture evidence, but no
proof yet from a deployed Standard-backed Home bridge, a real physical audio
sink, a controlled interrupt, or a Home-controlled transport loss. This gate
will establish that evidence when the external deployment and device
preconditions exist, and will record the limitation honestly when they do not.

Android remains Home-first: it opens only the approved
`/api/v1/bridge/ws` route with `Authorization: Device <credential>`. Home owns
the Standard Hermes credential and its `/api/ws` and audio connections. Android
receives only safe route/capability state, an opaque conversation handle, a
Home turn ID, normalized events, and local connection identity.

## Boundaries and safety rules

Always:

- Keep `@LiveRelay` excluded from ordinary instrumentation runs. The default
  runner value is the non-empty annotation name; the explicit live command
  must set `notAnnotation=org.junit.Ignore`. An empty override is invalid and
  runs zero live tests.
- Use one approved `wss` route whose final URL path is exactly
  `/api/v1/bridge/ws`; send the Device credential only in the upgrade header.
  A paired route may be a host-only `wss://host[:port]` base or already carry
  exactly that path (with an optional trailing slash). It must reject every
  other non-root path, including `/voice-session`,
  `/prefix/api/v1/bridge/ws`, and `/api/v1/bridge/ws/extra`;
  query strings and fragments are also rejected;
  `OkHttpRelaySessionClient.bridgeUrl` always emits the fixed bridge path
  rather than appending it to an arbitrary prefix.
- Supply these four separate, disposable opaque conversation handles through
  the exact instrumentation argument names
  `handshakeConversationHandle`, `typedConversationHandle`,
  `interruptConversationHandle`, and `reconnectConversationHandle`. Each live
  test consumes only its named handle; the wrapper rejects blank or duplicate
  handles before Gradle starts. The operator must reset or retire every handle
  after a partial run and obtain fresh handles for the next run; Android never
  resets Home state or retries with another handle.
- Supply three separate, ephemeral probe prompts through
  `LIVE_TYPED_PROMPT`, `LIVE_INTERRUPT_PROMPT`, and
  `LIVE_RECONNECT_PROMPT`. The wrapper maps them to `typedPrompt`,
  `interruptPrompt`, and `reconnectPrompt` in an argv array. None has a source
  default, and the interrupt prompt must be a documented long-running,
  non-tool Home probe. A missing probe prompt is an environment-limited
  `MISSING_PAIRING` branch, not permission to invent or reuse a prompt.
- Keep credentials, handles, prompts, response text, PCM, raw JSON, and raw
  logs out of source, test messages, snapshots, validation artifacts, and
  committed run output.
- Require a successful Home readiness result before any `prompt.submit` or
  `session.interrupt`. New-turn branches require an explicit
  `unresolved_turn=false`; the controlled reconnect branch requires
  `unresolved_turn=true` and uses `assertReconnectReadiness` without creating a
  replacement turn. Any missing or context-incompatible value fails closed.
- Preserve the parent migration record and leave the follow-up in `backlog`
  until every required live branch and its provenance evidence pass.

Never:

- Open Standard `/api/ws`, `/api/audio/speak-stream`, `/voice-session`, or a
  fallback route from Android.
- Treat TLS reachability, HTTP 101, a socket that answers, a mock/proxy/canned
  response, `404`, or `hermes_unavailable` as a live production pass.
- Treat the JSON-RPC interrupt acknowledgement as the terminal interruption.
- Automatically resend a prompt after uncertain delivery or reconnect.
- Put a value-bearing `AndroidTurnRequest`, result object, exception, or raw
  wire frame in an assertion message. Assertion labels must be constant and
  safe.

## Code Map

The current implementation was read before this plan revision. These are the
owned seams; new symbols must be added only to the named file or to the named
new file, so the implementer does not have to rediscover ownership.

- `app/src/main/java/com/achappell/hermesrelay/RelayCredentialStore.kt` --
  `HomeCredentialValidator` is the existing credential contract: exactly 43
  unpadded base64url characters decoding to 32 bytes. The wrapper must match
  this contract without reading Android storage.
- `app/src/main/java/com/achappell/hermesrelay/RelayProfile.kt` --
  `RelayProfileValidator.validateApprovedHomeRoute` owns the accepted
  host-only or exact bridge-path route rule; reuse it in focused tests rather
  than creating a second route policy.
- `app/src/main/java/com/achappell/hermesrelay/OkHttpRelaySessionClient.kt` --
  `reconnect`, `readOpenResult`, `parseCapabilities`, `beginTurn`,
  `interruptTurn`, `dispatch`, and `handleEvent` are the transport and event
  seams. Strict response-id/readiness checks, request telemetry, interrupt
  telemetry, and the live-readiness adapter belong here unless a focused
  internal companion file is named in the task.
- `app/src/main/java/com/achappell/hermesrelay/AndroidClientPort.kt` -- owns
  `AndroidHomeUnavailableReason`, route/capability projections, and any typed
  live-readiness result; do not put wire JSON or safe-record serialization in
  the port.
- `app/src/main/java/com/achappell/hermesrelay/AndroidAudioSink.kt` -- owns
  `AudioTrackAudioSink`, the production `AudioTrackDriver` factory, and the
  content-free `AndroidAudioSinkTelemetry` snapshot. `RecordingAudioSink`
  remains a deterministic fake and never becomes speaker evidence.
- `app/src/main/java/com/achappell/hermesrelay/HermesEventNormalizer.kt` --
  retains Home handle/turn binding and audio-frame normalization; no raw PCM or
  response content may be copied into telemetry. Its `standardEvents` terminal
  mapping is the canonical source for the signed `turn.completed`,
  `turn.interrupted`, and `turn.failed` trace facts.
- `app/src/main/java/com/achappell/hermesrelay/AndroidTurnState.kt` and
  `AndroidRecovery.kt` -- retain the reducer's `Complete`/`Delivered` ordering,
  own `AndroidReconnectOutcome`, and enforce the no-automatic-replay rule; use
  their existing typed events as the live test observation surface.
- `app/src/androidTest/java/com/achappell/hermesrelay/LiveRelayHandshakeTest.kt`
  -- owns the four scenario tests, deterministic deadlines, safe assertion
  labels, and the device-side result handoff. It must write one validated safe
  JSON result per scenario through the writer below.
- `app/src/androidTest/java/com/achappell/hermesrelay/LiveHomeSafeResult.kt`
  -- new device-side writer/validator for the versioned branch result. It
  writes only allowlisted counts, booleans, enums, and bounded identities to
  the target app cache; it never writes credentials, handles, prompts, text,
  PCM, frames, or assertion messages. It owns the exact per-scenario envelope
  and atomic write described in the wrapper contract.
- `app/src/androidTest/java/com/achappell/hermesrelay/AudioOutputPreflightTest.kt`
  -- new one-test physical-output probe used before Home traffic. It emits
  only the fixed `AUDIO_PREFLIGHT=PASS` or `AUDIO_PREFLIGHT=FAIL:AUDIO_OUTPUT`
  token.
- `app/src/test/java/com/achappell/hermesrelay/OkHttpRelaySessionClientTest.kt`,
  `AndroidAudioSinkFramesTest.kt`, `AndroidTurnStateTest.kt`,
  `AndroidRecoveryControllerTest.kt`, and `RelayProfileTest.kt` -- focused
  JVM contract fixtures for readiness, request/interrupt telemetry, route
  shape, audio-driver drain/underrun behavior, and no replay.
- `scripts/run-live-home-gate.sh` -- owns argument validation, command
  isolation, device preflight, fresh device-cache binding,
  `armCloseHook`/`stopCloseHook`, `adb` result retrieval, attestation ordering,
  branch aggregation, scans, atomic publication, and exit mapping.
- `scripts/verify-story-dispatch.sh`,
  `scripts/verify-home-attestation.py`, and
  `scripts/validate-live-home-inputs.py` -- own dispatch-hash verification,
  detached-signature verification, and the exact credential/route/run-id
  input checks respectively. The latter is a new content-free validator so
  shell length checks cannot drift from `HomeCredentialValidator`.
- `scripts/run-live-home-gate-contract-test.sh` -- fake-command coverage for
  the wrapper, safe handoff, attestation phases, partial records, scans, and
  exit codes. `_bmad-output/implementation-artifacts/validation-5-a-4-live-home-gate.md`
  records only the published safe projection.

## Exact Home readiness contract

The readiness helper and deterministic fixtures must assert the whole boundary,
not merely `Connected`:

1. `OkHttpRelaySessionClient.reconnect` passes only the frame belonging to the
   outstanding `conversation.open` or `conversation.reconnect` request to
   `readOpenResult(frame, expectedRpcId, expectedHandle, connectionId)`. That
   parser requires JSON-RPC 2.0 with top-level `schema: 1`, the exact expected
   response `id`, and no `error`.
2. `result.schema` is exactly `1`, `result.status` is exactly `"ready"`, and
   `result.conversation_handle` is the exact non-blank handle supplied for that
   scenario. A different, missing, or overlong handle is a protocol/binding
   failure.
3. `result.unresolved_turn` is present and boolean for both handshake methods.
   Missing or non-boolean values are `ProtocolError`. For the initial
   `conversation.open` used by `HANDSHAKE`, `TYPED_AUDIO`, and `INTERRUPT`,
   `false` is required before a new turn and `true` is parsed as the ordinary
   recovery outcome `Connected(unresolvedTurn=true)`, then rejected by
   `LiveHomeReadiness.assertNewTurnReadiness` as
   `AndroidReconnectOutcome.Unrecoverable` with
   `AndroidHomeUnavailableReason.UnresolvedTurn`; prompt and interrupt
   counters remain zero. For `conversation.reconnect`, the controlled-close
   branch requires explicit `true`, because Home must still own the accepted
   in-flight turn. `LiveHomeReadiness.assertReconnectReadiness` accepts that
   unresolved state without creating a turn, preserves
   `AndroidRecoveryState.unresolvedHomeTurn=true`, and proves no replacement
   prompt. A reconnect response with `false` is `RECONNECT_TRACE`, not a clean
   new-turn readiness result. This context-specific strictness is live-gate
   only: ordinary recovery keeps the parent contract and never silently clears
   uncertainty.
4. `result.route` is present with `class` in exactly `home`, `tailscale`, or
   `public`, and a non-blank `id` no longer than 256 characters.
5. `result.capabilities` is present with all of these fields: boolean
   `heartbeat=true`, string `timing="absent"`, array `commands=[]`, and
   boolean `interrupt` and `audio` fields. The parser accepts either boolean
   value for the two branch capabilities, but never accepts a missing or
   wrongly typed field. A missing field, wrong type, unknown timing value, or
   non-empty command list is `CAPABILITY_SHAPE_INVALID`; no typed/interrupt/
   audio claim may proceed. The live handshake requires the shape above;
   `assertLiveHomeCapabilities` then requires `audio=true` only for
   `TYPED_AUDIO` and `interrupt=true` only for `INTERRUPT`, mapping a valid
   false capability to `CAPABILITY_UNAVAILABLE` for that branch.
   The named `parseCapabilities` helper must validate JSON types and required
   presence instead of using `opt*` defaults; `readOpenResult` must validate
   the response id, exact handle, route, and capabilities before returning any
   connected outcome. Focused tests name each malformed field and assert that
   the socket has no active turn.

The implementation may keep the existing `AndroidReconnectOutcome.Connected`
shape for ordinary recovery, but the live gate must inspect `route`, every
capability field, and `unresolvedTurn` before it creates a turn. Strict live
readiness is an explicit `LiveHomeReadiness` test seam layered over that
ordinary outcome; it never changes ordinary recovery semantics. If strict
parsing belongs below the test seam, add the focused parser test and make
malformed or unknown values fail closed with `ProtocolError` rather than
silently taking a safe-looking default.

The typed reason symbols are concrete implementation requirements:
`AndroidHomeUnavailableReason.UnresolvedTurn` is the strict live-gate
outcome, and `AndroidHomeUnavailableReason.CapabilityShapeInvalid` is the
malformed-capability outcome. A mismatched JSON-RPC response id maps to
`AndroidHomeUnavailableReason.ProtocolError` with the internal diagnostic
label `RPC_RESPONSE_MISMATCH`; that label is never written to the safe record.
On any response-id mismatch, the client closes and clears the active socket,
clears the active-turn observer, publishes neither `Connected` nor an event,
and records no prompt or interrupt request. It must fail immediately rather
than becoming a timeout.

## Preflight and device binding

The opt-in wrapper performs these checks before Gradle starts the live class.
The exact inputs are `LIVE_DEVICE_SERIAL`, `LIVE_HOME_ROUTE`,
`LIVE_DEVICE_CREDENTIAL`, the four `LIVE_*_HANDLE` environment variables,
`LIVE_TYPED_PROMPT`, `LIVE_INTERRUPT_PROMPT`, `LIVE_RECONNECT_PROMPT`,
`LIVE_HOME_RUN_ID`, `LIVE_HOME_CLOSE_HOOK`, `LIVE_HOME_ATTESTATION_FILE`,
`LIVE_HOME_TRACE_ATTESTATION_FILE`,
`LIVE_HOME_ATTESTATION_PUBLIC_KEY_FILE`, and
`LIVE_HOME_ATTESTATION_KEY_ALLOWLIST_FILE`. The wrapper maps the three
prompts and four handles to instrumentation argument names using an argv
array; it never prints an expanded command or a value-bearing failure.

- `adb devices -l` resolves exactly one target selected by the explicit
  `ANDROID_SERIAL="$LIVE_DEVICE_SERIAL"`; the selected row must be in state
  `device`. Zero devices, multiple matching devices, offline/unauthorized
  state, or an unspecified serial is `NOT_RUN: DEVICE_SELECTION`. The wrapper
  computes `serial_fingerprint` as lowercase SHA-256 of the serial and never
  publishes or logs the serial itself.
- `adb -s "$LIVE_DEVICE_SERIAL" shell getprop ro.product.model` is `Pixel 6a`
  and `ro.build.version.sdk` is recorded as an integer. Both
  `ro.kernel.qemu` and `ro.boot.qemu` must be absent or `0`; either value `1`
  is an emulator and is `NOT_RUN: WRONG_DEVICE`. Any other model is the same
  branch.
- The wrapper invokes the installed device-side
  `AudioOutputPreflightTest.verifyPhysicalAudioOutput` helper with
  `adb -s "$LIVE_DEVICE_SERIAL" shell am instrument` before the live class.
  The helper creates and releases a real `AudioTrack` using only the fixed
  local probe format `16000 Hz`, mono, PCM 16-bit, writes one aligned silent
  buffer, observes a positive playback-head advance, releases the track, and
  emits exactly one content-free line
  `AUDIO_PREFLIGHT=PASS` or `AUDIO_PREFLIGHT=FAIL:AUDIO_OUTPUT`. The wrapper
  accepts only `AUDIO_PREFLIGHT=PASS` as success and records
  `audio_output: true`. A valid `AUDIO_PREFLIGHT=FAIL:AUDIO_OUTPUT` token on
  the already-selected physical device is `NOT_RUN: AUDIO_OUTPUT` with exit
  `20`. Missing, malformed, duplicate, or unexpected output, a non-zero
  instrumentation process, or a timeout is `HARNESS_FAILURE` with exit `30`.
  An emulator is rejected earlier as `NOT_RUN: WRONG_DEVICE`; it is not
  relabelled as an audio result.
  This is only a physical-output preflight; the Home-announced format is
  checked later, after the handshake's `audio.frame` `start`, by
  `AudioTrackAudioSink.start(format)` and cannot be claimed in advance.
- The approved route, Device credential, scenario-specific handles, three
  probe prompts, and `LIVE_HOME_RUN_ID` are present and structurally valid.
  Missing values are `NOT_RUN: MISSING_PAIRING`; malformed route, credential,
  run id, or a non-executable close hook is `NOT_RUN: INVALID_BINDING`. A
  handle is 1–256 UTF-8 bytes with no NUL, CR, LF, or whitespace; a Device
  credential must match the Android `HomeCredentialValidator` contract:
  exactly 43 characters from `[A-Za-z0-9_-]`, decoding as unpadded base64url
  to exactly 32 bytes. Each probe prompt is 1–4096 UTF-8 bytes with no NUL,
  CR, or LF; and the run id matches `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`.
  Duplicate handles are invalid even when their spellings differ only by
  surrounding whitespace. `scripts/validate-live-home-inputs.py` is the
  single script-level implementation of these structural checks and its
  tests include the Android credential boundary. No client is opened in
  either branch. The wrapper starts the close hook only after these checks
  and passes it `--run-id`, `--scenario reconnect`, `--after-method
  prompt.submit`, and `--once` in an argv array; it never passes a handle or
  prompt to the hook.
- `LIVE_HOME_RUN_ID` must be fresh for this invocation. The wrapper refuses an
  existing owner-only local run directory or an existing device-side
  `cache/hermes-live-home/<LIVE_HOME_RUN_ID>/` directory before the first live
  scenario; it proves the exact path is absent with the selected `run-as`
  identity. A reused run id, a stale result file, or an inability to prove
  absence is `NOT_RUN: INVALID_BINDING`; no stale device file can be accepted
  as a current result.
- The operator has supplied the signed deployment attestation described below.
  It is verified before `adb` or Gradle and proves only the Home deployment
  identity, adapter, signer, and approved route identity; it contains no
  current-run trace or device fingerprint. Missing or unverifiable deployment
  provenance stops before Home traffic and records
  `done-with-environment-limitation`. The signed current-run trace attestation
  is verified after the scenario result files are collected, because its
  `trace_run_id`, device fingerprint, and method outcomes cannot exist before
  this run. A missing or unverifiable run trace makes a started branch with no
  prior recorded failure `inconclusive` with `HOME_PROVENANCE`; it can never
  produce `live-pass`. It never downgrades a branch already recorded as
  `fail`, and the overall failure/exit-30 result remains authoritative.

The live test uses a small safe preflight result with an allowlisted reason
code. It must skip/record a not-run branch without attempting Home traffic; it
must not use a generic `require` whose failure text can contain an argument.
Missing environment values, unavailable device/audio, missing attestation, or
an unavailable close hook use named, constant-labelled branches such as
`NOT_RUN: MISSING_PAIRING` and return exit code `20`. Malformed route,
credential, run-id, hook, or duplicate handles are also recorded as
`NOT_RUN: INVALID_BINDING` with exit `20`; exit `64` is reserved for an
extra/unsupported command-line argument or an invalid dispatch hash. A
contract, assertion, replay, or artifact-scan defect returns `30`; an all-pass
attested run returns `0`.

The default connected run is a separate proof: run it with no
`notAnnotation` override, verify the XML report has a non-zero count of
non-live tests and zero `LiveRelay` test cases, and retain only those counts.

The physical-output helper is a precondition, not a Gradle assertion. The
wrapper builds/installs the debug and debug-test APKs, runs the default suite,
and then invokes the helper before the explicit live class. Its stdout is
parsed in memory and reduced to the one allowlisted token above. A valid
`FAIL:AUDIO_OUTPUT` token means the physical sink could not initialize, write
an aligned buffer, advance the playback head, or release; malformed/missing
output, a raw instrumentation failure, and timeout are `HARNESS_FAILURE`.
This proves device output-path capability, not that a human heard the silent
probe. No Home request is made by this helper.

## Home deployment provenance

The live result needs operator-supplied provenance because Android can observe
a compatible wire response without proving which Home deployment produced it.
There are two signed envelopes, both read from owner-only files outside the
repository and verified with the same pre-provisioned trust root:

1. `LIVE_HOME_ATTESTATION_FILE` is the **deployment attestation**. The wrapper
   verifies it before `adb` or Gradle and requires only deployment identity,
   `adapter=standard-backed`, signer identity, and the approved route class/id.
   It deliberately contains no current-run trace or device fingerprint.
2. `LIVE_HOME_TRACE_ATTESTATION_FILE` is the **run-trace attestation**. The
   Home operator or trusted runner creates it after this run's scenario traces
   exist. The wrapper verifies it after collecting the device-side scenario
   records and before publishing `live-pass`. It binds the run id, selected
   device fingerprint, observed route, and redacted method trace.

Both envelopes use schema `1`, RFC 8785 UTF-8 canonical JSON with no duplicate
keys, and a detached Ed25519 signature over the envelope without
`signature`. The signature is exactly 64 bytes of unpadded base64url. The
public-key file contains exactly 32 raw Ed25519 public-key bytes encoded as
unpadded base64url; its lowercase hexadecimal SHA-256 fingerprint must appear
in the owner-only allowlist. The allowlist is a regular owner-only file,
outside the repository and run directory, pre-provisioned and immutable for
the run. Each entry binds the approved `signer_key_id` to one fingerprint; a
fingerprint supplied only inside an attestation is never trusted. Unsigned,
self-supplied, or unverifiable metadata cannot produce `live-pass`.

The deployment envelope contains only:

```text
schema: 1
kind: deployment
deployment_id: <non-secret Home deployment identity>
deployment_revision: <non-secret Home revision>
adapter: standard-backed
signer_key_id: <non-secret pre-approved key identifier>
route_class: <home | tailscale | public>
route_id: <non-secret route identity>
signature: <detached signature, not the private key>
```

The run-trace envelope contains the same deployment fields plus:

```text
schema: 1
kind: run-trace
deployment_id: <non-secret Home deployment identity>
deployment_revision: <non-secret Home revision>
adapter: standard-backed
signer_key_id: <non-secret pre-approved key identifier>
route_class: <home | tailscale | public>
route_id: <non-secret route identity>
trace_run_id: <non-secret per-run identifier>
trace_digest: <SHA-256 of the canonical redacted trace array>
device_serial_fingerprint: <lowercase SHA-256 of the selected serial>
trace: [<signed redacted trace entries in sequence order>]
signature: <detached signature, not the private key>
```

The redacted trace records ordered method/status facts only, in the safe shape
`{schema: 1, scenario, sequence: integer, method, outcome, same_conversation}`.
The methods are `conversation.open`, `prompt.submit`,
`session.interrupt`, `peer.close`, `conversation.reconnect`, and one canonical
terminal event: `turn.completed`, `turn.interrupted`, or `turn.failed`.
Canonical terminal names are a normalization boundary, not claims about the
literal wire method. `HermesEventNormalizer.standardEvents` maps
`message.complete` with a blank/`complete`/`completed` status to
`AndroidNormalizedEvent.TurnCompleted` and the signed
`turn.completed:completed` fact; it maps `message.complete` with an approved
interrupted status, plus `session.interrupted`, `turn.interrupted`, or
`turn.cancelled`, to `TurnInterrupted` and
`turn.interrupted:interrupted`. A failed or unknown terminal status maps to
`turn.failed:failed` and cannot appear in a passing group. The trusted trace
producer applies this mapping before signing and records no raw payload.
`outcome` is one of `accepted`, `observed`, `ready`, `acknowledged`,
`completed`, `interrupted`, or `failed`. No handle or handle digest is stored;
the trace contains no credential, prompt, response, PCM, headers, or raw JSON.
`same_conversation` is present and boolean on reconnect entries.

`scenario` repeats on every entry in its group. A valid trace has exactly one
contiguous group per scenario, not one entry per scenario: the group sizes are
1 for `handshake`, 3 for `typed_audio`, 4 for `interrupt`, and 4 for
`reconnect`, for 12 entries total. Global `sequence` starts at 1 and increases
by one with no gaps or duplicates. The exact group orders are: `handshake`
`conversation.open:ready`; `typed_audio`
`conversation.open:ready`, `prompt.submit:accepted`,
`turn.completed:completed`; `interrupt` `conversation.open:ready`,
`prompt.submit:accepted`, `session.interrupt:acknowledged`,
`turn.interrupted:interrupted`; and `reconnect` `conversation.open:ready`,
`prompt.submit:accepted`, `peer.close:observed`,
`conversation.reconnect:ready` with `same_conversation=true`. Groups cannot
interleave, contain a second `prompt.submit`, or contain `turn.failed` in a
passing trace.

`LIVE_HOME_RUN_ID` is the non-secret run binding. The signed `trace_run_id`
must equal it; the signed device fingerprint, route class, and route id must
equal the wrapper's observed facts. The Android test receives the same run id
as `liveRunId` and records it only as safe metadata; the wrapper rejects a
trace from another run, device, or route. The close hook uses that run id, so
`peer.close` cannot be attached from an unrelated Home session. The validation
record stores deployment metadata and the trace digest only after both
attestations verify. A missing deployment attestation is
`NOT_RUN: HOME_PROVENANCE` before traffic. A missing or invalid run-trace
attestation after a started branch is `inconclusive` with `HOME_PROVENANCE`;
it prevents live pass without pretending the branch was never attempted.

## Scenario matrix and proof obligations

| Branch | Isolated input | Required proof | Safe non-pass result |
| --- | --- | --- | --- |
| `HANDSHAKE` | One disposable handle | Exact readiness contract above; route is Home-only; `unresolved_turn=false`; safe route and typed capability fields | `NOT_RUN` for preflight; `FAIL` for contract/auth defects |
| `TYPED_AUDIO` | A different disposable handle and `LIVE_TYPED_PROMPT` | `prompt.submit` accepted with the same opaque handle and non-blank Home turn ID; text reaches an authoritative terminal event; `audio=true`; supported `pcm_s16le` start; non-zero aligned PCM reaches the physical `AudioTrack`; no `AudioFailed`; sink drain callback; final reducer state is `Complete` + `Delivered` | Audio failure, missing terminal, or missing evidence is `FAIL`/`INCONCLUSIVE`, never pass |
| `INTERRUPT` | A different disposable handle and `LIVE_INTERRUPT_PROMPT`, an approved long-running, non-tool probe | `interrupt=true`; observe a non-terminal `Thinking` or `Speaking` state before sending interrupt; `session.interrupt` is sent; both the acknowledgement and the matching handle/turn `TurnInterrupted` event are observed; no natural completion before the request | Terminal before the request is `inconclusive: NATURAL_COMPLETION_RACE`; after a request, failure to observe both acknowledgement and matching terminal by the fixed deadline is `fail: TERMINAL_TIMEOUT` |
| `RECONNECT` | A different disposable handle, `LIVE_RECONNECT_PROMPT`, and an operator-controlled close hook | Home trace shows accepted prompt, peer close, explicit `conversation.reconnect`, same handle ready with `unresolved_turn=true`, and no replacement `prompt.submit`; `assertReconnectReadiness` preserves uncertainty and never replays | Missing controlled close or trace is `NOT_RUN: CONTROLLED_CLOSE`; any replay is `FAIL` |

### Typed response and real audio

The typed branch must use `AudioTrackAudioSink`, never
`RecordingAudioSink`. Add the named test-only, content-free
`AndroidAudioSinkTelemetry` snapshot and
`AudioTrackAudioSink.snapshotTelemetry()` seam. It is reset by `start`, uses
atomics for worker-thread visibility, and contains only
`started`, `acceptedBytes`, `acceptedFrames`, `drained`, `failed`,
`underrunCount`, and an allowlisted failure kind. The exact failure enum is
`AudioSinkFailureKind.StartFailure`, `WriteFailure`, `InvalidFrameAlignment`,
`PlaybackStalled`, `Underrun`, `DrainTimeout`, or `OutputUnavailable`. The
live test takes the snapshot after the drain callback and retains
counts/booleans only. `write` increments accepted values only for positive,
aligned `AudioTrack` writes; queued bytes are not accepted evidence.

The internal seam is concrete: `AudioTrackDriver` exposes
`state()`, `play()`, `write(buffer, offset, size): Int`,
`playbackHeadFrames(): Long`, `underrunCount(): Int`, `stop()`, and
`release()`. A
`AudioTrackDriverFactory.create(format, bufferSize)` supplies the production
driver that wraps the real `AudioTrack`; tests inject a driver that retains no
PCM. `AudioTrackAudioSink` receives the factory through an internal
constructor, and `snapshotTelemetry()` is the only test/live evidence handoff.
The driver additionally exposes `underrunCount(): Int`, backed by
`AudioTrack.getUnderrunCount()` on the production driver. `finish` keeps the
driver playing until the accepted frame count is reached, fails immediately
when the underrun count increases or the playhead stalls, and calls `stop`
only after a successful drain. The driver reports
`STATE_INITIALIZED`/start failure and negative or unaligned writes through the
allowlisted enum; no exception, frame, or driver object is copied into an
assertion or validation record.

The evidence record contains counts and booleans only:

- supported `start` metadata: `sample_rate`, `channels`, `sample_width=2`,
  `byte_order="little"`, `encoding="pcm_s16le"`;
- at least one non-empty binary frame accepted by the sink, with total byte
  count greater than zero and divisible by `bytesPerFrame(channels)`;
- `AudioChunkReceived` observed, no `AudioFailed`, and the sink reports at
  least one accepted frame;
- Home `audio.frame` `end` observed, the physical drain callback observed, and
  the final reducer state is `Complete` only after `Delivered`;
- cleanup occurs even on timeout, assertion failure, malformed data, or a
  precondition skip.

The test must not claim that a binary frame was heard merely because it was
queued. `AudioTrackAudioSink.finish` keeps the driver playing while it drains;
it reports `drained=true` only after the playback head reaches the accepted
frame count (or an explicit platform completion callback), then stops and
releases. An increased platform underrun count or a non-advancing playhead is
a failure, not success. A zero-byte write may retry until the fixed write deadline; a
negative write or a positive write that is not a whole PCM frame is
`WriteFailure` or `InvalidFrameAlignment`. A worker that exits before the
accepted frame count, or a positive accepted count whose playback head does
not advance for the fixed stall deadline, is `PlaybackStalled`; a platform
underrun is `Underrun`. A sink write failure, zero accepted frames, a drain
timeout, an underrun, or an unavailable output is not audio proof. The focused
`AndroidAudioSinkFramesTest` uses the internal playback-driver seam to prove a
delayed drain succeeds only after advancement, while write failure, a stalled
worker, and an underrun call `onFailure` and never set `drained`.
If a platform `AudioTrack` cannot be substituted directly in JVM tests, extract
the named internal `AudioTrackDriver`/factory seam behind
`AudioTrackAudioSink`; the production factory remains the real `AudioTrack`,
and the test driver retains no PCM.

### Interrupt

The interrupt prompt is an approved non-tool probe expected to remain
non-terminal long enough to interrupt. Name the precondition assertion
`awaitNonTerminalTurnState`; it waits for a typed event and requires
`state.phase == Thinking || state.phase == Speaking` and
`!state.isTerminal` immediately before `interruptTurn`. Non-empty text alone
does not qualify. Name the terminal assertion
`awaitMatchingTurnInterrupted`; it checks the binding's profile, opaque handle,
connection ID, and turn ID. Add the content-free
`AndroidInterruptTelemetry` snapshot with `sentCount`,
`acknowledgementObserved`, and `terminalObserved` booleans/counts, plus
`OkHttpRelaySessionClient.snapshotInterruptTelemetry()` and the bounded
`awaitInterruptAcknowledgement` helper. `interruptTurn` returning `true` means
only that the request was written; it is not the acknowledgement. The test
records the acknowledgement separately and only the matching
`TurnInterrupted` event settles the branch. If the response completes before
the request is sent, record
`NATURAL_COMPLETION_RACE` and mark the branch `inconclusive`. Otherwise, after
the request is written, the branch passes only when both the acknowledgement
and matching terminal event arrive before the fixed deadline. If either is
missing at that deadline, mark the branch `fail` with `TERMINAL_TIMEOUT`.
Neither non-pass outcome is eligible for live pass.
The Home trace must corroborate the method and terminal-event order without
exposing content.

`AndroidClientRequestTelemetry` is an internal, content-free seam with atomic
`promptSubmitCount` and `interruptRequestCount` values and
`resetRequestTelemetry()`/`snapshotRequestTelemetry()` methods. The counters
increment only when the corresponding JSON-RPC request is actually handed to
the socket writer, reset before each isolated branch, and are never serialized
with request arguments. A preflight or unresolved-turn branch must leave both
counters at zero.

### Reconnect and no replay

The Home deployment provides a documented controlled test hook executable at
`LIVE_HOME_CLOSE_HOOK`. The wrapper starts it once, after preflight and
immediately before the reconnect branch, with the exact argv
`--run-id <LIVE_HOME_RUN_ID> --scenario reconnect --after-method prompt.submit
--once`; it must arm before the branch begins and return a content-free
`HOOK_ARMED` token. Home closes the peer only after that branch's
`prompt.submit` is accepted, then the hook exits `0` and its signed trace
contains `peer.close:observed`. The Android test waits on its
`observeConnection` latch for the fixed 15-second deadline, calls
`client.reconnect()` exactly once after the latch, and requires a second
10-second readiness deadline. A hook timeout, non-zero exit, missing latch, or
missing signed event is `NOT_RUN: CONTROLLED_CLOSE` or
`INCONCLUSIVE: RECONNECT_TRACE`, never a pass.

`LiveRelayHandshakeTest.reconnect_reuses_the_same_home_conversation_without_replay`
asserts that the trace sequence is `prompt.submit:accepted`,
`peer.close:observed`, `conversation.reconnect:ready` with
`same_conversation=true`, and no second `prompt.submit`. The Android-side
`AndroidClientRequestTelemetry` snapshot counts outbound `prompt.submit`
attempts for that branch and requires exactly one total and zero after the
accepted one. The plan does not invent a magic prompt, client timer, or
undocumented close protocol; `LIVE_RECONNECT_PROMPT` is the explicit probe
input and the close hook is Home-owned.

## Deterministic contract coverage

Add or retain focused JVM tests in
`app/src/test/java/com/achappell/hermesrelay/OkHttpRelaySessionClientTest.kt`
and the existing normalizer/reducer tests for these exact cases. Add the
named `LiveHomeReadiness` and `AndroidClientRequestTelemetry` test seams even
when their implementation lives beside the client. The tests must use
`assertSafeBoolean(label, condition)` and `assertSafeCount(label, expected,
actual)` helpers whose labels are compile-time constants; never interpolate a
prompt, credential, handle, result, exception, or raw frame. For
content-bearing values, assert only a safe projection such as a boolean,
allowlisted enum, bounded count, or exact schema version:

- a JSON-RPC error response whose `error.data.code` is
  `"hermes_unavailable"`, and a result response with
  `status="unavailable"` and `reason="hermes_unavailable"`; both map to
  typed `HermesUnavailable`, never submit a prompt, and never use Standard or
  fork fallback;
- a 404 during the WebSocket upgrade maps deterministically to
  `Retryable(TransportUnavailable)` with no active socket or turn; the
  validation record names it `NOT_RUN: HOME_404`;
- missing/true/non-boolean `unresolved_turn`, missing route/capability fields,
  unknown route class, non-`absent` timing, and non-empty/oversized command
  values fail closed as protocol/readiness errors. The fixture supplies an
  expected JSON-RPC id and proves a mismatched id is rejected immediately with
  `ProtocolError`, the socket is closed, no `Connected` outcome or event is
  published, and no request counter changes; a ready response with unresolved
  state proves zero `prompt.submit` and `session.interrupt` requests while
  ordinary recovery still exposes `Connected(unresolvedTurn=true)`;
- `RelayProfileValidator.validateApprovedHomeRoute` accepts a host-only route
  and an exact `/api/v1/bridge/ws` route, rejects every other path prefix or
  suffix, and `OkHttpRelaySessionClient.bridgeUrl` produces exactly the fixed
  bridge path for either accepted input;
- response events and binary audio with the wrong handle or turn are ignored
  or typed as protocol failure; only matching bindings reach the observer;
- `HermesEventNormalizerTest` feeds `message.complete` with complete,
  interrupted, failed, and unknown statuses plus `session.interrupted` and
  `turn.cancelled`; the typed event projection and the redacted trace mapper
  agree on `turn.completed:completed`, `turn.interrupted:interrupted`, or
  `turn.failed:failed`, and only the first two approved terminal forms can
  satisfy a passing branch;
- interrupt acknowledgement does not settle the reducer and is exposed by
  `snapshotInterruptTelemetry()`, while a matching terminal event is required;
  reconnect selects `conversation.reconnect` without prompt replay and the
  request telemetry remains exactly one submit;
- the physical sink seam proves asynchronous writes, accepted-frame counts,
  drain callback ordering, and failure on a stranded/underrun path without
  pretending a JVM recording sink is speaker evidence.

The exact new or renamed deterministic test methods are
`HermesEventNormalizerTest.message_complete_statuses_map_to_canonical_terminal_events`,
`OkHttpRelaySessionClientTest.readiness_rejects_malformed_home_state_without_a_turn`,
`OkHttpRelaySessionClientTest.reconnect_uses_conversation_reconnect_without_prompt_replay`,
`AndroidTurnStateTest.interrupt_acknowledgement_is_not_terminal`,
`AndroidRecoveryControllerTest.reconnect_does_not_replay_an_uncertain_turn`,
and `AndroidAudioSinkFramesTest.delayed_drain_requires_playback_advancement`.
The wrapper contract is covered by
`scripts/run-live-home-gate-contract-test.sh`, which uses fake `adb`, Gradle,
attestation, XML, and close-hook commands to exercise missing/duplicate
inputs, invalid signatures, stale reports, no-echo behavior, protected-value
and raw-content scans, clean marker definitions versus captured-marker
fixtures, partial-run publication, no-arm and never-exit hook cleanup, mixed
failure-then-limitation precedence, and exit codes 0/20/30/64.

## Implementation tasks

1. **Safe live harness** — create
   `scripts/verify-story-dispatch.sh` to enforce `authority_revision: 5` and
   compare the canonical spec SHA-256 with the dispatch frontmatter before
   any stories-mode implementation leg; the script emits only pass/fail.
   Create `scripts/verify-home-attestation.py` using the `cryptography`
   Ed25519 verifier and the `rfc8785` canonicalizer; it accepts explicit
   `deployment` and `run-trace` kinds, verifies only the bindings valid for
   that phase, and emits only `ATTESTATION=PASS` or an allowlisted failure.
   Create `scripts/validate-live-home-inputs.py` to enforce the exact
   `HomeCredentialValidator` 43-character/32-byte credential boundary,
   route/handle/prompt/run-id shapes, and duplicate-handle rule without
   printing a value. Create
   `scripts/run-live-home-gate.sh`,
   `scripts/run-live-home-gate-contract-test.sh`, and update
   `app/src/androidTest/java/com/achappell/hermesrelay/LiveRelayHandshakeTest.kt`.
   The wrapper owns the functions `readLiveArguments`,
   `validateLiveArguments`, `runDeviceAudioPreflight`,
   `runDefaultIsolation`, `runLiveScenarios`,
   `scanProtectedArtifacts`, `writeSafeValidationRecord`, and
   `publishSafeResult`. `writeSafeValidationRecord` writes the versioned safe
   record to an owner-only temporary file and atomically renames it only after
   all branch and command fields pass the allowlist. An `EXIT` trap always
   publishes a safe partial record or a safe harness-failure record; expected
   Gradle/adb/hook failures run in guarded branches so `set -euo pipefail`
   cannot skip status mapping. The contract test uses fake commands and
   covers every required exit code and scan path. The Android test adds named
   helpers `readLiveArguments`, `assertLiveHomeCapabilities`,
   `awaitNonTerminalTurnState`, `awaitInterruptAcknowledgement`,
   `awaitMatchingTurnInterrupted`, `resetRequestTelemetry`, and
   `assertDefaultReportCounts`; use the four scenario-specific handles, three
   explicit probe prompts, constant assertion labels, no prompt default, and
   schema-2 device-cache result handoff, exact audio-preflight token/process
   classification, and `try/finally` cleanup for
   observation/client/sink resources. Keep
   `LiveRelay.kt` and the Gradle default exclusion intact.
2. **Strict readiness and unavailable fixtures** — update
   `OkHttpRelaySessionClient.readOpenResult` and
   `OkHttpRelaySessionClient.parseCapabilities` (or their named replacements)
   and the focused transport tests only where they expose a concrete contract
   defect. Change `readOpenResult` to receive `expectedRpcId` and verify the
   response id before publishing any outcome. Add `UnresolvedTurn` and
   `CapabilityShapeInvalid` to
   `AndroidHomeUnavailableReason`; pin
   schema 1, exact handle, explicit unresolved-turn semantics, exact
   route/capability types, the host-only/exact-path route rule, the two
   `hermes_unavailable` wire shapes, 404 classification, and no fallback.
   `LiveHomeReadiness` must layer strict live-gate rejection over ordinary
   `Connected(unresolvedTurn=true)` recovery. `assertNewTurnReadiness` rejects
   unresolved state, while `assertReconnectReadiness` accepts only the
   controlled reconnect state and preserves `AndroidRecoveryState.unresolvedHomeTurn`.
   Missing fields must never become safe-looking defaults. A mismatched response
   id closes the socket and maps to `ProtocolError` immediately. Add the
   normalizer/trace fixture that maps the existing Home terminal wire forms to
   the canonical signed `turn.completed`, `turn.interrupted`, or `turn.failed`
   vocabulary.
3. **Physical audio evidence** — create the named
   `AudioOutputPreflightTest.verifyPhysicalAudioOutput` helper and update
   `AndroidAudioSink.kt` with the named
   `AndroidAudioSinkTelemetry` and `AudioTrackAudioSink.snapshotTelemetry()`
   seam plus the internal `AudioTrackDriver`/
   `AudioTrackDriverFactory.create` seam so the live test can distinguish
   accepted non-zero aligned PCM and a real drain callback from mere queueing.
   Define the exact driver methods, `underrunCount()` production mapping, and
   allowlisted failure kinds above. Make a stranded/underrun track fail while
   still playing instead of treating a stopped playhead as drained. Do not
   retain PCM or change the normal `RecordingAudioSink` test meaning.
4. **Interrupt and reconnect evidence** — strengthen the live scenarios and
   their deterministic fixtures for non-terminal interrupt timing, matching
   terminal binding, separate acknowledgement telemetry,
   operator-controlled peer close, reconnect ordering, and zero prompt replay.
   Add `AndroidClientRequestTelemetry` beside the client in
   `app/src/main/java/com/achappell/hermesrelay/OkHttpRelaySessionClient.kt`
   with atomic per-branch `promptSubmitCount` and `interruptRequestCount`,
   reset before each branch;
   require exactly one reconnect-branch submit and zero after acceptance. The
   close hook protocol includes a five-second arm deadline, a ten-second
   guarded-exit deadline, and the two-second `TERM` to `KILL` escalation; the
   Android observation/reconnect deadlines remain 15 seconds and 10 seconds.
   Do not add Home close behavior to Android.
5. **Safe validation record** — create/update
   `_bmad-output/implementation-artifacts/validation-5-a-4-live-home-gate.md`
   with the schema-2 result below, exact commands, nullable device/command
   facts, two-phase provenance state, branch evidence, branch reasons,
   overall-rule mapping, and exit code. Never copy raw logs or value-bearing
arguments into it; `writeSafeValidationRecord` is the only publishing seam.
   `LiveHomeSafeResult.kt` writes the exact per-scenario schema-2 envelope,
   refuses to overwrite a device result, and the wrapper accepts it only after
   fresh-cache/run-id checks, exact schema validation, and safe projection.
   Keep environment-limited exit `20` distinct from started-operation
   `HARNESS_FAILURE` exit `30`, and preserve failure precedence through the
   bounded cleanup and publication path.
   A partial run hands off the same aggregate schema with completed branches preserved,
   started branches `inconclusive` when evidence is unavailable, and remaining
   branches `not-run` with null facts/counts; the `EXIT` trap maps the guarded
   command outcome to `20` or `30` and atomically publishes before exit.
6. **Local delivery records** — keep the story-index spec and validation links
   pointed at this follow-up, leave `5-a-4: done` untouched, and leave
   `5-a-4-live-home-gate: backlog` until all required branches pass. The sole
   allowed delivery transition is that row from `backlog` to `done`, in the
   same reviewed change that publishes an attested safe record with
   `status: live-pass` and `exit_code: 0`; any partial, environment-limited,
   or failed result leaves it `backlog`. Only an all-pass, attested run may
   move this follow-up to `done`.

## Acceptance Criteria

- Given the deployment envelope, public key, and allowlist are owner-only and
  valid, when the wrapper performs preflight, then it verifies deployment
  provenance before `adb` or Gradle and does not require a current-run trace
  that cannot exist yet.
- Given a credential that is absent or does not match the Android
  43-character base64url/32-byte contract, when input validation runs, then it
  records `MISSING_PAIRING` or `INVALID_BINDING` without opening a client or
  exposing the value.
- Given a later branch has not started, when the wrapper publishes a partial
  result, then schema version 2 records that branch as `execution:
  not-started` with null branch facts/counts while preserving any device and
  command facts already observed during preflight or earlier branches.
- Given Home returns a response for a different RPC id or malformed readiness
  fields, when Android processes the handshake, then it closes and clears the
  socket, publishes no `Connected` outcome or event, and leaves prompt and
  interrupt counters at zero.
- Given Home sends `message.complete` or an interruption event in one of the
  existing wire forms, when the normalizer and trusted trace producer process
  it, then both project the same canonical terminal fact
  (`turn.completed`, `turn.interrupted`, or `turn.failed`), and a passing trace
  cannot use an unmapped literal wire name.
- Given the typed branch receives supported PCM, when the physical sink accepts
  aligned frames and its playback head advances to the accepted frame count,
  then the safe result records non-zero counts, no underrun, a drain callback,
  and `Complete` plus `Delivered`; a stalled or underrunning driver fails the
  branch.
- Given the interrupt branch observes a non-terminal `Thinking` or `Speaking`
  state, when Android sends the interrupt, then the branch passes only after
  the acknowledgement and matching terminal event arrive before the deadline;
  a natural completion before the request is inconclusive, and a missing
  acknowledgement or terminal after the request is `fail: TERMINAL_TIMEOUT`.
- Given the reconnect close hook reports the accepted prompt and peer close,
  when Android reconnects, then it sends exactly one `conversation.reconnect`
  for the same opaque conversation and zero replacement `prompt.submit`
  requests, preserving uncertainty until the matching readiness result with
  `unresolved_turn=true`.
- Given the selected device already contains a result under the requested run
  id, when the wrapper performs its cache preflight, then it rejects the run
  before any live scenario and never treats that file as evidence; each
  scenario result must be absent before invocation and must be one fresh file
  written after that invocation.
- Given the physical preflight emits a valid `FAIL:AUDIO_OUTPUT` token, when
  the selected device is otherwise valid, then the wrapper records an
  environment-limited audio result with exit `20`; a missing, malformed,
  duplicate, timed-out, or crashed preflight instead records
  `HARNESS_FAILURE` with exit `30`.
- Given a device test safely completes without branch proof, when it writes a
  not-run handoff, then its request counts are observed integer values,
  including zero; if the process dies or the file is absent, the wrapper
  leaves counts unobserved and null and maps the harness stop to top-level
  failure/exit `30`.
- Given the implementation diff contains the scanner's marker definitions and
  a clean contract fixture, when artifact scanning runs, then those source
  literals pass; given a captured run log or report contains a raw marker, or
  any scanned artifact contains a protected value, then scanning fails with
  `HARNESS_FAILURE` and exit `30`.
- Given a scenario invocation completes, when the wrapper retrieves its device
  handoff, then the file has exactly the schema-2 per-scenario envelope below,
  binds the current run and scenario, and contributes only its allowlisted
  projection to the aggregate record.
- Given the close hook does not arm within five seconds or does not exit within
  its ten-second cleanup deadline, when the wrapper closes the run, then it
  terminates the hook process group with `TERM`, waits two seconds, escalates
  to `KILL`, publishes a safe bounded result, and never waits indefinitely.
- Given an earlier branch or command has already recorded a failure, when a
  later provenance or close-hook limitation occurs, then the wrapper preserves
  the failure, publishes `status: failed`, and returns normalized exit `30`
  rather than downgrading the run to an environment limitation.
- Given all four branch records pass, the deployment and run-trace envelopes
  agree on deployment identity/adapter/signer/route, and the run-trace binds
  the same run and selected device, when the wrapper publishes the result,
  then it may return `live-pass` with exit code 0; every other outcome leaves
  the follow-up backlog.

## Safe result schema and status rules

The validation record uses exactly this version-2 JSON shape, with only
allowlisted values, counts, bounded identities, and nullable fields. The
schema distinguishes facts not observed because execution never started from
facts observed as zero or false. `home` is `null` until both provenance
envelopes are verified; all other fields, including nullable device and
command facts, are still emitted for a partial run:

```json
{
  "schema_version": 2,
  "status": "done-with-environment-limitation",
  "exit_code": 20,
  "run_id": "<non-secret identifier>",
  "device": {
    "status": "observed",
    "model": "Pixel 6a",
    "api": 35,
    "physical": false,
    "audio_output": false,
    "serial_fingerprint": "<64 lowercase hex characters>",
    "default_non_live_count": 0,
    "default_live_count": 0
  },
  "provenance": {
    "deployment_attested": true,
    "run_trace_attested": false
  },
  "home": null,
  "branches": {
    "handshake": {
      "status": "not-run",
      "reason": "HOME_PROVENANCE",
      "execution": "not-started",
      "evidence": {
        "available": false,
        "request_counts": { "observed": false, "prompt_submit": null, "interrupt": null },
        "facts": null
      }
    },
    "typed_audio": {
      "status": "not-run",
      "reason": "HOME_PROVENANCE",
      "execution": "not-started",
      "evidence": {
        "available": false,
        "request_counts": { "observed": false, "prompt_submit": null, "interrupt": null },
        "facts": null
      }
    },
    "interrupt": {
      "status": "not-run",
      "reason": "HOME_PROVENANCE",
      "execution": "not-started",
      "evidence": {
        "available": false,
        "request_counts": { "observed": false, "prompt_submit": null, "interrupt": null },
        "facts": null
      }
    },
    "reconnect": {
      "status": "not-run",
      "reason": "HOME_PROVENANCE",
      "execution": "not-started",
      "evidence": {
        "available": false,
        "request_counts": { "observed": false, "prompt_submit": null, "interrupt": null },
        "facts": null
      }
    }
  },
  "commands": {
    "default": { "status": "not-run", "exit_code": null, "report_count": null },
    "audio_preflight": { "status": "not-run", "exit_code": null },
    "live": { "status": "not-run", "exit_code": null, "report_count": null }
  },
  "overall_rule": "live-pass only when all four branches pass and Home provenance is attested"
}
```

The displayed values are examples of the allowed types: the published JSON
contains one concrete string, integer, boolean, object, or `null` value at
each position, never pipe-delimited text. `run_id` is a bounded non-secret
string or `null` when validation never accepted one. `device.status` is
`observed` or `not-run`; its facts are nullable until observed. `provenance`
always contains both booleans. A branch always contains `status`, `reason`,
`execution`, and `evidence`; `execution` is `not-started`, `started`, or
`completed`. `evidence.available` means that the branch produced proof; it is
independent from whether request counts were observed. When it is false,
`facts` is null. Each evidence object also has
`request_counts.observed`: a valid device-written handoff sets it true and
publishes non-negative integer counts, including zero, even for a safely
not-run branch. If an instrumentation process dies after a branch starts or
its handoff is missing, the aggregate keeps `observed=false` with null counts
and the branch is `inconclusive: HARNESS_FAILURE`; the wrapper never invents
counts. Each valid handoff and aggregate branch uses one
exact branch-specific `facts` object. The exact projections are defined below:
The only handshake keys are `connection_ready`, `response_id_matched`,
`unresolved_turn`, `route`, and `capabilities`. `route` is either null or an
object with exactly `class` and `id`; `capabilities` is either null or an
object with exactly `heartbeat`, `timing`, `commands`, `interrupt`, and
`audio`. A passing projection is equivalent to:

```json
{
  "connection_ready": true,
  "response_id_matched": true,
  "unresolved_turn": false,
  "route": { "class": "home", "id": "<bounded route identity>" },
  "capabilities": {
    "heartbeat": true,
    "timing": "absent",
    "commands": [],
    "interrupt": false,
    "audio": false
  }
}
```

The only typed-audio keys are `format`, `accepted_bytes`, `accepted_frames`,
`audio_chunk_received`, `audio_failed`, `drained`, `underrun_count`,
`terminal_event_observed`, `final_phase`, and `final_audio`. `format` is either
null or an object with exactly `sample_rate`, `channels`, `sample_width`,
`byte_order`, and `encoding`. A passing projection is equivalent to:

```json
{
  "format": {
    "sample_rate": 16000,
    "channels": 1,
    "sample_width": 2,
    "byte_order": "little",
    "encoding": "pcm_s16le"
  },
  "accepted_bytes": 320,
  "accepted_frames": 160,
  "audio_chunk_received": true,
  "audio_failed": false,
  "drained": true,
  "underrun_count": 0,
  "terminal_event_observed": true,
  "final_phase": "Complete",
  "final_audio": "Delivered"
}
```

The only interrupt keys are `non_terminal_state_observed`,
`interrupt_sent_count`, `acknowledgement_observed`,
`terminal_event_observed`, and `terminal_event`. A passing projection is:

```json
{
  "non_terminal_state_observed": true,
  "interrupt_sent_count": 1,
  "acknowledgement_observed": true,
  "terminal_event_observed": true,
  "terminal_event": "interrupted"
}
```

The only reconnect keys are `accepted_prompt_submit_count`,
`post_accept_prompt_submit_count`, `peer_close_observed`,
`conversation_reconnect_observed`, `same_conversation`, and
`uncertainty_preserved`. A passing projection is:

```json
{
  "accepted_prompt_submit_count": 1,
  "post_accept_prompt_submit_count": 0,
  "peer_close_observed": true,
  "conversation_reconnect_observed": true,
  "same_conversation": true,
  "uncertainty_preserved": true
}
```

These examples show passing values. The validator rejects additional keys;
for a valid non-pass handoff, an observation not reached is `null` at its
declared field, a count is a non-negative integer or `null`, a boolean is a
boolean or `null`, and an enum is one of its declared values or `null`.
`final_phase` is one of `Idle`, `Listening`, `Transcribing`, `Thinking`,
`Buffering`, `Speaking`, `Complete`, `Unavailable`, `Disconnected`, or
`Interrupted`; `final_audio` is one of `NotStarted`, `Buffering`, `Speaking`,
`Delivered`, or `Unavailable`; and `terminal_event` is `completed`,
`interrupted`, or `null`. No fact contains a handle, turn ID, prompt, response,
frame, PCM, or exception. `branch.status` is one of `pass`, `fail`,
`inconclusive`, or `not-run`; the top-level `status` is one of
`done-with-environment-limitation`, `live-pass`, or `failed`; the top-level
`exit_code` is one of `0`, `20`, `30`, or `64`;
command `exit_code` fields are nullable captured child-process codes in
`0..255`; counts are nullable or non-negative integers; `api` is nullable or
an integer; and every branch `reason` is null on pass or one allowlisted code
below. A failed wrapper may publish a captured non-secret child-process code
while returning normalized top-level `exit_code: 30`.

When non-null, `home` has exactly
`{deployment_id, deployment_revision, adapter, signer_key_id, route_class,
route_id, trace_run_id, trace_digest, device_serial_fingerprint}`. The
deployment and run-trace envelopes additionally carry signed redacted trace or
deployment fields; those envelope-only fields are never copied into the
validation record.

The branch record may include only a safe reason code from this set:
`DEVICE_SELECTION`, `WRONG_DEVICE`, `AUDIO_OUTPUT`, `MISSING_PAIRING`,
`INVALID_BINDING`, `HOME_404`, `HOME_UNREACHABLE`, `HOME_UNAVAILABLE`,
`UNRESOLVED_TURN`, `CAPABILITY_SHAPE_INVALID`, `CAPABILITY_UNAVAILABLE`,
`HOME_PROVENANCE`,
`CONTROLLED_CLOSE`, `NATURAL_COMPLETION_RACE`, `TERMINAL_TIMEOUT`,
`AUDIO_FAILURE`, `RECONNECT_TRACE`, or `HARNESS_FAILURE`.

Rules are strict:

- Any failing assertion, replay, wrong binding, secret scan hit, or
  implementation defect is `failed` and blocks delivery.
- Failure precedence is monotonic: once a branch or command is recorded as a
  failure, later provenance, close-hook, cleanup, or publication limitations
  may add evidence but may not change it to `inconclusive`, `not-run`, or an
  environment-limited overall status. Any recorded failure keeps top-level
  `status: failed` and normalized `exit_code: 30`.
- Environment-limited exit `20` is reserved for a prerequisite or external
  condition that is known before an operation can produce untrustworthy
  evidence: missing pairing, wrong device, valid physical-output failure,
  missing deployment/run-trace provenance, Home 404/unreachable/unavailable,
  a valid false capability, or a close hook that never arms before reconnect.
  These leave the follow-up `backlog`.
- Harness exit `30` covers an operation that started but cannot be trusted:
  unexpected child-process failure, timeout, malformed/missing/duplicate
  report or device handoff, invalid preflight output, an armed hook that will
  not exit, cleanup/publication failure, or a protected-artifact scan hit.
  The affected branch may be `inconclusive: HARNESS_FAILURE`, but the
  top-level status is `failed` and the normalized exit is `30`.
- An incomplete branch is `inconclusive`, not pass. A partial run never becomes
  an overall live pass because another branch happened to pass.
- `writeSafeValidationRecord` initializes all four branches with
  `execution: "not-started"`, `evidence.available: false`, null facts, null
  request counts, `status: "not-run"`, and `reason: null`; it updates only the
  branch reached and publishes the partial record from the `EXIT` trap after
  replacing any missing branch reason with `HARNESS_FAILURE`. Device facts and
  command exit/report fields stay null until the corresponding operation is
  observed; zero is never used as a placeholder. The record is written first
  to `${RUN_DIR}/safe-validation-record.json.tmp` with mode `600` and then
  atomically renamed to `${RUN_DIR}/safe-validation-record.json`; the
  repository validation note is updated only from that safe projection. No
  command's stdout/stderr is used as a result field.
- The parent `5-A-4` status, parent specification, and parent validation are
  never rewritten by this follow-up.

The status mapping is deterministic: `HOME_404`, `HOME_UNREACHABLE`,
`HOME_UNAVAILABLE`, missing pairing, wrong device/audio, missing provenance,
valid false branch capability (`CAPABILITY_UNAVAILABLE`), and missing
controlled-close evidence before reconnect leave the affected branch
`not-run` (or `inconclusive` when a branch started) and set the overall status
to `done-with-environment-limitation` with exit `20`. A malformed readiness
response, authentication/route contract defect, wrong binding, prompt replay,
audio failure, implementation assertion failure, or secret-scan hit sets the
affected branch to `fail` and the overall status to `failed` with exit `30`.
An unexpected process/report/handoff failure uses `HARNESS_FAILURE` and the
same top-level failed/30 mapping even when that branch is incomplete. A
natural completion race is always `inconclusive: NATURAL_COMPLETION_RACE` with
environment-limited overall status. After an interrupt request, failure to
observe either the acknowledgement or matching terminal by the fixed deadline
is always `fail: TERMINAL_TIMEOUT` with overall status `failed`. Neither is
converted to `pass`, and no later environment limitation can downgrade a
failure.

## Live wrapper contract

`scripts/run-live-home-gate.sh` is the only supported entry point for the
opt-in run. It must:

1. Run with `set -euo pipefail` and no `set -x`; build Gradle arguments in a
   shell array; never print the array, expanded command, environment values,
   or assertion output containing an argument. An `EXIT` trap removes its
   owner-only temporary argument/attestation copies after
   `writeSafeValidationRecord` publishes the safe partial result.
2. Validate all required environment names, trust files, prompts, and the four
   handles before any `adb`, Gradle, TLS, or Home operation. It rejects
   duplicate handles and invalid route/credential shapes without displaying
   their values. It verifies the deployment envelope and its key fingerprint
   with `scripts/verify-home-attestation.py --kind deployment`, then verifies
   the canonical dispatch hash before the live class is started. The run-trace
   envelope is intentionally not required at this point: it is verified with
   `--kind run-trace` only after all started scenario records and observed
   route/device facts exist. Missing verifier dependencies are
   `HOME_PROVENANCE`, not permission to bypass verification.
3. Every `adb` and Gradle invocation uses the explicit selected serial; no
   ambient device or parallel target is accepted. `isolateReports` moves any existing
   `app/build/outputs/androidTest-results/connected/` directory to an
   owner-only run backup (never deleting it), creates a fresh empty report
   directory, records the run start time, and after each command builds a
   current-run manifest. The default command has no `notAnnotation` override;
   its XML is parsed using `testsuite/testcase` counts and requires total
   non-live cases greater than zero and `classname` containing `LiveRelay`
   equal to zero. The default reports are then moved into the run's default
   evidence directory and the live report directory is isolated again. A
   missing, unreadable, stale, or ambiguous report is `HARNESS_FAILURE`, not a
   zero count.
4. Run `runDeviceAudioPreflight` after APK installation and before the
   explicit live Gradle command. Start `LIVE_HOME_CLOSE_HOOK` only after all
   preflight checks and immediately before the reconnect scenario. The
   wrapper-owned `armCloseHook` captures the process group, accepts only the
   content-free `HOOK_ARMED` token within five seconds, and records the PID in
   owner-only run state. If it does not arm, the wrapper sends `TERM`, waits
   two seconds, sends `KILL` if needed, records `NOT_RUN: CONTROLLED_CLOSE`,
   publishes the safe partial result, and exits `20` only when no earlier
   branch has recorded a failure. If an earlier branch failed, that failure
   remains authoritative and the wrapper exits `30`. The wrapper-owned
   `stopCloseHook` waits at most ten seconds for a guarded exit; on timeout it
   sends `TERM`, waits two seconds, then sends `KILL`, records
   `HARNESS_FAILURE` for an armed hook that will not exit; that harness
   failure and exit `30` take precedence over earlier environment limitations,
   and cleanup continues to publication. The `EXIT` trap always calls this bounded cleanup before
   publishing, so hook cleanup can never block the safe result. The hook
   receives only the run id, scenario, method, and once flag described above.
5. `runLiveScenarios` runs the explicit command below four times, once each
   with `scenario=handshake`, `typed_audio`, `interrupt`, and `reconnect`.
   Each invocation uses `notAnnotation=org.junit.Ignore`, the named class,
   `liveRunId`; the test consumes only the handle/prompt pair selected by that
   scenario (`handshakeConversationHandle`, `typedConversationHandle` plus
   `typedPrompt`, `interruptConversationHandle` plus `interruptPrompt`, or
   `reconnectConversationHandle` plus `reconnectPrompt`). The wrapper still
   validates all four handles for uniqueness before starting and may pass the
   full validated argv set shown below without allowing a test to consume a
   different scenario's pair. It starts the
   close hook immediately before the `scenario=reconnect` invocation, so the
   operator-controlled close cannot race the other branches. All values are
   passed through an argv array and are never echoed. Before launching each
   scenario invocation, the wrapper proves that the exact scenario path is
   absent. Only then does the test write exactly one schema-2 per-scenario
   result to the target app cache at the fixed relative path
   `cache/hermes-live-home/<liveRunId>/<scenario>.json` through
   `LiveHomeSafeResult.kt`. The wrapper retrieves only that file with
   `adb -s "$LIVE_DEVICE_SERIAL" exec-out run-as
   com.achappell.hermesrelay cat <fixed-relative-path>`, validates its exact
   run/scenario/schema binding, requires one post-invocation file with no
   pre-existing timestamp or content, and copies only its allowlisted JSON
   projection into the owner-only run directory. It removes that exact device
   result after retrieval and proves the path is absent before the next
   scenario. Missing, stale, duplicate, malformed, or content-bearing
   handoffs are `HARNESS_FAILURE`; JUnit XML and assertion messages are never
   used as branch evidence. After collection, the wrapper verifies
   `LIVE_HOME_TRACE_ATTESTATION_FILE` against the observed run, device, and
   route before it records `live-pass`.

### Device-side per-scenario handoff

`LiveHomeSafeResult.kt` writes exactly one atomic JSON object for the current
scenario. The final file has exactly these top-level keys, rejects duplicate or
additional keys, and contains no aggregate record:

```json
{
  "schema_version": 2,
  "run_id": "<the validated LIVE_HOME_RUN_ID>",
  "scenario": "handshake",
  "status": "pass",
  "reason": null,
  "evidence": {
    "available": true,
    "request_counts": {
      "observed": true,
      "prompt_submit": 0,
      "interrupt": 0
    },
    "facts": {
      "connection_ready": true,
      "response_id_matched": true,
      "unresolved_turn": false,
      "route": { "class": "home", "id": "<bounded route identity>" },
      "capabilities": {
        "heartbeat": true,
        "timing": "absent",
        "commands": [],
        "interrupt": false,
        "audio": false
      }
    }
  }
}
```

The example uses the `handshake`/`pass` values; the allowed scenario values are
`handshake`, `typed_audio`, `interrupt`, and `reconnect`, and the allowed
statuses are `pass`, `fail`, `inconclusive`, and `not-run`.

`reason` is null only for `pass`; a non-pass result uses one of the
allowlisted branch reason codes. In a device-written envelope,
`evidence.available=false` means the test completed a safe not-run outcome:
`request_counts.observed=true` with integer counts, normally zero, and
`facts=null`. A process that dies, or a missing/malformed handoff, produces no
valid device envelope; the wrapper publishes an aggregate
`inconclusive: HARNESS_FAILURE` with `evidence.available=false`,
`request_counts.observed=false`, and null counts/facts. A valid started handoff
may publish partial safe facts and non-negative integer counts, including zero,
but the wrapper never invents counts. `facts` is the exact branch-specific projection
listed in the aggregate schema; it may contain only booleans, bounded enums,
counts, fixed format values, and bounded route identities. It never contains a
credential, handle, prompt, text, turn ID, raw frame, PCM, exception, or
assertion message. The writer creates a temporary file in the same cache
directory and atomically renames it to the fixed scenario path; an existing
final path is a write failure, not an overwrite. The wrapper validates this
envelope and merges it into the version-2 aggregate only after the run and
scenario match. The merge copies `evidence.available`, `facts`, and
`request_counts` independently; it does not replace observed integer counts
with null merely because proof is unavailable. If no valid envelope exists,
the aggregate fallback is the only case that uses unobserved/null counts.

6. `scanProtectedArtifacts` redacts process output before writing any
   owner-only run log. It scans the generated captured-artifact roots—run
   logs, isolated XML/protobuf reports, device handoff projections, and
   `${RUN_DIR}/safe-validation-record.json`—for each protected value and these
   exact raw-content markers: `RAW_FRAME=`, `RAW_JSON=`, `RAW_RESPONSE=`,
   `RESPONSE_TEXT=`, `PCM_BYTES=`, `PCM_BASE64=`, `Authorization: Device `,
   and `Content-Disposition: attachment`. It scans the current `git diff`
   separately for protected values and forbidden binary/media files, but does
   not apply raw-marker matching to source, specification, or contract-fixture
   text: marker definitions there are expected code, not captured content.
   Text files must be valid UTF-8. The expected Gradle instrumentation
   protobuf reports `test-result.pb`/`test-results.pb` under the isolated
   report roots are the only binary exception: the scan reads their bytes in
   memory for every protected value and raw-content marker, records only their
   size and SHA-256 in the run manifest, and never copies their contents into
   the safe record. Any other binary file, or any file named with `.pcm`,
   `.raw`, `.wav`, `.pcap`, `.har`, or `.jsonl`, fails the run. The scan
   evaluates values without printing them; a match prevents contaminated
   artifacts from being published. Only the safe JSON projection is handed to
   `writeSafeValidationRecord`.

The wrapper exits `0` for an attested all-pass live run, `20` for
`done-with-environment-limitation`, `30` for a failed run, and `64` for an
invalid invocation. Device and audio preflight occurs before the live Gradle
command and makes no Home request; the default non-live proof may be retained
only when its report is independently present and counted.

## Acceptance-to-proof map

The implementation must leave these named proof seams, so a green build cannot
hide an unrun branch:

| Requirement | Deterministic proof | Live/wrapper proof | Published result |
| --- | --- | --- | --- |
| Readiness and no unsafe turn | `OkHttpRelaySessionClientTest.readiness_rejects_malformed_home_state_without_a_turn` plus one parameterized case per malformed field | `LiveRelayHandshakeTest.run_selected_scenario` → `runHandshake` readiness path and `assertLiveHomeCapabilities` | `handshake` + safe reason |
| Home-only route and unavailable mapping | `RelayProfileTest.approved_route_rejects_non_bridge_paths`; `OkHttpRelaySessionClientTest.home_404_is_retryable_without_an_active_socket`; both unavailable wire-shape tests | wrapper route/preflight and signed deployment attestation | `HOME_404`, `HOME_UNAVAILABLE`, or `failed` |
| Typed text and physical audio | `ResponseAudioPlaybackTest` and `AndroidAudioSinkFramesTest` delayed-drain/failure cases | `LiveRelayHandshakeTest.run_selected_scenario` → `runTypedAudio` with `snapshotTelemetry()` | `typed_audio` + counts/booleans |
| Interrupt terminal binding | `AndroidTurnStateTest.interrupt_acknowledgement_is_not_terminal` | `LiveRelayHandshakeTest.run_selected_scenario` → `runInterrupt` with `awaitNonTerminalTurnState` and `awaitMatchingTurnInterrupted` | `interrupt` + safe race/timeout reason |
| Reconnect without replay | `AndroidRecoveryControllerTest.reconnect_does_not_replay_an_uncertain_turn` and client request-selection fixture | `LiveRelayHandshakeTest.run_selected_scenario` → `runReconnect` plus signed `peer.close` trace | `reconnect` + `CONTROLLED_CLOSE`/`RECONNECT_TRACE` |
| Default isolation and artifact safety | Gradle exclusion/argument tests where applicable | wrapper XML counts and protected-value scan | overall status/exit code |
| Safe wrapper handoff and provenance binding | `scripts/run-live-home-gate-contract-test.sh` fake-command cases for stale reports, invalid signatures, partial publication, scans, and exit mapping | `writeSafeValidationRecord`, `scanProtectedArtifacts`, signed run/device/route binding, and the current-run report manifest | versioned safe record + `exit_code` |

## Secret and artifact scan

The operator wrapper runs with shell tracing disabled, does not echo the
expanded live command, injects values only for the instrumentation process,
and removes any temporary owner-only input on exit. Android assertions use
only constant labels and safe projections; no JUnit assertion receives a
request, binding, result, exception, response text, raw frame, or other
content-bearing object as expected/actual data. Before publishing the
validation record, `scanProtectedArtifacts` scans generated captured-artifact
roots—run logs, Gradle/XML reports, device handoff projections, and the
run-local safe validation record—for the credential, each handle, the three
prompt values, and the exact raw-content markers listed in the wrapper
contract. It scans the current diff separately for protected values and
forbidden binary/media files, but does not raw-marker-scan source,
specification, or contract-fixture text; marker definitions and positive-test
literals there are expected implementation content, not captured output. Text
files must be UTF-8; the only binary exception is the allowlisted
`test-result.pb`/`test-results.pb` Gradle report files, which are byte-scanned
for protected values/markers and manifest-hashed without their contents
entering the validation record. Raw-media/capture extensions and unallowlisted
binary files fail the scan. The scan evaluates values in memory without
printing them; a match stops contaminated-artifact publication and sets the
safe result to `failed` with `HARNESS_FAILURE`. The published record contains
only the safe schema, counts, booleans, allowlisted reason codes, and signed
attestation metadata.

## Verification commands

Run and record the outcomes separately:

Every connected-device Gradle command below runs with
`ANDROID_SERIAL="$LIVE_DEVICE_SERIAL"`; the wrapper supplies that environment
without echoing it.

```text
./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon
scripts/check-apk-metadata.sh
./gradlew compileDebugAndroidTestKotlin --no-daemon
./gradlew connectedDebugAndroidTest --no-daemon
# Repeat once per scenario; select only that scenario's handle and prompt.
ANDROID_SERIAL="$LIVE_DEVICE_SERIAL" ./gradlew connectedDebugAndroidTest --no-daemon \
  -Pandroid.testInstrumentationRunnerArguments.notAnnotation=org.junit.Ignore \
  -Pandroid.testInstrumentationRunnerArguments.class=com.achappell.hermesrelay.LiveRelayHandshakeTest \
  -Pandroid.testInstrumentationRunnerArguments.scenario="$LIVE_SCENARIO" \
  -Pandroid.testInstrumentationRunnerArguments.homeRoute="$LIVE_HOME_ROUTE" \
  -Pandroid.testInstrumentationRunnerArguments.homeCredential="$LIVE_DEVICE_CREDENTIAL" \
  -Pandroid.testInstrumentationRunnerArguments.handshakeConversationHandle="$LIVE_HANDSHAKE_HANDLE" \
  -Pandroid.testInstrumentationRunnerArguments.typedConversationHandle="$LIVE_TYPED_HANDLE" \
  -Pandroid.testInstrumentationRunnerArguments.interruptConversationHandle="$LIVE_INTERRUPT_HANDLE" \
  -Pandroid.testInstrumentationRunnerArguments.reconnectConversationHandle="$LIVE_RECONNECT_HANDLE" \
  -Pandroid.testInstrumentationRunnerArguments.typedPrompt="$LIVE_TYPED_PROMPT" \
  -Pandroid.testInstrumentationRunnerArguments.interruptPrompt="$LIVE_INTERRUPT_PROMPT" \
  -Pandroid.testInstrumentationRunnerArguments.reconnectPrompt="$LIVE_RECONNECT_PROMPT" \
  -Pandroid.testInstrumentationRunnerArguments.liveRunId="$LIVE_HOME_RUN_ID"
LIVE_DEVICE_SERIAL="$LIVE_DEVICE_SERIAL" \
LIVE_HOME_ROUTE="$LIVE_HOME_ROUTE" \
LIVE_DEVICE_CREDENTIAL="$LIVE_DEVICE_CREDENTIAL" \
LIVE_HANDSHAKE_HANDLE="$LIVE_HANDSHAKE_HANDLE" \
LIVE_TYPED_HANDLE="$LIVE_TYPED_HANDLE" \
LIVE_INTERRUPT_HANDLE="$LIVE_INTERRUPT_HANDLE" \
LIVE_RECONNECT_HANDLE="$LIVE_RECONNECT_HANDLE" \
LIVE_TYPED_PROMPT="$LIVE_TYPED_PROMPT" \
LIVE_INTERRUPT_PROMPT="$LIVE_INTERRUPT_PROMPT" \
LIVE_RECONNECT_PROMPT="$LIVE_RECONNECT_PROMPT" \
LIVE_HOME_RUN_ID="$LIVE_HOME_RUN_ID" \
LIVE_HOME_CLOSE_HOOK="$LIVE_HOME_CLOSE_HOOK" \
LIVE_HOME_ATTESTATION_FILE="$LIVE_HOME_ATTESTATION_FILE" \
LIVE_HOME_TRACE_ATTESTATION_FILE="$LIVE_HOME_TRACE_ATTESTATION_FILE" \
LIVE_HOME_ATTESTATION_PUBLIC_KEY_FILE="$LIVE_HOME_ATTESTATION_PUBLIC_KEY_FILE" \
LIVE_HOME_ATTESTATION_KEY_ALLOWLIST_FILE="$LIVE_HOME_ATTESTATION_KEY_ALLOWLIST_FILE" \
scripts/run-live-home-gate.sh
git diff --check
```

The final command is illustrative of the protected environment-variable
injection contract; the wrapper owns the no-tracing/no-echo behavior and the
operator supplies no values in source or committed shell history. The default
connected run must prove non-zero non-live coverage and zero live cases before
the explicit live command is considered. Live tests are not run on an
emulator, a multi-device ADB selection, or a device without microphone/speaker
capability; this gate does not claim Compose UI, microphone recognizer timing,
or TalkBack coverage.

## Spec change log

- Iteration 1: replaced the blocked draft with a live evidence contract
  covering registry linkage, strict readiness, clean handles, device
  selection, provenance, artifact safety, audio drain, interrupt, reconnect,
  cleanup, and partial-result rules.
- Iteration 2: aligned the registered root with the ready-for-dev dispatch
  status; named the route, parser, wrapper, telemetry, interrupt, reconnect,
  attestation, cleanup, report-count, and validation seams; and made every
  environment-limited versus failed outcome deterministic.
- Iteration 3: made the canonical root authoritative with a dispatch hash;
  separated strict live readiness from ordinary unresolved-turn recovery;
  added typed reason symbols, branch-specific capabilities, explicit probe
  inputs, the physical audio preflight protocol, exact audio/interrupt/request
  telemetry seams, signed RFC 8785 provenance and run/device/route binding,
  controlled-close hook deadlines, atomic partial-result handoff, current-run
  report isolation, raw-content markers, wrapper contract tests, and one
  versioned safe JSON result schema.
- Iteration 4: made the generated dispatch story path explicit, bound the
  attestation allowlist to pre-provisioned signer IDs, enumerated the exact
  signed trace order for every branch, rejected route queries/fragments, and
  separated normalized wrapper exit codes from captured child-process codes.
- Iteration 5: split deployment and current-run provenance so the trace is not
  required before the run exists; aligned wrapper credential validation with
  `HomeCredentialValidator`; added explicit Given/When/Then acceptance
  criteria, a schema-2 safe branch-evidence record with honest nulls, a fixed
  Android-to-wrapper handoff, platform underrun telemetry, deterministic
  interrupt outcomes, concrete file ownership, and contiguous multi-entry
  trace-group cardinality. No Android source was changed.
- Iteration 6: made reconnect's unresolved-turn contract context-specific;
  required fresh run/device-cache bindings; specified the exact per-scenario
  schema-2 envelope and atomic handoff; bounded close-hook arm, exit, and
  escalation behavior; and made failure precedence monotonic across later
  environment limitations. No Android source was changed.
- Iteration 7: separated valid audio-preflight failure tokens from malformed or
  crashed instrumentation; distinguished safe not-run handoffs with observed
  zero counts from missing handoffs; defined deterministic environment versus
  harness exit mapping; and aligned the companion validation record with
  cleanup-before-publication ordering. No Android source was changed.
- Iteration 8: made proof availability independent from request-count
  observation in both handoff and aggregate schemas; and scoped raw-marker
  scanning to captured artifacts while retaining protected-value scanning over
  the diff. Clean marker definitions and positive captured-content fixtures are
  now explicitly distinct. No Android source was changed.
- Iteration 9: bound the signed terminal trace vocabulary to the existing
  `HermesEventNormalizer` mapping for `message.complete`, interruption, and
  failed terminal forms, with a deterministic mapper fixture. No Android
  source was changed.

## Review triage log

- Prior supervised plan gate: failed with 13 findings; iteration 1 addressed
  the original scope, safety, readiness, audio, interrupt, reconnect, and
  artifact concerns. No Android source was changed by that gate.
- Second supervised plan gate: failed with 15 findings. Iteration 2 addresses
  the authority/status split, exact route and parser seams, executable wrapper
  contract, signed provenance, four-handle lifecycle, content-free audio
  telemetry, preflight ordering, named interrupt/reconnect proofs, and full
  branch/status mapping. No Android source was changed by that gate.
- Third independent plan gate: failed with 24 findings. Iteration 3 addresses
  the dispatch/schema mismatch, typed outcome symbols, ordinary-recovery
  semantics, branch-specific capability requirements, provenance canonicalization
  and binding, controlled-close synchronization, acknowledgement and request
  counters, audio-driver and preflight protocols, atomic partial publication,
  stale-report isolation, raw-content scanning, device identity evidence, and
  wrapper-contract proof. No Android source was changed by that gate.
- Final synchronized independent review was started against the revised copy
  but its direct reviewer harness timed out after roughly 26 minutes before
  writing a verdict. The prior 24-finding failure therefore remains the last
  completed gate; this plan stays `draft` and no Android source was changed.
- The repaired reviewer smoke pass then returned ten concrete findings. This
  iteration resolves them as follows: provenance ordering in the two-phase
  attestation section; missing testability in `Acceptance Criteria`; credential
  drift in `Preflight and device binding`; missing branch evidence in the
  schema-2 record; unspecified Android-to-wrapper handoff in the wrapper
  contract; missing platform-underrun observation in the audio driver seam;
  ambiguous interrupt status in the scenario matrix and status rules; phantom
  partial facts in the nullable record rules; unowned symbols in `Code Map`;
  and contradictory trace cardinality in the provenance section. No Android
  source, device, or live endpoint was touched.
- The next independent plan pass returned six findings: reconnect readiness
  conflicted with the universal new-turn rule; a started branch could
  contradict its request-count state; partial publication could erase observed
  global facts; ordinary protobuf reports were rejected as binary; the audio
  preflight wording lacked an observation; and `AndroidReconnectOutcome` was
  mapped to the wrong file. Iteration 6 repaired all six without changing
  Android source or touching a device or live endpoint.
- The following independent plan pass returned four findings: reconnect's
  context-specific unresolved state needed an explicit contract; stale device
  cache results could be reused; the per-scenario handoff lacked an exact
  envelope; and close-hook arm/cleanup waits were unbounded. Iteration 6
  repaired those four. No Android source, device, or live endpoint was touched.
- The latest independent plan pass returned four findings: audio-preflight
  token and process failures had conflicting classifications; valid not-run
  handoffs conflicted with unobserved request counts; harness-stop exit mapping
  was not deterministic; and the companion validation record published before
  cleanup. Iteration 7 repaired all four. No Android source, device, or live
  endpoint was touched.
- The next independent plan pass returned two findings: aggregate evidence
  availability conflicted with observed counts from a valid not-run handoff;
  and raw-marker scanning would reject the scanner's own definitions and
  fixtures. Iteration 8 repaired both with an explicit merge rule and separate
  captured-artifact versus source-diff scan policy. No Android source, device,
  or live endpoint was touched.
- The latest independent plan pass returned one finding: the signed trace used
  canonical terminal names without defining how the existing Home wire events
  map to them. Iteration 9 binds that mapping to
  `HermesEventNormalizer.standardEvents` and adds the named fixture. The final
  independent pass returned `status: pass` with no findings, so this plan is
  `ready-for-dev`. No Android source, device, or live endpoint was touched.

### Implementation review triage

The implementation review recorded 47 findings in reviewer order: Fermat 17,
Maxwell 23, Rawls 6, and Parfit 1. Overlapping Rawls observations are recorded
once in the six verification-gap rows below. The dispositions are explicit so
the remaining live-only gaps do not masquerade as build evidence.

| ID | Location | Severity | Verdict | Disposition |
| --- | --- | --- | --- | --- |
| F-01 | OkHttpRelaySessionClient.kt:404 classification | medium | false-positive | The transport contract intentionally maps upgrade 404 to TransportUnavailable; the safe record retains HOME_404 as its public reason vocabulary. |
| F-02 | run-live-home-gate.sh:deployment attestation | high | patched | A successful deployment verification is recorded before device traffic. |
| F-03 | run-live-home-gate.sh:preflightToken | medium | patched | Only one exact PASS or FAIL:AUDIO_OUTPUT token is accepted. |
| F-04 | run-live-home-gate.sh:audio preflight failure | medium | patched | An observed audio-output failure records false rather than null. |
| F-05 | run-live-home-gate.sh:close-hook exit | high | patched | An armed hook with a non-zero exit or failed escalation produces HARNESS_FAILURE. |
| F-06 | run-live-home-gate.sh:device cache | high | patched | The complete run directory must be absent before live traffic. |
| F-07 | validate-live-home-inputs.py:route port | medium | patched | Parsed ports are rejected unless they are in the 1..65535 range. |
| F-08 | AndroidAudioSink.kt:underrun sampling | high | patched | The baseline is captured at start and checked continuously during writes and drain. |
| F-09 | AudioOutputPreflightTest.kt:write deadline | high | patched | The probe uses bounded non-blocking writes and a deadline. |
| F-10 | OkHttpRelaySessionClient.kt:stale frame binding | high | patched | Binary fragments are frame-reassembled and queued data is bound to the active turn. |
| F-11 | LiveHomeSafeResult.kt:safe pass facts | high | patched | Pass requires available evidence and scenario facts. |
| F-12 | live-home-safe-record.py:live-pass prerequisites | high | patched | Aggregate pass now requires physical/audio evidence, default isolation, counts, commands, and provenance. |
| F-13 | live-home-safe-record.py:sample rate | medium | patched | Safe format validation is bounded to 8 kHz through 192 kHz. |
| F-14 | HermesEventNormalizer.kt:coercing Home fields | high | patched | Home envelope, binding, metadata, and audio-frame fields require exact JSON types; legacy compatibility remains scoped separately. |
| F-15 | verify-story-dispatch.sh:body drift | high | patched | Canonical and dispatch bodies are compared in addition to the hash. |
| F-16 | Code Map:LiveHomeSafeResult.kt | medium | patched | The writer now lives under androidTest, matching ownership and the map. |
| F-17 | Acceptance-to-proof map:named methods | low | patched | The map names the actual run_selected_scenario and scenario helper seams. |
| M-01 | OkHttpRelaySessionClient.kt:PCM fragments | high | patched | Partial PCM frames are carried across WebSocket messages and only aligned bytes are written. |
| M-02 | AndroidAudioSink.kt:cancelled write generation | high | patched | Generation checks prevent stale workers from updating a new stream. |
| M-03 | AndroidAudioSink.kt:queued writes after failure | high | patched | Failed or cancelled streams drop later queued writes promptly. |
| M-04 | AndroidAudioSink.kt:underrun during streaming | high | patched | Continuous platform underrun observation is enforced. |
| M-05 | OkHttpRelaySessionClient.kt:duplicate interrupt | high | patched | One active turn can emit only one interrupt request. |
| M-06 | LiveHomeSafeResult.kt:proof-less pass | high | patched | Kotlin validation rejects pass without evidence and passing facts. |
| M-07 | LiveHomeSafeResult.kt:concurrent writer race | medium | false-positive | The supported single instrumentation process serializes writers and atomic no-replace publication contains the race. |
| M-08 | HermesEventNormalizer.kt:string schema coercion | high | patched | Strict Home parsing rejects wrong JSON types at the protocol boundary. |
| M-09 | AudioOutputPreflightTest.kt:muted/emulated playhead | high | false-positive | The contract selects a physical Pixel 6a and treats AudioTrack write/drain/playhead evidence as the output precondition; it does not claim human-heard proof. |
| M-10 | run-live-home-gate.sh:qemu identity | high | false-positive | The contract requires the Pixel 6a model and rejects affirmative emulator properties before live traffic. |
| M-11 | run-live-home-gate.sh:preflight substring | high | patched | Exact anchored token parsing is now covered. |
| M-12 | run-live-home-gate.sh:preflight hang | high | patched | Instrumentation is bounded, terminated, and mapped to harness failure. |
| M-13 | run-live-home-gate.sh:trace ordering | high | patched | Deployment is verified before traffic; the current-run trace is verified afterward. |
| M-14 | run-live-home-gate.sh:stale later result | high | patched | The whole device run directory is checked before the first scenario. |
| M-15 | live-home-safe-record.py:aggregate device/audio proof | high | patched | live-pass has explicit device, audio, report, command, and provenance prerequisites. |
| M-16 | live-home-safe-record.py:proof-less branch | high | patched | Branch pass validation requires evidence and semantic facts. |
| M-17 | verify-home-attestation.py:trace replay freshness | high | deferred | Run/device/route/signature binding is enforced; anti-replay freshness beyond the operator run ID requires the live provenance producer and remains a security follow-up. |
| M-18 | scan-live-home-artifacts.py:staged diff | high | patched | Unstaged and staged diffs are both scanned for protected values and forbidden media. |
| M-19 | scan-live-home-artifacts.py:Git errors | medium | patched | Non-zero Git inspection fails closed. |
| M-20 | run-live-home-gate.sh:instrumentation argv exposure | high | deferred | Protected inputs remain required instrumentation arguments by the approved contract; process-list exposure needs a separate transport design. |
| M-21 | run-live-home-gate-contract-test.sh:coverage breadth | medium | partial | Invalid ports, proof-less passes, staged scans, duplicate signers, and exit 64 are covered; valid signed end-to-end and full fake default/live exit paths remain live-environment work. |
| M-22 | bmad-loop-story-auto.py:bounded subprocesses | medium | patched | JSON validation, foreground runs, and loop/resume operations now have deadlines and fail closed. |
| M-23 | verify-home-attestation.py:duplicate signer IDs | medium | patched | Duplicate JSON and line allowlist entries are rejected before map construction. |
| R-01 | safe handoff validators:pass facts | high | patched | Host and device validators now enforce scenario-specific pass invariants and preserve observed counts. |
| R-02 | verify-home-attestation.py:valid crypto fixtures | high | deferred | The host lacks cryptography and RFC 8785 dependencies; only malformed/duplicate pre-crypto checks were exercised. |
| R-03 | reconnect uncertainty:deterministic JVM proof | high | deferred | The strict reconnect state is exercised by the opt-in live test; a dedicated JVM fixture remains useful follow-up work. |
| R-04 | WebSocket callback:mismatched response ID | high | deferred | Direct parser coverage exists; a callback-level MockWebServer fixture remains unrun. |
| R-05 | runDefaultIsolation:fake XML fixtures | medium | deferred | The real device run exposed the existing Compose-hierarchy failure; a fake Gradle matrix for mixed/zero/live-only XML remains unrun. |
| R-06 | close-hook arm/cleanup:fake hooks | medium | deferred | The bounded implementation is present; timeout, TERM, and KILL behavior still need isolated fake-hook execution. |
| P-01 | intent alignment:surface ambiguity | low | rejected | The existing scoped story is the Android live Home evidence gate; no UI, iOS, or TUI implementation was authorized by this build request. |

Triage totals: 34 patched, 1 partial, 4 false-positive, 7 deferred, and
1 rejected. The deferred rows are called out again in Auto Run Result; they do
not become hidden claims of live proof.

## Auto Run Result

Result: done-with-environment-limitation. The Android Home transport and audio
hardening, safe schema-2 handoff, live scenario instrumentation, wrapper,
attestation/scanning utilities, BMAD supervisor timeout handling, dispatch
verification, focused tests, and delivery records are implemented.

Verification completed:

- Gradle unit tests, debug assembly, and lint passed.
- APK metadata passed; debug and debug-test APK installation passed on the
  selected physical Pixel 6a.
- Android-test Kotlin compilation, wrapper contract tests, dispatch verification,
  and all nine BMAD supervisor unit tests passed.
- The physical audio preflight passed one test and emitted only the exact
  allowlisted status token.

Limits retained:

- The default connected run reported 37 non-live cases and zero live cases, but
  28 cases failed before a Compose hierarchy appeared. It is not recorded as a
  passing device gate.
- No signed Home deployment or current-run trace was available, the optional
  host crypto/RFC 8785 dependencies were absent, and no live Home endpoint or
  live scenario was exercised. The follow-up delivery row remains backlog.
- A future run must supply the owner-only provenance inputs and a passing
  default connected suite before the wrapper can produce live-pass.
