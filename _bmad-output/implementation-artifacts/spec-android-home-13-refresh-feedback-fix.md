---
title: 'ANDROID-HOME-13: retain Refresh Profiles feedback'
type: 'bugfix'
created: '2026-10-07'
status: 'in-progress'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Refresh Profiles invokes the generic configuration-change callback, which dismisses the parent sheet whenever a Profile is selected. The real Pixel run recorded in PR #136 lost its result this way.

**Approach:** Separate data-refresh notification from intentional setup-completion dismissal. A Refresh keeps the configuration sheet and truthful success/failure message visible; existing setup-completion behavior remains unchanged. Prove the parent-sheet behavior fails before the fix and passes afterward, then exercise exactly one real Refresh on the fixed APK, preserving and restoring the original APK/settings/data. No grant mutation, Unpair/re-pair or prompt is part of HOME-13 acceptance. Newly-active-grant and unwaived TalkBack gates remain explicit; do not falsely mark the story done.

</frozen-after-approval>

## Implementation Notes

- Base: current main `f11f62d4c8a7465cd5ec0ccb6f327ef3d3e6f9d7`; merged PR #136 evidence is preserved. Separate source-fix branch/worktree.
- No unresolved intent gaps or irreversible production changes. Small callback change in `RelayConfigurationScreen.kt` and `MainActivity.kt`; new parent-screen regression uses existing `AndroidClientScreenTestSupport.kt`, in-memory stores and controlled service only for deterministic regression, never as live-device acceptance.
- MainActivity owns dismissal; RelayConfigurationScreen owns collection reload; PairedHomesSection already owns the truthful result and must retain that state. Existing select/save/pair/delete completion behavior must not change.
- All device actions are exclusive and safe; no `connectedAndroidTest`, uninstall, clear-data or production credential-store test class. Gradle window released by ANDROID-STD-01 owner.
- Behavioral baseline-red on Pixel API 37: built current-main production with the new `PairedHomesFeedbackTest`; direct `am instrument` ran four tests. No-new, added-grant and failed-refresh tests each failed because the real parent configuration sheet disappeared; intentional Profile selection passed. The test uses in-memory stores only and never touches the real grants.
- `onChanged` now only reloads/notifies data; `onSetupCompleted` owns the existing selected-Profile dismissal. Existing select, delete, save and pair-completion paths call both, while Paired Homes data changes call refresh only. No selected-ID workaround or coordinator behavior change.
