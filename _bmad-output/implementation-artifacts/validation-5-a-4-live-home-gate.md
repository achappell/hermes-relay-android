---
title: 'Validation — 5-A-4 live Home bridge gate'
type: 'validation'
story_id: '5-A-4-LIVE-HOME-GATE'
parent_story: '5-A-4'
created: '2026-09-15'
status: 'live-pass'
spec: '_bmad-output/implementation-artifacts/spec-5-a-4-live-home-gate.md'
---

# Validation — 5-A-4 live Home bridge gate

This is the companion record for the additive live-evidence follow-up. The
Android and live Home verification passed on the physical Pixel 6a in run
`android-live-3899e128557f4471`. All four branches, signed deployment and run
trace, and artifact scanning pass. Temporary credentials were revoked and
configuration restored. The harness used an in-memory test profile; persistent
in-app pairing remains outside this evidence. Earlier failures are retained
below.

## 2026-09-17 authority amendment and preflight record

The user explicitly approved signed Home provenance, ordered-turn tracing, a
fresh Device credential, and the four live branches. The user also approved
three temporary prompts that contain no private information. Revision 6 of
the canonical spec narrows this work to the authorized live gate. The Android
harness still uses an in-memory profile and credential, so it cannot prove
persistent profile pairing in the app.

One preflight deviation is recorded: before signed deployment provenance was
available, a compile-only Gradle task was run in the Android main checkout.
It did not invoke ADB or contact Home, and it did not compile the feature
worktree. It crossed the spec's preflight boundary. No further Android Gradle,
ADB, or live Home request is permitted until the deployment attestation
verifies.

The Home reconnect branch now uses Home's opt-in trace context to close the
peer after the accepted prompt response and park the active conversation. The
wrapper obtains a single disposable handle immediately before each branch;
there is no external close-hook input or four-handle prefill. Prompt contents,
credentials, and handles remain ephemeral and must not be copied here.

## Recording contract

This is the safe, content-free outcome projection for the 2026-09-15 build
pass. Never copy credentials, opaque handles, prompts, responses, PCM, raw
JSON, or raw logs into this file. The canonical spec is the source of truth;
this record uses the same versioned JSON serialization. The default command is
marked inconclusive because the report was produced but every Compose-backed
instrumentation case failed before a Compose hierarchy appeared; it is not
presented as a passing device gate.

```json
{
  "schema_version": 2,
  "status": "done-with-environment-limitation",
  "exit_code": 20,
  "run_id": "build-validation-20260915",
  "device": {
    "status": "observed",
    "model": "Pixel 6a",
    "api": 37,
    "physical": true,
    "audio_output": true,
    "serial_fingerprint": null,
    "default_non_live_count": 37,
    "default_live_count": 0
  },
  "provenance": {
    "deployment_attested": false,
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
    "default": { "status": "inconclusive", "exit_code": 1, "report_count": 37 },
    "audio_preflight": { "status": "pass", "exit_code": 0 },
    "live": { "status": "not-run", "exit_code": null, "report_count": null }
  },
  "overall_rule": "live-pass only when all four branches pass and Home provenance is attested"
}
```

The placeholder is deliberately a not-started record: null means the wrapper
did not observe the fact, while zero or false means it did observe that value.
The schema distinguishes the deployment attestation from the current-run trace
attestation; the latter is verified only after scenario result handoffs exist.
When both verify, `home` contains only deployment ID/revision,
`adapter: standard-backed`, signer key ID, route class/id, trace run ID,
trace digest, and device fingerprint. The signed trace array is never copied
into this record. `trace_run_id` must equal the operator-supplied
`LIVE_HOME_RUN_ID`; the signed trace's route and device fingerprint must equal
the observed wrapper facts.

The top-level `exit_code` is limited to `0`, `20`, `30`, or `64`. The command
objects retain nullable captured child-process exit codes in the inclusive
range `0..255`; those codes are not required to equal the wrapper's normalized
top-level result.

