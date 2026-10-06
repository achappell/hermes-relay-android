---
id: ANDROID-UX-10
title: Live reduced-motion tracking and a recorded TalkBack pass
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-02
  - android:ANDROID-UX-04
parity_source: 'PX-27 (matrix X2, X3; design section 3.3)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/106
---

# ANDROID-UX-10 — Live motion settings and TalkBack pass

Source: PX-27 (matrix X2, X3; design section 3.3) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size S.

## Background

Android is ahead of iOS on traversal order and contrast tests, but manual TalkBack, Switch Access and font-scale passes were deferred (`deferred-work.md` \"Manual TalkBack traversal and focus restoration\", the `5-A-3` scope note), and the motion mode is read once, so toggling animation scales while the app runs does not update it.

## Android today (checked against `main` unless marked [INFERENCE])

- `AndroidMotion.kt` resolves the mode from `Settings.Global.ANIMATOR_DURATION_SCALE`, `TRANSITION_ANIMATION_SCALE` and `WINDOW_ANIMATION_SCALE` once via `remember(context)`.
- Polite live regions exist on authorization, capture state and turn phase; they may over-announce while streaming [INFERENCE].

## Required behavior

- Observe the three settings with a `ContentObserver` (and honor `AccessibilityManager` where relevant) so the orb, rail scroll and transitions switch live.
- Live regions announce phase changes only, never every partial or timer tick.
- Run and record a manual TalkBack pass (and a Switch Access spot check) once the orb, header, rail and composer exist, at font scales 1.0 and 2.0.

## Acceptance criteria

- Unit test: motion mode changes when the fake settings source changes, without recomposition of unrelated state.
- Instrumented test: streaming a long reply produces a bounded number of announcements (assert count).
- `validation-android-ux-10.md` records the TalkBack walkthrough with pass/fail per screen and the device/TalkBack version.

## Android design notes

- Do not add per-event haptics; optional context-click haptics belong to `ANDROID-UX-12`.

## Dependencies

`ANDROID-UX-02`, `ANDROID-UX-04` (surfaces to test).

## Test notes

JVM and instrumented counts; manual pass.

## Device verification

Pixel with TalkBack: connect, speak, interrupt, disconnect, open settings.
