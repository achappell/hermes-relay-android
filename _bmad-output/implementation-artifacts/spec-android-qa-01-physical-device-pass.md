---
id: ANDROID-QA-01
title: Physical-device QA pass with a validation record
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S6
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-08
  - android:ANDROID-VOICE-01
  - android:ANDROID-HOME-09
  - android:ANDROID-DIAG-01
parity_source: 'PX-36 (audit risk 1)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/95
---

# ANDROID-QA-01 — Physical-device QA pass and validation record

Source: PX-36 (audit risk 1) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Background

Every Android Home result so far is emulator evidence against live Home; the physical-Pixel pass was waived by Amanda for `ANDROID-HOME-02` on 2026-09-25 ("The emulator is good enough"), and a spoken Home turn has never been confirmed. `AGENTS.md` records that the emulator has no microphone, no speech pack and no audio route, and that every 2026-09-12 defect passed the unit suite and live gate first. iOS 0.7.0 itself shows the cost: three builds of background audio fixes were guesses until the journal named the cause.

## Android today (checked against `main` unless marked [INFERENCE])

- `validation-android-home-02.md` waives the physical-Pixel pass; `validation-5-a-4-live-home-gate.md` covers a signed Pixel run with four live branches but not voice, lock screen or route changes.

## Required behavior

- Run and record a checklist on a physical Pixel (and a second OEM device if available): spoken turn end to end; QR scan with the real camera; lock-screen reply playback and controls; Bluetooth headset connect/disconnect mid-reply; phone-call interruption; Doze (30 min idle) then talk; rotation and font-scale change mid-reply; 2.0x font-scale layout; Share diagnostics; version label.
- Record device model, Android version, build identity (`ANDROID-REL-01`), what was and was not exercised, and any failure with its journal excerpt, in `validation-android-qa-01.md`; a verdict of `done-with-environment-limitation` is acceptable and preferred over overstating.
- File a defect per failure; do not fix inside this ticket.

## Acceptance criteria

- The validation record contains one pass/fail/not-run row per checklist item with evidence references (journal excerpts, screenshots) and no content (prompts, replies, handles).
- Items that need a ticket's code (for example lock-screen controls) are marked blocked-by with the ticket ID, not failed.

## Android design notes

- Run after `ANDROID-HOME-08`, `ANDROID-HOME-09` and `ANDROID-VOICE-01` land; repeat for the release candidate.

## Dependencies

`ANDROID-HOME-08`, `ANDROID-HOME-09`, `ANDROID-VOICE-01`, `ANDROID-DIAG-01`.

## Test notes

Manual, with the checklist.

## Device verification

This ticket is the device verification.