The follow-up remains `backlog` unless all four branches pass, the deployment
and run-trace envelopes agree on Standard-backed deployment identity/adapter/
signer/route, and the run-trace binds the same run and device. The allowed
branch reasons are `DEVICE_SELECTION`,
`WRONG_DEVICE`, `AUDIO_OUTPUT`, `MISSING_PAIRING`, `INVALID_BINDING`,
`HOME_404`, `HOME_UNREACHABLE`, `HOME_UNAVAILABLE`, `UNRESOLVED_TURN`,
`CAPABILITY_SHAPE_INVALID`, `CAPABILITY_UNAVAILABLE`, `HOME_PROVENANCE`,
`CONTROLLED_CLOSE`, `NATURAL_COMPLETION_RACE`, `TERMINAL_TIMEOUT`,
`AUDIO_FAILURE`, `RECONNECT_TRACE`, and `HARNESS_FAILURE`; a passing branch
has `reason: null`. Missing external preconditions or an incomplete branch is
`done-with-environment-limitation`; a test failure, replay, binding defect, or
secret-scan hit is `failed`. Exit `20` is reserved for a prerequisite or
external condition known before trustworthy evidence can be produced, such as
missing pairing, wrong device/audio, missing provenance, Home unavailability,
or a close hook that never arms. An operation that starts and then loses
trustworthy evidence—unexpected child failure, timeout, malformed/missing
report or device handoff, invalid preflight output, or cleanup/publication
failure—uses `HARNESS_FAILURE` and normalized exit `30`; its branch may be
`inconclusive`, but the top-level result is `failed`. A natural completion race
is always `inconclusive: NATURAL_COMPLETION_RACE`; after an interrupt request,
a missing acknowledgement or matching terminal is always
`fail: TERMINAL_TIMEOUT`. A previously recorded failure cannot be downgraded
by later provenance or cleanup limitations. The completed parent `5-A-4`
record remains untouched in every case.

The wrapper initializes every branch with null facts and counts. Its `EXIT`
trap performs bounded close-hook cleanup, resolves failure precedence, and then
atomically publishes the safe partial record; cleanup cannot introduce a
failure after publication. It moves stale connected-test reports aside rather
than deleting them, requires a fresh current-run XML manifest, records only
counts and exit codes, and treats a missing/unreadable/ambiguous report as
`HARNESS_FAILURE`. A valid device-written not-run envelope records observed
integer request counts, including zero; a missing handoff leaves the aggregate
counts unobserved and null. Proof availability and count observation are merged
independently. No stdout/stderr, value-bearing assertion, raw frame, or binary
media is copied into this file.

## Evidence status

Passed local evidence:

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon`.
- `scripts/check-apk-metadata.sh` — min SDK 26, version `0.3.1` / version code
  `301`; signing verification was correctly skipped because no expected signer
  was configured.
- `./gradlew compileDebugAndroidTestKotlin --no-daemon`.
- `scripts/run-live-home-gate-contract-test.sh` — wrapper, input, safe-record,
  staged-scan, duplicate-signer, and invalid-invocation cases passed.
- `scripts/verify-story-dispatch.sh` and the nine BMAD supervisor unit tests.
- Debug and debug-test APK installation on the selected physical Pixel 6a.
- `AudioOutputPreflightTest.verifyPhysicalAudioOutput` — one test passed and
  emitted the exact allowlisted `AUDIO_PREFLIGHT=PASS` status token. This is
  an output/drain precondition only; it is not live Home proof.

Observed limitation, retained rather than hidden:

- The default connected instrumentation run reported 37 non-live cases and
  zero `@LiveRelay` cases, but 28 cases failed with `No compose hierarchies
  found in the app`. The default coverage gate therefore did not pass.
- The live wrapper was not run against Home. Deployment and current-run trace
  attestations were unavailable, and the host lacks the optional crypto/RFC
  8785 Python dependencies needed for a signed-verifier fixture. No live
  endpoint, live prompt, or live branch handoff was exercised.

The remaining operator action is to supply the owner-only signed deployment
and current-run trace attestations, the disposable handles/prompts, and a
passing default connected suite before running the opt-in wrapper. The parent
`5-A-4` record remains complete; this follow-up remains `backlog` until the
four live branches and provenance all pass.

The required operator inputs are the four disposable handles,
`LIVE_TYPED_PROMPT`, `LIVE_INTERRUPT_PROMPT`, `LIVE_RECONNECT_PROMPT`,
`LIVE_HOME_RUN_ID`, and the owner-only deployment attestation, run-trace
attestation, public-key, fingerprint allowlist, and close-hook paths. The
explicit live command passes prompts through `typedPrompt`, `interruptPrompt`,
and `reconnectPrompt`, plus `liveRunId`; there is no source prompt fallback.

## Plan-gate harness validation

On 2026-09-15 the supervised plan harness was repaired without changing
Android source or this gate's status. Both `.agents/skills/bmad-build-auto`
and `.claude/skills/bmad-build-auto` now use a literal cwd-relative renderer
command, so the planner cannot inherit a truncated interpolated quote. The
new `scripts/bmad-loop-story-auto.py` launches the independent reviewer only
after bmad-loop reports `plan-checkpoint`, follows the paused story's persisted
worktree when isolation is enabled, runs read-only with a bounded deadline,
validates the returned JSON, writes identical run/task verdicts, and resumes
only for `status: pass` with no findings. Timeout, process failure, malformed
output, missing Codex, and any non-pass verdict leave the plan paused.

Focused validation passed:

- `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest scripts/test_bmad_loop_story_auto.py` — 9 tests passed.
- Both mirrored renderer commands passed:
  `uv run --no-cache _bmad/scripts/render_skill.py --project-root . --skill .agents/skills/bmad-build-auto`
  and the matching `.claude/skills/bmad-build-auto` command.
- The prior malformed renderer command reproduced `zsh: unmatched "`; the new literal command parsed successfully.
- A real read-only Codex smoke review of the repaired reviewer returned a schema-valid fail verdict with ten concrete findings; no source or live endpoint was touched.

