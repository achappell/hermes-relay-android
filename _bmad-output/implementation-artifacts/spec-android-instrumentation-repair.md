---
title: 'Repair retained Android instrumentation failures'
type: 'bugfix'
created: '2026-10-16'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
context: []
baseline_commit: '0330c7fa5115b85f59707ad0b1da70b74caaf817'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Eighteen existing non-live instrumentation cases fail identically by ID before and after ARCH01. Their behavioral assertions must become reliable evidence rather than waived baseline failures.

**Approach:** Trace each failure through the actual UI, asynchronous runtime, and fixture lifecycle. Repair deterministic prerequisites and obsolete timing expectations; fix production only where a real behavior defect is established. Preserve meaningful turn, accessibility, ordering, visibility, and liveness coverage.

## Boundaries & Constraints

**Always:** Preserve current branch and commits, historical failure receipts, and owner emulator/recovery data. Use a new empty owned AVD with explicit serial-targeted adb instrumentation. Retain unchanged production timing policy. Archive each verification run and stop subsequent Gradle operations on the first unexpected failure until diagnosed. Deliver stable full diff for parent formal review and local exact-path commits only.

**Never:** Run Gradle connected tasks, touch emulator-5554 or owner credentials, push or create a PR, weaken assertions, skip failures, add sleeps or blanket timeouts, invent production features, or claim physical/live acceptance from deterministic fixtures.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Typed submission | Connected runtime and entered prompt | Exactly one selected-profile request, accepted binding and observer before events | Disabled send remains disabled while disconnected |
| Normalized reply | Observed accepted turn | Coherent text, audio phases, terminal settlement | Lost turn unconfirmed; resend only explicit |
| Hands-free completion | Audio then terminal turn | No reopen during real policy tail; exactly one reopen after manual time reaches deadline | Stop ends capture without submission |
| Accessible controls | Offscreen or IME-constrained content | Actual reachable content and meaningful semantic order preserved | Error announcement remains assertive |

</frozen-after-approval>

## Code Map

- `app/src/androidTest/java/com/achappell/hermesrelay/AndroidClientScreenTestSupport.kt`: composition-owned HomeRuntime with real worker executor and Handler; add narrow optional runtime/timer seams, retaining normal threading.
- `app/src/androidTest/java/com/achappell/hermesrelay/{MainActivityTest,AccessibilityOrderTest,InterruptControlTest,LocalHistoryTest,MicrophoneCaptureTest,HomeConversationsSheetTest}.kt`: eighteen retained failure IDs enumerated in validation record. Fake requests/bindings are not observation-installation barriers.
- `app/src/androidTest/java/com/achappell/hermesrelay/ClientHolderSurfaceSmokeTest.kt`: existing runtime-state/observer predicate and semantic action pattern; retain both new holder smoke tests.
- `app/src/main/java/com/achappell/hermesrelay/HomeRuntime.kt`: worker and main dispatch; observation installed by updateInitiation; terminal events settle capture; default real clock and policy timers.
- `app/src/main/java/com/achappell/hermesrelay/{ClientActionSurface,DoorwayZones}.kt`: independently scrolling bounded action surface and typed controls. Investigate actual bounds before geometry attribution.
- `app/src/test/java/com/achappell/hermesrelay/{ManualVoiceTimers,ManualClock}.kt`: existing explicit-time fixture patterns to reuse for hands-free policy boundary.

## Tasks & Acceptance

**Execution:**
- `AndroidClientScreenTestSupport.kt` and affected instrumentation fixtures: expose deterministic runtime readiness and observation prerequisites; ensure controls are reachable before physical actions; preserve all behavior assertions.
- `MicrophoneCaptureTest.kt`: inject manual policy clock/timers; assert no premature wake and exactly one post-tail wake, then stop without submission.
- Relevant production files only if tracing establishes a behavior defect: fix root cause and add regression coverage without changing unrelated semantics.
- `validation-android-arch-01.md` and existing changelog: append diagnosis, exact verification receipts, retained baseline history, and explicit physical/live limitations.

**Acceptance Criteria:**
- Given the eighteen recorded failures, when repaired fixtures run, then every named behavior assertion passes without skips or weakened requirements.
- Given the current instrumentation APK, when all non-LiveRelay tests are discovered and executed, then actual discovery count is recorded (expected 100 including two new holder smoke tests), with no failures.
- Given the changed sources, when full JVM verification completes, then full XML is archived before a fail-fast N=1 repetition gate; each run has separate receipts.
- Given a fresh owned empty emulator, when runtime screens are exercised, then actual screenshot/UI evidence demonstrates reachable controls and turn behavior without owner data.
- Given emulator fixture evidence, when reporting results, then prepaired fixture and synthetic-audio-only limits are explicit; Pixel/live-Home/native-audio acceptance remains separate.

## Implementation Notes

- Completed all eighteen retained failure repairs without production changes or assertion removals. Shared fixture support retains real worker threading, exposes actual runtime state, supplies functional retention timers and permits manual policy time.
- Full non-LiveRelay runner discovered100:98 passes, zero failures, two unchanged explicit-liveSpeech assumption skips. Both new holder smokes and all18 formerly failing IDs passed in that invocation.
- Final full JVM784/57, APK builds and lint passed; complete XML archived before30 separately archived fail-fast N=1 repetitions (206 each,6180 passes). Explicit SDK metadata invocation passed after preserving and diagnosing the Homebrew-tool-path failure.
- Actual emulator screen capture of the passing normalized-turn test shows typed input, Buffering response and Complete/re-enabled capture. Synthetic normalized audio remains UI-only evidence.
- Parent's three formal reviews and focused coverage closure completed. Existing physical/live acceptance limitations remain outside this test-repair deliverable. Post-build publication/issue dispatch is handed to the parent under the explicit no-push/no-PR worker boundary.
- Detailed receipts, failed-attempt history, historical skip accounting and owned-emulator cleanup are appended to `validation-android-arch-01.md`; prior receipts were not changed.

## Spec Change Log

## Review Triage Log

- Parent's three formal reviews: blind and edge reviews reported no findings. Coverage review identified Retry/Resend assertions still using Compose idle rather than worker completion. Accepted: `MainActivityTest` now waits on actual connected/non-recovering state after Retry, then completed resend plus accepted replacement binding and installed observer on the UI queue before exact-count/no-replay assertions. This is a source-identified race, not a claimed observed failure.

## Verification

- Full JVM task with XML archived before repetition gate; first unexpected failure stops subsequent Gradle work until diagnosis.
- Build debug application and instrumentation APKs, install only on fresh owned AVD, run direct `adb -s <owned-serial> shell am instrument` excluding LiveRelay; record actual discovered and passed counts.
- Capture actual screen runtime proof and preserve all prior evidence unchanged. Parent owns formal review and publication authorization.
