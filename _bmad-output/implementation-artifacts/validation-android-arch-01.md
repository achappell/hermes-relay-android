# ANDROID-ARCH-01 local verification — 2026-10-16

## Boundary and disposition

Canonical specification: [state holders](spec-android-arch-01-state-holders.md).
Software implemented; local review only. **Not fully accepted:** canonical all-existing-instrumentation-green acceptance remains open. No push/PR. Three formal reviews and focused closures completed; late rename after hide/Profile change (E1) and late approval rows/count after Profile change (E2) are individually documented pre-existing deferrals, not new behavior changes.

Baseline: `c4ba8add6f7d46d96e4695c7b7f27928cfd82a2c` (fresh main after merged HOME10).
Branch: `story/next-required-software-after-home10`.
Worktree: `/Users/amanda/Development/hermes/hermes-relay-android/.worktrees/next-required-software-after-home10`.
Evidence root, abbreviated **E** below:
`/Users/amanda/Development/hermes/hermes-relay-android/.worktrees/next-required-software-after-home10/.local-evidence/arch01-20261016-071759`.

## Implementation and review

The approximately 250-line `AndroidClientScreen` renders lifecycle-collected immutable holder state and forwards intents. Runtime-owned Turn/Capture/Recovery controllers remain the sole controller owners. Plain holders own real transitions; management holders retain existing generation/Profile/claim/title-enrichment guards. Render-only extraction is split by existing surface responsibilities, not a replacement giant composable. Existing tests were not changed.

P1 provider rebinding is covered by a real ModeAwareClientPort Standard→Home switch. B1 pending-submit header enablement is covered on the actual Activity. G1 recreation/subsequent delta and stopped-collector/resume catch-up are asserted against visible screen output. B2 outer accepted-turn projection uses the shared TurnHolderState rule and collected state. The surface test asserts accepted controls blocked, terminal voice/editor enabled before editing another draft, then Send enabled after a new draft and a second actual submit. All formal source findings are closed or explicitly pre-existing/deferred.

Preserved: 1000 ms audio cushion / 1500 ms cap, fresh-driver zero-head/no pre-play query, HOME10 timer separation and no automatic resubmit. Historical Standard iteration23 remains unattributed. No live Home, physical Pixel, native-audio quality, physical microphone, or acoustic claim is made.

## Verification receipts

Owned empty API36 arm64 Google APIs AVD `hermes_arch01_empty`, explicit serial `emulator-5588`, unchanged 320×640 / density160. Both ports were checked/claimed before launch. No owner emulator5554, AVD, credentials, userdata or recovery directory was touched. No Gradle connected task ran.

| Evidence under E | Observed outcome |
| --- | --- |
| `baseline-gradle.log`, `baseline/app/build/` | Before edits: JVM767/54 suites, build, lint, both APKs pass; full XML/reports archived. |
| `baseline-instrumentation.log` | Existing98:18 failures. Runner process exit0 is not a passing suite. |
| `post-gradle-01.log`, `post-01/` | JVM784/57 pass; lint found two moved-block indentation errors. Fixed actual indentation, no suppression. |
| `post-gradle-02.log`, `post-02/` | New lifecycle test compile failure: existing extension requires a different rule type. New fixture switched to the existing direct rail semantics operation. |
| `post-gradle-03.log`, `post-03/` | JVM784/build/lint/APKs pass. |
| `post-existing-instrumentation.log` | Existing98:20 failures: baseline18 plus two actual introduced regressions, preserved failed-before-B2. |
| `post-gradle-04.log`, `post-04/` | After B2: JVM784/57, build/lint/APKs pass, full XML archived before later repetitions. |
| `post04-focused-instrumentation.log` | Four cases: both unchanged introduced-regression tests pass; both new smoke fixtures fail (lazy rail Connect not revealed; synthetic terminal lacked normalized audio events). All four outcomes preserved. |
| `post-gradle-05.log`, `post-05/` | Corrected new fixtures: test APK and lint pass. |
| `post05-smoke-instrumentation.log` | Two cases, one failure: **lifecycle case status0/pass**; surface failed because fallback voice uses text, not the speech-input tag. This aggregate is NOT green. |
| `post-gradle-06.log`, `post-06/` | Actual fallback surface selector corrected in new test only; test APK and lint pass. |
| `post06-surface-instrumentation.log` | **OK (1 test)**: actual surface flow passes. Together with post05 lifecycle, two new cases passed across separate receipts, not one all-green two-case invocation. |
| `final-existing-instrumentation.log` | Existing98:18 failures, exactly the original18 IDs. Both introduced regressions now pass in the full suite as well. |
| `baseline-final-comparison.json` | Machine-readable exact ID/signature comparison; zero new/removed failing IDs,17 same substantive signatures, one advanced fixture signature. Also records per-invocation repetition results and smoke receipt provenance. |
| `*.derived.xml` | Derived instrumentation testcase records/stack traces alongside raw runner logs; raw logs remain authoritative. |
| `repetition/01/` through `repetition/30/` | Thirty separate `scripts/run-flake-gate.sh <classes> 1` invocations, fail-fast, individually archived log/full XML/report:206 tests each,6180 executions, zero failures. |
| `apk-metadata.log` | minSDK26, version0.3.1/code301, backup exclusions,16KB alignment pass. Release signer comparison skipped because EXPECTED_SIGNER_SHA256 unset. |

