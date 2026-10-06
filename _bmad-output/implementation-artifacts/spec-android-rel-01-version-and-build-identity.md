---
id: ANDROID-REL-01
title: Show the app version and build identity in settings and diagnostics
status: backlog
product_epic: 4
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S6
created: 2026-10-06
depends_on: []
ios_reference: 'iOS 19669cf, 89c40a4 (version label), 27139b6 (automated build numbers), docs/testflight.md'
github_issue: https://github.com/achappell/hermes-relay-android/issues/79
---

# ANDROID-REL-01 — App version and build identity

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): matrix R2 and the version part of D2. The Play internal-testing track (PX-34) is `ANDROID-REL-02`; R8, baseline profile and 16 KB checks (PX-35) are `ANDROID-REL-03`.

Smaller parity ticket for the iOS commits `19669cf`/`89c40a4` ("show app version in relay settings"), `27139b6` ("automate local app build numbers") and the TestFlight documentation in `docs/testflight.md` (PR #122, v0.7.0).

## Background

iOS device debugging over several builds (15–19, all marketing version 0.6.0) depended on knowing *which build* was installed: the journal reports carried no build SHA (`validation-ios-home-06.md` follow-up), and TestFlight/local builds were told apart only by `CFBundleVersion`. iOS now (a) shows `Version 0.6.0 (4)` in **Configure Relay → Troubleshooting**, read from the built app's `CFBundleShortVersionString` and `CFBundleVersion`, (b) stamps a monotonic per-user local build number on every local build so no two installed builds look alike, and (c) documents that CI supplies its own external build number (GitHub run number) and the TestFlight upload.

## Android today (verified against `main` at `3e10ae2`)

- The app shows no version anywhere: `RelayConfigurationScreen.kt` has no version row and `app/src/main` has no `BuildConfig`/`PackageManager` version read.
- `versionName` comes from `appVersionName` in `app/build.gradle.kts` (rewritten by release-please via the `x-release-please-version` marker); `versionCode = major*10000 + minor*100 + patch`, enforced by `scripts/check-apk-metadata.sh` (which fails if the APK's version name differs from the source or the code differs from the formula) and by `AGENTS.md` ("never pin it"). Two builds of the same version are indistinguishable by version alone.
- Distribution is a **signed APK attached to a GitHub Release** (`.github/workflows/release.yml`, `scripts/install-release.sh`), for sideloading. `README.md`: "Play Store distribution remains intentionally outside this repository"; the release notes say "Not a Play Store build." There is no AAB build and no Play track.

## Scope decision (recorded so the ticket is not over-read)

The TestFlight half of the iOS change has **no Android counterpart today by policy**. This ticket therefore scopes the Android equivalent to what it can deliver inside the current policy: a visible, copyable version/build identity. A Play internal-testing track is listed as an *optional follow-up that needs an explicit owner decision to reverse the README policy*; it is not part of this ticket's acceptance.

## Required behavior

1. **Settings label.** `Configure Relay → Troubleshooting` (or the nearest existing section of `RelayConfigurationScreen`) shows a read-only row, for example `Version 0.3.1 (301)`, from the installed package's `versionName` and `versionCode` (`PackageManager.getPackageInfo(...).longVersionCode`), computed once, selectable/copyable, with a stable test tag (iOS: accessibility identifier `app-version`) and a content description that reads naturally for TalkBack.
2. **Build identity beyond the version.** The row and the diagnostics header (`ANDROID-DIAG-01`) also carry the short git revision and build type (`debug`/`release`), e.g. `Version 0.3.1 (301) · release · 3e10ae2`, generated at build time (a Gradle `providers.exec` of `git rev-parse --short HEAD` into a `buildConfigField`; fall back to `unknown` when git is unavailable, such as a source tarball) without touching `versionName` or `versionCode`. Two local builds from different commits are therefore distinguishable on the device and in a shared journal; this is the Android substitute for iOS's per-user build counter.
3. **Do not change the versionCode contract.** `versionCode` stays derived from `versionName`; `scripts/check-apk-metadata.sh` keeps passing unchanged for debug and release APKs; no `versionNameSuffix` is added (it would fail that script).
4. **Docs.** `README.md` (or `docs/`) states where the label is, what each part means, and that same-version rebuilds are distinguished by the revision, not the code. Release process docs keep the "sideload APK" statement.

## Acceptance criteria

- JVM test for the label formatter (version/code/revision/type, `unknown` revision, long version code overflow guard) and a Compose/instrumented test that the row is displayed with the test tag and the expected text for a fake `PackageInfo`.
- `./gradlew testDebugUnitTest assembleDebug lintDebug` and `scripts/check-apk-metadata.sh` pass; the APK's manifest version name and code are unchanged from before this ticket.
- On a physical device: install two debug builds of the same version from different commits; the row differs only in the revision; Share diagnostics header shows the same string.
- The row never includes serials, accounts or IPs.

## Android design notes

- `buildFeatures { buildConfig = true }` is not enabled today; add it only for the two fields (`GIT_REVISION`, `BUILD_TYPE_LABEL`). Prefer reading `BuildConfig.DEBUG` for the type.
- iOS stamps `CFBundleVersion` from a counter file; do not port that. Android's installer already refuses downgrades by `versionCode` and allows same-code reinstall, so no counter is needed.
- The label must be computed from the installed package (not a hard-coded string) so it cannot drift from the APK that `check-apk-metadata.sh` verifies.

## Decision (2026-10-06): no Play follow-up

Play Store distribution stays out of scope per `README.md` ("Play Store distribution remains intentionally outside this repository"). `ANDROID-REL-02` is declined and closed as not planned. `ANDROID-REL-01` remains version/build identity only: it does not own an optional Play follow-up or change the signed-APK/sideloading release path.

## Dependencies

None. Feeds `ANDROID-DIAG-01` (header) and `ANDROID-DIAG-02` (`app_version`/`build`).

## Device verification

Read the row on the device; compare with `apkanalyzer manifest version-name/version-code` and `git rev-parse --short HEAD` for the installed commit.

## References

iOS: `docs/testflight.md` ("Local build numbers"), `scripts/stamp-ios-build-number.sh`, `RelayConfigurationView.swift` (`appVersionLabel`); Android: `app/build.gradle.kts`, `scripts/check-apk-metadata.sh`, `.github/workflows/release.yml`.
