---
id: ANDROID-VOICE-06
title: Mute Hermes's voice without interrupting the reply
status: backlog
product_epic: 1
release_scope: later
parity_epic: ANDROID-PARITY-01
parity_stream: S2
parity_tag: FB-MUTE
created: 2026-10-07
depends_on:
  - android:ANDROID-UX-15
parity_source: 'User feedback 2026-10-07 (parity tag FB-MUTE); iOS/macOS twin IOS-UX-F8'
---

# ANDROID-VOICE-06 — Mute Hermes's voice without interrupting the reply

Source: user feedback, 2026-10-07. Parity tag `FB-MUTE`; iOS and macOS twin `IOS-UX-F8` in hermes-relay-ios.

## Background

The user sometimes needs Hermes to stop talking without stopping Hermes. Today the only way to silence a reply is Interrupt, which ends the turn. A sticky mute that gates only local playback: audio frames are still consumed and dropped, no interrupt is sent, text keeps streaming. Two defaults are proposed for owner confirmation.

**Parity:** tag `FB-MUTE`. iOS and macOS: `IOS-UX-F8` (hermes-relay-ios). Android: `ANDROID-VOICE-06` (hermes-relay-android). The acceptance criteria below are worded identically in both repos; only the platform notes differ. Mac and iPad stay the most alike.

## Android today (checked against `main` unless marked [INFERENCE])

Checked against `origin/main` (64f12cb), and the interrupt from `origin/feat/android-voice-04-interrupt` (3faa86d, PR #129) via `git show`.
- **Mute gates here:** `OkHttpRelaySessionClient.kt:1289` (`dispatch`), where `:1314-1323` merge PCM with the frame remainder and `:1324-1326` call `audioSink.write(...)` then deliver `AudioChunkReceived`. While muted, frames are still merged and `AudioChunkReceived` is still delivered, but `audioSink.write` is skipped. The sink is `AudioTrackAudioSink.write` (`AndroidAudioSink.kt:312`).
- End of reply: `audioSink.finish` at `OkHttpRelaySessionClient.kt:1418-1427` (journal `home audio completed`, `:1427`). A muted reply must drain immediately, not wait on audio that was never written.
- **Distinct from Interrupt:** on `main`, `OkHttpRelaySessionClient.kt:1028-1043` (`interruptTurn`) sets `interruptRequested`, cancels the sink and sends the interrupt RPC. On the VOICE-04 branch, `TurnInterrupt.kt:30-60` (`TurnInterruptCoordinator`; `send` "stops local audio and sends the interrupt", `:33-34`; `interrupt()`, `:52`) is wired in `HomeRuntime.kt:146-151` and `interruptAndListen` (`:157-159`). Mute must call none of these.

## Required behavior

**Layout classes (shared by FB-THINK, FB-TYPE, FB-MUTE, FB-LAYOUT).** *Compact* is a window whose horizontal size class is compact: iPhone, and an Android phone (`WindowWidthSizeClass` Compact). *Large* is a window whose horizontal size class is regular: iPad, Mac, and Android tablets and unfolded foldables (`WindowWidthSizeClass` Medium or Expanded). The class comes from the window's size class (SwiftUI `horizontalSizeClass`, Android `WindowSizeClass`), never from a device model or idiom check. A window that changes class (Split View, Stage Manager, window resize, fold or unfold, rotation) switches layout without losing the draft, the transcript scroll position or screen-reader focus.

The compact criteria are buildable now; the large-format criteria wait for `ANDROID-UX-15` (FB-LAYOUT) approval.

## Acceptance criteria

Shared wording, identical in `IOS-UX-F8`:

1. While connected, a mute control is visible next to the other voice controls. It is visually and positionally distinct from Interrupt and never shares its glyph or label.
2. Muting during a reply silences output at once. No interrupt is sent; reply text keeps streaming; the turn finishes normally and its phase and completion are unchanged.
3. While muted, incoming reply audio is still received and consumed, then dropped. Nothing is buffered for later playback, and end-of-reply does not wait for dropped audio to play.
4. Mute is sticky: it applies to every following reply until the user taps unmute.
5. [Proposed default, owner to confirm] Mute is not kept across app restarts; the app always launches unmuted.
6. [Proposed default, owner to confirm] Unmuting mid-reply resumes live audio from the current point; dropped audio is never replayed.
7. The muted state is always visible: the control shows a muted glyph and "Muted" appears in the status line. Screen readers read the control as "Mute Hermes" or "Unmute Hermes" and announce the new state once per change.
8. While muted, reply text is shown as it streams rather than waiting for speech that will not play.
9. Interrupt still works while muted and still ends the turn. Mute does not change capture, hands-free or barge-in rules, system volume, or other apps' audio.
10. Each mute change writes one content-free diagnostics journal line naming the new state and whether a reply was playing.
11. Large: the mute control sits with the small orb as placed by the approved FB-LAYOUT proposal; compact placement is next to the voice control.

## Android design notes

- Mute state lives in `HomeRuntime` (process-scoped, so it survives Activity recreation and is not persisted, matching criterion 5) and is read by the session client at the write gate.
- Journal line (AGENTS.md Diagnostics journal): `home audio mute state=on|off reply=playing|idle`, written once per change, never per frame, asserted in a JVM test through `RecordingJournal`.
- Audio focus: mute neither requests nor abandons focus (existing focus rules stay).
- Unmute mid-reply (criterion 6): the `AudioTrack` stays started while muted so the next written frame plays from the live point.

## Dependencies

`ANDROID-UX-15`.

## Test notes

JVM with `RecordingAudioSink` and `RecordingJournal` (`ANDROID-TEST-01` rules): no interrupt RPC while muted; frames merged but not written; `AudioChunkReceived` still delivered; muted reply drains and completes; sticky across turns; unmute writes from the live point; one journal line per change.

## Device verification

Pixel on speaker and headphones: mute mid-reply, reply text keeps streaming, next reply silent, unmute mid-reply resumes live; TalkBack labels.

## Release scope decision (2026-10-07)

`later` by default for a new feedback ticket; the owner may promote it to `migration`.
