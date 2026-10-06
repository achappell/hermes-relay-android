---
id: ANDROID-ENTRY-01
title: Launcher shortcut and Quick Settings tile spike
status: backlog
product_epic: 1
created: 2026-10-06
depends_on: []
parity_source: 'PX-37 (audit section 4, home-screen entry points)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/96
---

# ANDROID-ENTRY-01 — Launcher shortcut and Quick Settings tile spike

Source: PX-37 (audit section 4, home-screen entry points) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P2, size S.

## Background

The audit lists optional Android entry points beyond the launcher icon: a Quick Settings tile ("Talk to Hermes"), a launcher shortcut ("New conversation"), a voice-assistant role and a Glance widget. A product decision is needed before any build. Note the platform constraint: the microphone cannot start from a tile or widget without opening the Activity.

## Android today (checked against `main` unless marked [INFERENCE])

- No `TileService`, `shortcuts.xml`, assistant role or widget in the manifest; one launcher Activity with a `hermes-home://pair` link filter.

## Required behavior

- A one-page decision record: which entry points are wanted, what each opens (always the Activity; never a background mic start), how each behaves when no Profile is configured or Home is unpaired, and the Play policy impact (assistant role).
- If any is approved, split a follow-up implementation ticket; this ticket ships no code.

## Acceptance criteria

- `validation-android-entry-01.md` states the decision and the rejected options with reasons.

## Android design notes

- Static launcher shortcuts are the cheapest and need no service; a tile needs `TileService` and `startActivityAndCollapse` on API 34+ with a `PendingIntent`.

## Dependencies

None.

## Test notes

None (decision record).

## Device verification

None.