## Iteration 5 plan repair

The ten-finding smoke verdict was treated as a plan defect, not as evidence to
paper over:

- provenance is now two-phase: deployment identity is verified before traffic,
  while the current-run trace is verified after device-side branch handoffs;
- the story now has explicit Given/When/Then acceptance criteria;
- wrapper credential validation is pinned to Android's 43-character base64url,
  32-byte `HomeCredentialValidator` contract;
- schema version 2 carries branch evidence, request counters, telemetry, and
  explicit nullable/not-started facts;
- `LiveHomeSafeResult.kt` and the fixed `adb exec-out run-as` cache path define
  the Android-to-wrapper handoff;
- `AudioTrackDriver.underrunCount()` and the production
  `AudioTrack.getUnderrunCount()` mapping make platform underruns observable;
- interrupt outcomes are deterministic: natural completion is inconclusive,
  while a post-request acknowledgement/terminal deadline is a failure;
- partial records distinguish unobserved from zero/false facts;
- the Code Map assigns every new seam to a concrete source or script file; and
- trace cardinality now means one contiguous multi-entry group per scenario,
  with the exact 1/3/4/4 entry counts.

No Android implementation, device, or live endpoint validation was performed
by these plan repairs.

## Iterations 6–9 plan repair

The subsequent bounded, read-only reviewer passes returned six, four, four,
two, and one findings respectively. They were repaired in the canonical spec
and regenerated dispatch copy: reconnect readiness is context-specific;
device-cache/run-id freshness and the exact per-scenario envelope are enforced;
hook and harness deadlines/precedence are bounded; branch facts and request
count observation are independent; captured-artifact scans no longer confuse
source marker definitions with leaked content; and Home terminal wire events
map through `HermesEventNormalizer.standardEvents` to the signed canonical
trace vocabulary. No Android source, device, or live endpoint was touched.

The final independent plan review returned a schema-valid `status: pass` with
an empty findings array. It confirmed the source references, dispatch hash,
acceptance criteria, and deterministic/device/live proof seams. This promotes
the specification to `ready-for-dev`; it does not constitute implementation,
device, or live-endpoint validation.

The canonical checkout was not used for a full bmad-loop run in this pass:
fresh preflight returned `ok: false` because the working tree is intentionally
dirty and the current Claude policy has not registered the bmad-loop hooks.
The dispatch folder and story manifest are present and structurally valid. The
Android implementation, device gate, and live Home endpoints remain
unverified.

## Planned commands

See the exact default and opt-in commands, preflight rules, safe result schema,
and artifact scan in the linked specification. The live command is not run
until its external route, pairing, disposable handles, device, audio, and Home
provenance prerequisites exist.

## Follow-up client hardening — 2026-09-15

The post-build review found three transport-boundary races in the Android
client and they are now covered and fixed:

- only one `prompt.submit` initiation can be in flight at a time;
- an uncertain delivery blocks a new prompt until the explicit resend or
  discard path clears it; and
- text events received before Home's prompt acknowledgement are queued until
  the returned turn binding is known. Uncorrelated binary audio is still
  discarded until a verified binding exists.

The focused regression tests are
`a_second_prompt_is_rejected_while_first_acknowledgement_is_pending`,
`an_uncertain_prompt_blocks_new_turns_until_explicit_resend_is_prepared`, and
`events_received_before_prompt_ack_are_delivered_after_binding_is_known` in
`OkHttpRelaySessionClientTest`. The full JVM/build/lint gate and Android-test
compilation were rerun successfully after these changes.

The physical Pixel 6a remained at the secure lock screen during this pass, so
the Compose-backed connected suite was not rerun. Its earlier failure reported
the device as asleep/non-interactive; no app crash was established. Unlocking
the handset is still required for the on-device UI validation. This does not
change the live Home provenance limitation or the `backlog` delivery status.

