---
id: ANDROID-REL-03
title: R8 shrinking, baseline profile, 16 KB page-size check and data-extraction rules
status: backlog
product_epic: 4
created: 2026-10-06
depends_on: []
parity_source: 'PX-35 (audit section 4: release signing and Play, app backup)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/94
---

# ANDROID-REL-03 — R8, baseline profile, 16 KB page size and data-extraction rules

Source: PX-35 (audit section 4: release signing and Play, app backup) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Background

The release build ships unshrunk and without a baseline profile, and has never been checked for 16 KB page-size compatibility, which current Play requirements ask for on API 35+ targets. Android 12+ also expects `dataExtractionRules` for device-to-device transfer even when `allowBackup=false`.

## Android today (checked against `main` unless marked [INFERENCE])

- No `minify`/`shrink` setting in `app/build.gradle.kts`; no ProGuard files; no baseline profile; CameraX ships native libraries; ZXing is pure Java.
- `allowBackup=false`; no `dataExtractionRules`/`fullBackupContent` attribute in the manifest.

## Required behavior

- Enable R8 for release with keep rules for OkHttp/Okio, ZXing and CameraX where needed; verify QR scan, pairing, a turn and audio on the minified build.
- Add a baseline profile (macrobenchmark module or generated profile) and measure startup; keep it only if it measurably helps.
- Add an ELF alignment check (`check_elf_alignment`-style) for 16 KB page size to CI or the metadata script, covering CameraX native libs.
- Add `android:dataExtractionRules` excluding all app data; re-confirm `allowBackup=false`.

## Acceptance criteria

- `./gradlew assembleRelease` with R8 passes `scripts/check-apk-metadata.sh`; a signed minified APK passes the Home pairing and a typed turn on a device.
- Alignment check fails on a deliberately misaligned `.so` and passes on the build.
- Startup time before/after is recorded with the tool and device model.
- Lint reports no new warnings.

## Android design notes

- Keep rules minimal and commented; R8 full mode is on by default for AGP 9.

## Dependencies

None. Prerequisite for `ANDROID-REL-02`.

## Test notes

Build checks in CI; smoke on a device with the minified APK.

## Device verification

Install the minified release APK on a Pixel and run: pair, QR scan, typed turn, spoken turn, lock-screen playback.
