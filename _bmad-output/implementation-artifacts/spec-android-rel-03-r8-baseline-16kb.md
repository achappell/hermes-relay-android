---
id: ANDROID-REL-03
title: R8 shrinking, baseline profile, 16 KB page-size check and data-extraction rules
status: backlog
product_epic: 4
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S6
created: 2026-10-06
depends_on: []
parity_source: 'PX-35 (audit section 4: release signing and Play, app backup); re-scoped 2026-10-06 for sideloaded builds'
github_issue: https://github.com/achappell/hermes-relay-android/issues/94
---

# ANDROID-REL-03 — R8, baseline profile, 16 KB page size and data-extraction rules

Source: PX-35 (audit section 4: release signing and Play, app backup) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Scope decision (2026-10-06)

Play Store distribution stays out of scope (`ANDROID-REL-02` declined, `README.md` policy). This ticket therefore covers only what helps the **signed APK that GitHub Releases ships for sideloading**. Play-only requirements are dropped: no AAB, no Play App Signing, no Play pre-launch or policy checks, no "required by Play" justification. What remains is justified by the sideloaded build itself:

- **R8 shrinking** is kept: a smaller APK, but only if the minified build still behaves; the risk to manage is R8 removing or renaming classes the app needs (OkHttp/Okio, ZXing, CameraX), so the acceptance is correctness of the minified release APK, not size.
- **Baseline profile** is kept as an optimisation, and only if it measurably improves startup on a physical device.
- **16 KB page-size compatibility** is kept because it is a **runtime** requirement, not a store rule: on devices running Android 15+ with a 16 KB page size, a native library that is not 16 KB aligned may be unable to load. The app ships native libraries through CameraX, so a sideloaded APK can fail on such a device.
- **`dataExtractionRules`** is kept: Android 12+ device-to-device transfer consults it even with `allowBackup=false`; excluding all app data is a privacy safeguard that does not depend on how the APK is distributed.

## Background

The release build ships unshrunk and without a baseline profile, and has never been checked for 16 KB page-size compatibility. Android 12+ also expects `dataExtractionRules` for device-to-device transfer even when `allowBackup=false`.

## Android today (checked against `main` unless marked [INFERENCE])

- No `minify`/`shrink` setting in `app/build.gradle.kts`; no ProGuard files; no baseline profile; CameraX ships native libraries; ZXing is pure Java.
- `allowBackup=false`; no `dataExtractionRules`/`fullBackupContent` attribute in the manifest.
- `release.yml` builds the signed APK that is attached to the GitHub Release; `scripts/check-apk-metadata.sh` guards its version name and code.

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
- The release workflow still produces the same sideloadable signed APK asset name and signature; nothing in the change requires a Play account.

## Android design notes

- Keep rules minimal and commented; R8 full mode is on by default for AGP 9.

## Dependencies

None. (It was a prerequisite for the declined `ANDROID-REL-02`; that dependency no longer exists.)

## Test notes

Build checks in CI; smoke on a device with the minified APK.

## Device verification

Install the minified release APK on a Pixel and run: pair, QR scan, typed turn, spoken turn, lock-screen playback.