## Device retest — 2026-09-16

The wireless physical Pixel 6a was awake and unlocked for a fresh validation
pass. The current `com.achappell.hermesrelay` debug APK and test APK installed
successfully, `MainActivity` resolved and launched, and the UI tree exposed the
expected Compose hierarchy for the empty-profile state. The final crash buffer
was empty.

The default connected instrumentation report passed all 37 non-live tests with
zero failures, errors, or skips. The runner selected zero `@LiveRelay` tests,
as intended. `AudioOutputPreflightTest.verifyPhysicalAudioOutput` then passed
as a separate device-only check with `AUDIO_PREFLIGHT=PASS`.

This clears the former lock-screen/Compose-hierarchy limitation. It does not
constitute live Home proof: no signed deployment or current-run trace
attestation was available, so the four opt-in live branches remain not run and
the record stays `done-with-environment-limitation`.

## Readiness-shape fix and device retest — 2026-09-17

The focused review found that the Home wire parser accepted both a Boolean and
an object for `unresolved_turn`, while live readiness requires the exact
Boolean shape. The parser now preserves the richer object for ordinary
recovery and records its wire shape; strict new-turn, reconnect, and capability
readiness checks reject a non-Boolean value as `ProtocolError`. The regression
test verifies that a malformed shape cannot proceed to `prompt.submit` or
`session.interrupt`.

Verification on the worktree passed:

- `testDebugUnitTest assembleDebug lintDebug`.
- `compileDebugAndroidTestKotlin`.
- `scripts/check-apk-metadata.sh` — min SDK 26 and version `0.3.1` / version
  code `301`; signer verification was skipped because no expected signer was
  configured.
- `scripts/run-live-home-gate-contract-test.sh` — `LIVE_HOME_GATE_CONTRACT=PASS`.
- Final default `connectedDebugAndroidTest` run on the physical Pixel 6a —
  37 passed, 0 failed, 0 errors, 0 skipped. The first run after restart had
  one Compose-hierarchy failure; that focused test passed on retry, and the
  final complete run passed. `@LiveRelay` remained excluded throughout.

No live Home request or separate audio preflight was run in this pass. The
approved pairing inputs and signed deployment/run-trace attestations were not
available, and the 1Password MCP connector was absent from the session.

The live instrumentation builds a test-only `RelayProfile` and
`RelayHomeBinding` from runner arguments with an `InMemoryRelayCredentialStore`.
It does not call `RelayConfigurationController.migrateToHome` or exercise
persistent Android Keystore pairing. This proves the live Home transport path
when its external inputs exist; it does not prove user-facing enrollment or
credential persistence. `ANDROID-HOME-01` remains the separate backlog story
for that pairing and configuration flow. Parent story `5-A-4` remains `done`,
and this live-gate follow-up remains `backlog` until all four signed live
branches pass.

## CaticornQueen pairing and provenance inspection — 2026-09-17

Read-only SSH inspection confirmed that the Home host has a Tailscale-only
bridge route on the expected versioned path. Its operator-managed grant file
contains five active, structurally valid, unique conversation handles. Their
disposable status is not established, so they are not yet accepted as the four
isolated live-gate inputs.

The Home credential store contains three active Device credential records, but
each record holds only a keyed digest and metadata. No static Device credential
file is configured. The original tokens cannot be recovered from this server;
a fresh enrollment or rotation must return a new one-time credential. No Home
API or bridge request was made, and the server state was not changed.

The deployment attestation, pre-provisioned public key, and key allowlist were
unset in the local environment and were not found in the checked repository,
user configuration, or Home deployment/data locations. The current-run trace
attestation does not exist because no live scenario ran. The gate therefore
remains not run; neither server route configuration nor active grant metadata
substitutes for signed provenance.

The inspected Home implementation at `73d58e5` records content-safe generic
phase/outcome diagnostics. It does not provide the method-level signed run-trace
producer required by this gate. Establishing a signing key alone would not
close the gap; a trusted producer must also capture and validate the ordered
bridge methods and terminal outcomes.

Sequencing deviation: after reading the gate's provenance-before-ADB rule, this
inspection issued read-only ADB inventory, property, and package queries before
the signing-file search was complete. No app was installed or launched, no
Gradle command ran, and no Home request was made. No further ADB, Gradle, or
Home traffic is authorized by this record until the deployment attestation is
verified. This does not change the live-gate verdict or either story status.


## Locked-device live-gate attempt — 2026-09-17

