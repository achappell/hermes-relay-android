---
title: 'Freeze audio deadlines across concurrent pause transitions'
type: bugfix
created: '2026-10-13'
status: done
route: oneshot
baseline_revision: a192e0cb25d8024eaadde314f2a2e9ebc8c10686
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Standard restart verification exposed one failure in the unchanged audio pause/drain regression. Subsequent runs passed, so timing rather than a confirmed root cause was recorded. The shared AudioTrack sink must not charge paused time against write-stall, drain-stall or overall drain deadlines, including pauses between worker observations.

**Approach:** Reproduce the timing deterministically before changing production. Correct the confirmed deadline accounting, retain real unpaused stall/timeout failures and existing pause/resume/cancel behavior, and remove wall-clock scheduling assumptions from the affected pause tests. Verify real emulator notification Pause/Play and completion without resetting the configured app. No deadline increases, skipped assertions, audio transport changes, physical-device claims, unattended microphone, prolonged bake or automatic publication.

</frozen-after-approval>

## Implementation Notes

- No intent gaps or irreversible changes. Small shared-sink bug fix and existing test-file updates; route oneshot. Worktree `fix/audio-pause-deadline` isolates merged main and leaves unrelated checkout changes alone.
- `AndroidAudioSink.kt`: `pause`/`resume` flip an atomic flag; write/drain loops approximate paused time only on polls, while `elapsedMillis` reads raw time again after checking the flag. Candidate: pause between the flag check and playback-head read charges held time to a deadline. A complete pause/resume between polls also goes unseen. Confirm using existing `AudioTrackDriver`, `MonotonicClock` and `Sleeper` seams.
- Prefer one private pause-aware elapsed clock shared by write and drain deadlines, with synchronized primitive timestamp accounting at pause transitions. Do not hold its lock across driver calls, sleeps or callbacks; retain existing stream-generation cancellation guards and real timeout thresholds. No exported API change or new dependency.
- `AudioSinkPauseTest.kt`: replace the probabilistic mid-drain wait with latches and explicit worker-boundary events; prove pre-fix failure. Cover pause inside a head/write observation, pause/resume entirely between polls, resumed genuine stalls/timeouts and cancel/new-stream behavior. Retain original consumer-visible pause guarantees without wall-clock sleeps.
- Existing `AndroidAudioSinkFramesTest`, `ResponseAudioPlaybackTest` and runtime retention tests provide unchanged audio/output behavior coverage. Run full JVM/build/lint/metadata, a 30-run audio/lifecycle gate, relevant direct instrumentation and actual configured-emulator Pause/Play through terminal drain. No broad Android-HOME-08 acceptance: 08b and physical gates remain open.
- Update README/changelog, HOME-08 validation and local trackers after smoke proof; append a resolution to the deferred observation rather than rewriting its historical evidence. Finish with independent review and a local conventional commit.
- Confirmed red on the unchanged merged sink: block the second playback-head read (after the drain loop's pause check), call Pause, advance the manual clock by 5 s and release the read. The single selected test failed at the expected-next-poll assertion (`AudioSinkPauseTest.kt:135`); the worker instead settled as failed. This supplies the red-first proof the original drain-pause regression lacked.
- Implemented a private elapsed-playback clock: transition-locked `heldSince`/`heldNanos`, excluding current and completed holds from every write/drain deadline read. Removed poll-based timestamp resets, preserving the remaining active-time budget rather than granting a fresh deadline after each pause. Driver calls and worker waits remain outside the time-accounting lock. Cancel ends the hold; accumulated offset remains monotonic across streams.
- Replaced all wall-clock sleeps/spins in `AudioSinkPauseTest` with bounded latch/queue barriers. Ten tests cover held/pre-start playback, idempotent transitions, pause inside driver reads/writes, complete pause/resume between observations, last-poll-to-resume held time, real resumed write/drain stalls, overall progressing drain timeout and cancel/new-stream reuse.
- Focused `AudioSinkPauseTest`, `AndroidAudioSinkFramesTest` and `ResponseAudioPlaybackTest` passed after the fix. No timeout threshold, audio buffering policy, transport or notification code changed; no new lifecycle decision or journal event was introduced.

## Review Triage Log

- **Medium, patched — lost playing-output Pause assertion:** coverage review correctly found the rewritten playing-reply test no longer proved the active driver was held. Restored the assertion at a worker poll barrier and checked that repeated Pause adds no driver transition. Do not require exactly one initial pause call: the existing start-playback race repair may reapply Pause while start and Pause overlap; the consumer guarantee is held output, not a call count at that race.
- **Production review — no supported findings:** independent blind review inspected pause/resume/cancel, active elapsed time, failure paths and baseline behavior. It reported no qualifying patch-introduced defect after checking five candidate areas; it declined to manufacture findings to satisfy the review floor.
- Full JVM/build/test-APK/lint and metadata passed before the assertion-only review correction; the 30-run audio/lifecycle gate passed 58 tests/run, zero failures. Final full JVM and repetition runs include the restored assertion. Native verification is recorded below after execution.

## Final verification

- Final full JVM suite passed after the review assertion fix. Final repetition gate: 30 consecutive runs of `AudioSinkPauseTest,AndroidAudioSinkFramesTest,ResponseAudioPlaybackTest,HomeRuntimeRetentionTest`, 58 tests/run, zero failures (1,740 executions). APK/test assembly, lint and metadata passed; signer comparison skipped without an expected signer.
- Direct native instrumentation passed 18 tests (notification lifecycle, main screen, runtime recreation and silent audio output preflight). Main app/data retained.
- Actual configured API 36 Standard reply accepted at 21:04:48.622Z. Native notification Pause held the AudioTrack from 21:05:10.701Z to Play at 21:05:45.953Z (35.252 s). The same track's frame position stayed fixed across the hold, then advanced on resume. Inspected native paused-card screenshot. Reply drained at 21:06:19.758Z, retention ended, service stopped and wake lock released.
- Final app Ready/Verified, original draft and accessibility/radio settings restored, no Hermes media session and zero wake locks. Removed only test APK and owned temporary captures. Output was already port-muted; no audible or physical-phone acceptance claimed.
- README/changelog, HOME-08 validation, local story/sprint tracking and append-only resolution of the earlier deferred observation updated. Broader HOME-08/08b status remains unchanged.
- Post-build dispatcher reached review-finish and stopped at the existing missing `_bmad-output/planning-artifacts/prd.md` prerequisite. No PRD/key fabricated and no remote mutation. Delivery is a local commit; publication remains a separate user action.
