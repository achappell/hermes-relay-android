---
story: ANDROID-HOME-07
spec: spec-android-home-07-single-home-runtime.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-06
---

# ANDROID-HOME-07 validation record

The gates are kept separate. The user-visible claim of this ticket (rotation and font scale during a streaming reply keep audio, socket and turn) is a **device gate that has not been run**.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | Below |
| CI | Pending the pull request | `ci.yml`: JVM suite, build, lint, APK metadata |
| `ActivityScenario.recreate()` instrumented test | **Not run** | `HomeRuntimeRecreationTest` compiles; no emulator or device was available |
| Device: 30+ s reply, rotate twice, change font scale once | **Unverified** | Needs a Pixel or emulator; audio continuity needs real output |
| Journal: one `runtime created` per process | Covered by `ANDROID-DIAG-01` (JVM); device export unverified | Update 2026-10-07: `HomeRuntimeBox` now writes the content-free journal line `runtime created` once at its single creation point, and `HomeRuntimeTest.runtime_created_is_journaled_once_across_rotations` asserts exactly one across two recreations (no `runtime teardown`). Reading it from a shared export on a device is still unverified. |

## What changed

- `HomeRuntime` (new, `HomeRuntime.kt`) owns, for the process: the Home client and its audio sink, the initiation, recovery and capture controllers, the worker executor, Local History's recorder, and the state the user sees mid-conversation: recovery, initiation, turn, capture, unconfirmed turn (`lastRequest`, `resendResult`), hands-free, in-flight flags and prompt history. It holds no `Activity` or Activity `Context`.
- `HomeRuntimeBox` is the `resolve()` seam (analogue of iOS `ContentViewRuntimeBox`): the factory runs once until the runtime tears itself down; `createdCount` is the number the `runtime created` journal line will report.
- `HermesRelayApplication` (new, registered in the manifest) owns `HermesRelayServices` (credentials, stores, configuration, pairing coordinator) and the box, so every Activity instance sees the same `RelayConfigurationController` the running client was built against. Previously each `MainActivity.onCreate` built its own.
- `MainActivity.onCreate` resolves the runtime and calls `activityCreated()`; `onDestroy` calls `activityDestroyed(isFinishing, isChangingConfigurations)`.
- `AndroidClientScreen` takes the runtime, observes its state, and sends intents (`initiate`, `recover`, `resendUnconfirmedTurn`, `discardUnconfirmedTurn`, `switchConversation`). Its `DisposableEffect` no longer shuts the executor down or closes the client. The connection and turn observers, the turn reducer, response recording on settle, and the settle/hands-free handoff moved into the runtime.
- Androidtest hosts keep their 26 call sites: `AndroidClientScreenTestSupport` is an androidTest-only overload that builds a composition-scoped runtime on the test's fake port.

### Teardown rules

| Event | Result |
| --- | --- |
| Recreation (`isChangingConfigurations`): rotation, font scale, locale, theme, window resize | Runtime kept: no `close()`, audio and turn untouched |
| System destroy of a backgrounded Activity (`!isFinishing`) | Runtime kept: the unconfirmed turn survives the process-keeping destroy |
| Another Activity instance still attached | Runtime kept |
| Finishing, nothing in flight | Torn down once: observers cancelled, capture and hands-free cancelled, executor shut down, client closed, box released (the next `resolve()` builds a fresh runtime) |
| Finishing with a reply in flight | `close()` **not** called; torn down once, when the turn reaches a terminal event with no Activity attached; a new Activity attaching first cancels the pending teardown |

"Same rules as `ANDROID-HOME-06`": HOME-06 does not exist yet. These are HOME-07's own rules; HOME-06 replaces the "finishing and idle" branch with its lifecycle coordinator, which must take the runtime (never construct a second owner).

## Local gate — 2026-10-06

See the pull request for the final run output. Locally (JDK 21.0.12, Android SDK 37.0, build-tools 36.0.0):

