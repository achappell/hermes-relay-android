# ANDROID-VOICE-04 validation record

Status: `in-progress`. Baseline: `64f12cb6292c7a2eee205eb0bedf875942af9e53` (`origin/main` with VOICE-03 #125 and HOME-13 #122 merged). A deliberate Disconnect (HOME-12) discards a pending interrupt so interrupt-and-listen never opens the microphone afterwards.

## Implemented

- Interrupt stops local playback before sending the existing `session.interrupt` frame, and the runtime prevents a second send for the same active turn.
- The action shows `Interrupting…` while awaiting acknowledgement, exposes a visible/announced unconfirmed state at 2 seconds, and starts capture once after a terminal or the deadline when hands-free is off.
- The control announces the current turn phase as its state description and `Interrupt` as its click action; accessibility traversal order is covered.

## Checks

- Issue-tracking tests and override checks passed (18 tests; script syntax and `--check`).
- Full Gradle checks passed at the rebased head (code verified at 49a4806; later commit is docs-only): `testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon --console=plain` (57 actionable tasks); the new instrumented tests compiled but were not executed.
- VOICE-04 repetition gate passed: `scripts/run-flake-gate.sh TurnInterruptCoordinatorTest,HomeRuntimeInterruptTest,OkHttpRelaySessionClientTest.interrupt_stops_local_audio_before_the_frame_reaches_home_and_is_sent_once 30` (30 runs, 15 tests/run, 0 failures; includes the HOME-12 disconnect-after-interrupt test).

## Remaining gates and dependency

- Pixel long-reply interrupt pass remains unrun: verify immediate local speech stop, terminal within 2 seconds, and one Home interrupt. Physical TalkBack verification is also unrun.
- No device authorization was available; no pairing, connected tests, app-data removal, install, or device operation was performed.
- The shared `HomeTurnDeadlines` type described in the design note is not present in the current VOICE-03 base; ANDROID-HOME-10 remains backlog. The acknowledgement is injected through existing `VoiceTimings` until that shared type is available.
