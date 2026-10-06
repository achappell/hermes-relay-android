---
id: ANDROID-UX-06
title: Collapsible connection details card
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-04
  - android:ANDROID-DIAG-01
parity_source: 'PX-23 (matrix C6)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/102
---

# ANDROID-UX-06 — Connection details card

Source: PX-23 (matrix C6) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P2, size S.

## Background

iOS shows a Home bridge status block (id `home-bridge-status`) with Route, Delivery, Audio, Timing (always "Absent in the pinned Standard baseline"), structured request, command events and Reason. It is low user value, but it is what a household owner reads aloud when something fails. Android has only `android_connection_label` and `android_audio_unavailable` fragments.

## Android today (checked against `main` unless marked [INFERENCE])

- No details view; pieces of the state exist in `AndroidClientSnapshot` and `AndroidTurnState`.

## Required behavior

- A collapsible "Connection details" card (collapsed by default) fed from runtime state: route (approved host only, no handles), delivery, audio capability, Timing text, last failure reason code and attempt count.
- Never shows handles, claim refs, tokens or content; reuses the same strings the journal uses.

## Acceptance criteria

- JVM test that the card model contains none of the forbidden substrings with a fixture holding secrets.
- Collapsed by default, expands with a state description for TalkBack, no live-region chatter.

## Android design notes

- Reuse in `ANDROID-DIAG-01`'s Troubleshooting section.

## Dependencies

`ANDROID-UX-04`, `ANDROID-DIAG-01`.

## Test notes

JVM model test; one Compose test.

## Device verification

Pixel: expand while connected and during a forced failure.