The signed Home deployment attestation and Android story-dispatch hash both
passed. Home's Standard profile list contained the selected Profile ID, while
Home's pairing configuration had no Profile, Room, Wake Mapping, or Device
rows. A run-scoped Home Profile row was added for that exact Standard Profile,
then a temporary Room and Wake Mapping were paired through the Home enrollment
API. Home issued a fresh Device credential and the matching Device row was
published at configuration revision 7.

The live wrapper stopped in its required default connected Android suite,
before audio preflight or any Home wake claim. A separate non-live rerun
reported 28 failures among 37 tests, all with `No compose hierarchies found in
the app`. The Pixel 6a was dozing and securely locked, with no app Activity in
the foreground. Waking the display and requesting the standard keyguard
dismissal did not unlock it; the sampled logcat contained no app fatal
exception. This supports a device lock-state limitation, not an Android code
defect. None of the three temporary prompts reached Home, and no Home run trace
was produced.

Cleanup revoked the fresh Device credential and removed only this run's Profile,
Room, Wake Mapping, and Device rows. Home configuration is now at revision 8;
all four arrays are empty and the run state file is gone. The attempt remains
inconclusive and the live-gate follow-up remains `backlog` until the connected
Pixel 6a is unlocked for the complete device and Home run.


## Harness repairs after device unlock — 2026-09-17

The first unlocked-device launch omitted `LIVE_DEVICE_SERIAL` from its local
operator environment. The gate stopped at device selection before any Home
prompt. Its exit-time artifact scan then replaced that reason with generic
`HARNESS_FAILURE`: a previous connected-test report contained `device-info.pb`,
which was missing from the scanner's recognized Android protobuf filenames.
The scanner now permits that exact report filename and still scans its bytes
for protected values. Contract checks verify both acceptance of binary device
metadata and rejection when that metadata contains a protected prompt. The
contract and the previously rejected artifact set both pass.

A subsequent operator preflight saw no attached device and stopped before
provisioning. After USB reconnection, the phone was exposed over both USB and
wireless ADB; the operator selected the single USB transport explicitly.

Run `android-live-8568f45a2f3c40e3` successfully provisioned a fresh temporary
pairing for the existing Standard `amanda` profile. All 37 default connected
Android tests passed, with zero live tests included. The direct audio preflight
then exited 1. Post-run package queries found neither the app nor its test APK
installed: Gradle's connected-test teardown had removed both. This was a
harness sequencing defect, not evidence of speaker failure or duplicate
transport interference. No Home wake claim or prompt was sent in this run.
Cleanup revoked the temporary credential, removed its configuration rows, and
confirmed four empty arrays at Home revision 18.

The wrapper now passes
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to its Gradle
invocations, preserving the app/test APKs for the direct audio preflight and
subsequent `run-as` handoff collection. The option exists in the locally
installed AGP 9.4.0; the gate contract passes after the change. Its full live
retest is pending.

Unit tests, debug assembly, and lint passed with the Android SDK environment
explicitly set. An earlier invocation without that environment stopped with
SDK-location-not-found before tests. APK metadata matched min SDK 26 and
version 0.3.1 (301); signer comparison was skipped because the expected signer
fingerprint was unset. The parent story remains `done` and the live Home
follow-up remains `backlog` until all four branches and signed run provenance
pass together.


Run `android-live-77100d0b92564352` confirmed APK retention: all 37 default
connected tests passed and the audio instrumentation launched with exit 0.
The wrapper still classified its output as malformed. A direct reproduction
showed that `am instrument -w` places the test-class prefix on the same line as
`INSTRUMENTATION_STATUS: AUDIO_PREFLIGHT=PASS`. Using raw output (`-w -r`)
produced exactly one standalone status token and exit 0 on the Pixel. The
wrapper now uses that raw mode; the gate contract passes. No Home prompt was
sent in this attempt. Cleanup revoked the temporary credential and confirmed
four empty configuration arrays at revision 21.


Run `android-live-488d5fbac4f141d4` stopped in the default suite: 36 of 37
tests passed; `saving_a_profile_never_renders_the_token_back` lost its Compose
hierarchy while entering the third field. Its captured device log shows
`NOTIFY_STARTED_GOING_TO_SLEEP` and a transition from `GONE` to `AOD` at the
failure. No Home branch ran. Cleanup confirmed revoked credentials and empty
configuration arrays at revision 24.

The operator had requested USB-only keep-awake, but Android reported
`mPlugType=1` (AC). That setting did not cover this charging source. Setting
keep-awake for all powered sources produced `mStayOn=true`. The operator also
now reads the explicit keyguard `showing` state; absence of a dreaming-lockscreen
flag is not proof that the secure lock is dismissed. The phone requires the
user to unlock again before the next run. Its original keep-awake setting
was recorded for restoration after testing.


