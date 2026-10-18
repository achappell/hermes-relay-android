# ANDROID-UX-03 validation record

Status: SessionHeaderCard behavior and automated coverage are implemented. The story remains `in-progress`: physical Pixel 1.0x/2.0x and manual TalkBack status-announcement acceptance are unavailable and remain open. PR #134's waiver applies only to its separate header-reachability slice.

## Implementation

- Compact two-row card: state-colored dot, one-line conversation title action, one-line accessible Profile, polite status-only live region, elapsed connection duration, existing Settings action, and HOME-12 Disconnect action.
- Connection start uses the runtime's monotonic timestamp and survives Activity recreation. Title reads through the existing Home conversations API; title/settings actions use existing destinations.
- JVM coverage exercises state-to-dot/color/text mapping and fake-clock `mm:ss` / `h:mm:ss` boundaries. Instrumentation covers long names/accessibility, title and Settings navigation, HEADER/PROFILE/STATE traversal, and the single polite status region without a live region on elapsed-time ticks.
- The `DoorwayHeaderLayoutTest` asserts one-line title and Profile text and captures real emulator screenshots with a long synthetic Profile at 1.0x and 2.0x. The card is two rows at 2.0x.

## Verification — 2026-10-18

- `./gradlew testDebugUnitTest`: **BUILD SUCCESSFUL**. `scripts/run-flake-gate.sh SessionHeaderStateTest,HomeRuntimeTest,ConversationsHolderTest 1`: **36 tests, 1 run, 0 failures**.
- `lintDebug` and `assembleDebug`: **BUILD SUCCESSFUL**. `assembleDebugAndroidTest` passed after the last instrumentation-test edit.
- `scripts/check-apk-metadata.sh`: passed; minSdk 26, version 0.3.1/code 301, backup disabled with extraction rules, 16 KB alignment. Signer comparison was skipped because `EXPECTED_SIGNER_SHA256` was unset.
- Focused UX-03 instrumentation (`DoorwayHeaderLayoutTest`, `MainActivityTest`, `AccessibilityOrderTest`): **29/29 passed**. `AccessibilityOrderTest` verifies semantics (one polite region on status; elapsed has none); this is not a claim about spoken TalkBack output.
- Full non-live instrumentation on disposable API 36 arm64 `hermes_ux03_fold_20261018`, explicit serial `emulator-5578`, with the standard `notAnnotation=LiveRelay` filter and declared HOME-11 row arguments: **108 tests, 0 failures**. `DoorwayRealWindowMatrixTest` and `DoorwaySplitWindowMatrixTest` were included in this run. The two opt-in live-speech checks were not activated (no `liveSpeech` argument).
- Against fresh-main `48218ef`, HOME-11 records a 105-test full non-live run. UX-03 adds three instrumented methods (`MainActivityTest.tapping_session_title_opens_the_existing_conversations_sheet`, `MainActivityTest.session_header_settings_icon_opens_existing_relay_settings`, `AccessibilityOrderTest.session_status_is_the_only_polite_region_and_elapsed_ticks_are_not_live`), so the equivalent current run is 108 tests.
- The intermediate **101/101** run used `-e notClass` and therefore omitted `DoorwayRealWindowMatrixTest` (4 test methods) and `DoorwaySplitWindowMatrixTest` (1 method), which require HOME-11 row/font/rotation/navigation/display arguments. It also omitted `LiveEndpointingTest` (1) and `LiveSpeechRecognizerTest` (1); the standard non-live runner includes these assumption-guarded methods, but without `liveSpeech` they do not activate. Thus 101 + 5 matrix methods + 2 guarded methods = 108. The same intermediate filter omitted `AudioOutputPreflightTest` and `LiveRelayHandshakeTest`, both `@LiveRelay`; the proper `notAnnotation=LiveRelay` run excludes these as the main baseline did.
- A raw unfiltered attempt ran 110 tests and failed 7: it included the two non-default `@LiveRelay` tests, invoked row-required matrix methods without row arguments, and contained the stale authorization-label assertion for the replaced header. These attempts and the corrected 108-test run are preserved in the evidence directory.
- `ClientHolderLifecycleSmokeTest` initially failed while looking for the completed response. Diagnosis confirmed the runtime still held `"Final visible reply"`; the test helper's rail `performScrollToNode` located the lazy item but did not bring it fully into view. The test now performs `performScrollTo()` on `android_response_text` before asserting visibility. This is a **test-only** fix, not a production change. The targeted lifecycle test passed 1/1 and the final 108-test suite passed. `StandardCaptureRecoveryTest` likewise now asserts the new visible reconnect-failure status while retaining its exact authorization-guidance assertions; no assertion was weakened.

## HOME-11 matrix

Decision: run the full matrix because UX-03 replaces the previous stacked title/description/Profile/authorization header with a compact two-row card. That changes header height and the available real/split window area used by HOME-11's reachability assertions.

`scripts/run-home11-layout-matrix.sh` ran both HOME-11 classes on four newly created, empty API 36 arm64 AVDs. It stopped at the first failed cell and verified each declared configuration. All **96/96 instrumentation invocations passed (240 test executions), with 144/144 HOME-11 logical cells passed, 0 failures, 0 unexecuted cells, and 0 configuration mismatches**. HOME-11 defines 144 cells as 6 viewports × 3 scales × 2 IME states × 2 orientations × 2 navigation modes: 96 full-window cells across the small/large phone, tablet and Fold viewports, plus 48 split-window cells across small/large phone viewports. The runner also exercised split-window on tablet and Fold, adding 24 passing test executions outside those 144 logical cells. Each of the four AVD rows had 12/12 real-window and 12/12 split-window invocations (four real-window tests per invocation, one split-window test).

| Row | Real-window | Split-window | Result |
| --- | ---: | ---: | --- |
| Small phone (720×1280) | 12/12 (48 tests) | 12/12 | pass |
| Large phone (1080×2400) | 12/12 (48 tests) | 12/12 | pass |
| 10.1-inch tablet (1280×800 natural) | 12/12 (48 tests) | 12/12 | pass |
| Fold inner (2208×1840) | 12/12 (48 tests) | 12/12 | pass |

Two small-phone 1.0x/portrait/gestural smoke invocations were performed before the full row and are not included in the 96/96 or 240-test totals. The screenshots and per-cell receipts/logs are preserved under the evidence directory.

## Evidence and remaining gates

Evidence is outside the repository at `~/Library/Application Support/Hermes Recovery/ux03-20261018/`. The SessionHeaderCard screenshots are `header-light-1x.png` and `header-light-2x.png`, captured on the owned Pixel 6a-profile AVD (`emulator-5570`) with a long synthetic Profile name.

The owned AVDs were deleted after evidence capture. No owner emulator, owner AVD/userdata, credentials, or recovery directory was touched. Physical Pixel 1.0x/2.0x visual acceptance and manual TalkBack status-change announcement testing remain open; no physical or spoken-output pass is claimed.

## Historical PR #134 slice

PR #134 delivered the earlier stacked-header reachability layout. Its 2026-10-07 evidence and the accepted TalkBack waiver apply only to that slice. They do not verify the SessionHeaderCard and do not waive the current story's physical-device gates.
