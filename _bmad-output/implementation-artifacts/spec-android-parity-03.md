---
id: ANDROID-PARITY-03
title: 'Android parity: design and polish'
status: backlog
product_epic: 1
kind: epic
created: 2026-10-06
depends_on:
  - android:ANDROID-PARITY-01
  - android:ANDROID-PARITY-02
github_issue: https://github.com/achappell/hermes-relay-android/issues/110
---

# ANDROID-PARITY-03 — Android parity: design and polish (epic)

Brings the Android UI to the iOS 0.7.0 Night Console design quality: the orb and ambient HUD, session header, transcript rail with reveal-with-voice, compact composer, iconography, tokens, adaptive layouts and accessibility verification. Source: `android-ios-parity-audit.md` section 3 (design rated roughly 30 %). Palette parity already exists (`Palette.kt` equals the iOS dark palette and is contrast-tested); the gap is that the conversation surface was never rebuilt around it (`android-visual-design-pass.md`, story `5-A-3`).

## Principles carried into every child

- Android-native adaptation of the brand, not a copy of iOS chrome: no Liquid Glass imitation, opaque `consoleSurface` bars with hairlines, M3 components, predictive back, dynamic colour stays off.
- Android's light palette values stay (they are AA-measured); the iOS light values are not ported.
- Every new surface joins `A11yOrder` and the `AccessibilityOrderTest`, and every colour pair joins `PaletteContrastTest`.
- All visual claims need device or emulator evidence; the audit had no emulator, so every Android "today" statement about rendering is an inference until a child's validation record shows a screenshot.
- Depends on the functional epic's P0 items: runtime owner (`ANDROID-HOME-07`) and holder split (`ANDROID-ARCH-01`) before the HUD.

## Ordering

### A. Foundations

Tokens and icons, and the holder split (functional epic).

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-UX-01`](https://github.com/achappell/hermes-relay-android/issues/97) | Night Console design tokens: spacing, shapes, typography, state washes and vector icons | P1 | M | PX-17 |
| [`ANDROID-ARCH-01`](https://github.com/achappell/hermes-relay-android/issues/83) | Decompose AndroidClientScreen into testable state holders | P0 | M | PX-02 |

### B. The surface

Orb and HUD, session header, transcript rail, composer and bottom bar. These four define the new conversation screen.

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-UX-02`](https://github.com/achappell/hermes-relay-android/issues/98) | Voice orb and nine-mode ambient HUD | P1 | L | PX-18 |
| [`ANDROID-UX-03`](https://github.com/achappell/hermes-relay-android/issues/99) | Session header card with status dot, profile, conversation title and duration | P1 | M | PX-19 |
| [`ANDROID-UX-04`](https://github.com/achappell/hermes-relay-android/issues/100) | Live transcript rail with reveal-with-voice and Resume live | P1 | L | PX-21 |
| [`ANDROID-UX-05`](https://github.com/achappell/hermes-relay-android/issues/101) | Compact composer and bottom control bar recomposition | P1 | M | PX-22 |

### C. Layout robustness

Reachability audit (PARITY-01), then adaptive layouts.

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-HOME-11`](https://github.com/achappell/hermes-relay-android/issues/75) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |
| [`ANDROID-UX-07`](https://github.com/achappell/hermes-relay-android/issues/103) | Adaptive layouts: window size classes, two-pane, landscape and table-top | P1 | M | PX-24 |

### D. Settings and states

Settings restructure, connection details, state surfaces.

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-UX-11`](https://github.com/achappell/hermes-relay-android/issues/107) | Full-screen grouped Settings and device administration under Advanced | P1 | M | PX-30, PX-31 |
| [`ANDROID-UX-06`](https://github.com/achappell/hermes-relay-android/issues/102) | Collapsible connection details card | P2 | S | PX-23 |
| [`ANDROID-UX-12`](https://github.com/achappell/hermes-relay-android/issues/108) | State surfaces, transitions and optional haptics | P2 | M | Design section 3.1 |

### E. Platform polish and verification

Copy/select, splash and predictive back, live motion and the TalkBack pass (last, on the finished surfaces).

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-UX-08`](https://github.com/achappell/hermes-relay-android/issues/104) | Copy conversation, per-message copy and selectable transcript text | P2 | S | PX-25 |
| [`ANDROID-UX-09`](https://github.com/achappell/hermes-relay-android/issues/105) | Splash screen, dark window background and predictive back | P2 | S | PX-26 |
| [`ANDROID-UX-10`](https://github.com/achappell/hermes-relay-android/issues/106) | Live reduced-motion tracking and a recorded TalkBack pass | P1 | S | PX-27 |

## Acceptance

All children `done` or deferred with a decision; a final screenshot set at font scale 1.0 and 2.0 (light and dark, phone and tablet) and the TalkBack record from `ANDROID-UX-10` are attached to this epic's validation record.
