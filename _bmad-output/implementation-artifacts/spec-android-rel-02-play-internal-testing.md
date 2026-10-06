---
id: ANDROID-REL-02
title: 'Play internal testing: AAB, Play App Signing, Data safety and foreground-service declarations'
status: backlog
product_epic: 4
created: 2026-10-06
depends_on:
  - android:ANDROID-REL-01
  - android:ANDROID-HOME-08
parity_source: 'PX-34 (matrix R1)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/93
---

# ANDROID-REL-02 — Play internal testing track (owner-gated)

Source: PX-34 (matrix R1) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Background

iOS ships to TestFlight from CI. Android ships a signed APK to GitHub Releases for sideloading, and `README.md` states "Play Store distribution remains intentionally outside this repository". The household members' phones would get a normal install/update path and Play's foreground-service review only through an internal-testing track. This ticket is **owner-gated**: it must not start until the owner reverses that README policy. `ANDROID-REL-01` records the same gate.

## Android today (checked against `main` unless marked [INFERENCE])

- `release.yml` builds and attaches a signed APK; no `bundleRelease`, no AAB, no Play upload, no R8 (`release` buildType only selects signing).
- `versionCode` is derived from `versionName` and enforced by `scripts/check-apk-metadata.sh`; Play requires a strictly increasing code per upload, which collides with that rule.
- CI compiles and targets API 37, published as the "37.0" preview platform; [INFERENCE] Play does not accept uploads that target a preview SDK.

## Required behavior

- Owner decision recorded first (README policy, signing: Play App Signing with an upload key versus the current developer keystore).
- `bundleRelease` AAB, a protected `play-internal` environment holding upload credentials as secrets (never printed or committed), a workflow job after packaging, and a documented process in `README.md`.
- A versionCode scheme that is strictly increasing per upload and still passes (or deliberately replaces) `check-apk-metadata.sh`; the replacement is part of this ticket and updates `AGENTS.md` ("never pin it").
- Play Console prerequisites recorded in the validation file: Data safety form (microphone audio processed on device and not collected; camera for QR; no analytics), privacy-policy URL, foreground-service type declarations with demo video for `mediaPlayback` (and `microphone` if shipped), target API that Play accepts, 16 KB page-size compatibility (`ANDROID-REL-03`).

## Acceptance criteria

- A tagged release produces an AAB and uploads it to the internal track from a dry-run branch; the household device installs and updates from Play.
- `check-apk-metadata.sh` (or its replacement) still guards version/code consistency.
- No secret appears in logs; the upload job is gated by an environment approval.
- Data safety answers match actual behavior (audited against `ANDROID-VOICE-01` if online recognition is offered).

## Android design notes

- Consider keeping the GitHub APK path for sideloading; do not remove it in this ticket.
- Check `compileSdk`/`targetSdk` against Play's current requirement before the first upload.

## Dependencies

Owner decision; `ANDROID-REL-01`; `ANDROID-HOME-08` (service types to declare); `ANDROID-REL-03`.

## Test notes

Workflow dry run; local `./gradlew bundleRelease` and `bundletool` validation.

## Device verification

Install from the internal track on a physical device; update over it with the next build.
