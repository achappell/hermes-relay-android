---
id: ANDROID-UX-14
title: Switch to a typing-focused view when the composer is focused
status: backlog
product_epic: 1
release_scope: later
parity_epic: ANDROID-PARITY-03
parity_stream: S7
parity_tag: FB-TYPE
created: 2026-10-07
depends_on:
  - android:ANDROID-UX-02
  - android:ANDROID-UX-15
parity_source: 'User feedback 2026-10-07 (parity tag FB-TYPE); iOS/macOS twin IOS-UX-F7'
---

# ANDROID-UX-14 — Switch to a typing-focused view when the composer is focused

Source: user feedback, 2026-10-07. Parity tag `FB-TYPE`; iOS and macOS twin `IOS-UX-F7` in hermes-relay-ios.

## Background

The app opens in the voice view with a large orb. When the user wants to type, the orb keeps most of the screen and the transcript is a short recent rail. Typing should feel like a focused terminal session for reading and writing (TUI feel, not TUI features), while voice stays the default. Keep the voice view as the launch default; focusing the composer switches to a typing view and one action returns to voice. When to return to voice automatically is an owner decision, investigated below and not built yet.

**Parity:** tag `FB-TYPE`. iOS and macOS: `IOS-UX-F7` (hermes-relay-ios). Android: `ANDROID-UX-14` (hermes-relay-android). The acceptance criteria below are worded identically in both repos; only the platform notes differ. Mac and iPad stay the most alike.

## Android today (checked against `main` unless marked [INFERENCE])

Checked against `origin/main` (64f12cb).
- `MainActivity.kt:479-552`: the bottom bar always stacks `TypedComposerZone` (`:503`) above the capture zone (`:516-548`), capped at 280 dp (`:498`). The conversation is a `LazyColumn` (`:559-564`) capped at `widthIn(max = 720.dp)` (`:563`); that cap is the only large-screen handling.
- `DoorwayZones.kt:425-447`: the composer `OutlinedTextField` has a `FocusRequester` but no focus listener.
- There is no orb on `main`; it is `ANDROID-UX-02` (backlog). No `WindowSizeClass` dependency in `app/build.gradle.kts` or `gradle/libs.versions.toml`.

## Required behavior

**Layout classes (shared by FB-THINK, FB-TYPE, FB-MUTE, FB-LAYOUT).** *Compact* is a window whose horizontal size class is compact: iPhone, and an Android phone (`WindowWidthSizeClass` Compact). *Large* is a window whose horizontal size class is regular: iPad, Mac, and Android tablets and unfolded foldables (`WindowWidthSizeClass` Medium or Expanded). The class comes from the window's size class (SwiftUI `horizontalSizeClass`, Android `WindowSizeClass`), never from a device model or idiom check. A window that changes class (Split View, Stage Manager, window resize, fold or unfold, rotation) switches layout without losing the draft, the transcript scroll position or screen-reader focus.

The compact criteria are buildable now; the large-format criteria wait for `ANDROID-UX-15` (FB-LAYOUT) approval.

## Acceptance criteria

Shared wording, identical in `IOS-UX-F7`:

1. The app still opens in the voice view on every launch.
2. Focusing the composer (tap, or a hardware-keyboard focus or first keystroke) switches to the typing view: the transcript and the composer are front and centre, and the large orb shrinks to a small voice control. The draft, scroll position and recent prompts are unchanged.
3. The typing view shows the conversation as a full scrollable transcript at compact density (tighter spacing, no decorative chrome), newest at the bottom, anchored above the composer. The keyboard never covers the newest message or the composer.
4. One tap on the small voice control, or one keyboard shortcut on a hardware keyboard, returns to the voice view, dismisses the keyboard and does not start listening by itself.
5. Neither transition moves transcript content except for the space the orb gives up or takes back. With Reduce Motion or animations off the switch is instant.
6. Screen-reader focus stays in the composer when the typing view opens and moves to the voice control when the voice view returns; nothing else is announced.
7. The typing view adds no TUI features: no slash commands, monospace mode, key-chord editing or command palette.
8. No automatic return to voice is built until the owner chooses one from the investigation below; until then the typing view stays until the user returns to voice.
9. Large: the transcript and composer are always visible, the orb is a small toolbar or side control, and a conversations sidebar may be shown, as placed by the approved FB-LAYOUT proposal. Until that proposal is approved, large windows use the compact behaviour.

## Investigation: when to return to voice automatically (owner decision; not a build criterion)

| Option | Layout jumps | One action to voice | Keyboard dismissal | Screen-reader focus continuity |
|---|---|---|---|---|
| (a) Stay until the voice control is tapped | None unprompted | Yes, the voice control | User-driven (swipe, tap transcript, send) | Best: focus never moves on its own |
| (b) Composer empty and blurred | Frequent: scrolling or tapping the transcript dismisses the keyboard, which blurs an empty composer and flips the view while the user reads | Yes, but it also fires by accident | Coupled to the switch, so reading closes typing | Poor: focus is pulled to the orb mid-read |
| (c) Idle N seconds | Unprompted jump while the user is reading a long reply | Yes, but timed | Keyboard drops on a timer | Poor: focus moves with no user action |
| (d) The user speaks or uses a voice shortcut (Siri/App Shortcut, Android assistant or app shortcut, hands-free wake when already armed) | Only on an explicit voice action | Yes, the shortcut itself | Dismissed by the deliberate switch | Good: the user caused the move |

**Recommendation for the owner:** (a) plus (d). Stay in the typing view until the user taps the voice control, or starts voice another explicit way (a voice shortcut, or speech when hands-free is already armed). Reject (b) and (c): both turn reading the transcript, the very thing the typing view is for, into an accidental switch, and both move screen-reader focus without a user action. Owner decides; criterion 8 changes only after that decision.

## Android design notes

- Track composer focus with `onFocusChanged` on the `OutlinedTextField`; the view choice is UI state (`rememberSaveable`), not `HomeRuntime` state (AGENTS.md, Home runtime ownership).
- Until `ANDROID-UX-02` lands, the voice view is the current capture zone; the small voice control replaces it in the typing view.
- Criterion 4's shortcut on Android is a hardware-keyboard shortcut; Back with the keyboard open keeps its platform meaning (dismiss the keyboard).

## Dependencies

`ANDROID-UX-02`, `ANDROID-UX-15`.

## Test notes

Compose UI test: launch in voice view; composer focus enters typing view; one action returns; draft kept; semantics focus targets. Robolectric or instrumented as the repo already does for `AccessibilityOrderTest`.

## Device verification

Pixel phone with software keyboard; tablet with hardware keyboard; TalkBack focus on entry and return.

## Release scope decision (2026-10-07)

`later` by default for a new feedback ticket; the owner may promote it to `migration`.
