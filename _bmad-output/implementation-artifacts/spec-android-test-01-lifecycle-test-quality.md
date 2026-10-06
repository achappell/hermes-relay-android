---
id: ANDROID-TEST-01
title: Make lifecycle, reconnect and voice tests platform-independent and flake-free
status: backlog
product_epic: 1
created: 2026-10-06
depends_on: []
ios_reference: 'iOS b46e8fc, 31d8a2e, aabb275 (PR #122); validation-ios-home-07.md "Follow-up: PR #122 CI failure on the iOS Simulator destination"'
github_issue: https://github.com/achappell/hermes-relay-android/issues/80
---

# ANDROID-TEST-01 — Test quality for the Home reliability work

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): matrix R3.

Parity with the iOS test-quality fixes in PR #122 (v0.7.0). This ticket defines the rules the other `ANDROID-PARITY-01` tickets' tests must meet and audits the existing Android tests against them. It ships independently and early.

## What iOS learned (evidence)

- **Tests that silently depended on the platform.** Two `AppleLifecycleTests` built `AppleLifecycleCoordinator` with the platform default `backgroundRetentionEnabled` (true on iOS, false on macOS) and asserted the old teardown-on-inactive policy. They were green on the developer's macOS runs and red on the iOS Simulator CI destination (3 failures in 2 tests). They had been destination-dependent since the retention feature landed. Fix: pass the flag explicitly. `31d8a2e` later waited for the actual socket close the transport-failure tests asserted on.
- **Flakes that were production races, not test noise.** `ConversationStoreReconnectTests.testAutomaticHomeReconnectCanReleaseConfirmedInactiveRecovery` failed 14 of 30 iterations because an uncertain submission armed a 500 ms connect retry and a following transport loss started the reconnect loop without cancelling it; the fix is in production code (`handleUnexpectedHomeTransportLoss` cancels the pending retry), 0 of 30 after. A voice test (`…BackgroundOutputEngineFailureEndsTheReplyInsteadOfStayingSpeaking`) raced the teardown queued by the retention-ended callback (7 of 40 failed before, 0 of 60 after) and now asserts the failure at the moment the reply ends (journal line written synchronously), accepting `failed` or `idle` afterwards.
- Baseline-red discipline: every regression test was first run against the previous code and recorded failing (`validation-ios-home-04/06.md`), except where the new API did not exist, which was recorded honestly as compile-red, not behavior-red.
- The simulator could not run `AudioOutputTests` (CoreAudio HAL); the report says so instead of claiming it.

## Android today (verified against `main` at `3e10ae2`)

- Unit tests are JVM-only (`app/src/test`, JUnit4 + MockWebServer + `org.json`); no Robolectric. Instrumented tests (`app/src/androidTest`) need a device/emulator and are **not** run by CI (`ci.yml` runs `testDebugUnitTest assembleDebug lintDebug` and the APK metadata check only).
- `HomeClientPairingTest.kt` contains three sleep/delay uses; the rest of the unit suite is largely clock-free. `AndroidAudioSink.kt` branches on `Build.VERSION.SDK_INT >= S` (line 122) — platform-dependent code with no injectable seam.
- The new work in `ANDROID-HOME-04/06/08/09/10` is lifecycle-, timer- and thread-heavy and will not be testable by the existing patterns unless the seams exist first.

## Required behavior