Run `android-live-0695486abf514146` passed all 37 default tests and the raw-mode
audio preflight. Its handshake branch stopped before Android connection because
the handle provider returned failure (`HOME_UNAVAILABLE`). Home's metadata
confirms it created a conversation claim, later closed by credential revocation;
no prompt was sent. Cleanup confirmed empty configuration arrays at revision 27.
The existing provider did not preserve a specific failure reason.

A separate, disposable handle-provider probe succeeded: SSH returned exit 0,
stdout contained exactly 43 characters matching the opaque-token shape, and the
provider returned exit 0. No handle or credential was printed. The probe's
credential was revoked and temporary configuration removed at revision 30. This
recovery does not establish the cause of the preceding failure. The provider
now emits fixed failure tokens for SSH timeout, launch error, nonzero exit, or
invalid response; the wrapper retains only those allowlisted tokens on failure.
The gate contract passes.


Run `android-live-bcb4d06d09794e3e` stopped before Home traffic: 36 of 37
default tests passed, while `renders_normalized_phases_and_one_coherent_response`
lost its Compose hierarchy. The captured log shows sensor rotation 3 to 0
(landscape to portrait), Activity destruction/recreation, and the failed
assertion during that transition. The phone remained awake with keep-awake
active. This is a test-host recreation limitation; the log does not establish
a failure of the production Activity's rotation handling. The operator now
holds portrait during the gate and restores the prior rotation settings in
cleanup. Credential revocation and empty configuration arrays were confirmed
at revision 33.


Correction to the initial handle-provider diagnosis above: both
`android-live-0695486abf514146` and `android-live-415c93b8ef8f4ec3` contain a
device-produced `handoffs/handshake.json`. The handle reached Android. Each
handshake completed with `HOME_UNAVAILABLE` and zero prompt submissions. The
aggregate branch reason was misread as a failure of the initial provider call;
the device handoff disproves that diagnosis. The later branch could not obtain
a new claim while the first unused conversation still occupied the room.

Run `android-live-415c93b8ef8f4ec3` passed the 37-test default suite and audio
preflight, then stopped on that network path. Cleanup confirmed empty arrays
at revision 36. On the Pixel, Home's hostname did not resolve and its tailnet
IP did not answer. Android's VPN service reported no active package. Opening
the installed Tailscale app restored its connected state; both hostname and
IP probes then succeeded. No VPN account was changed. The local operator now
checks Home hostname reachability before issuing a temporary credential.


### 2026-09-17 — advertised command catalog correction

Run `android-live-840f9e186cb84e04` passed all 37 default tests and audio
preflight but its handshake returned `HOME_UNAVAILABLE`, with zero prompts.
Cleanup revoked its credential and removed temporary configuration at revision 39.
A subsequent focused Android handshake returned `CapabilityShapeInvalid`; its
credential and configuration were cleaned at revision 42. An independent
content-free Home probe returned ready, heartbeat/audio/interrupt true, timing
absent, and 271 commands. Cleanup was confirmed at revision 45. The probe does
not retrospectively establish why the earlier run returned unavailable.

Amanda explicitly approved correcting the zero-command requirement. Authority
revision 7 accepts a valid advertised command list as metadata without adding
command UI. Safe evidence advances to schema 3 and records only command count.
The parser retains all other readiness checks, and the four live branches and
signed current-run trace remain mandatory. Contract tests pass with 271
advertised commands; the full live result remains pending.


Run `android-live-db525dbf58544bd9` passed 37 default tests, audio preflight,
and the real Home handshake with schema-3 evidence (271 advertised commands).
It sent zero prompts: the typed-audio handle provider could not obtain another
claim while the handshake conversation occupied the room. Home's production
bridge deliberately retains a resumable claim after socket teardown; this is
not a pairing failure. The live harness now explicitly sends
`conversation.close` before socket teardown. Ordinary app close behavior is
unchanged. Cleanup for the failed run revoked the credential and confirmed
empty configuration arrays at revision 48. Unit/build/lint verification before
this cleanup change passed 182 tests, with no failures/errors/skips.


Run `android-live-190eae3f2b5e4001` passed the baseline/audio preflight and
handshake, then successfully acquired the typed-audio conversation after explicit
cleanup. The typed invocation returned no test result; the gate correctly failed
rather than treating zero executed tests as live evidence. Cleanup completed at
revision 51. A focused reproduction returned Gradle exit 0 with an XML suite
containing zero tests; cleanup completed at revision 54. No prompt submission
was evidenced by either run.

