---
id: ANDROID-UX-12
title: State surfaces, transitions and optional haptics
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-03
parity_stream: S8
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-01
  - android:ANDROID-UX-04
parity_source: 'Design section 3.1 (empty/loading/error, motion, haptics) and matrix O3 (confirmation-code card); not in the PX list'
github_issue: https://github.com/achappell/hermes-relay-android/issues/108
---

# ANDROID-UX-12 — State surfaces, motion and haptics polish

Source: Design section 3.1 (empty/loading/error, motion, haptics) and matrix O3 (confirmation-code card); not in the PX list (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P2, size M.

## Background

iOS gives each state its own empty caption ("Configure a relay to begin", "Connecting to Hermes…", "Tap the microphone to begin"), one prominent recovery action, a cached-conversation notice with an icon, press-scale and cross-fade motion, and shows the enrollment confirmation code (`AAAA-BBBB`) as a large monospaced card. Android swaps conditional `Text`s with no transitions, shows loading as text, and renders the confirmation code inline.

## Android today (checked against `main` unless marked [INFERENCE])

- `DoorwayStateZone` is a card with title, description and one `Button`; conversations and approvals show text "Loading…"; no `AnimatedContent`, no `CircularProgressIndicator` loading states, no haptics (grep).

## Required behavior

- Per-state empty captions and a single recovery action per state; the cached-conversation notice with an icon.
- `AnimatedContent`/`animateColorAsState` (200-300 ms) for mode changes and `animateContentSize` for cards, all gated by `AndroidMotionMode`; press scale 0.94 over 120 ms.
- Progress indicators for Conversations/Approvals loading.
- Pairing confirmation code in a large monospaced card with a Copy action.
- Optional context-click haptics on capture start/stop and interrupt only, behind a setting that defaults on and respects system haptic settings; none on streaming events.

## Acceptance criteria

- Every `AndroidDoorwayState` has a test for caption and primary action; the confirmation-code card is selectable and announced once.
- With animation scales 0 nothing animates (instrumented).
- No haptic fires from the streaming path (unit test on the event handler).

## Android design notes

- Keep copy in `strings.xml` (all UI strings are externalised today).

## Dependencies

`ANDROID-UX-01`, `ANDROID-UX-04`.

## Test notes

JVM state-to-caption table; instrumented motion gating.

## Device verification

Pixel: walk unconfigured, unavailable, connecting, cached states; pair with a code.
