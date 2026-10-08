---
story: ANDROID-REL-01
spec: spec-android-rel-01-version-and-build-identity.md
status: done
story_status: done
updated: 2026-10-07
---

# ANDROID-REL-01 validation record

Accepted under the owner's 2026-10-07 review-closeout authorization. All specified acceptance criteria are covered by the retained implementation tests and the actual two-commit Pixel row/share-header comparison below. No release signing or distribution policy change was authorized or needed.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM) | Passed | Below |
| `versionName`/`versionCode` unchanged; `check-apk-metadata.sh` | Passed | Below |
| Instrumented row test on a device | **Passed**, Pixel 6a, Android 17 (API 37) | `AppVersionRowTest` 2/2 |
| Two builds from different commits differ only in the revision on the device | **Passed** | Actual installed `f11f62d` and `98ee229` debug builds: same 0.3.1 / 301 / debug, distinct revisions; real shared headers agree |
| Row visible in the real configuration sheet | **Passed** | Configure relay → Troubleshooting opened on the Pixel for each build; actual row and actual Share diagnostics export read |

## What changed

- `AppBuildIdentity` (version name, long version code, `debug`/`release`, short git revision) with `label()` = `Version 0.3.1 (301) · release · 3e10ae2`, built from the installed `PackageInfo` (`PackageInfoCompat`) plus `BuildConfig`, so it cannot drift from the APK. Raw values are normalised: a revision that is not a 4-40 character lowercase hex string reads `unknown`; a negative version code reads 0; the version name is capped at 32 characters.
- `app/build.gradle.kts`: `buildFeatures.buildConfig = true` (only for `GIT_REVISION`; `DEBUG` is generated); `GIT_REVISION` from `providers.exec { git rev-parse --short HEAD }`, `unknown` when git is unavailable. `versionName`, `versionCode` and `versionNameSuffix` are untouched.
- Configuration sheet, Troubleshooting section: a selectable row (`app-version` test tag, TalkBack description "App version ...") above Share diagnostics. Shown with or without a Profile.
- `DiagnosticsHeader` adds `build_type` and `revision`, so a shared journal names the exact build.
- `README.md`: where the row is and what each part means; same-version rebuilds are told apart by the revision, not the code; releases remain a signed APK to sideload.

## Local gate

- `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon`: passed; 314 unit tests, 0 failures (307 on the DIAG-01 branch + 7 `AppBuildIdentityTest`: release/debug label, same version differing only in revision, missing/odd revision, long and negative version code, bounded name).
- `scripts/check-apk-metadata.sh`: passed. `apkanalyzer manifest version-name/version-code` on the debug APK: `0.3.1` / `301`, unchanged.
- `git diff --check`, tracking unittest and `apply_repo_issue_tracking_overrides.sh --check`: see the PR.

## Device (Pixel 6a, API 37)

`AppVersionRowTest` (row displayed with `app-version` and the exact label; the installed package's version name, positive version code, and build type agree with `BuildConfig.DEBUG`) and `DiagnosticsShareTest` (with the new header) run through `adb install -r` plus `am instrument` (no uninstall): 2/2 and 2/2 pass.

## Remaining non-acceptance limitation

A release-signed APK's installed label remains unverified. The spec's physical-device acceptance explicitly requires two **debug** builds; no release key, package signer or version contract was changed. This is not a waiver of a required gate and no release-signed runtime claim is made.

## Physical two-commit acceptance — 2026-10-08 UTC

On the Pixel 6a, Android 17/API 37, installed each app with `adb install -r`, opened its real configuration sheet, read the selectable version row, tapped Share diagnostics and read the generated export header without sharing externally:

| Source commit | Installed APK SHA-256 | Actual row |
| --- | --- | --- |
| `f11f62d4c8a7465cd5ec0ccb6f327ef3d3e6f9d7` | `c67b14d25451c3e511394c9261a6a301965245f5fe0e0715ab43c7a0bca9f3de` | `Version 0.3.1 (301) · debug · f11f62d` |
| `98ee22932036aff9871dcfebd9f62995bf82417b` (HOME-13 callback fix, PR #138) | `b8c91c3be1a95a3b5d2c931a1472051aaf01066d2e3e69cecf4ceba6b6440cdc` | `Version 0.3.1 (301) · debug · 98ee229` |

Both real headers reported `app_version=0.3.1`, `version_code=301`, `build_type=debug` and the matching row's revision; only the revision differs in build identity. No serial/account/IP is part of either identity. The source's same-version identity contract is unchanged by the HOME-13 fix.

`AppVersionRowTest` 2/2 and `DiagnosticsShareTest` 2/2 passed in the direct 16-test safe instrumentation batch. The source build aggregate passed 428 JVM tests, build and lint; `check-apk-metadata.sh` confirmed min SDK 26 and 0.3.1 / 301. Earlier formatter/unknown/overflow cases and documentation evidence above remain applicable.

Original app `5692a179b2347214b7e31cc1010f650ec8a224fe00439b8533ecbb5437ddeb0b` and original test APK restored and hash-verified; captured settings restored. Profiles/selection/credentials/session references retained; the newer app only added valid `claim_management_supported: true` capability metadata to its pairing. No grant mutation, unpair/re-pair, signing change or external diagnostics upload. This closes REL-01 without claiming any other story complete.
