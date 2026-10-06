---
id: ANDROID-UX-11
title: Full-screen grouped Settings and device administration under Advanced
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-03
parity_stream: S7
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-01
  - android:ANDROID-HOME-13
parity_source: 'PX-30, PX-31 (matrix D4, D5, O8)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/107
---

# ANDROID-UX-11 — Settings restructure and device administration under Advanced

Source: PX-30, PX-31 (matrix D4, D5, O8) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Background

iOS settings are a grouped form: saved profiles, household devices, Home pairing, operator bridge, Home administration, relay endpoint, device identity, credentials, voice, automatic diagnostics, troubleshooting. Android renders the relay configuration form, the device-administration forms and the pairing section in one `ModalBottomSheet` (`heightIn(max = 720.dp)` plus `verticalScroll`, `MainActivity.kt:830-870`), including comma-separated free-text fields for rooms.

## Android today (checked against `main` unless marked [INFERENCE])

- `RelayConfigurationScreen.kt` (672 lines) and `HomeDeviceAdministration.kt` (1,235 lines, real enrollment/approve/consume/config/revoke forms) share the sheet.
- No Voice or Troubleshooting section; no version row (`ANDROID-REL-01`).

## Required behavior

- A full-screen Settings destination with grouped M3 `Card`s and section headers: Profiles, Home pairing (with `ANDROID-HOME-13`), Voice (`ANDROID-VOICE-02`/`-05`), Advanced (device administration, operator handle), Troubleshooting (version, Share diagnostics, details, automatic reports).
- The sheet remains only for pairing and confirmations; `AlertDialog` for Revoke and Unpair.
- Device administration moves behind Advanced with a short explanation; replace comma-separated fields with chips or pickers where the data is a list of known rooms.

## Acceptance criteria

- Every existing settings action is reachable (checklist test), and `RelayConfigurationTest` is updated, not weakened.
- Back and predictive back behave (`ANDROID-UX-09`); TalkBack order passes.
- No screen requires horizontal scrolling at 2.0x font scale.

## Android design notes

- Do not touch pairing protocol code; this is navigation and layout.

## Dependencies

`ANDROID-UX-01`, `ANDROID-HOME-13`; hosts `ANDROID-REL-01`, `ANDROID-DIAG-01/02`.

## Test notes

Instrumented checklist and order tests.

## Device verification

Pixel: walk every section at 1.0x and 2.0x.