Repetition classes: HomeRuntimeHoldersTest, ConversationsHolderTest, ApprovalsHolderTest, HomeRuntimeReconnectTest, HomeRuntimeInterruptTest, HomeRuntimeBackgroundTransportTest, HomeRuntimeVoiceRetentionTest, HomeRuntimeTest, HomeRuntimeRetentionTest, AndroidRecoveryControllerTest, AndroidInitiationControllerTest, AndroidCaptureControllerTest, AndroidTurnStateTest, AndroidTurnStateLateAudioTest.

New smoke prerequisites were corrected only after inspecting the actual surface/reducer: scroll the real lazy rail before Connect; fallback voice is labelled Tap to speak; synthetic AudioStarted/AudioEnded makes Complete an honest normalized **UI phase fixture**, not native playback proof. Readiness waits observe runtime/listener state rather than treating Compose idle as executor completion. The smoke uses a pre-paired fixture (`selectedIsPaired = true`) for Connect; it does not exercise Home pairing/enrollment or prove live pairing. Disposable fixture interfaces exercise typed turn, conversation resume, approval, history and Activity recreation without owner credentials or live Home.

## Exact retained failures

All identifiers below have package prefix `com.achappell.hermesrelay.`. Baseline and final have the same18 IDs. NPE means `java.lang.NullPointerException`; NSE means `java.util.NoSuchElementException: List is empty.`. Full exception text and first fixture frame are in the comparison JSON; full stacks are in raw logs/derived XML.

| Test ID | Baseline → final signature |
| --- | --- |
| AccessibilityOrderTest#connection_status_is_on_demand_and_errors_remain_assertive | NoActivityResumedException at302 → NPE at305; only changed substantive signature. |
| AccessibilityOrderTest#the_interrupt_control_follows_turn_state_in_accessibility_order | NPE260, unchanged. |
| AccessibilityOrderTest#the_reading_order_runs_profile_then_state_then_response_then_action | NPE220, unchanged. |
| HomeConversationsSheetTest#open_claims_show_server_count_current_marker_and_only_close_non_current_refs | Profile name unavailable not displayed89, unchanged. |
| InterruptControlTest#no_terminal_within_two_seconds_shows_the_unconfirmed_state_and_opens_the_microphone_once | startTurn NPE86, unchanged. |
| InterruptControlTest#one_tap_spends_the_control_and_sends_one_interrupt | startTurn NPE86, unchanged. |
| InterruptControlTest#the_control_announces_its_mode_and_its_action | startTurn NPE86, unchanged. |
| LocalHistoryTest#a_completed_turn_is_kept_and_can_be_cleared | NPE130, unchanged. |
| LocalHistoryTest#a_submitted_prompt_can_be_recalled_without_losing_a_half_typed_one | NPE197, unchanged. |
| MainActivityTest#a_lost_turn_is_marked_unconfirmed_and_resent_only_by_an_explicit_action | NPE351, unchanged. |
| MainActivityTest#authorized_typed_turn_uses_the_selected_profile_once | android_turn_status missing in rail, extension14, unchanged. |
| MainActivityTest#disconnected_composer_keeps_a_draft_and_explains_why_send_is_disabled | android_cached_draft not displayed163, unchanged. |
| MainActivityTest#preserves_completed_text_when_normalized_audio_delivery_fails | android_turn_phase missing in rail, extension14, unchanged. |
| MainActivityTest#prompt_history_is_grouped_under_a_recent_prompts_label | performScrollTo failed219, unchanged. |
| MainActivityTest#renders_normalized_phases_and_one_coherent_response | NSE265, unchanged. |
| MainActivityTest#retained_context_is_labelled_as_cached_while_disconnected | NPE398, unchanged. |
| MicrophoneCaptureTest#hands_free_continues_after_a_completed_turn_and_stop_ends_it | window did not reopen expected2/actual1 at219, unchanged. |
| MicrophoneCaptureTest#typed_turns_still_work_when_no_recognizer_exists | NSE143, unchanged. |

The first Accessibility fixture calls Connect→waitForIdle→type→Start→closeSoftKeyboard then dereferences lastBinding. Compose idle does not guarantee worker connect readiness. NoActivityResumed at keyboard-close does not establish a runtime crash. Missing bindings fail before intended behavior assertions. Viewport/cross-test interference is not established as a common cause; no unrelated production/test fixes or blanket geometry attribution were made.

**Introduced then fixed, not baseline failures:**
- `StandardCaptureRecoveryTest#completing_a_standard_turn_restores_capture`: post03 Tap to speak remained disabled; passes post04 targeted and final98 after B2.
- `AccessibilityOrderTest#accessibility_focus_reveals_a_clipped_turn_without_moving_keyboard_focus`: post03 clip precondition at120 failed; passes unchanged post04 and final98 after B2 on the same AVD. Its clip/voice assertions were not weakened.

## Acceptance remaining

Existing instrumentation remains non-green; it is not waived or relabelled as accepted. This is a locally implemented/reviewed refactor with bounded emulator proof and preserved baseline exceptions, not whole-story acceptance. Physical/live limitations from HOME09/HOME10 remain unchanged. Publishing and any broader baseline remediation require separate authorization.

The owned AVD was `hermes_arch01_empty` (emulator serial `emulator-5588` in
`E/baseline-final-comparison.json`). The emulator log identifies that AVD and
the owned-emulator cleanup log records its shutdown. The cleanup receipt
`E/owned-avd-disk-cleanup.json` records removal of only
`E/avd` with `exists_after_cleanup: false`; I confirmed that path is absent.
The separate config manifest remains in `E/owned-avd-config`. All logs,
instrumentation XML, APKs and comparison evidence remain intact. Owner AVDs
and recovery assets were untouched.
