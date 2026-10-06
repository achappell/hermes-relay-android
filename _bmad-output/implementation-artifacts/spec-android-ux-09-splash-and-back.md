---
id: ANDROID-UX-09
title: Splash screen, dark window background and predictive back
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-03
parity_stream: S8
created: 2026-10-06
depends_on: []
parity_source: 'PX-26 (matrix X9; design section 3.2; audit section 4 predictive back)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/105
---

# ANDROID-UX-09 — Splash, window background and predictive back

Source: PX-26 (matrix X9; design section 3.2; audit section 4 predictive back) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P2, size S.

## Background

`Theme.HermesRelay` inherits `android:style/Theme.Material.NoActionBar`, which is light, so a dark-mode launch can flash a light window before Compose draws [INFERENCE: not observed, no emulator was available to the audit]. The manifest has no `enableOnBackInvokedCallback`, and sheets and settings have no `BackHandler`.

## Android today (checked against `main` unless marked [INFERENCE])

- `res/values/themes.xml` has the single light parent theme; `core-splashscreen` is not a dependency; no `BackHandler`/`PredictiveBackHandler` in `app/src/main` (grep).

## Required behavior

- `Theme.SplashScreen` with window background `#0B101B` in dark and the light canvas in light, plus the adaptive icon; no flash on cold or warm start.
- `android:enableOnBackInvokedCallback="true"` and back handling for every sheet and the settings destination so predictive back previews and returns correctly.

## Acceptance criteria

- Cold start recorded on a device in both uiModes shows no light flash (screen recording attached).
- Back from each sheet dismisses only that sheet; back from the main screen follows platform behavior; instrumented back tests for configuration, conversations, approvals and history.

## Android design notes

- Verify the API 36 default for the predictive-back attribute before relying on it [INFERENCE].

## Dependencies

None.

## Test notes

Instrumented back tests.

## Device verification

Pixel: cold start light and dark, gesture-back preview on each sheet.
