---
story: ANDROID-REL-01
spec: spec-android-rel-01-version-and-build-identity.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-07
---

# ANDROID-REL-01 validation record

Stacked on `ANDROID-DIAG-01` (#120): the diagnostics header it extends does not exist on `main` yet.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM) | Passed | Below |
| `versionName`/`versionCode` unchanged; `check-apk-metadata.sh` | Passed | Below |
| Instrumented row test on a device | **Passed**, Pixel 6a, Android 17 (API 37) | `AppVersionRowTest` 2/2 |
| Two builds from different commits differ only in the revision on the device | **Unverified** | Only one commit's build was compared on the phone |
| Row visible in the real configuration sheet | **Unverified** | Compose test renders the row; the sheet was not opened by hand |

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

## Unverified

Comparing two debug builds of the same version from different commits on the device, reading the row in the open configuration sheet, and a release-signed APK's label (`release` and the release revision).
