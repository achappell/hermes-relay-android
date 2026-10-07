# ANDROID-UX-03 validation record

Status: `in-progress`. This PR validates the existing header's layout/reachability only; the planned `SessionHeaderCard` and the parent story remain open. Baseline: `origin/main` `3681339073348d9986e7adc51a129f584a27b289`; device pass on a Pixel 6a (Android 17, API 37), 2026-10-07.

## Implemented

- Replaced the fixed-height app bar with a status-bar-inset-aware, dynamically measured surface. Title, description, and Profile/authorization details are stacked in a weighted column; the existing overflow menu remains a separate trailing action.
- Preserved the existing menu actions, content description, test tag, `A11yOrder` traversal bands, and live-region modifier.
- Added `DoorwayHeaderLayoutTest` (instrumented, synthetic content): font scales 1x/2x x light/dark; asserts title, description, Profile label, Profile name and authorization are displayed, stacked top to bottom and inside the header; the header is inside the screen; the full Profile name stays in the accessible text; the overflow menu is displayed, clickable, keeps its single content description, stays on screen without overlapping the title, opens, and offers Conversations. It captures closed/open-menu screenshots into the app cache (pulled off-repo and deleted).

## Local verification

- `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug compileDebugAndroidTestKotlin --no-daemon --console=plain`: `BUILD SUCCESSFUL` (Android Studio JBR, SDK from the main checkout's ignored `local.properties`).
- `python3 -m unittest discover -s tests -p 'test_bmad_issue_tracking*.py'`: 18 tests passed; `bash -n scripts/apply_repo_issue_tracking_overrides.sh` and `--check` passed.
- `scripts/check-apk-metadata.sh` could not run locally (`apkanalyzer` is not installed). Equivalent `aapt dump badging` of the debug APK: min SDK 26, versionName `0.3.1`, versionCode `301` (= 0*10000+3*100+1 as the script expects). CI runs the real script.

## Device acceptance (Pixel 6a)

Installed with `adb install -r` only (never uninstall, clear-data, re-pair or `connectedAndroidTest`). The system font scale (0.85) and night mode (`auto`) were not changed: the test applies the scale and theme itself.

- `am instrument -e class ...DoorwayHeaderLayoutTest`: **OK (1 test)**, covering the 4-way matrix. A first run failed only in the screenshot helper (the test package has no external cache; the helper was moved to the target app's cache dir), after the layout assertions for light 1x had passed.
- `...AccessibilityOrderTest` (6 tests, unchanged) ran in the same instrument pass as the first attempt and passed, so HEADER/PROFILE/STATE traversal bands are intact.
- Screenshots inspected (8 images: light/dark x 1x/2x x menu closed/open, kept off-repo and not committed). At 2.0x in light and dark: title `Hermes conversation` fully visible, description wraps to two lines, `Selected Hermes Profile`, the Profile name ellipsized to one line (`Long synthetic profile name for l...`, full text is in the accessible node), `Authorization: Verified`, and the overflow button visible at the top right; opening it shows `Conversations`. Nothing overlaps or is clipped.
- Protected app data was byte-identical before and after (`relay-profiles.json` `a88712b1...`, `home-client-pairings.json` `3a85e5e1...`, the 2 `history-*.json` combined digest `535139a1...`). The original installed APK was pulled before the install and reinstalled afterwards; system settings are unchanged. The androidTest package from this run stays installed.

## Open

- Exact-head GitHub CI for the PR's final head is recorded in the PR.
- **TalkBack speech not captured.** adb cannot capture TalkBack speech, so physical TalkBack was not run and is **not waived**. The instrumented tests assert semantics (single menu label and click action, full Profile name in the accessible node, traversal bands) but do not replace it. Human step for Amanda, on the Pixel with TalkBack on and this build installed: open the app, swipe right through the header from the top and confirm each of these is announced once, in this order, with no silent or duplicated stop: `Hermes conversation` (heading), the subtitle, `Selected Hermes Profile`, the Profile name (announced in full even when visually ellipsized), `Authorization: Verified`, then the overflow button as `Open conversation menu` (double-tap opens the menu). This header has no status dot or duration; those arrive with the full `SessionHeaderCard`, whose traversal (status, Profile, title, duration, and an unlabeled dot never taking its own stop) must be re-checked on that work.
- This does not complete the status dot, timer, title-to-Conversations tap, settings icon or Disconnect placement, or the full ANDROID-HOME-11 scale/scroll matrix.
