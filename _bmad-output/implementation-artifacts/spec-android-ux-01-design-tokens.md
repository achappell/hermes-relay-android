---
id: ANDROID-UX-01
title: 'Night Console design tokens: spacing, shapes, typography, state washes and vector icons'
status: review
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-03
parity_stream: S7
created: 2026-10-06
depends_on:
  - android:5-A-3
parity_source: 'PX-17 (design section 3.1: color, state roles, typography, spacing/shape, iconography)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/97
---

# ANDROID-UX-01 — Design tokens: spacing, shapes, typography, state washes and icon set

Source: PX-17 (design section 3.1: color, state roles, typography, spacing/shape, iconography) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Background

The Android palette already equals the iOS dark palette (`Palette.kt`, `StateColors.kt`, contrast-tested by `PaletteContrastTest`, 56 pairs). What is missing is everything around colour: iOS uses a 4/8/12/16/24/32 spacing rhythm, 16 pt cards with a 0.5 pt hairline, a 24 pt bottom bar, semantic type styles, 16 % state washes and SF Symbols. Android uses ad hoc `dp` literals, default Material 3 typography and shapes, and no icons at all, so the UI reads as a form rather than the brand.

## Android today (checked against `main` unless marked [INFERENCE])

- No custom `Typography`, no font family, no `Shapes` (grep of `ui/theme`); `Theme.kt` wires colour schemes only.
- No `Icon`, `ImageVector` or `painterResource` in `app/src/main`; the overflow menu is the text glyph "⋮" (`DoorwayZones.kt:175`); every control is a text button.
- State roles exist (`LocalHermesStateColors`: live, attention, identity, unavailable) but are used mostly as text colour; there are no wash fills or tinted surfaces.

## Required behavior

- Add `HermesSpacing` (4/8/12/16/24/32), `HermesShapes` (card 16 dp with a 0.5 dp outline at 22 % alpha, bar 24 dp, field 18 dp) and `HermesTypography` mapping the iOS semantic styles to Material roles in `sp` (orb status SemiBold titleMedium; header labelLarge; caption/footnote bodySmall/labelSmall; callout bodyLarge 16 sp; transcript title3 20 sp/28 lineHeight; role label labelSmall Bold, uppercase, 1 sp tracking). All sizes in `sp`.
- Add state wash tokens (`live`/`attention`/`identity`/`unavailable` at 16 % over the surface) and use them for notices and state cards. Scope change 2026-10-07: the header dot moves to `ANDROID-UX-03`, which creates it.
- Add a small vector icon set (about 16: mic, waveform, thinking dots, buffering, speaker, check, pause, warning, hand (interrupt), ear (listen), stop, settings, history, home, diagnostics, QR) as `ImageVector`s or drawables; avoid `material-icons-extended`.
- Replace the "⋮" text with an icon button with a content description; do not restyle anything else in this ticket beyond adopting tokens in the existing surfaces.
- Extend `PaletteContrastTest` with every text/wash pair, including text on the 12 % HUD gradient tint (blend the base with each state tint).

## Acceptance criteria

- Tokens are the only source of spacing, radius and type in the touched files (lint-style grep test for raw `dp` literals in the migrated composables is acceptable).
- `PaletteContrastTest` covers the new wash and gradient pairs and still enforces 4.5:1; a known-bad wash pair is rejected by the guard test.
- Screenshot (physical or emulator) of the home state before/after at font scale 1.0 and 2.0; no text clipped at 2.0.
- `AccessibilityOrderTest` unchanged and passing; all icons have `contentDescription` or are marked decorative.

## Owner decision: pre-existing header clipping

Owner approval (2026-10-07): “Exempt pre-existing header clipping from #127 acceptance.” This independent exception is limited to the session-header clipping documented in the UX-01 device pass, reproduced unchanged on `main`, and tracked for correction under ANDROID-UX-03 (#134) with ANDROID-HOME-11 reachability coordination. It does not waive physical TalkBack; the separate decision below does.

## Owner decision: manual physical TalkBack acceptance waiver

Amanda (2026-10-07) waived manual physical TalkBack spoken-output/focus-gesture acceptance for PR #127. Spoken output and focus gestures remain unverified, with known residual spoken-label, focus-order and gesture risk; automated semantics/focus-order checks are not a manual TalkBack pass. This supersedes the historical required/open TalkBack gate, not the independent header-clipping exemption or any other acceptance criterion.

Delivery status is `review` (ready for review after final exact-head CI succeeds), not `done` or fully TalkBack verified. Existing automated and font-scale/light/dark device evidence retains its recorded scope; no new device scenarios or implementation changes are required by this waiver.


## Android design notes

- Dynamic colour stays off (contrast guarantees); light-theme values stay Android's tested ones. The audit found the iOS light palette fails AA in places; do not port iOS light hex values (the iOS findings are routed to the iOS repo, not here).
- Keep tokens as plain Kotlin objects next to `Palette.kt` so the contrast test can read them.

## Dependencies

`5-A-3` (palette, done). Blocks `ANDROID-UX-02`..`-06`, `-11`, `-12`.

## Test notes

JVM contrast and token tests; instrumented screenshot is manual.

## Device verification

Pixel at font scale 1.0 and 2.0, light and dark: icons render, nothing clipped, contrast readable outdoors.
