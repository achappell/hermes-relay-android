---
title: 'Distinguish connection recovery from authorization repair'
type: bugfix
created: '2026-10-13'
status: done
route: oneshot
baseline_revision: e8ef4355d5d9234ec0ed636827948f3beacbea2d
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Ordinary network loss leaves Standard authorization Verifying, but the doorway, typed composer and idle microphone instructions treat it as a credential fault and ask users to edit their Profile. Actual rejected authorization must remain distinguishable and actionable.

**Approach:** Derive shared presentation guidance from current authorization and typed recovery evidence. During ordinary disconnection/retry, explain connection recovery rather than Profile repair. Missing/rejected credentials retain authorization guidance and access to configuration. Preserve session-refusal reasons instead of calling them authentication failures. Preserve draft/response retention, explicit recovery/no replay, verified-and-connected admission, active/uncertain-turn and microphone-permission guards, and PR156's read-on-focus policy.

**Verification:** Prove the misleading Verifying/offline surface before the fix, then test ordinary recovery, missing/rejected authorization, non-auth session refusal, and recovery clearing the failure. Exercise the installed app through a bounded real emulator outage; exercise authorization-error UI on the emulator without altering saved real credentials. Preserve actual Profile/history and restore device settings. No unattended microphone capture, main-app reset/uninstall, server changes, physical-device acceptance, automatic publication or merge.

</frozen-after-approval>

## Implementation Notes

- No unresolved user choice or irreversible change. One cohesive classification fix, internal state/presentation contracts only; route oneshot. Fresh worktree `fix/recovery-guidance` preserves unrelated main-checkout edits. Kotlin reference lookup found no language server.
- Baseline build: debug and test APK assembly passed (21 s). Native pre-fix evidence is retained in the preceding recovery-announcement validation: ordinary radio loss showed Profile-edit, verify-before-send and verified-Profile-before-speaking instructions despite intact credentials. Do not relabel that evidence as rerun.
- `AndroidRecovery.kt`: preserve the existing typed outcome reason in the Failed variant. Retry, success and deliberate reset replace that variant naturally. Do not change retry scheduling, session negotiation or no-replay behavior.
- Shared presentation classification: distinguish pending offline verification from explicit authorization/configuration failures, using enum evidence, never parsing localized error text or treating credential presence as authorization. Retain fail-closed behavior for unverified connected states. Use it in `AndroidDoorwayState.kt`, `AndroidComposerState.kt`, `MainActivity.kt` and `DoorwayZones.kt`; migrate internal callers/tests. Capture admission remains controller-owned; only its displayed ProfileUnavailable explanation becomes connection guidance during transient recovery.
- Preserve HTTP401/403 as authorization refusal and session RPC RequestRejected as a session failure. Current Standard reconnect outcomes already distinguish them; snapshots omit that typed evidence. Preserve the outcome at the existing recovery boundary rather than adding a second remembered client failure state.
- Keep authorization admission based on the real snapshot. A pending authorization header may display Unavailable after an explicit refusal; no state is promoted to Verified for presentation.
- Regression coverage: doorway/composer/availability classification, typed failure retention and clearing, existing permission/uncertainty guards, and real Compose recovery/error surfaces. Existing MainActivity/Accessibility/Standard recovery fixtures are reusable. Run JVM/build/lint, focused direct instrumentation, metadata and applicable deterministic repetition gates. Never Gradle connected-test cleanup on the preserved AVD.
- Update the local disconnected-state specification/validation, story index, sprint note and changelog after native proof. Broader Standard and physical-device gates remain unchanged. Independent read-only scouts identified all three duplicated instructions and the typed-reason loss; implementation/review must not assume their recommendations are verification.
- Red UI regression: the merged APK rendered `Verify the selected Hermes Profile before sending.` where connection guidance was expected (1 failure, 2.981 s). The test retains non-live/readable semantics and disabled-capture assertions.
- Integration also updates `HomeRuntime.newStandardConversation` to publish typed failure under its existing no-live-transport guard. A failed request with a still-live old transport does not manufacture disconnection. Removed the failure wrapper's unconditional Retry instruction; the specific refusal and available recovery action now determine what to do.
- Static integration review caught an incorrect, unexecuted test assumption: Standard with an intact credential returns Verifying, not Unavailable, after either HTTP authorization rejection or session RPC refusal. Corrected fixtures/assertions without changing the client. Untyped Unavailable remains fail-closed even with older non-auth failure evidence; an explicitly typed non-auth snapshot reason is not treated as a credential fault.
- First full JVM run executed 574 tests with one old contract failure: StandardRuntimeTest expected bare Disconnected after a refused New conversation. Migrated that behavioral assertion to the retained Failed reason/code while keeping uncertainty/history/no-replay checks. JVM tests, debug/test APK assembly and lint then passed (16 s).
- Independent production and verification reviews found no supported remaining defect. Their reviews are static evidence, not emulator acceptance. Final instrumentation, the 30-run JVM recovery gate, metadata and actual installed-app smoke are tracked separately below.
- Recovery repetition gate passed: 30 consecutive runs of AndroidRecoveryControllerTest, ReconnectLadderTest, OkHttpStandardSessionClientTest and StandardRuntimeTest; 50 tests per run, zero failures (1,500 executions). APK metadata passed: min SDK 26, version 0.3.1 (301), backup off/extraction rules and 16 KB alignment. Signer verification was explicitly skipped because EXPECTED_SIGNER_SHA256 was unset.
- First direct emulator batch ran 48 tests with five failures. Two new reason-retention assertions used Compose's exact-match default despite expecting a substring; switched to explicit substring matching. Three Standard typed-send cases timed out. A targeted failed run plus a temporary screenshot showed the software keyboard covering the send surface, with the synthetic draft retained and the doorway still Ready. Those tests now dismiss the software keyboard before tapping Send, using the suite's existing Espresso helper; no timeout or production gate was weakened. Removed the temporary screenshot code before the corrected batch.

