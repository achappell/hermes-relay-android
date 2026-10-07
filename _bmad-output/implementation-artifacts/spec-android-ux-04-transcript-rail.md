---
id: ANDROID-UX-04
title: Live transcript rail with reveal-with-voice and Resume live
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-03
parity_stream: S7
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-02
parity_source: 'PX-21 (matrix C5)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/100
---

# ANDROID-UX-04 — Live transcript rail with reveal-with-voice

Source: PX-21 (matrix C5) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size L.

## Background

The iOS `RecentTranscriptRail` shows the last six entries (YOU and HERMES LIVE), `title3` for the live and latest entry and `callout` for older ones, with a soft top edge, auto-scroll that pauses when the reader scrolls and a "Resume live" control, and reply text revealed in step with the audio position (2.8 words/s fallback when no position is available, 320 ms wall-clock pacing per fragment when there is no audio). Android shows the current reply as one `bodyLarge` text item.

## Android today (checked against `main` unless marked [INFERENCE])

- `TurnZone` renders `responseText` as a single `Text` in a `LazyColumn` item; the user's partial transcript is shown in the bottom bar (`ActiveCaptureZone`).
- History is a separate sheet (`LocalHistoryZone`, text rows); there is no in-surface History link.
- `AudioTrackAudioSink` already polls `playbackHeadPosition` for draining, which is the natural reveal clock.

## Required behavior

- `TranscriptRail` composable: live entry, latest, older entries (up to six), role labels in the `ANDROID-UX-01` role-label style.
- Reveal-with-voice: reveal text proportional to the played fraction of the reply using the sink's played-frames count; fall back to 2.8 words/s when no position is available and to 320 ms per fragment when there is no audio; never reveal beyond what has arrived.
- Follow/pause: auto-scroll follows the live entry; a user scroll pauses it and shows **Resume live**; resuming scrolls with the 160-180 ms animation (skipped under reduced motion).
- A History link in the rail opens Local History; per-message copy arrives with `ANDROID-UX-08`.
- Reveal never changes what is stored or exported: Local History and export contain the full text.

## Acceptance criteria

- Pure reveal function with JVM tests: played fraction to character/word count, monotonic, never exceeds received text, fallback rates, no-audio pacing.
- Compose test: follow/pause/resume behavior with `LazyListState`; the live entry exposes a polite live region at phase changes only, not per word.
- TalkBack reads each entry once as `Role, text` (assert the label string; iOS had a label-interpolation bug here, so test the actual text).
- Order: HEADER, PROFILE, STATE, RESPONSE (rail), ACTION in `AccessibilityOrderTest`.

## Android design notes

- Use `LazyListState` and `animateScrollToItem`; keep the item count bounded.
- Expose the played-frames count through a small `PlaybackClock` interface so reveal is testable without `AudioTrack`.

## Dependencies

`ANDROID-UX-02`; `ANDROID-HOME-09` for a reliable playback position after restarts.

## Test notes

JVM reveal tests with a manual clock and fake playback clock; instrumented Compose.

## Device verification

Pixel: long reply, scroll up during playback, Resume live; reveal tracks speech without running ahead; TalkBack reads entries once.