A direct ADB comparison using only a synthetic two-word diagnostic argument
reproduced the issue: the unquoted invocation returned exit 0 without running
the precheck; the correctly quoted invocation produced `AUDIO_PREFLIGHT=PASS`.
Runtime prompts now travel as unpadded URL-safe Base64 arguments and are decoded
in memory by the test. The scanner rejects both plaintext and encoded prompts.
The Android test APK build and the updated harness contract checks pass.
The preceding client-cleanup change passed all 183 unit tests plus build/lint.


### Real typed turn and Home event-stream correction

The encoded-prompt focused run `android-probe-aff8477655734d5b` executed
exactly one prompt submission. It failed `AUDIO_FAILURE`: Android observed
24 kHz mono PCM metadata but accepted zero bytes and never received a terminal
event. Cleanup revoked the credential and removed temporary configuration at
revision 57. A direct Standard audio probe produced 22,016 bytes, so a TTS
outage was not established.

A content-free adapter probe identified the actual Home defect: `session.title`
contained the authenticated runtime Session ID in its envelope and the matching
stored Session ID in its payload. Home rejected the valid pair as conflicting
identity, closing the event stream and then its audio sidecar. Home commit
`9418cc6961bc341a1d803061684150b498ecd8c2` accepts only this exact bound pair
for title events; unrelated pairs and conflicting turn-event identities remain
rejected. The Home suite passes 445 tests and Ruff checks. A separate-process
probe using the built wheel observed message completion and 66,048 audio bytes.
That diagnostic is not the required Pixel gate.

The first update installer attempt failed while launched from the virtual
environment being replaced; the old service remained running. A direct
PowerShell invocation succeeded. The new signed deployment attestation verified,
and configuration remained empty at revision 57. Android's latest required
unit/build/lint checks also pass. The full four-branch run against this deployed
revision is now pending.


Run `android-live-da7210777c24412e` passed its default checks and handshake.
The typed branch submitted once and received 29,696 bytes / 14,848 frames,
but failed after one playback underrun. Interruption submitted once and sent
one interrupt; its evidence includes acknowledgement and the interrupted
terminal event, despite the branch returning `TERMINAL_TIMEOUT`. Inspection
found the collector releasing its interrupted latch before publishing the
reduced terminal state. The reconnect branch submitted once without resubmit,
but did not observe the controlled close. Android's OkHttp listener lacked
`onClosing`, so it never acknowledged the peer's close frame promptly.
Cleanup confirmed four closed claims and empty temporary configuration at
revision 60; no recent audio events remained.

The collector now publishes state before releasing the latch. Android now
acknowledges peer closes, with a focused regression test. AudioTrack now primes
up to one second of audio before starting (or starts on end-of-stream for
shorter replies), preserving the strict underrun failure check. Buffering and
short-reply drain regression tests pass. Latest required checks pass 185 unit
tests plus build and lint. Device verification of these corrections is pending.


### Subsequent live findings and approved recovery contract

Run `android-live-2620b3fd31fc4208` passed handshake and interruption.
The typed branch submitted once but received neither audio metadata nor a
terminal event before the deadline; this does not establish a playback-buffer
failure. Reconnect failed at initiation in that run. Cleanup removed temporary
configuration at revision 63.

Focused reconnect runs `android-probe-6a3e018ef2704cf5` and
`android-probe-a84c9ce56e854355` each observed one accepted submission, the
controlled peer close, and an explicit reconnect without replay. The latter
reported `Connected` from the Android parser. The live readiness helper then
rejected Home's structured unresolved-turn record solely because it was not a
boolean; its false same-conversation result therefore did not demonstrate an
actual conversation mismatch. Cleanup completed at revisions 66 and 69.

Amanda explicitly approved updating the recovery contract. Authority revision
8 permits the structured record only after schema, conversation identity, turn
identity and profile/connection binding validation. The initial new-turn gate
remains strict, and same-conversation, unresolved-state, and no-replay evidence
remain required. The regression now accepts the bound record and rejects an
unbound record. Live verification remains pending.

Focused typed run `android-probe-4160844477e5409e` again accepted one
submission but received no audio metadata, PCM or terminal event. Cleanup
completed at revision 72. Separately, the required unit run exposed an
acknowledgement/PCM race: a burst response could arrive after the accepted RPC
but before the waiting beginTurn thread installed its binding, dropping PCM.
PendingRpc now commits the fully validated submitted-turn binding on the socket
reader before releasing the waiter. Pre-acknowledgement binary remains rejected.
The subsequent required unit/build/lint run passes all 185 tests.

