---
id: ANDROID-ARCH-01
title: Decompose AndroidClientScreen into testable state holders
status: in-review
type: refactor
route: dispatch
baseline_commit: c4ba8add6f7d46d96e4695c7b7f27928cfd82a2c
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-01
parity_stream: S1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-07
parity_source: 'PX-02 (matrix D6 follow-through)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/83
---

# ANDROID-ARCH-01 — Decompose AndroidClientScreen into testable state holders

Source: PX-02 (matrix D6 follow-through) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P0, size M.

## Background

`AndroidClientScreen` is one composable of roughly 700 lines inside a 961-line `MainActivity.kt`. It owns turn, capture, recovery, conversations, approvals and history state through `remember`, so none of it can be unit-tested without Compose and none of it can survive recreation. The HUD, header, rail and composer tickets (`ANDROID-UX-02` to `-05`) cannot be built, or reviewed, on top of that shape. `ANDROID-HOME-07` moves the Home runtime out of the Activity; this ticket splits the UI-facing state into holders over that runtime without changing behavior.

## Android today (checked against `main` unless marked [INFERENCE])

- `MainActivity.kt:139-520` declares about 40 `remember`/`mutableStateOf` values in one function (turn state, recovery state, capture state, conversations, approvals, pending divider, history revision, prompt history).
- The only UI unit coverage is `AndroidComposerStateTest`, `AndroidDoorwayStateTest`, `AndroidTurnStateTest` and the instrumented `AccessibilityOrderTest`/`MainActivityTest`; the glue between them is untested.
- `DoorwayZones.kt` (1,363 lines) mixes zone composables and logic.

## Required behavior

- Extract `TurnHolder`, `CaptureHolder`, `RecoveryHolder`, `ConversationsHolder`, `ApprovalsHolder` (names indicative) as plain Kotlin classes exposing immutable state and intent functions, driven by the runtime's flows; the composable only renders state and forwards intents.
- No behavior change: the existing unit and instrumented tests pass unmodified (`AccessibilityOrderTest` order included).
- Split `DoorwayZones.kt` into per-zone files only where a holder boundary makes it natural; do not reformat unrelated code.
- State that must survive recreation goes in the runtime or `rememberSaveable`; state that is purely visual stays local.

## Acceptance criteria

