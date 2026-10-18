# ANDROID-UX-11 validation record

Status: software and automated emulator coverage are implemented. The story is `in-progress`. Two gates are open: a physical Pixel walk of every section at 1.0x and 2.0x, and a manual TalkBack order/spoken-output pass. This record does not claim either.

## Implementation

- `ClientSettingsScreen` replaces the `ModalBottomSheet` (`ClientConfigurationSheet`, deleted). It is a full-screen `Dialog` (`usePlatformDefaultWidth = false`) with a `Settings` heading and a `Done` action. Back and predictive back go through the dialog window's back dispatcher, which calls the same `onDismiss`. Selecting, pairing or saving a Profile still closes Settings.
- `RelayConfigurationScreen` groups its content into `SettingsSection` cards: Profiles, Home pairing (HOME-13 pairing plus Paired Homes), Voice (microphone state plus the VOICE-02 `Open settings` route), Advanced and Troubleshooting (REL-01 version and DIAG-01 Share diagnostics). Each card title has heading semantics.
- Device administration and the retained legacy operator-handle form are now inside Advanced. Advanced starts collapsed, explains what it is for, and appears under the same visibility conditions as before.
- Requested Rooms use `FilterChip`s. A chip appears for each known Room from loaded Home configuration and for each Room ID you add. The `Room to edit` field is now a single-select chip picker over the loaded Rooms. Capabilities and Wake Mapping IDs are not Room lists, so they stay as text fields.
- `Revoke` now asks for confirmation in an `AlertDialog` before calling `revokeDevice`. Unpair already used an `AlertDialog`. Profile, connection-mode, Paired Homes and Renew/Re-enroll/Revoke button rows now use `FlowRow`, so at 2.0x they wrap onto more lines instead of overflowing.
- No pairing, protocol or administration-controller code changed.
- Not hosted yet: DIAG-02 automatic reports and connection details. Those stories are still backlog, and no placeholder rows were added for them.

## Verification — 2026-10-18 (branch `ux-11-settings-restructure`, base `8e08dea`)

Evidence: `~/Library/Application Support/Hermes Recovery/ux-11-settings-20261018/`.

- `./gradlew testDebugUnitTest`: **792 tests, 59 suites, 0 failures** (`jvm-tests.log`, after `--rerun-tasks`). The first run (`jvm-tests-run1-flake.log`) had one failure: `OkHttpRelaySessionClientTest.admission_preserves_preaudio_hold_and_duck_through_first_home_pcm` threw `ConcurrentModificationException`. That test and code are unchanged, and the test passed on rerun. New tests: `SettingsSectionsTest` (4).
- `./gradlew lintDebug assembleDebug assembleDebugAndroidTest`: **BUILD SUCCESSFUL** (`lint-assemble.log`).
- Disposable API 36 arm64 AVD `ux11-disposable-20261018` (pixel_6, 1080×2400), explicit serial `emulator-5590`. APKs were installed with `adb -s` and tests run with `am instrument`; no Gradle `connected*` task was used. The AVD was killed and deleted afterward.
- Focused instrumentation: **55/55 passed** (`focused-instrumentation.log`). Classes: `SettingsScreenTest`, `RelayConfigurationTest`, `MainActivityTest`, `PairedHomesFeedbackTest`, `AppVersionRowTest`, `DiagnosticsShareTest`, `StandardCaptureRecoveryTest`, `StandardConfigurationRecoveryTest`. An earlier attempt, `focused-instrumentation-run1-prefix.log`, failed one section-order assertion. That test measured viewport-clipped bounds; it now uses layout positions. No production change was needed.
- Full non-live instrumentation (`notAnnotation=LiveRelay`, large-phone HOME-11 row arguments) ran twice. Each run executed **114 tests with 113 passing**: 108 baseline plus 6 new (`SettingsScreenTest` ×5 and `MainActivityTest.done_dismisses_full_screen_settings_and_returns_to_home`). In both runs, the single failure was `ClientHolderLifecycleSmokeTest.pending_header_and_recreated_then_resumed_screen_observe_latest_holder_state` (`full-nonlive-instrumentation.log`, `full-nonlive-instrumentation-run2.log`).
  - This failure is pre-existing and intermittent. Fresh `origin/main` APKs (`8e08dea`) built in a throwaway worktree failed the same test **3 of 6** times on the same AVD, with the same visibility/text signatures at lines 77 and 94 (`baseline-main-lifecycle-smoke-*.log`). On this branch it failed 7 of 9 isolated runs (`lifecycle-smoke-rerun-*.log`), with the same signatures. The test does not open Settings. It is not fixed here and is not claimed as passing.
- `SettingsScreenTest` covers:
  - every settings action reachable by scrolling (Profiles, Standard setup, Home pairing, Paired Homes, Voice, every Advanced administration/operator field and action, version, Share diagnostics);
  - section and heading order Profiles → Home pairing → Voice → Advanced → Troubleshooting;
  - at 2.0x font scale with Advanced expanded, no horizontal-scroll semantics and no node extending past the container width (unclipped extents);
  - Revoke requiring confirmation, with Keep changing nothing;
  - Room chips adding and removing.
- `RelayConfigurationTest` was updated without weakening it: the administration test now asserts administration is hidden until Advanced is expanded, then runs its original discovery-is-not-authorization assertions.
- HOME-11 layout matrix: **not run**. UX-11 changes only the Settings destination. The doorway/header layout that `DoorwayRealWindowMatrixTest`/`DoorwaySplitWindowMatrixTest` assert is unchanged, and both classes passed inside the full non-live runs on the large-phone row.

## Open gates

- Physical Pixel: walk every section at 1.0x and 2.0x.
- Manual TalkBack: Settings section order, heading navigation and the Revoke/Unpair dialogs. Semantics assertions do not substitute for spoken output.
- Back gesture on a physical device. Emulator coverage was `pressBack` (`MainActivityTest.back_dismisses_configuration_sheet_and_returns_to_home`); the predictive-back animation was not observed.
