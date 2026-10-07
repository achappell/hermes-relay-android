---
id: ANDROID-UX-05
title: Compact composer and bottom control bar recomposition
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-03
parity_stream: S7
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-01
  - android:ANDROID-UX-02
parity_source: 'PX-22 (matrix C9; design section 3.1 bottom control bar and empty/loading/error)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/101
---

# ANDROID-UX-05 — Compact composer and bottom control bar

Source: PX-22 (matrix C9; design section 3.1 bottom control bar and empty/loading/error) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Background

The iOS composer is a single field that grows to three lines, Return sends, an up-arrow send button, a "Recent prompts" menu, a cached-draft notice and a dismissible error banner, under the orb with a quiet "Keep listening" pill. Android's bottom bar stacks a field, a full-width "Start typed turn" button, "tap to speak", a hands-free control and several verbose block messages inside a column capped at 280 dp.

## Android today (checked against `main` unless marked [INFERENCE])

- `TypedComposerZone` (`DoorwayZones.kt:405-509`) uses an `OutlinedTextField` with no `KeyboardOptions`/`ImeAction` (grep), a text `Button` and hint `Text`s.
- Blocking reasons (`android_capture_block`, `android_send_blocked`, `android_cached_draft`) render in the bottom bar; `Scaffold.bottomBar` is a `Surface(tonalElevation = 2.dp)`.

## Required behavior

- Field grows 1-3 lines; `ImeAction.Send` sends; a compact icon send button; prompt history stays (steppers or a Recent menu as decided in the story spec).
- Keep listening is a Material 3 `FilterChip` (selected = identity fill with `onIdentity` ink, measured in `PaletteContrastTest`; iOS's white-on-`#7C8CFF` pill is 2.98:1 and is not to be copied).
- Dismissible error row; blocking reasons and the cached-draft notice move to the transcript rail's state card (`ANDROID-UX-04`), leaving at most one short supporting line in the bar.
- Bar is opaque `consoleSurface` with a hairline and 24 dp top corners, not an imitation of Liquid Glass; the bar is at most orb + composer row.

## Acceptance criteria

- Compose tests: IME Send submits once; Enter inserts nothing unexpected on hardware keyboards; send disabled with a reason exposed to TalkBack when blocked.
- Bottom bar height at 1.0x font scale is no more than the orb row plus one composer row; at 2.0x the rail remains usable (`ANDROID-HOME-11` matrix).
- `PaletteContrastTest` includes the chip's selected and unselected pairs.
- `AccessibilityOrderTest` passes; every control keeps a 48 dp minimum touch target.

## Android design notes

- Remove the second and third full-width text buttons; voice entry is the orb.
- Hide the orb while typing unless a reply or capture is active, matching iOS.

## Dependencies

`ANDROID-UX-01`, `ANDROID-UX-02`; rail state card from `ANDROID-UX-04`.

## Test notes

Instrumented Compose; JVM for the enabled/blocked reason function.

## Device verification

Pixel: type with the IME open at 1.0x and 2.0x, send via IME Send, toggle Keep listening, TalkBack.
