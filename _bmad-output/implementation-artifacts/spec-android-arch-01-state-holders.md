---
id: ANDROID-ARCH-01
title: Decompose AndroidClientScreen into testable state holders
status: backlog
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