- Rebased onto `main` after `ANDROID-TEST-01` (#116) merged; local results below are from the rebased tree.
- `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon`: passed; 289 unit tests, 0 failures, 0 errors, 0 skipped (277 on `main` + 12 new `HomeRuntimeTest`).
- Repetition gate (`scripts/run-flake-gate.sh HomeRuntimeTest,AndroidRecoveryControllerTest,OkHttpRelaySessionClientTest,AndroidCaptureControllerTest,AndroidHandsFreeTest,ResponseAudioPlaybackTest,HomeClientPairingTest,AndroidAudioSinkFramesTest,AndroidPlatformTest 30`): **30 consecutive runs, 0 failures** (153 tests per iteration). `HomeRuntimeTest` has no sleeps, no real threads and no wall-clock reads: the executor runs inline and main-thread posts run synchronously. An earlier pre-rebase run of `HomeRuntimeTest,AndroidRecoveryControllerTest` was also 30/30.
- `scripts/check-apk-metadata.sh`, `git diff --check`, `python3 -m unittest discover -s tests -p "test_bmad_issue_tracking*.py"` (18 tests) and `scripts/apply_repo_issue_tracking_overrides.sh --check`: passed on the rebased tree.

### Acceptance criteria

| Criterion | Status |
| --- | --- |
| Rotation / font scale during a streaming reply keeps audio, connection and the unconfirmed turn; recreated Activity shows the same turn | JVM: `recreation_mid_reply_keeps_the_socket_the_audio_and_the_turn` and `recreation_keeps_the_unconfirmed_turn` prove the runtime keeps the client open and the state. Rendering the same turn after recreation and uninterrupted audio: **device gate, unverified** |
| Two `resolve()` calls return the same runtime | Passed: `consecutive_activity_resolves_return_the_same_runtime` |
| The lifecycle coordinator's turn source is the instance the UI observes | **Partly**: there is no lifecycle coordinator yet (`ANDROID-HOME-04/06`). The destroy rule and the screen read one `turnState`/`initiationState` field (`the_screen_and_the_teardown_rules_read_the_same_turn_state`). HOME-04/06 must construct the coordinator from the runtime. |
| Destroy with a reply in flight does not call `close()`; destroy with nothing in flight tears down once | Passed: `destroy_with_a_reply_in_flight_does_not_close_the_client`, `destroy_with_nothing_in_flight_tears_down_exactly_once`, plus the deferred-teardown, re-attach, system-destroy and overlapping-Activity cases |
| `runtime created` appears exactly once per process in a device journal | **Covered in JVM by `ANDROID-DIAG-01` (2026-10-07); device journal unverified.** The line is emitted from the box's single creation point and a teardown names its initiator (`runtime teardown reason=activityFinished` or `replySettled`; `runtime teardown deferred reply=inFlight`). A device export across a rotation has not been read. |
| No in-flight state lost on recreation that the user would perceive as a reset | Moved to the runtime: turn text and phase, unconfirmed prompt, hands-free, capture state, prompt history, resend result. Not verified on a device. |

A mutation check: forcing `activityDestroyed` to tear down on every call made `a_system_destroy_of_a_backgrounded_activity_keeps_the_runtime` and `recreation_keeps_the_unconfirmed_turn` fail (2 of 11); the mid-reply tests still passed because the in-flight rule alone protects them. The new API did not exist at baseline, so these tests are compile-red against the previous code, not behaviour-red.

## Behaviour changes worth reviewing

- **Teardown moved from composition disposal to Activity finish.** Before, any composition disposal closed the client and killed the audio sink. Now a recreation does not, and a finishing Activity with a reply in flight leaves the client open until the reply settles. There is no deadline on that wait yet: a reply that never reaches a terminal event keeps the socket open until `ANDROID-HOME-10`'s control deadline or process death.
- **An unconfirmed turn is still lost when the runtime is torn down** (Activity finished while idle). Surviving that is `ANDROID-HOME-14`.
- **Capture and hands-free are cancelled at teardown.** Before, the microphone could stay live after its screen was gone.
- **Lost connections with no screen attached are recorded but do not trigger the foreground reconnect**, because the screen registers that callback and clears it when it leaves composition. Background reconnect belongs to `ANDROID-HOME-04/06`.
- Settling a turn (recording the response, `onTurnSettled`) now runs once in the runtime when the terminal event arrives, not from a `LaunchedEffect`, so a screen that is recreated after the settle neither records the response twice nor steals composer focus.

## Still Activity-scoped (not in-flight reply state)

`remember` state that a recreation still resets: `conversationsState`, `approvalsState`, `pendingApprovals`, `renameMessage`, `approvalsMessage`, `approvalsBusy`, `conversationMessage`, and `pendingDivider`. Losing `pendingDivider` when the Activity is recreated between requesting a conversation switch and Home's claim opening means that one divider is not recorded. Sheet visibility and the draft prompt remain `rememberSaveable`. `ANDROID-ARCH-01` is the follow-on that splits the screen state.

## Observation, not changed here

`AudioTrackAudioSink.close()` shuts its worker executor down for good, and `OkHttpRelaySessionClient.close()` calls it. The configuration screen's `onHomeCredentialChanged` calls `clientPort.close()` and then keeps using the same client, so audio after a credential change appears to fail with `WriteFailure` until the process restarts [INFERENCE from the code; not reproduced]. HOME-07's own teardown avoids this by discarding the whole runtime. The credential-change path is out of scope here.

## Merge note

`ANDROID-TEST-01` merged first. `HermesRelayApplication.createHomeRuntime` now reads `AndroidPlatform.current(this)` once at the runtime root and builds the sink with `AudioTrackAudioSink(driverFactory = platformAudioTrackDriverFactory(platform))`. `MainActivity` no longer constructs the sink, so the platform wiring lives only in the Application. Story-index, sprint-status and spec status for both tickets remain `review`.

## CI honesty

`HomeRuntimeRecreationTest` and every other `androidTest` class do not run in CI and were not run locally.