1. **Explicit policy in tests.** Any component whose behavior varies with platform/API/build type (retention enabled, foreground-service type, notification permission, `SDK_INT` branches, debug vs release) takes that policy as a constructor argument with **no default in tests**. A test that asserts a policy passes it explicitly. Add an `AndroidPlatform` value (api level, notification permission, foreground-service support) injected from the runtime and faked in tests.
2. **Lifecycle tests are JVM tests.** The lifecycle coordinator, retention predicate, retry/backoff, deadline timers and audio-focus/route state machines are plain Kotlin behind small interfaces (`Clock`/`Sleeper`, `ForegroundServiceController`, `MediaSessionPublisher`, `AudioFocusController`, `AudioRouteEvents`). They run identically on any developer machine and CI. A thin instrumented smoke runs on **two** API levels (the min SDK 26 image and the newest installed image) to prove the real wiring, and its result states the API level.
3. **No wall-clock waits.** New and touched tests use injected manual clocks, countdown latches/`CountDownLatch` with a signal from the code under test, or a single-thread executor drained deterministically. `Thread.sleep`/`delay` in unit tests are removed or justified in a comment (audit the three in `HomeClientPairingTest`).
4. **Race tests assert outcomes, not timings.** For overlapping recoveries (uncertain submit + transport loss), assert the single resulting state and that exactly one reconnect ran; assert teardown results at the moment the signal is raised (e.g. journal line), not after an arbitrary delay.
5. **Repetition gate.** A helper (`scripts/run-flake-gate.sh <TestClass> [N=30]` or a Gradle property) runs the named JVM test classes N times; the validation record of every parity ticket states "N consecutive runs, 0 failures" for its new lifecycle/reconnect/voice tests. Record flakes that fail on the baseline as baseline flakes, with counts.
6. **Baseline-red discipline.** Every regression test for `ANDROID-HOME-03` to `-10` is run against the pre-fix code first and its failure recorded in the validation record; tests that cannot compile at baseline because the API is new say "compile-red" explicitly.
7. **Journal assertions.** Teardown initiators are asserted through `ANDROID-DIAG-01`'s in-memory journal (as iOS `testJournalNamesTheInterruptionThatEndedBackgroundRetention`), so a regression names itself on a device.
8. **CI honesty.** Decide and record whether to add an emulator job (API 26 and the newest API) running the instrumented lifecycle smoke and `ANDROID-HOME-11` layout tests, or to keep them as manual device gates. Until then, the validation records state which tests did not run in CI. Do not claim route, mic, speaker or foreground-service behavior from the emulator (`AGENTS.md` "What only a real device finds").

## Acceptance criteria

- Platform/policy seam introduced with tests proving both branches of each current `SDK_INT`/build-type decision.
- Audit table (in the validation record) listing every existing unit test that uses a sleep/delay/real thread timing and its disposition (removed, made deterministic, or justified).
- The flake-gate script exists, is documented in `README.md`/`AGENTS.md` verification section, and is run on `OkHttpRelaySessionClientTest`, `AndroidRecoveryControllerTest` (`AndroidRecoveryController` tests), `AndroidCaptureControllerTest`, `AndroidHandsFreeTest`, `ResponseAudioPlaybackTest` and `HomeClientPairingTest` with 30 iterations each and the results recorded.
- A deliberately racy fixture (uncertain submit followed by transport loss) fails 100% of iterations against a version of the recovery controller without the single-owner guard and passes 30/30 with it — proving the harness catches the iOS race.
- `./gradlew testDebugUnitTest assembleDebug lintDebug` and `scripts/check-apk-metadata.sh` remain green.

## Android design notes

- iOS destination (macOS vs Simulator) → Android's equivalents are *API level*, *debug vs release* and *JVM vs device*. The rule is the same: no hidden defaults in assertions.
- Prefer small hand-written fakes (as in the existing suites) over mocking libraries; there is no Mockito in the dependency catalog and none is needed.
- Keep the seams few and named after the platform concept they hide; avoid a generic `Platform` god-object.

## Dependencies

None. Lands before or with `ANDROID-HOME-04`; the seams it introduces are used by `ANDROID-HOME-06/08/09/10`.

## Test notes

This ticket is itself about test mechanics; its own deliverable is the helper script, the seams, and the audit table.

## Device verification

None beyond the two-API instrumented smoke noted above.

## References

iOS: `validation-ios-home-07.md` (sections "Follow-up: PR #122 CI failure on the iOS Simulator destination"), `validation-ios-home-04.md`, `validation-ios-home-06.md`; commits `b46e8fc`, `31d8a2e`.
