---
id: ANDROID-VOICE-04
title: Interrupt as the primary voice action with a 2 s acknowledgement
status: backlog
product_epic: 1
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

## Required behavior

- While a reply is thinking, buffering or speaking, the primary voice control (orb, `ANDROID-UX-02`) is Interrupt, with `onClickLabel` "Interrupt" and the state description from the orb table.
- Acknowledgement deadline 2 s; expiry marks the interrupt unconfirmed with a visible state and keeps the local audio stopped.
- Interrupt-and-listen: when hands-free is off, one tap interrupts and starts capture after the terminal or the deadline.
- Until the orb ships, the existing button takes the 2 s deadline and the same label.

## Acceptance criteria

- Fake client: interrupt cancels audio before the frame is sent; terminal within 2 s completes; no terminal at 2 s shows the unconfirmed state; a second tap never sends a second `session.interrupt`.
- Interrupt-and-listen starts capture exactly once.
- TalkBack: the control announces the mode and the action (extend `AccessibilityOrderTest`).

## Android design notes

- The deadline lives in the shared `HomeTurnDeadlines` value introduced by `ANDROID-HOME-10`; reuse it.
- Do not enable voice barge-in here; see `ANDROID-VOICE-05`.

## Dependencies

`ANDROID-UX-02` for the final control; the deadline change can ship earlier on the existing button.

## Test notes

JVM with a manual clock.

## Device verification

Pixel: start a long reply, tap interrupt; speech stops immediately and the turn ends within 2 s; Home shows one interrupt.
