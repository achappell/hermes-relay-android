---
id: ANDROID-UX-15
title: Propose the large-format whole-screen layout
status: backlog
product_epic: 1
release_scope: later
parity_epic: ANDROID-PARITY-03
parity_stream: S7
parity_tag: FB-LAYOUT
created: 2026-10-07
depends_on:
  - android:ANDROID-HOME-11
parity_source: 'User feedback 2026-10-07 (parity tag FB-LAYOUT); iOS/macOS twin IOS-UX-F9'
---

# ANDROID-UX-15 — Propose the large-format whole-screen layout

Source: user feedback, 2026-10-07. Parity tag `FB-LAYOUT`; iOS and macOS twin `IOS-UX-F9` in hermes-relay-ios.

## Background

iPad, Mac and Android large screens show the phone layout stretched or width-capped. The large-format behaviour of FB-THINK, FB-TYPE and FB-MUTE needs one approved whole-screen layout first. An investigation that produces one whole-screen proposal with mockups for the owner to approve. Compact criteria elsewhere stay buildable now.

**Parity:** tag `FB-LAYOUT`. iOS and macOS: `IOS-UX-F9` (hermes-relay-ios). Android: `ANDROID-UX-15` (hermes-relay-android). The acceptance criteria below are worded identically in both repos; only the platform notes differ. Mac and iPad stay the most alike.

## Android today (checked against `main` unless marked [INFERENCE])

Checked against `origin/main` (64f12cb): one single-pane `Scaffold` in `MainActivity.kt`; the only large-screen handling is the 720 dp width cap (`MainActivity.kt:497`, `:563`). No `WindowSizeClass` dependency. Related: `ANDROID-HOME-11` (reachable at every screen size) and `ANDROID-UX-02` (orb and HUD).

## Required behavior

**Layout classes (shared by FB-THINK, FB-TYPE, FB-MUTE, FB-LAYOUT).** *Compact* is a window whose horizontal size class is compact: iPhone, and an Android phone (`WindowWidthSizeClass` Compact). *Large* is a window whose horizontal size class is regular: iPad, Mac, and Android tablets and unfolded foldables (`WindowWidthSizeClass` Medium or Expanded). The class comes from the window's size class (SwiftUI `horizontalSizeClass`, Android `WindowSizeClass`), never from a device model or idiom check. A window that changes class (Split View, Stage Manager, window resize, fold or unfold, rotation) switches layout without losing the draft, the transcript scroll position or screen-reader focus.

This is an investigation; its output is the approved proposal, not code.

## Acceptance criteria

Shared wording, identical in `IOS-UX-F9`:

1. One written whole-screen proposal covers the large layout class on iPad, Mac and Android tablets and foldables, with a mockup per platform in voice view and typing view, during a reply with thinking text, and with mute on.
2. It places the transcript, composer, small orb, mute, Interrupt, thinking pane, status line and an optional conversations sidebar, and says what collapses when a large window narrows toward compact.
3. iPad and Mac use the same arrangement; Android large follows it, differing only where a platform convention requires (noted per item).
4. It gives screen-reader reading order, keyboard focus order and the keyboard shortcuts for voice/typing and mute on each platform.
5. It uses only the shared layout classes; nothing in it depends on device model or idiom.
6. The owner approves it in writing (date recorded in this spec); the large-format criteria of FB-THINK, FB-TYPE and FB-MUTE then follow it. Compact criteria do not wait for it.

## Starting wireframes (for the proposal to refine; not approved)

Compact (iPhone, Android phone), typing view:

```
+----------------------------------+
| Profile · Connected       [Mute] |
| transcript (full, compact)       |
|  You: ...                        |
|  Hermes: ...                     |
|                                  |
| [v] Thinking · latest 3 lines    |
| [o] [ Message Hermes...   ][Send]|
+----------------------------------+
  [o] = small voice control (returns to voice)
```

Large (iPad, Mac, Android tablet or unfolded foldable):

```
+-------------+--------------------------------+------------------+
| Conversa-   | Profile · Connected · 02:14    | Thinking         |
| tions       |                                | (accumulating,   |
| (optional,  | transcript (full, compact)     |  scrollable,     |
|  sidebar)   |  You: ...                      |  per turn)       |
|             |  Hermes: ...                   |                  |
|             |                                |------------------|
|             |                                | (o) orb, small   |
|             | [ Message Hermes...     ][Send]| [Mute] [Interrupt|
+-------------+--------------------------------+------------------+
```

Narrowing toward compact: the sidebar collapses first, then the thinking pane becomes the compact thinking area above the composer.

## Android design notes

- Android large is `WindowWidthSizeClass` Medium or Expanded; foldables follow the fold posture only where the proposal says so.
- The approved proposal is linked from this spec once it exists (it is written once for all platforms in hermes-relay-ios); Android-specific deviations are recorded here.

## Dependencies

`ANDROID-HOME-11`.

## Test notes

None; owner review of the proposal.

## Device verification

Mockups checked against a tablet and an unfolded foldable at Medium and Expanded widths.

## Release scope decision (2026-10-07)

`later` by default for a new feedback ticket; the owner may promote it to `migration`.
