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
- First full non-live runs (`notAnnotation=LiveRelay`, large-phone HOME-11 row arguments): two runs, each **114 tests with 113 passing** (`full-nonlive-instrumentation.log`, `full-nonlive-instrumentation-run2.log`). 114 is the 108 baseline plus 6 new tests (`SettingsScreenTest` ×5 and `MainActivityTest.done_dismisses_full_screen_settings_and_returns_to_home`). Both failures were `ClientHolderLifecycleSmokeTest.pending_header_and_recreated_then_resumed_screen_observe_latest_holder_state`.
- **Root cause of that failure (test synchronization; no UX-11 regression).** When the turn settles, `HomeRuntime.settleIfDue` increments `settleRevision`. `ClientRuntimeEffects` then focuses the composer, which raises the soft keyboard. `DoorwayLayout` applies `safeDrawingPadding`, so the keyboard's inset animation shrinks the rail. That animation happens in the system window and is not tracked by Compose idling. The test scrolled and asserted on the rail while it was still resizing, so nodes were "not displayed" or "no longer in the tree" (signatures at lines 77 and 94). Evidence is in `flake-rootcause/`:
  - logcat shows the app's settlement `onRequestShow ... SHOW_SOFT_INPUT_BY_INSETS_API` followed by `onShown`, between 0.3 s and 1.4 s before every one of 8 failures (`before-fix-*-logcat.txt`);
  - on a fresh disposable AVD the failure rate is the same for fresh-main `8e08dea` APKs (**1/10 passed**, `main-before-fix-*.log`) and for the unfixed branch (**1/8 passed**, `before-fix-*.log`). The test never opens Settings. So UX-11 does not raise the rate; the earlier 3/6 vs 7/9 difference was sampling noise.
- **Fix (test-only, no assertion changed, no sleep).** After the terminal state, `awaitSettlementKeyboard` waits for three things in order: the composer has focus; the IME inset is above zero (`imeBottomPx`, the existing HOME-11 helper); and the doorway geometry is stable (the existing `settleGeometry`). Only then does the test scroll and assert. The existing geometry helpers in `DoorwayMatrix.kt` now take a `ComposeTestRule` receiver so the empty-activity rule can use them; their behaviour is unchanged.
- After the fix, on disposable AVD `ux11-flake-20261018` (`emulator-5592`), the test passed **12/12 isolated runs** (`after-fix-*.log`). The full non-live run passed **114/114** (`flake-rootcause/full-nonlive-after-fix.log`).
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
