---
id: ANDROID-HOME-07
title: Own the Home runtime in one process-scoped object that survives Activity recreation
status: done
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-01
parity_stream: S1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-02
ios_reference: 'IOS-HOME-07 (root cause "orphaned voice coordinator", 8482051)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/71
---

# ANDROID-HOME-07 — One Home runtime, bound to what the user sees

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): PX-01 (runtime owner outside the Activity), matrix D6. Follow-on UI split: `ANDROID-ARCH-01`; recovery persistence: `ANDROID-HOME-14`.

Android analogue of iOS commit `8482051` ("build the voice and lifecycle stack once so background retention sees the real reply", PR #122, v0.7.0). It is the prerequisite for `ANDROID-HOME-06` and `ANDROID-HOME-08`.

## Background — the iOS bug and why Android is exposed to a sibling of it

On iOS, `ContentView.init` rebuilt the voice stack and the lifecycle coordinator on every `HermesRelayApp.body` re-evaluation (every scene-phase change). `@State` kept only the first voice coordinator, but the scene-phase handler used a lifecycle coordinator bound to the newest, never-used one. That orphan had no response task, so background retention always read "nothing in flight", and `deactivate()` closed the shared Home socket under a streaming reply (build 17 journal: `lifecycle deactivate trigger=backgroundWithoutRetention reply=none` six seconds after Home audio started). Weeks of audio-session fixes (builds 15–16) were chasing a symptom of this single bug.

## Android today (verified against `main` at `3e10ae2`)

- `MainActivity.onCreate` builds `OkHttpRelaySessionClient`, `AudioTrackAudioSink`, the claim provider and all stores. `AndroidClientScreen` then builds `AndroidRecoveryController`, `AndroidInitiationController`, the main `Handler`, a single-thread `workExecutor`, turn state, recovery state, capture state and the pending turn with Compose `remember`.
- The `DisposableEffect(clientPort)` in `AndroidClientScreen` calls `workExecutor.shutdownNow()` and `clientPort.close()` in `onDispose`.
- `MainActivity` declares no `android:configChanges`. An Activity recreation (rotation, font scale or locale change, dark-mode toggle, multi-window resize, system-initiated process-keeping recreation) therefore **disposes the composition, closes the Home socket and the audio sink mid-reply, and throws away `turnState`, `recoveryState` and the unconfirmed turn**, because `remember` is not `rememberSaveable` and the runtime is Activity-scoped. [INFERENCE from the code; reproduce with `adb shell settings put system font_scale 1.3` or rotating during a reply.]
- The held claim (`HeldClaim`) and `reconnectRequired*` state live inside the client instance, so every recreation also abandons the held claim and the next connect makes a new one (a claim leak for `ANDROID-HOME-03`).
- There is no `Application` subclass, no `ViewModel`, and no foreground service; nothing outlives the Activity.

## Required behavior

- Exactly one Home runtime exists per process: it owns `OkHttpRelaySessionClient`, the audio sink, the recovery controller, the current turn/recovery/capture state, and the lifecycle coordinator (`ANDROID-HOME-04/06`). The Activity/Compose layer **observes** it and sends intents; it never constructs or closes it.
- Activity recreation (configuration change, theme change, window resize) never closes the Home socket, never stops audio, never clears the unconfirmed turn, and never starts a second runtime. When the Activity is finally destroyed and nothing is in flight, the runtime tears down under the same rules as `ANDROID-HOME-06`.
- The lifecycle coordinator is constructed with the runtime and bound to the same turn/voice objects the UI shows; there is no code path where a lifecycle handler holds a different instance than the screen (assert by construction, not by convention).
- A content-free journal line `runtime created` is written once per process (`ANDROID-DIAG-01`); two or more per process is a regression.

## Acceptance criteria

- Rotating the device (and changing font scale) during a streaming reply neither interrupts audio nor drops the connection nor loses the unconfirmed turn; the recreated Activity shows the same turn state.
- Unit test: two consecutive Activity-style `resolve()` calls (the seam, analogous to iOS `ContentViewRuntimeBox`) return the same runtime instance; the lifecycle coordinator's turn source is the instance the UI observes.
- Unit test: simulated Activity destroy with a reply in flight does not call `close()` on the client; destroy with nothing in flight tears down once.
- `runtime created` appears exactly once per process in a device journal across a rotation.
- No in-flight state (`remember`) is lost on recreation that the user would perceive as a reset: turn text, Speaking/Complete phase, unconfirmed prompt, hands-free state.

## Android design notes

- Options for the owner, to be settled in the story spec: (a) `Application`-scoped singleton created in `Application.onCreate` (simplest, process-lifetime, shared with the foreground service in `ANDROID-HOME-08`); (b) an activity-retained `ViewModel` (survives rotation but not an Activity that is finished and recreated by a service-started flow). Because the foreground service needs the same runtime without an Activity, (a) is the recommended default; a thin `ViewModel` can adapt it for Compose.
- Move state out of `AndroidClientScreen`'s `remember` into runtime-owned `StateFlow`/Compose state holders; keep `rememberSaveable` only for purely UI state (sheet visibility, draft prompt).
- This is a refactor with a user-visible fix and no new feature. Keep it separable: land it first, behind no flag, with the rotation test.
- Do not hold an `Activity` or `Context` from the Activity in the runtime (leaks); pass `applicationContext` stores as today.
- Process death is out of scope here (a reply cannot survive it); `ANDROID-HOME-06` already handles reconnect on cold start.

## Dependencies

None for the refactor itself. Blocks `ANDROID-HOME-06` (lifecycle owner) and `ANDROID-HOME-08`.

## Test notes

JVM tests for the runtime holder and its teardown rules (fake client/sink). An instrumented test that recreates the Activity (`ActivityScenario.recreate()`) while a fake Home streams a reply and asserts the same runtime, a single `runtime created`, and uninterrupted fake audio writes. Compose-only semantics tests can stay as they are.

## Device verification and expected journal lines

Start a 30+ s reply, rotate twice and change font scale once during playback; audio continues, transcript intact. Journal: one `runtime created` per process; no `lifecycle deactivate` and no `home client close` until the reply ends and the Activity is gone.

## References

iOS: `validation-ios-home-07.md` section "Follow-up: build 17 retest — lifecycle bound to an orphaned voice coordinator"; tests `testViewReinitialisationReusesOneRuntimeBoundToTheVoiceCoordinatorInUse`, `testRuntimeLifecycleKeepsAReplyWhoseControlTurnCompletedBeforeItsAudioEnded`.

## Acceptance closeout — 2026-10-07 owner authorization

Accepted on retained deterministic runtime/uncertain-state evidence plus the
actual Pixel run in `validation-android-home-07.md`: 377.408-second real reply,
two rotations/font recreation, one runtime/same process and output track,
advancing PCM with zero underruns, unmuted speaker route, retained transcript
and Speaking→Complete state. `HomeRuntimeRecreationTest` also passed on API 37.
Routed playback is proven; no human-heard or acoustic-recording claim is made.
No new waiver, microphone/permission mutation or future lifecycle-policy
completion is inferred. Original APK/settings/media state restored.
