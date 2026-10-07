---
id: ANDROID-UX-03
title: Session header card with status dot, profile, conversation title and duration
status: in-progress
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-03
parity_stream: S7
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-01
  - android:ANDROID-HOME-12
parity_source: 'PX-19 (matrix C1)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/99
validation: _bmad-output/implementation-artifacts/validation-android-ux-03.md
---

# ANDROID-UX-03 — Session header card

Source: PX-19 (matrix C1) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Background

The iOS header is a card: status dot, Profile name, session title (tap opens Conversations), `Hermes Profile · <status> · mm:ss` with a one-second timer, and a settings gear. The Android top bar shows a title, a two-line description, the Profile name, "Authorization: Verified" and a text overflow.

## Android today (checked against `main` unless marked [INFERENCE])

- `DoorwayHeaderZone` (`DoorwayZones.kt:87`) is a `TopAppBar` with title, `maxLines = 2` description and an actions row (profile label/name, menu).
- No status dot, no timer, no conversation title, no tap target for Conversations other than the menu.

## Current header reachability delivery

This PR addresses clipping in the existing `DoorwayHeaderZone` as the first UX-03 delivery. It stacks the Profile block below the title/description and keeps the existing overflow menu as a dedicated trailing action. The header grows vertically instead of dropping text or the menu; the broader screen/scroll matrix remains owned by ANDROID-HOME-11.

This does not complete the planned `SessionHeaderCard`: the status dot, elapsed timer, title-to-Conversations action, settings gear, and the final two-line card layout remain open UX-03 acceptance.

## Required behavior

- Replace the top bar content with a compact `SessionHeaderCard`: status dot in a state colour, Profile name, conversation title (tap opens the Conversations sheet), status text plus elapsed time while connected, settings icon, and the Disconnect action from `ANDROID-HOME-12`.
- One polite live region announces status changes only (not the timer tick).
- Keeps traversal order HEADER, PROFILE, STATE as today.
- Long names truncate to a single line with ellipsis and are fully available to TalkBack; the card never exceeds 2 lines at 2.0x font scale (coordinate with `ANDROID-HOME-11`).

## Acceptance criteria

- State-to-dot/colour/text table with JVM tests; timer formatting tests (mm:ss, h:mm:ss) with a fake clock.
- `AccessibilityOrderTest` passes with the new header; the live region fires on status change and not per second (instrumented test).
- Header remains reachable and unclipped at font scale 2.0 (`ANDROID-HOME-11` matrix row).
- Tapping the title opens Conversations; the settings icon opens settings.

## Android design notes

- Use M3 `Surface`/`ListItem` and the tokens/icons from `ANDROID-UX-01`; no iOS-style inline nav title.
- The timer reads the runtime's connection start time (`ANDROID-HOME-07`) so it survives recreation.

## Dependencies

`ANDROID-UX-01`; `ANDROID-HOME-12` for Disconnect.

## Test notes

JVM for table and timer; instrumented for order and live-region behavior.

## Device verification

Pixel: header at 1.0x and 2.0x, long Profile name, TalkBack announcements while connecting and disconnecting.