A direct authenticated Home endpoint diagnostic produced 62,208 PCM bytes.
It was not a physical-device gate and did not produce a signed four-branch
trace. An earlier diagnostic incorrectly targeted the separate admin port;
that failure is not evidence against the bridge. Both disposable configurations
were removed, at revisions 75 and 78.

With acknowledgement binding committed on the reader, Pixel run
`android-probe-b0a6ed2d38c04d7f` received 22,016 bytes / 11,008 frames at
24 kHz mono and a terminal event, with zero underruns, but playback failed to
drain. Cleanup completed at revision 81. Android's documented default streaming
start threshold equals buffer capacity; the new one-second buffer can therefore
strand a shorter reply. The platform driver now sets the threshold to queued
frames before play on API 31+, or requests that effective buffer size on older
Android. The fake driver now models the threshold before advancing playback.
The exact device failure code and successful drain still require live proof.
Reference: https://developer.android.com/reference/android/media/AudioTrack#getStartThresholdInFrames()

The threshold change passes all 185 Android unit tests, build and lint. Pixel
run `android-probe-3701aac259ff4a7f` again received no events after acceptance,
so it could not verify the playback correction; cleanup reached revision 84.
Home's concurrent authorization refreshes were then reproduced deterministically:
one valid refresh replaced the grant object while another was still checking
it, and the second treated the changed identity as transport loss. The endpoint
then stopped its event pump. This explains a concrete path to silent accepted
turns; it is not evidence that every previous timeout had the same cause.
Home revision `376583d7273e08a090d7ec33c416e0b32880a343` serializes grant
refresh and session persistence. The regression failed before the fix; 98
focused and 446 full Home tests pass after it, with Ruff checks passing.

### First complete signed run against the grant-refresh fix

Run `android-live-812b784271604a66` verified deployment and current-run signed
trace, passed 37 baseline device tests and audio preflight, and passed handshake,
interruption and reconnect. Reconnect preserved the same conversation and its
unresolved turn with zero replacement submissions. The typed branch received
25,856 bytes / 12,928 frames and its terminal event, but failed with exactly one
underrun (`AUDIO_FAILURE=Underrun`). The overall gate correctly returned FAIL/30.
Cleanup removed temporary configuration at revision 87.

The drain watcher now queues a 100 ms silent guard after the response, allowing
it to stop when the last response frame has played before the platform reports
an empty-stream underrun. Guard samples are excluded from response byte/frame
counts and the completion target. A regression models the empty-stream edge and
asserts that response counts remain exact. The gate still requires zero
underruns; no failure classification or threshold was relaxed.

Focused Pixel run `android-probe-7f1f8e3c2a2a44d2` passes typed audio:
98,560 response bytes / 49,280 frames, physical playback drained, zero underruns,
terminal observed, final Complete/Delivered, one prompt and no interrupt.
Cleanup completed at revision 90. The new final-frame regression passes, and
the required Android checks pass all 186 unit tests plus build and lint.
A single all-pass four-branch signed run is still required for delivery closure.


## Final delivery — 2026-09-17

`LIVE_HOME_GATE=PASS`, exit 0, run `android-live-3899e128557f4471`.

- Physical Pixel 6a, API 37: 37 baseline tests, audio preflight, and four live tests pass.
- Handshake: Home-only route, exact response binding, 271 advertised command names counted without storage or command UI.
- Typed response: one submission, 33,536 real PCM bytes / 16,768 frames at 24 kHz mono, fully drained, zero underruns, final Complete/Delivered. Silent drain-guard samples are excluded from these counts.
- Interruption: one prompt and one interrupt, acknowledgement and interrupted terminal event observed.
- Reconnect: controlled peer close, explicit reconnect, same conversation, unresolved state preserved, zero replacement submissions.
- Deployment `376583d7273e08a090d7ec33c416e0b32880a343` and current-run trace signatures verify. Trace digest: `01b85a3c136292da2e606a48531f15d1be6221c6f8f3d396ebd58b3e124922ae`.
- Final artifact scanner passes; the wrapper returned exit 0.
- Cleanup passes at Home configuration revision 93; profiles, rooms, wake mappings and devices return to their original empty arrays, and the temporary Device credential is revoked.
- Android: 186 unit tests plus build and lint pass. Home: 446 tests plus Ruff checks pass. APK metadata remains min SDK 26 / version 0.3.1 (301); signer comparison was skipped because no expected signer fingerprint was configured.

Safe aggregate evidence, signed deployment and signed run trace are retained in
`evidence-5-a-4-live-home-gate/`. Parent `5-A-4` remains done; the additive
`5-A-4-LIVE-HOME-GATE` row now transitions from backlog to done.
