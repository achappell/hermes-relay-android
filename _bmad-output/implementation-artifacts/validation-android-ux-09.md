# ANDROID-UX-09 validation record

Status: `review`. Baseline: rebased onto `origin/main` at `85090f6` (after #118–#126, #131, #132 merged); code verified at `64f12cb`, and the only newer main commit is docs. Device pass on a Pixel 6a (Android 17, API 37), 2026-10-07.

## Implemented

- Added AndroidX `core-splashscreen`, a `Theme.SplashScreen` launch theme using the existing adaptive launcher icon, a post-splash app theme, and day/night window backgrounds (`#F7F9FC` / `#0B101B`). `MainActivity.installSplashScreen()` runs before `super.onCreate`; the manifest enables `enableOnBackInvokedCallback`.
- Kept the existing Material3 `ModalBottomSheet` back path. The resolved Material3 1.4.0 source uses a `ComponentDialog` back callback with predictive progress and calls each sheet's `onDismissRequest`; adding a parent `BackHandler` would compete with that gesture. Added Android tests for configuration, conversations, approvals, and history dismissal, plus day/night splash-theme resource assertions.

## Overlap audit and merge risk

The branch is rebased directly onto clean `origin/main`; it does not copy or modify another worktree. PRs #118, #120, #121, #123, #124 and #126, which touched `MainActivity.kt` and `AndroidManifest.xml`, have merged; the rebase applied without conflicts. The UX-09 `MainActivity.kt` hunks are limited to the splash import and `onCreate` call, and the manifest hunk changes application attributes. Open HOME-03 (#130) also edits `MainActivity.kt`, in `AndroidClientScreen` state and content, so file-level merge risk remains until it lands.

## Verification

- Issue-tracking checks passed: 18 helper tests; shell syntax and workflow override `--check` passed.
- Local Gradle checks passed on the rebased tree at `64f12cb`: `testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon` (378 unit tests, 0 failures/errors/skipped); `scripts/check-apk-metadata.sh` and `git diff --check origin/main..HEAD` passed.
- Repetition gate passed on the rebased tree: `scripts/run-flake-gate.sh AndroidLocalHistoryTest 30` (30 consecutive runs, 10 tests per run, 0 failures). This covers adjacent history logic only; the UX-09 behaviour is proved on the device below.

## Device acceptance (Pixel 6a)

Installed with `adb install -r` only (never uninstall, clear-data, re-pair or `connectedAndroidTest`); `relay-profiles.json`, `home-client-pairings.json` and the history files were backed up via `run-as` and were byte-identical after the install.

- **Instrumented, `adb shell am instrument -w -e class …ModalSheetBackTest,…SplashThemeResourcesTest,…MainActivityTest`:** `OK (19 tests)`. `ModalSheetBackTest` (3: conversations, approvals, history), `SplashThemeResourcesTest` (2: splash and post-splash backgrounds `#F7F9FC` light / `#0B101B` dark; launcher adaptive icon; post-splash theme), `MainActivityTest` (14, including `back_dismisses_configuration_sheet_and_returns_to_home`). App data hashes unchanged after the run.
- **Cold and warm starts, light and dark (`screenrecord`, `cmd uimode night no|yes`; original mode `auto` restored):** four recordings (cold = force-stopped process; warm = back out of the single activity, process alive, relaunch). Per-frame luminance of every recording (AVFoundation, 8 fps): in dark mode the frames go from the launcher (mean luminance 148) straight to the dark window (`#090F1D`, mean 21–25) with no frame brighter than the launcher, cold and warm. In light mode they go from the launcher (159) to the light canvas (`#F9FBFC`, 244). No light flash in dark mode.
- **Predictive-back edge gesture on configuration, conversations and history sheets** (`input motionevent` from x=4: gesture navigation enabled temporarily, three-button navigation and `enable_back_animation=0` restored afterwards): on each sheet a gesture dragged in and back out cancelled and kept the sheet open; a drag past the commit threshold dismissed only that sheet and left the home surface shown (app not exited). The mid-gesture screenshot shows the system preview (sheet scaled with the back chevron). The approvals sheet has no device run beyond `ModalSheetBackTest`.
- Screen recordings and screenshots stay off-repo because they show conversation content.

## Open

- Nothing against the acceptance criteria. Physical TalkBack and the approvals sheet by gesture were not exercised.
