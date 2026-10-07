---
story: ANDROID-TEST-01
spec: spec-android-test-01-lifecycle-test-quality.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-06
---

# ANDROID-TEST-01 validation record

Gates are kept separate. Nothing here claims device behaviour.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | Below |
| Repetition gate (30 iterations) | Passed | Below |
| Racy-fixture proof | Passed | Below |
| CI | Passed on the pull request (run 37559247177: build/test/lint/APK metadata and issue tracking) | `ci.yml` runs the JVM suite, build, lint and APK metadata only |
| Instrumented smoke on two API levels (26 and newest) | **Not run** | No emulator or device was available. `AndroidPlatformSmokeTest` compiles (`compileDebugAndroidTestKotlin`) and has never executed. |
| Physical device | Not applicable to this ticket | The ticket adds seams and tooling only; no route, microphone, speaker or foreground-service behaviour is claimed |

## What changed

- `AndroidPlatform` (api level, notification permission, foreground-service support) built once by `AndroidPlatform.current(context)` at the runtime root and injected. `AudioTrackAudioSink` no longer reads `Build.VERSION.SDK_INT`: the one existing API decision (start threshold from API 31, buffer sizing below) is `preparePlaybackBuffer(platform, control, queuedFrames)`, and `platformAudioTrackDriverFactory(platform)` has no default, so a test must name the policy it asserts.
- `MonotonicClock` and `Sleeper` (fun interfaces, `Real` defaults for production). `AudioTrackAudioSink` takes both; every `System.nanoTime()` and `Thread.sleep` in its write and drain loops now goes through them.
- Test helper `ManualClock` (a clock whose `sleeper` advances time instead of blocking).
- `scripts/run-flake-gate.sh <TestClass>[,<TestClass>...] [N=30]`, documented in `README.md` and `AGENTS.md`.
- A race fixture in `AndroidRecoveryControllerTest` (uncertain submit, then a transport loss from a second owner while the first ladder is reconnecting).
- `AndroidPlatformSmokeTest` (instrumented, reports the API level in logcat tag `HermesPlatformSmoke`).

### Seams the spec names that this ticket does not add

The spec lists `ForegroundServiceController`, `MediaSessionPublisher`, `AudioFocusController` and `AudioRouteEvents` as the interfaces lifecycle code should sit behind. No code consumes them today (no service, media session, focus handling or route handling exists), so adding them now would be unused interfaces with guessed shapes. They belong to `ANDROID-HOME-06/08/09`, which will define them against the real consumers and must follow the rules in `AGENTS.md` (explicit `AndroidPlatform`, injected `MonotonicClock`/`Sleeper`). `Clock` is split into `MonotonicClock` (deadlines) because `HomeClientPairing` already injects wall time as `clock: () -> Double` and that stays as is.

## Local gate — 2026-10-06

- `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon`: passed; 277 unit tests, 0 failures, 0 errors, 0 skipped. This change adds 11 tests: `AndroidPlatformTest` (10) and the recovery race fixture (1). The toolchain was a standalone Temurin 21.0.12 and Android SDK platform 37.0 / build-tools 36.0.0 (no Android Studio).
- `scripts/check-apk-metadata.sh` (min SDK 26, version 0.3.1 / 301, signing skipped), `git diff --check`, `python3 -m unittest discover -s tests -p "test_bmad_issue_tracking*.py"` (18 tests) and `scripts/apply_repo_issue_tracking_overrides.sh --check`: all passed locally.

### Both branches of each current platform decision

The only `SDK_INT` decision in the app is the `AudioTrack` start policy. `AndroidPlatformTest` pins it at API 26 and 30 (buffer sizing) and 31 and 37 (start threshold), the empty-queue clamp on both branches, and the rejected-call failure on both branches. It also pins notification permission (no runtime permission below API 33; follows the grant from 33) and foreground-service support. There is **no debug-versus-release branch** in the app (`BuildConfig` is not generated and nothing reads the debuggable flag), so nothing exists to pin on that axis.

## Repetition gate — 30 iterations

`scripts/run-flake-gate.sh OkHttpRelaySessionClientTest,AndroidRecoveryControllerTest,AndroidCaptureControllerTest,AndroidHandsFreeTest,ResponseAudioPlaybackTest,HomeClientPairingTest,AndroidAudioSinkFramesTest,AndroidPlatformTest 30`

Result: **30 consecutive runs, 0 failures** (141 tests per iteration; every named class ran in every iteration, so each class has 30 iterations). The six classes the spec names plus the two this ticket touched.

No baseline flake was observed in the post-change runs, so no baseline gate run was made. The gate was not run against the pre-change code, so this record makes no claim about flake rates before this change.

## Racy fixture — fails 100% without the guard, passes 30/30 with it

Fixture: `AndroidRecoveryControllerTest.an_uncertain_submit_then_a_transport_loss_runs_exactly_one_reconnect`. An uncertain submit retains a turn and starts `recover()` on a second thread; the fake port blocks inside the first `reconnect()`; the test thread then reports a transport loss and calls `recover()` again. Ordering is forced by latches signalled by the code under test; nothing sleeps. It asserts exactly one reconnect, one retained uncertain turn, no replay (one request total) and the connected end state.

