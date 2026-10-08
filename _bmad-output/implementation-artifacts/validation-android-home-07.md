---
story: ANDROID-HOME-07
spec: spec-android-home-07-single-home-runtime.md
status: done-with-environment-limitation
story_status: done
updated: 2026-10-07
---

# ANDROID-HOME-07 validation record

Accepted under the owner's 2026-10-07 review-closeout authorization: the existing deterministic ownership/state legs and the actual long-reply Pixel continuity leg below cover this story's criteria. Physical evidence establishes uninterrupted routed PCM on an unmuted speaker track, not a claim that a human heard it or an acoustic recording. No new waiver is applied.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | Below |
| CI | Pending the pull request | `ci.yml`: JVM suite, build, lint, APK metadata |
| `ActivityScenario.recreate()` instrumented test | **Passed** | `HomeRuntimeRecreationTest` 1/1 in the safe direct Pixel API 37 instrumentation batch |
| Device: 30+ s reply, rotate twice, change font scale once | **Passed at runtime/output-route scope** | One 377.408-second actual speech playback; same process/AudioTrack, advancing server frames, zero underruns; unmuted speaker route during repeated recreation; same response/phase after portrait and font recreation |
| Journal: one `runtime created` per process | **Passed** | Actual private device journal: one creation across seven Activity creations, no runtime teardown or mid-reply connection churn |

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
| Rotation / font scale during a streaming reply keeps audio, connection and the unconfirmed turn; recreated Activity shows the same turn | Passed as distinct legs: real reply/audio/socket/UI continuity below; uncertain-turn retention by existing `recreation_keeps_the_unconfirmed_turn` (including prior behavior-red mutation evidence). The live turn was confirmed; no artificial uncertain delivery was induced. |
| Two `resolve()` calls return the same runtime | Passed: `consecutive_activity_resolves_return_the_same_runtime` |
| The lifecycle coordinator's turn source is the instance the UI observes | Current lifecycle handling is on the same runtime, not a second coordinator instance: `the_screen_and_the_teardown_rules_read_the_same_turn_state` and `HomeRuntimeRecreationTest` pass. HOME-04/06's future lifecycle policies remain separate stories; this closure does not claim those policies exist. |
| Destroy with a reply in flight does not call `close()`; destroy with nothing in flight tears down once | Passed: `destroy_with_a_reply_in_flight_does_not_close_the_client`, `destroy_with_nothing_in_flight_tears_down_exactly_once`, plus the deferred-teardown, re-attach, system-destroy and overlapping-Activity cases |
| `runtime created` appears exactly once per process in a device journal | Passed: one actual line across six configuration-change destroys/seven Activity creations, with no teardown during the run; retained JVM journal assertion also passes. |
| No in-flight state lost on recreation that the user would perceive as a reset | Actual response text and Speaking→Complete state retained; same runtime ownership and deterministic uncertain-turn tests cover state retention. Hands-free was not armed in the live run: its field remains on the retained runtime, not recreated Compose state. No microphone/permission test or new hands-free acceptance claim is inferred. |

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

Instrumented tests remain outside CI. `HomeRuntimeRecreationTest` was run locally on the Pixel (1/1 passed) in the dated acceptance below; this does not imply CI ran it.

## Actual long-reply continuity acceptance — 2026-10-08 UTC

### Build and bounded scenario

- Pixel 6a/API 37; source `98ee22932036aff9871dcfebd9f62995bf82417b` (HOME-13 callback fix, PR #138), APK SHA-256 `b8c91c3be1a95a3b5d2c931a1472051aaf01066d2e3e69cecf4ceba6b6440cdc`, 0.3.1/code 301. Home `d803994d1d47c63bb1b3c92cff42695de19a4434`, all 34 installed sources previously reverified in this same acceptance batch; no deployment.
- One authorized neutral household prompt requested a bounded 900–1000-word general kitchen/pantry guide without tools or private information. No second prompt, capture or grant change. One `prompt.submit` completed at `01:34:59.260Z`; text terminal at `01:35:21.888Z`; actual AudioTrack began at `01:35:18.474Z` and `home audio completed` at `01:41:35.882Z` (**377.408 s**).
- The text terminal's `audio=none` means **no post-text wait**, not absence of audio. Audio already started before the terminal; the client only emits `home audio started after_text_ms=...` for a post-text start. This run's real UI was Speaking and its AudioFlinger track active. No >30-second audio-start delay is inferred: acceptance-to-track-start was **19.214 s**, so this does not close HOME-05.

### Recreation and output evidence

- First rotation pair and font change at `01:38:51–01:38:59Z`: portrait→landscape→portrait and font 0.85→1.3, during the same active reply. One PID and AudioTrack ID 433 / 24 kHz persisted, with advancing server frame counts and zero underruns.
- Media was initially volume 0/muted. The owner explicitly authorized temporary volume 3/unmute, with exact restoration. After unmuting, real output route was speaker, track `PortMuted=false`, gain/port volume −52 dB. This is routed PCM/output evidence, not microphone capture or human listening.
- Repeated portrait→landscape→portrait plus font restoration while unmuted at `01:40:45–01:40:54Z`. Same process/track; server frames advanced `0x007821C0` → `0x00790860` → `0x007ABD40` → `0x007BA5C0`, zero underruns/flushes. Playback continued for more than 40 seconds after these recreations and ended normally.
- Full 5,812-character response compared privately by hash: identical before recreation, after returning to portrait and after each font recreation; no content is published. Speaking remained visible in portrait/font snapshots; the landscape viewport did not expose the long response node, so continuous landscape text visibility is not claimed. Final real UI: `Turn phase: Complete`.
- Actual content-free journal: exactly one `runtime created`, seven `app activity created`, six `app activity destroyed finishing=false config_change=true remaining=0`. No `runtime teardown`, client close or connection event during the reply/recreations. The only open/reconnect entries precede the prompt; one submission, no replay. This closes the hardware/runtime continuity leg together with the distinct deterministic uncertain-turn/ownership legs above.

### Preservation and limits

Original app APK `5692a179b2347214b7e31cc1010f650ec8a224fe00439b8533ecbb5437ddeb0b` restored/hash-verified and app safely stopped. Original test APK had already been restored after the instrumentation batch. Font, rotation/auto-rotation, accessibility, animation and night settings restored; media exactly volume **0**, **muted=true**. Profiles, pairing/session references, credentials and permission-request preferences are byte-identical to this live-turn baseline. The completed legitimate household turn remains in authorized history; no valid state was rolled back.

No acoustic recording or human-heard verdict is claimed. The spec asks for uninterrupted playback/runtime state, not a separately defined listening study; this acceptance uses the real unmuted speaker route and continuously advancing same-track PCM, while retaining the original deterministic checks for unconfirmed and other runtime-owned state. Microphone permission revocation, airplane mode, release signing, process-death continuity and future HOME-04/06/08 lifecycle policies are neither exercised nor waived by this closeout.