## Final Verification — 2026-10-13

- Corrected direct instrumentation: all 48 tests passed on `hermes-std-20261012`, API 36, in 84.607 s. Includes MainActivity, AccessibilityOrder, HomeVoiceNotification, MicrophoneCapture, MicrophonePermission, HomeRuntimeRecreation, StandardCaptureRecovery, StandardConfigurationRecovery, DoorwayHeaderLayout and OverflowMenuSemantics. The original red connection-guidance regression is green. Authorization refusal hides futile Retry and opens configuration; session refusal retains its reason and supports explicit New conversation; failed New conversation preserves uncertainty until successful deliberate recovery.
- Actual installed Standard smoke: one controlled typed turn completed and its audio drained. Two bounded Wi-Fi/mobile-data outages showed connection/reconnect, connect-before-send and connect-before-speak instructions, not Profile repair. Screenshots showed disabled Send and Tap to speak. Both radios were restored inside `finally` before inspecting results.
- The real reply and unsent draft remained after recovery. The content-free journal recorded exactly one accepted prompt across the entire smoke and session.resume recovery, including additional observed transport-loss/resume events after radio restoration; no prompt replay or new conversation was introduced. No microphone capture was attempted.
- Cleanup observed Ready, restored the original empty draft, retained the saved Profile/credential/history, removed only the test APK and owned captures, and confirmed Wi-Fi/mobile data on, TalkBack off as before, and zero wake locks. The fixed main APK remains installed.
- Two independent static reviews reported no supported defect and no deferred finding. Final test-only corrections addressed the observed substring and keyboard-interaction failures; they did not change production code, admission or timeouts.
- Limits: live rejection was not induced against the saved real credential. HTTP 401/403 and session RPC rejection were exercised against MockWebServer, and error/recovery surfaces through emulator fixtures. No physical Pixel, fresh native TalkBack speech acceptance, release signer check or prolonged bake is claimed. Broader Standard acceptance remains unchanged.