- Mutant: `AndroidRecoveryController.recover()` guard `if (state.isRecovering)` replaced by `if (false && state.isRecovering)` (temporary, restored before commit).
  `scripts/run-flake-gate.sh com.achappell.hermesrelay.AndroidRecoveryControllerTest.an_uncertain_submit_then_a_transport_loss_runs_exactly_one_reconnect 30` → **30 runs, 30 failed (0 passed)**; first assertion to fail: "the second owner was told recovery was already running".
- Real code: passes in all 30 iterations of the repetition gate above.

Limit worth knowing: the existing guard is a plain `isRecovering` check on unsynchronised state. The fixture proves the guard's behaviour with a happens-before edge (the latch) between the two owners; it does not prove the guard is atomic against two truly simultaneous callers. `AndroidClientScreen` wires `transportLost` on the main thread and `recover()` on a worker executor, so that remains an `ANDROID-HOME-04/07` concern.

## Audit — unit tests that use a sleep, delay or real-thread timing

| Test class | Use | Disposition |
| --- | --- | --- |
| `HomeClientPairingTest` | The three uses the spec counted are an injected fake `sleeper = { sleeps += it }` and `fixture.sleeps` (lines 269, 740, 757). Nothing sleeps; the wall clock is the injected `now`. | Already deterministic; no change. The spec's "three sleep/delay uses" were not real waits. |
| `AndroidAudioSinkFramesTest` | Stall/timeout deadlines of 20–1000 ms measured with `System.nanoTime()` and real `Thread.sleep(5)` polling in the sink worker. | Made deterministic: sink takes `ManualClock`; `Sleeper` advances it. Deadlines are crossed by clock advance, not time. |
| `AndroidAudioSinkFramesTest` | Four `assertFalse(latch.await(20 ms))` negative waits after the other callback fired. | Removed: `drained` and `failed` callbacks are exclusive by compare-and-set, so the test asserts the other latch's count is still 1. |
| `AndroidAudioSinkFramesTest` | `await(1–2 s)` on latches. | Justified: signalled by the code under test; the timeout only bounds a hung test. |
| `OkHttpRelaySessionClientTest` | `await(5 s)` on latches and `takeRequest(5 s)`. | Justified: signalled by the server/client; bound only. |
| `OkHttpRelaySessionClientTest` | `helloTimeoutMillis = 250` (line 595) and `requestTimeoutMillis = 250` (lines 989, 1127) with a server that never answers; `2_000` with scripted release latches (1058, 1187, 1239). | Justified, not changed: the client implements these deadlines as `CountDownLatch.await(timeout)` inside `OkHttpRelaySessionClient`, so the test must wait one real 250 ms. Making them deterministic needs a clock seam in the client; deferred to `ANDROID-HOME-10` (deadline timers), which owns that code. |
| `OkHttpRelaySessionClientTest` | Two real threads (lines 1025, 1064) blocked on latches to hold a first prompt open while a second is attempted. | Justified: order is forced by `firstPromptSeen`/`releaseFirstResponse`, not timing. |
| `OkHttpRelaySessionClientTest` | `System.currentTimeMillis() / 1000.0 + 60 * 86_400` (line 1463). | Justified: a credential expiry 60 days ahead is not sensitive to test speed. |
| `AndroidRecoveryControllerTest` (new fixture) | One real thread plus two latches. | Justified: the race is between two owners; latches signalled by the code under test force the order. The 10 s safety timeout bounds a hang only. |
| `AndroidCaptureControllerTest`, `AndroidHandsFreeTest`, `ResponseAudioPlaybackTest` and all other unit classes | No sleep, delay, thread or wall-clock use found by search of `app/src/test` (`sleep`, `delay`, `Thread`, `nanoTime`, `currentTimeMillis`, `yield`, `timeout`). | No change. |

Production code that still reads real time and is out of scope here: `OkHttpRelaySessionClient` request/hello timeouts and the graceful-close `Thread.sleep` (`ANDROID-HOME-10`), credential-expiry reads in `OkHttpRelaySessionClient` and `HomeDeviceAdministration`, and `AndroidLocalHistory`'s injected clock (already injectable).

## CI honesty decision (spec item 8)

Decision: **keep instrumented lifecycle, layout and the two-API smoke as manual device gates; add no emulator job now.** Reasons: a hosted emulator cannot exercise the routes, microphone, speaker or foreground-service behaviour the parity tickets need (`AGENTS.md`), so a green emulator job would overstate evidence; the lifecycle logic itself is JVM-testable behind these seams; and an emulator matrix would add a flaky, slow job before any instrumented lifecycle test exists. Revisit when `ANDROID-HOME-06` lands the first instrumented lifecycle test. Reversal is adding a job; nothing in the repository depends on this decision.

Until then, every parity validation record must say which tests did not run in CI. For this ticket: `AndroidPlatformSmokeTest` and all other `androidTest` classes did not run in CI and were not run locally.

## Device gates (unverified)

- Run `AndroidPlatformSmokeTest` on an API 26 image and the newest installed image; each result must state the API level (logcat tag `HermesPlatformSmoke`, `apiLevel=`). Status: **unverified**.
- Nothing about audio output, routes or foreground services is claimed by this ticket.

## Journal assertions and baseline-red discipline (spec items 6–7)

These are standing rules for `ANDROID-HOME-03` to `-10` and depend on `ANDROID-DIAG-01`'s journal, which does not exist yet. They are written into `AGENTS.md`; no code could satisfy them in this ticket. The racy fixture above is baseline-red evidence for the existing guard (behaviour-red against the mutant, not compile-red).
