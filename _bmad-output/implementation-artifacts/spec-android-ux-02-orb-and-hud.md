---
id: ANDROID-UX-02
title: Voice orb and nine-mode ambient HUD
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-01
  - android:ANDROID-ARCH-01
parity_source: 'PX-18 (matrix V6; design section 3.1 orb, backdrop, bottom control bar)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/98
---

# ANDROID-UX-02 — Orb and the nine-mode ambient HUD

Source: PX-18 (matrix V6; design section 3.1 orb, backdrop, bottom control bar) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size L.

## Background

The orb is the iOS signature: a 260 pt control with three rings and a radial core, a glyph and a tint per mode, intensity from microphone level while listening and playback level while speaking, over a backdrop gradient of the mode tint. It is also the talk control and, while Hermes speaks, the interrupt control (`voice-orb`). Android has three vertical 5 dp bars (`VoiceActivityIndicator`, 900 ms tween) and phase text in `headlineSmall`.

## Android today (checked against `main` unless marked [INFERENCE])

- `VoiceActivityIndicator` at `DoorwayZones.kt:530`; no `Canvas` orb; the page background is flat `MaterialTheme.colorScheme.background`.
- `PlatformSpeechInput.onRmsChanged` is ignored (`:95`), so there is no microphone level; `AndroidAudioSink` exposes no playback level.
- `AndroidMotionMode` (Static/Reduced/Full) is read once via `remember(context)`; it does not update live.

## Required behavior

- `HermesOrb(mode, level, motion)` as one `Canvas` composable using the iOS table: base 142 dp; rings at stroke tint 0.18 - 0.04·i alpha, 1.5 dp, scale 1 + 0.18·i + intensity·0.10 + phase·(0.04 + 0.02·i); radial core tint 0.88 to 0.22 alpha, diameter 116 + 26·intensity + 5·phase; 34 dp glyph; phase sine of period about 3.14 s; intensity: listening max(0.12, mic), speaking max(0.12, playback) else 0.18, thinking 0.24, interrupted 0.34, complete 0.10, failed 0.10, idle 0.08.
- Nine modes (idle, listening, transcribing, thinking, buffering, speaking, complete, interrupted, failed) each with a glyph from `ANDROID-UX-01` and a tint from the state roles; mode changes cross-fade in 200-300 ms; reduced motion pauses the phase and the pulse (gated by `AndroidMotionMode`).
- Backdrop: a two-stop diagonal gradient of the mode tint at 12 % to clear to 4 %, animated with `animateColorAsState`.
- Inputs: microphone RMS from `onRmsChanged` (smoothed, normalized) and a playback level from the PCM peak of the sink; neither level is logged or stored.
- The orb is the primary voice control: tap to talk, tap to stop when capturing, tap to interrupt while replying (see `ANDROID-VOICE-04`); one `Button` with `contentDescription = "Voice"`, `stateDescription` = mode label, `onClickLabel` describing the action.
- Compresses to 0.6 of full size before the layout scrolls (shared with `ANDROID-HOME-11` and `ANDROID-UX-07`); hidden while the composer has focus unless a reply or capture is active, like iOS `shouldShowVoiceInterface`.

## Acceptance criteria

- Mode-to-glyph/tint/intensity table is a pure function with JVM tests for every mode and level boundary.
- Compose tests: semantics (label, state, click label) for each mode; orb is in the traversal order between STATE and RESPONSE (extend `AccessibilityOrderTest`).
- Reduced motion: with animation scales 0 the orb is static and no infinite transition runs.
- Frame cost: a 60 s recording of the orb in `speaking` on a Pixel shows no dropped-frame regression versus the bars (record with `adb shell dumpsys gfxinfo` or Perfetto).
- Contrast of glyph and status text over the tinted backdrop meets 4.5:1 (`PaletteContrastTest`).

## Android design notes

- Prefer `withFrameNanos`/`rememberInfiniteTransition` over a ticker thread; cap redraw at 30 fps while idle.
- This replaces `VoiceActivityIndicator`; delete it and its test in the same change.
- Playback level should come from the sink's write path without extra copies (the audio thread must not allocate per chunk).

## Dependencies

`ANDROID-UX-01` tokens/icons, `ANDROID-ARCH-01` holders. Feeds `ANDROID-VOICE-04`, `ANDROID-UX-04`, `ANDROID-UX-07`, `ANDROID-UX-10`.

## Test notes

JVM table and level-smoothing tests; instrumented Compose semantics; manual visual and perf check.

## Device verification

Pixel (no emulator for visuals): every mode reachable (offline, wrong code, long reply, interrupt), 2.0x font scale, reduced animations on, TalkBack reading the orb.
