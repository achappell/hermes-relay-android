---
title: 'Read ordinary recovery status on focus'
type: bugfix
created: '2026-10-13'
status: done
route: oneshot
baseline_revision: d108cf3b076bf05d9ab0c675137400dec531fc84
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** TalkBack can start obsolete Unavailable speech after Hermes has returned Ready. The earlier polite-queue mitigation failed native acceptance. The independently verified focus, notification and layout fixes are now merged and must remain intact.

**Approved decision, 2026-10-13:** Amanda selected **Read status on focus**: stop automatic ordinary availability/retry announcements, while keeping current status readable when selected. Recovery no longer needs to announce itself automatically. This explicitly changes the prior requirement for polite connection-status live regions; it is not a claim to repair TalkBack's queue.

**Approach:** Remove live-region semantics from the doorway availability label and connection/retry label. Keep duplicate connection-blocked explanations readable but non-live in the typed composer and idle capture controls. Preserve other composer guidance, authorization feedback, capture/turn announcements, actionable microphone-permission guidance and assertive failures. Preserve traversal bands, visible text, keyboard focus, recovery controls, explicit reconnect/no replay, the one process-owned runtime, credentials/history and the three merged fixes.

**Verification:** Cover Ready → Unavailable/retry → Ready, including loss immediately after reply completion. With ordinary status unfocused, app live regions must not enqueue availability/retry claims. Native TalkBack focus must still read current status and details. Preserve urgent error/permission regressions. Focused-node changes and already-queued user-requested speech remain TalkBack-owned; do not promise global silence. Use the existing emulator only; no physical or human-audibility claim.

**Never:** Import the parked patch wholesale, alter network/session behavior, intercept global speech, add a screen-reader service, add delays or deduplication tricks, hide current information, clear/uninstall the main app, run connected-test cleanup, capture the microphone unattended, merge or deploy.

</frozen-after-approval>

## Implementation Notes

- Policy selection resolves the only intent gap. Re-evaluated the small-change route: no irreversible operation or new runtime API; UI semantics, behavior tests and local delivery records only.
- `DoorwayZones.kt`: availability/retry labels, header authorization status, composer `Disconnected`/`Authorization`, and idle capture `NotConnected`/`ProfileUnavailable` are read on focus. Other block reasons retain their existing live feedback.
- `AccessibilityOrderTest.kt`: reuse native semantics/focus and permission fixtures; add recovery-transition coverage and preserve assertive failure/streaming checks. Semantics alone are not speech-output proof.
- Update `spec-5-a-2-accessibility.md`, its validation, `story-index.yaml`, `sprint-status.yaml` and `CHANGELOG.md` after smoke proof. Do not close broad physical acceptance.
- Preserved evidence lives in the unpublished `talkback-focus-announcements` worktree. Its 01:19:35.519 Ready feedback was followed by obsolete Unavailable speech starting at 01:19:37.138. That counterexample is reused, not represented as rerun.
- Pinned public [TalkBack queue ordering](https://github.com/google/talkback/blob/229212fdf5842191d0a93fc95d9ca1423b346866/utils/src/main/java/com/google/android/accessibility/utils/output/SpeechControllerImpl.java#L1339) checks duplicates before clearing queued speech; content/state-description feedback also enables duplicate suppression. This explains the failed approach but is not proof of installed-binary identity.
- Baseline verification: merged-main JVM tests, debug assembly and lint passed (43 s). Confirmed idle, installed with `adb install -r`, and inspected the actual Ready surface. Amanda Standard remained Verified; process 8721 connected at 07:25:01.123. No production edit or new speech pass was claimed at that point.
- Implementation verification: JVM/build/lint/metadata checks; reviewed focused instrumentation; actual TalkBack keyboard navigation, screenshots and speech diagnostics. Never UIAutomator while TalkBack runs. Restore settings/radios/permissions and remove only test-owned artifacts. Any unexpected lifecycle/reconnect/voice timing change requires replanning and the 30-run gate.
- Initial implementation: two policy tests failed on merged main (`expected null, but was Polite`, 1.614 s), then JVM/build/lint and 38 focused tests passed (61.123 s). Native smoke nevertheless exposed remaining recovery-driven Profile announcements. This was not accepted as a speech pass.
- Confirmed cause: `OkHttpStandardSessionClient.snapshot()` reports `Verifying` whenever configured credentials exist but transport is not live. Consequently recovery also renders composer `Authorization` and capture `ProfileUnavailable`; these are duplicate availability messages, not microphone-permission failures. Native output spoke both Profile instructions and `Authorization: Verifying` while the header description held focus.
- Refined the same on-demand policy to cover the header authorization status and these Profile availability explanations. Their text and traversal remain; authorization feedback is preserved for focus reading. Permission, recognizer/session-replacement guidance, capture/turn announcements and assertive errors are unchanged. Added the actual `Verifying` fixture; it failed before this refinement (1.149 s). No runtime or authorization decision changed.
- Final verification: JVM tests, debug/test assembly and lint passed (24 s); the refined 39-test suite passed (67.951 s). Review requested explicit readable/displayed connection-block explanations; those assertions were added and the final 39-test suite passed again (69.301 s). APK metadata passed; signer comparison was skipped because `EXPECTED_SIGNER_SHA256` was unset.
- Native TalkBack 16 on API 36 passed the approved narrow policy: idle recovery while reading the header description, and loss 44 ms after a real Standard audio reply drained. No automatic availability/retry/Profile-verification utterance appeared in either observed recovery window. Native focus still read Ready, Unavailable and retry details; the response remained readable and the journal recorded one accepted prompt, not a replay. Turn-phase feedback and the empty-prompt hint still spoke. Exact evidence and limits are in [the validation record](validation-5-a-2-accessibility.md#2026-10-13--recovery-status-on-focus).
- Review: two independent read-only lenses found no remaining supported production defect. The connection-explanation readability coverage gap was fixed and rechecked. No lifecycle, reconnect or voice timing implementation changed; the 30-run timing gate was not invoked.
- Restored TalkBack disabled/no enabled services, ERROR logging, its original denied notification permission/flags, Wi-Fi and data enabled, font 1.0, automatic rotation/user rotation 0 and hardware-keyboard IME setting 0. Verified Ready/Verified, empty focused draft and zero wake locks; dismissed the keyboard, removed the test APK and 29 owned capture pairs, and closed the native keyboard channel. Main-app data, credentials/history and other worktrees were untouched.
- Remaining limits: no physical Pixel, human-audibility or gesture-only acceptance; no global queue cancellation or guarantee for already-requested/focused-node speech. Existing transport-loss Profile-edit wording is unchanged, not a diagnosis that saved credentials need editing.
