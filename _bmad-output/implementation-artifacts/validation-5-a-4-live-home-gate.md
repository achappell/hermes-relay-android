---
title: 'Validation — 5-A-4 live Home bridge gate'
type: 'validation'
story_id: '5-A-4-LIVE-HOME-GATE'
parent_story: '5-A-4'
created: '2026-09-15'
status: 'done-with-environment-limitation'
spec: '_bmad-output/implementation-artifacts/spec-5-a-4-live-home-gate.md'
---

# Validation — 5-A-4 live Home bridge gate

This is the companion record for the additive live-evidence follow-up. The
Android implementation and local build gates are complete, but the live Home
proof remains environment-limited: no signed deployment/run-trace inputs were
available, and the default connected suite could not build a Compose hierarchy.

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
