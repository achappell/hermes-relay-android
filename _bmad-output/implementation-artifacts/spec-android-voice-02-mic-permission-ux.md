---
id: ANDROID-VOICE-02
title: Microphone permission rationale and Open Settings after permanent denial
status: done
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S3
created: 2026-10-06
depends_on: []
parity_source: 'PX-13 (matrix V3)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/86
---

# ANDROID-VOICE-02 — Microphone permission rationale and Open Settings

## Owner acceptance — 2026-10-07 CDT / 2026-10-08 UTC

Amanda explicitly selected **option 2**, accepting existing automated/instrumented evidence and waiving both remaining manual checks: **deny microphone twice → Open settings → grant → return → capture**, and **physical TalkBack permission-state/action speech**. Status is **done under owner acceptance**. Both manual legs remain **unverified, waived, not passed**. No permission/device action was performed for this closeout; the waiver does not extend to HOME-12, HOME-13 or any other story. See [validation](validation-android-voice-02.md).

Source: PX-13 (matrix V3) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size S.

## Background

iOS shows a combined microphone and speech request and, after denial, an "Open Settings" button. On Android, after the user picks "Don't allow" twice (or once with "Don't ask again"), the platform no longer shows the dialog, so the current "Allow microphone" button silently does nothing and voice is a dead end.

## Android today (checked against `main` unless marked [INFERENCE])

- `DoorwayZones.kt:687` launches `ActivityResultContracts.RequestPermission` for `RECORD_AUDIO`; there is no `shouldShowRequestPermissionRationale` check and no `ACTION_APPLICATION_DETAILS_SETTINGS` intent (grep).
- `MicrophonePermission` state is recomputed from `permissionRevision`.

## Required behavior

- Distinguish four states: not yet asked, granted, denied with rationale available, permanently denied.
- Rationale state: one sentence on why ("Hermes transcribes your speech on this device"; adjust if `ANDROID-VOICE-01` allows online recognition) and the request button.
- Permanently denied state: explain and offer **Open settings**, which opens the app details screen; re-check on `ON_RESUME`.
- Never nag: no request without a user action.

## Acceptance criteria

- Unit tests of the state function with a fake permission/rationale source for all four states.
- Instrumented test: granting via `GrantPermissionRule` enables capture; denial shows rationale; a permanently denied fake shows Open settings and its `Intent` action equals `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with the package URI.
- TalkBack reads the state and the action (extend `AccessibilityOrderTest`).

## Android design notes

- Rationale heuristic: `shouldShowRequestPermissionRationale == false` after a prior request means permanently denied. Persist "has asked" in preferences to tell it from "never asked".
- The camera permission for QR scanning has the same shape (`HomePairingScanner.kt:96`); reuse the helper there.

## Dependencies

None.

## Test notes

JVM state function; one instrumented test.

## Device verification

Pixel: deny twice, observe Open settings, grant in Settings, return, capture works.
