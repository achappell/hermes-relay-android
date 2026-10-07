---
id: ANDROID-UX-13
title: Make Hermes's thinking text readable
status: backlog
experiment: true
product_epic: 1
release_scope: later
parity_epic: ANDROID-PARITY-03
parity_stream: S7
parity_tag: FB-THINK
created: 2026-10-07
depends_on:
  - android:ANDROID-UX-15
parity_source: 'User feedback 2026-10-07 (parity tag FB-THINK); iOS/macOS twin IOS-UX-F6'
---

# ANDROID-UX-13 — Make Hermes's thinking text readable

Source: user feedback, 2026-10-07. Parity tag `FB-THINK`; iOS and macOS twin `IOS-UX-F6` in hermes-relay-ios.

## Background

While Hermes thinks, its reasoning text flashes across the bottom bar one fragment at a time and is gone before it can be read. The user wants to actually read it. The owner wants to try a transcript-row design and judge it in use. An experiment: accumulate thinking text per turn in memory, show it as a collapsed row in the transcript, open it full-screen on tap (sheet on compact, pane on large), and announce it once to screen readers. Shared criteria below.

**Parity:** tag `FB-THINK`. iOS and macOS: `IOS-UX-F6` (hermes-relay-ios). Android: `ANDROID-UX-13` (hermes-relay-android). The acceptance criteria below are worded identically in both repos; only the platform notes differ. Mac and iPad stay the most alike.

## Android today (checked against `main` unless marked [INFERENCE])

Checked against `origin/main` (64f12cb). Android does not flash thinking text: it drops it.
- `HermesEventNormalizer.kt:275-276` maps `thinking`, `reasoning`, `thinking.delta`, `reasoning.delta` and `reasoning.available` to `AndroidNormalizedEvent.Thinking(binding)` with no text; `:263` (`message.start`) and `:358` (status starting `think`) do the same.
- `AndroidTurnState.kt:79-81`: `Thinking` carries only the binding; `:214-232` only advances the phase to `Thinking`.
- UI: `DoorwayZones.kt:1012-1024` shows the phase label ("Thinking", `:1446`) as a polite live region (`:1022`). No reasoning text reaches the screen.

## Required behavior

**Layout classes (shared by FB-THINK, FB-TYPE, FB-MUTE, FB-LAYOUT).** *Compact* is a window whose horizontal size class is compact: iPhone, and an Android phone (`WindowWidthSizeClass` Compact). *Large* is a window whose horizontal size class is regular: iPad, Mac, and Android tablets and unfolded foldables (`WindowWidthSizeClass` Medium or Expanded). The class comes from the window's size class (SwiftUI `horizontalSizeClass`, Android `WindowSizeClass`), never from a device model or idiom check. A window that changes class (Split View, Stage Manager, window resize, fold or unfold, rotation) switches layout without losing the draft, the transcript scroll position or screen-reader focus.

**One layout-class resolver.** The layout class is computed in exactly one place per app and read everywhere else; views never branch on platform or device. On macOS the resolver always returns large. (SDK check: `EnvironmentValues.horizontalSizeClass` is available on macOS 10.15+ per the macOS `SwiftUICore` swiftinterface in Xcode 27.2 beta 2, lines 22064-22066, but nothing there defines its value on macOS, so the resolver does not read it there.)

The compact criteria are buildable now; the large-format criteria wait for `ANDROID-UX-15` (FB-LAYOUT) approval.

## Acceptance criteria

Shared wording, identical in `IOS-UX-F6`:

**Experiment** (owner, 2026-10-07: "try it and see"). After the owner has used it, a follow-up review decides whether to keep, change or remove it; record the outcome in this spec.

1. Thinking text (`thinking.delta`, `reasoning.delta`, `reasoning.available`, and any `reasoning` carried on the completed message) accumulates in arrival order for the active turn. A new delta appends; it never replaces earlier thinking text.
2. Thinking text and status text are kept apart. A status update never overwrites or hides thinking text, and thinking text never appears inside the reply text.
3. Thinking appears as one collapsed row inside the transcript, in place for its turn (before that turn's reply). The row has a fixed height that does not grow, shrink or jump per delta, shows that Hermes is thinking or has thought, and stays in place when the reply arrives. It replaces the bottom-bar thinking line.
4. Tapping the row opens the full thinking text for that turn full-screen: a sheet or modal on compact; on large, a pane or panel placed by the approved FB-LAYOUT proposal (until approval, large windows use the compact sheet). While the turn is still thinking, the opened view accumulates live; it does not auto-scroll while the user has scrolled up. Closing it returns to the same transcript position.
5. Thinking text is held in memory only. It is never written to the stored transcript, so a relaunch or restored conversation shows no thinking rows for past turns.
6. Screen readers (VoiceOver, TalkBack) announce the row once when it appears ("Hermes is thinking"), never per delta. The accumulated text is read when the user opens the row, and the opened view is navigable line by line.
7. With Reduce Motion (iOS/macOS) or animations off (Android) the row and the opened view update without animated scrolling or expansion.
8. No thinking text reaches diagnostics, journal lines or logs. No Hermes protocol, Home or wire change.

## Android design notes

- Add a `text` field to `AndroidNormalizedEvent.Thinking` and keep accumulated thinking in `AndroidTurnState` (memory only), separate from `responseText`; `AndroidLocalHistory` never stores it.
- The row is a `LazyColumn` item in the conversation rail; the opened view is a full-screen `ModalBottomSheet` or dialog on compact. The row is not a live region; the existing phase-label live region (`DoorwayZones.kt:1022`) must not repeat the announcement.
- Large: needs a `WindowSizeClass` source (for example `androidx.compose.material3.adaptive`; dependency to be added by the build, not here).

## Dependencies

`ANDROID-UX-15`.

## Test notes

JVM: normalizer carries thinking text; turn state appends deltas in order, keeps status separate, nothing persisted. Compose UI test for row height stability and open/close.

## Device verification

Pixel phone: long-thinking turn row and opened sheet, TalkBack announces once; tablet or foldable after FB-LAYOUT approval.

## Release scope decision (2026-10-07)

`later` by default for a new feedback ticket; the owner may promote it to `migration`.
