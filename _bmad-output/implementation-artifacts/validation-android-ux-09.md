# ANDROID-UX-09 validation record

Status: `in-progress`. Baseline: rebased onto `origin/main` at `3fd3d765b66d9d3d8c7e250fb3e5e17e8ccf682d` (originally `4ef41308bc2b66330d9af7a400dc9b0b705bf4d9`).

## Implemented

- Added AndroidX `core-splashscreen`, a `Theme.SplashScreen` launch theme using the existing adaptive launcher icon, a post-splash app theme, and day/night window backgrounds (`#F7F9FC` / `#0B101B`). `MainActivity.installSplashScreen()` runs before `super.onCreate`; the manifest enables `enableOnBackInvokedCallback`.
- Kept the existing Material3 `ModalBottomSheet` back path. The resolved Material3 1.4.0 source uses a `ComponentDialog` back callback with predictive progress and calls each sheet's `onDismissRequest`; adding a parent `BackHandler` would compete with that gesture. Added Android tests for configuration, conversations, approvals, and history dismissal, plus day/night splash-theme resource assertions.

## Overlap audit and merge risk

The branch is rebased directly onto clean `origin/main`; it does not copy or modify another worktree. PRs #118, #120, #121, #123, #124 and #126, which touched `MainActivity.kt` and `AndroidManifest.xml`, have merged; the rebase applied without conflicts. The UX-09 `MainActivity.kt` hunks are limited to the splash import and `onCreate` call, and the manifest hunk changes application attributes. Open HOME-03 (#130) also edits `MainActivity.kt`, in `AndroidClientScreen` state and content, so file-level merge risk remains until it lands.

## Verification

- Issue-tracking checks passed: 18 helper tests; shell syntax and workflow override `--check` passed.
- Local Gradle checks passed on the rebased tree: `testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon` (350 unit tests, 0 failures/errors/skipped); `scripts/check-apk-metadata.sh` and `git diff --check origin/main..HEAD` passed. `compileDebugAndroidTestKotlin` compiled the new instrumented tests but did not execute them.
- Repetition gate passed on the rebased tree: `scripts/run-flake-gate.sh AndroidLocalHistoryTest 30` (30 consecutive runs, 10 tests per run, 0 failures). This covers adjacent history logic only, not UX-09's predictive-back/device acceptance.
- Cold/warm start recordings in light and dark and physical predictive-back gesture evidence remain open because device authorization is unavailable. No connected tests, pairing, app-data removal, install, or other device operation occurred.
