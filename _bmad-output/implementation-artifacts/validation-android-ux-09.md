# ANDROID-UX-09 validation record

Status: `in-progress`. Baseline: `4ef41308bc2b66330d9af7a400dc9b0b705bf4d9` (`origin/main`).

## Implemented

- Added AndroidX `core-splashscreen`, a `Theme.SplashScreen` launch theme using the existing adaptive launcher icon, a post-splash app theme, and day/night window backgrounds (`#F7F9FC` / `#0B101B`). `MainActivity.installSplashScreen()` runs before `super.onCreate`; the manifest enables `enableOnBackInvokedCallback`.
- Kept the existing Material3 `ModalBottomSheet` back path. The resolved Material3 1.4.0 source uses a `ComponentDialog` back callback with predictive progress and calls each sheet's `onDismissRequest`; adding a parent `BackHandler` would compete with that gesture. Added Android tests for configuration, conversations, approvals, and history dismissal, plus day/night splash-theme resource assertions.

## Overlap audit and merge risk

The branch is based directly on clean `origin/main`; it does not copy or modify another worktree. Open PRs #120 (DIAG-01), #121 (HOME-12), #123 (VOICE-01), #124 (REL-01), and #118 (VOICE-02) modify `MainActivity.kt`; #120 also inserts a FileProvider in `AndroidManifest.xml`. Dirty HOME-03 and VOICE-04 worktrees also modify `MainActivity.kt` and were left untouched. No open PR or dirty worktree changes `themes.xml`.

The UX-09 `MainActivity.kt` hunks are limited to the splash import and `onCreate` call; the known PR/worktree hunks are in lifecycle callbacks or `AndroidClientScreen` state/content. The manifest hunk changes application attributes; #120 inserts its provider after the activity. These are separate line ranges, so no current line-level overlap was observed, though file-level merge risk remains until the open work lands.

## Verification

- Issue-tracking checks passed: 18 helper tests; shell syntax and workflow override `--check` passed.
- Local Gradle checks passed: `testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon --console=plain` (57 tasks); focused `:app:testDebugUnitTest --tests com.achappell.hermesrelay.AndroidLocalHistoryTest` passed. `compileDebugAndroidTestKotlin` compiled the new instrumented tests but did not execute them.
- Repetition gate passed: `scripts/run-flake-gate.sh AndroidLocalHistoryTest 30` (30 consecutive runs, 10 tests per run, 0 failures). This covers adjacent history logic only, not UX-09's predictive-back/device acceptance.
- Cold/warm start recordings in light and dark and physical predictive-back gesture evidence remain open because device authorization is unavailable. No connected tests, pairing, app-data removal, install, or other device operation occurred.
