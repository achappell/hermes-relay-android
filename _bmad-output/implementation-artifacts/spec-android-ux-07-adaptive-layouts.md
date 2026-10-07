---
id: ANDROID-UX-07
title: 'Adaptive layouts: window size classes, two-pane, landscape and table-top'
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-03
parity_stream: S7
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-02
  - android:ANDROID-HOME-11
parity_source: 'PX-24 (matrix X6; design section 3.2); the scroll/inset audit part is ANDROID-HOME-11'
github_issue: https://github.com/achappell/hermes-relay-android/issues/103
---

# ANDROID-UX-07 — Adaptive layouts for large screens, landscape and fold postures

Source: PX-24 (matrix X6; design section 3.2); the scroll/inset audit part is ANDROID-HOME-11 (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Background

iOS caps content widths (900/760/680) and uses `ViewportFillLayout` plus a `CompressibleOrb` (0.6-1.0) so the HUD fits short screens and scrolls otherwise (PR #123). `ANDROID-HOME-11` proves reachability on the current layout. This ticket designs the layouts that use space well: stacked on compact, two-pane on expanded.

## Android today (checked against `main` unless marked [INFERENCE])

- Single column with `widthIn(max = 720.dp)`; no `WindowSizeClass`, no `material3-adaptive`, no landscape or posture handling (grep); `window` and `adaptive` libraries are absent from `libs.versions.toml`.

## Required behavior

- Use `currentWindowAdaptiveInfo()`: Compact keeps the stacked HUD; Medium/Expanded and landscape use two panes (orb and status on one side, transcript rail on the other); Conversations can be a permanent side pane on Expanded.
- Table-top posture on foldables: orb above the fold, rail below.
- Orb compresses from 1.0 to 0.6 before any scrolling starts; content widths capped at 900/760/680 dp by class.
- No content is reachable only by rotating the device.

## Acceptance criteria

- Compose tests per class (360x640, 411x914, 600x960 tablet, 840x1280, landscape phone) assert no overlap with bars and rail reachability (reusing the `ANDROID-HOME-11` matrix).
- Orb compression is a pure function of available height with JVM tests (1.0 above threshold, 0.6 floor, monotonic).
- Screenshots on a tablet and a foldable emulator/device attached to the validation record; do not claim foldable posture from a phone.

## Android design notes

- Add `androidx.compose.material3.adaptive` and `androidx.window` to the version catalog.

## Dependencies

`ANDROID-UX-02`, `ANDROID-HOME-11`.

## Test notes

JVM compression function; instrumented per size class.

## Device verification

Tablet and (if available) Pixel Fold: two-pane, rotation, split screen.