- `AndroidClientScreen` shrinks below about 250 lines and contains no `Executors`, `Handler` or `workExecutor`.
- Each holder has JVM tests for its state transitions using fakes (no Compose, no device).
- `./gradlew testDebugUnitTest assembleDebug lintDebug` and the existing instrumented tests give the same results as before the refactor (record both).
- Rotating the device mid-turn shows the same state (covered by `ANDROID-HOME-07`'s test; re-run it).

## Android design notes

- Compose state holders over `StateFlow`; collect with `collectAsStateWithLifecycle` (add `androidx.lifecycle:lifecycle-runtime-compose` and `lifecycle-viewmodel-compose` to `gradle/libs.versions.toml`, which today has neither).
- Land as a pure refactor PR with no feature change so review stays mechanical.
- Keep `AndroidClientPort` as the seam between holders and the runtime.

## Dependencies

`ANDROID-HOME-07` (runtime owner). Blocks `ANDROID-UX-02`..`-05`, `ANDROID-HOME-12`/`-14`.

## Test notes

JVM tests per holder with fake `AndroidClientPort`; no `Thread.sleep` (see `ANDROID-TEST-01`).

## Device verification

Smoke on a device: pair, send a typed turn, resume a conversation, approve a request, open Local History, rotate. Behavior identical to the previous build.

## 2026-10-16 approved implementation boundary

<frozen-after-approval reason="Parent approved the complete canonical story">

Preserve all required behavior and acceptance above. Reuse the process-owned
turn, capture and recovery controllers; holder names are indicative, not a
requirement to duplicate controllers. Plain Kotlin holders own immutable
StateFlow state and meaningful transitions/intents. Compose collects with
lifecycle awareness and retains only saveable draft/visual state. Extract
natural rendering zones rather than moving the entire screen into another
large composable to satisfy the line target.

Preserve conversation generation/Profile checks and HomeProfileRequestGate,
including title-enrichment guards and hide invalidation. Do not silently fix
pre-existing approval request races as part of this behavior-preserving work,
or extend their lifetime into another Profile. No UI feature changes, replay,
controller duplication, timing changes, owner credentials, or physical-device
acceptance claims. Existing tests remain unchanged.

Given an accepted turn, when the Activity recreates, then the same runtime,
turn and controllers continue without resend or duplicate observers. Given
an obsolete conversation or claims result, when it arrives after invalidation,
then it cannot replace current state. Given an approval action, when its result
arrives, then busy/result/refreshed state follows the existing flow. Given a
disconnected or uncertain turn, when UI state is collected again, then recovery
and explicit resend/discard behavior remain unchanged.

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/MainActivity.kt`:
  screen-owned conversation/claims/approval state and work at 147–178,
  297–466 and 959–1030; lifecycle integration at 480–559; rendering at
  613–1053. Preserve saveable visibility/draft and accessibility ordering.
- `app/src/main/java/com/achappell/hermesrelay/HomeRuntime.kt`:
  sole process owner; Compose-backed state at 60–114; existing controller
  callbacks and turn reducer already serialize publication on main.
- `AndroidTurnState.kt`, `AndroidRecovery.kt`, `AndroidCaptureController.kt`:
  reuse their existing state machines, never create a second ladder/reducer.
- `HomeProfileRequestGate.kt`: existing claims response guard.
- `app/src/androidTest/java/com/achappell/hermesrelay/AndroidClientScreenTestSupport.kt`:
  unchanged fake-backed composition runtime; existing integration tests are
  not production Home evidence.

## Tasks & Acceptance

- Replace runtime UI-state storage with plain immutable observable holder
  state while preserving controller ownership and existing property consumers.
- Extract conversation and approval asynchronous state/intents into plain
  holders with injected execution and existing port seams.
- Split screen rendering along header/actions/rail/sheets boundaries; collect
  holder flows using lifecycle-aware collection. Keep AndroidClientScreen
  below approximately 250 lines without a replacement monolithic composable.
- Add deterministic JVM transition tests using queued fake execution for
  each holder responsibility, including stale conversation/claims results,
  approval result refresh, and runtime turn/capture/recovery transitions.
- Record unchanged baseline and post-change existing suites; retain every
  failure, full logs and XML before repetition. Run actual emulator surface
  smoke on disposable fixtures and Activity recreation; do not claim real
  Home, physical audio or Pixel acceptance.
- Update this specification, story index, sprint status, validation and
  changelog with exact local evidence and limitations; commit locally only.

## Verification

Baseline: `c4ba8add6f7d46d96e4695c7b7f27928cfd82a2c`.
Evidence: `.local-evidence/arch01-20261016-071759/`.

`testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest --no-daemon`
passed: 767 JVM tests in 54 suites, zero failures. Full reports/XML/APKs are
archived under `baseline/`.

The unchanged non-LiveRelay instrumented suite on new empty API36 AVD
`hermes_arch01_empty`, explicitly targeted `emulator-5588`, ran 98 tests with
18 failures. The runner process returned zero but instrumentation reported
failure; this is not a passing baseline. Full runner and logcat are preserved.
Default AVD display was 320×640 at density160. Failure causes and any changed
verification prerequisites must be established before repetition. No
production or existing-test changes are authorized to hide baseline failures.

## Implementation Notes

Approval request generation guarding is not present in the original screen.
The refactor must preserve its scope and must not extend stale response
lifetime; a separate defect decision is required for behavioral hardening.

## Spec Change Log

- 2026-10-16: Grounded the complete parent-approved canonical refactor against
  merged HOME10 baseline; retained original acceptance and recorded failing
  pre-change instrumentation rather than weakening existing tests.

## Review Triage Log

| Finding | Verdict | Evidence and disposition |
| --- | --- | --- |
| Parent P1: frozen management providers | high | `ModeAwareClientPort.kt:56–64` returns null outside Home. Unkeyed remember retained null after Standard→Home. Keyed both holders to runtime/provider (and configuration for conversations); actual-screen smoke now crosses the real mux boundary before listing and approving. |
| Blind B1: disconnect enablement can be skipped | high | The extracted header lambda read non-Compose runtime getters. It now captures collected recovery/turn snapshots directly for canDisconnect and enabled. Add pending-submit→accepted actual-header regression; do not rely on parent recomposition alone. |
| Verification G1: lifecycle UI catch-up unproved | medium | Existing recreation test checks runtime identity/text, not rendered Compose output after recreation or a stopped collector. Add a separate actual-Activity test for post-recreation delta and stopped/resumed latest text/phase; existing tests stay unchanged. |
| Edge E1: late rename after hide/Profile change | medium | Original MainActivity rename completion at baseline 976–983 had no generation/Profile guard and called loadConversations after success. Extracted holder preserves that same ordering and composition scope. Pre-existing behavioral defect; deferred separately, not silently fixed in a pure refactor. |
| Edge E2: late approval rows/count after Profile change | medium | Original MainActivity 447–466 published loaded approval rows/count without a Profile/generation check. Same-provider Profile changes retain the same holder scope and same existing behavior; queued JVM test documents it. Pre-existing defect deferred, not a new authorization-race feature fix. |
| Blind B2: outer accepted-turn projection unobserved | high | Outer availability read runtime's StateFlow.value getter while collected turn state was read only in nested rendering scopes. Shared `TurnHolderState.hasAcceptedTurn(port)` now supplies both runtime and the outer collected-state projection, preserving the single rule. New surface test asserts accepted controls blocked, terminal controls available without draft/sheet/recreation stimulus, and a second explicit turn. Existing post-change Standard capture regression corroborated the stale busy state. |

## Local verification outcome — 2026-10-16

Software implementation and all three formal review closures are complete locally.
Canonical all-existing-instrumentation-green acceptance is **not** met: the final
98-case run retains exactly the same 18 baseline failing test IDs, with 17 matching
substantive signatures and one fixture failure advancing from keyboard-close
`NoActivityResumedException` to missing-binding `NullPointerException`. This is not
a fully accepted/done story. Existing tests were not modified.

Final JVM suite: 784 tests / 57 suites, zero failures; build, lint, both APKs and
APK metadata pass (release signer comparison skipped). Thirty separate fail-fast
N=1 invocations each passed 206 affected JVM tests, with full XML/log archives.
The actual surface smoke passed in post06; the lifecycle case passed in post05,
whose aggregate still failed the other, subsequently corrected surface fixture.
The two introduced post03 regressions passed after B2 in targeted and final98 runs.

P1, B1, G1 and B2 are source-reviewed and runtime-proved closed. E1 and E2 remain
individually deferred pre-existing defects. Synthetic normalized audio events in
the lifecycle fixture prove UI phase rendering, not native audio or live Home.
No physical Pixel/live Home acceptance, audio tuning, or historical Standard
iteration23 attribution is claimed. No push or PR was authorized.

Full receipts, exact failing IDs/signatures, and evidence paths:
[ARCH-01 validation](validation-android-arch-01.md).
