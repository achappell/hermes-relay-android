---
id: ANDROID-VOICE-04
title: Interrupt as the primary voice action with a 2 s acknowledgement
status: in-progress
baseline_commit: 64f12cb6292c7a2eee205eb0bedf875942af9e53
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S3
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-02
parity_source: 'PX-16 (matrix V5)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/88
---

# ANDROID-VOICE-04 — Interrupt as the primary voice action with a 2 s acknowledgement

Source: PX-16 (matrix V5) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size S.

## Background

On iOS the orb becomes "Tap to interrupt" while Hermes speaks; it stops local audio first, sends `session.interrupt`, and waits at most 2 s (`interruptAcknowledgement`) for the terminal. When hands-free is off it can interrupt and begin capture in one tap.

## Android today (checked against `main` unless marked [INFERENCE])

- Protocol parity exists: `interruptTurn` cancels the sink first, then sends `session.interrupt` (`OkHttpRelaySessionClient.kt:970`).
- The UI is an `OutlinedButton` "Stop responding" in the response zone (`TurnZone`), and the acknowledgement wait is `requestTimeoutMillis` = 10 s.

## Code Map

- `OkHttpRelaySessionClient.interruptTurn` is the protocol seam: it cancels local playback before sending `session.interrupt`.
- `TurnInterruptCoordinator` owns the once-per-turn guard, 2 s acknowledgement state, timeout, and interrupt-and-listen handoff; `HomeRuntime` wires it to normalized terminal events and capture.
- `DoorwayZones.kt::TurnZone` renders the Interrupt action, phase state description, and unconfirmed announcement; `MainActivity` routes the control through `HomeRuntime`.
- `TurnInterruptCoordinatorTest`, `HomeRuntimeInterruptTest`, `OkHttpRelaySessionClientTest`, `InterruptControlTest`, and `AccessibilityOrderTest` cover timing, ordering, capture handoff, and accessibility semantics. The physical Pixel pass remains a device gate.

## Required behavior

- While a reply is thinking, buffering or speaking, the primary voice control (orb, `ANDROID-UX-02`) is Interrupt, with `onClickLabel` "Interrupt" and the state description from the orb table.
- Acknowledgement deadline 2 s; expiry marks the interrupt unconfirmed with a visible state and keeps the local audio stopped.
- Interrupt-and-listen: when hands-free is off, one tap interrupts and starts capture after the terminal or the deadline.
- Until the orb ships, the existing button takes the 2 s deadline and the same label.
- When the user sends a typed next prompt during interrupt-and-listen, that explicit action owns the next turn: cancel the outstanding capture and pending listen handoff before submitting. A late recognizer result must neither submit a second prompt nor replace the accepted typed turn or its observer. Keep typing available; do not work around this by requiring the user or test driver to cancel listening first.

## Acceptance criteria

- Fake client: interrupt cancels audio before the frame is sent; terminal within 2 s completes; no terminal at 2 s shows the unconfirmed state; a second tap never sends a second `session.interrupt`.
- Interrupt-and-listen starts capture exactly once.
- TalkBack: the control announces the mode and the action (extend `AccessibilityOrderTest`).
- Given interrupt-and-listen is Starting, Listening or Transcribing, when the user sends a typed prompt, then capture is cancelled before the one typed submission and late recognizer callbacks cannot change its accepted state or response observation.
- Given the old interrupt still has a pending listen deadline, when the typed next turn begins, then that deadline cannot open a microphone over the typed reply.

## Android design notes

- The deadline lives in the shared `HomeTurnDeadlines` value introduced by `ANDROID-HOME-10`; reuse it.
- Do not enable voice barge-in here; see `ANDROID-VOICE-05`.

## Dependencies

`ANDROID-UX-02` for the final control; the deadline change can ship earlier on the existing button.

## Test notes

JVM with a manual clock.

## Device verification

Pixel: start a long reply, tap interrupt; speech stops immediately and the turn ends within 2 s; Home shows one interrupt.

## Resumed implementation — next-turn ownership

On live Home `d803994d1d47c63bb1b3c92cff42695de19a4434`, retained T3 evidence showed Home accepted the typed next prompt; a second client `SessionUnavailable` rejection never reached Home. The UI permitted typed Send during auto-listen. `HomeRuntime.initiate` submits on its executor without retiring capture, while `AndroidCaptureController.submit` separately calls the initiation controller and applies its result through the runtime. A late final from the still-open recognizer can therefore overwrite the accepted typed initiation and remove its response observer. T4 succeeds only after listening ends, so it does not prove this legitimate overlapping flow. T5/T6 contain real ambient recognizer submissions and are not controlled immediate-typed evidence.

Reuse `AndroidCaptureController.cancelCapture` (closes `captureOpen` before platform cancellation, rejecting late events) and `TurnInterruptCoordinator.reset` (cancels the old listen handoff) at the explicit runtime initiation boundary. Preserve hands-free policy; do not change the UI, Home signaling or recovery semantics.
When a fresh capture later opens, the displaced capture's retained callback must still be ignored. `captureOpen` alone cannot distinguish the old and new capture; bind speech callbacks to their capture generation in `AndroidCaptureController` if the deterministic reopen regression demonstrates that hole. Do not expand into recognizer/platform redesign.
Use the runtime's injected `DiagnosticsJournal` for the two bounded, content-free lifecycle decisions needed by the device gate: `voice capture started` when capture enters Starting, and `voice capture cancelled reason=typed_prompt` when typed Send retires an open capture. Assert these fixed lines through `RecordingJournal` in the runtime regression. Never log a transcript, prompt, response, identifier or audio.

### Tasks & Acceptance

- [ ] In `HomeRuntime.kt::initiate`, retire the prior interrupt handoff and capture before an explicit typed prompt is submitted.
- [ ] Extend `HomeRuntimeInterruptTest` with deterministic Starting/Listening/Transcribing cases, including recognizer cancellation/final callbacks, preservation of accepted response observation, a pending deadline that must not reopen capture, and old callbacks after a fresh voice capture opens. No wall-clock waits.
- [ ] Keep the saved `88ed7123da256459ae0c9087e1bfbe1022df7f26` protocol fixes: an actual Home text terminal followed by audio still permits a scoped tail interrupt; PCM in flight after a user stop is not a new failed stream.
- [ ] Run failing-before ownership regression, full JVM/build/lint/tracking gates and the combined repetition gate.
- [ ] Exercise tail and before-terminal speech interrupt followed immediately by legitimate typed Send during automatic listening on the Pixel; verify local audio stop, exactly one interrupt and next submit, no replay or false Unavailable, and one capture opening. Restore the baseline APK/data/settings.
- [ ] Publish exact-head CI and an honest validation/PR record. Physical TalkBack speech remains a separate human-only gate.
