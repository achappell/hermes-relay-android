---
title: 'Verify Standard connection isolation and preserve shared history on deletion'
type: bugfix
created: '2026-10-13'
status: done
route: oneshot
baseline_revision: 614ea7c382bb775d4f96e829d8b7d648fd95212b
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Standard configuration/isolation has deterministic coverage but incomplete live emulator acceptance. Inspection found that Standard connections sharing canonical endpoint/Hermes identity intentionally share Local History, while deleting one unconditionally deletes that shared history, erasing the surviving connection's history and draft.

**Approach:** Prove and fix the deletion defect by retaining identity-scoped history until no saved connection owns it. Exercise real configured-emulator credentials/history isolation and pending-draft, active-turn and uncertain-turn configuration guards with disposable profiles. Preserve independent credentials, existing history identity semantics, no replay, and the user's original configuration/data. Do not weaken guards, clear global preferences, uninstall the main app, change server configuration, capture the microphone, use the unavailable Pixel or publish automatically.

</frozen-after-approval>

## Implementation Notes

- No intent gap or irreversible migration; bounded controller fix and behavioral regressions. Worktree `fix/standard-isolation` isolates merged main. Reuse existing `RelayProfile.historyKey` (canonical Standard endpoint plus normalized Hermes Profile), not a second ownership key.
- `RelayProfileStore.kt:delete`: profile/credential deletion stays per Android Profile ID; history deletion must occur only after successful configuration persistence and only when the deleted history key has no remaining owner. Keep restart-marker and Home pairing cleanup unchanged.
- `StandardProfileTest.kt`: deterministic red-first shared-history/draft deletion regression, last-owner cleanup and unrelated-identity preservation; retain failed-save and credential-slot contracts.
- Native safety: the configured emulator currently has one Standard connection (`amanda`). A throwaway opt-in helper may read its credential only inside the app process, verify another real Hermes identity with the production transport and create explicitly test-owned profiles. Never pass secrets in shell arguments, status bundles, assertions or logs. Require a distinct history key and no existing owner before preparing data; remove only test-owned profiles/history/credential slots. Delete the helper source and test APK after smoke proof.
- Exercise actual native selection and Local History, negative authentication on only a disposable credential slot, and blocked configuration controls for draft/active/restarted-uncertain states. Use successful deliberate New conversation to resolve uncertainty; no silent replay or forced state clearing. Verify original credential still authenticates and original profile/history remain after cleanup.
- Existing `RelayConfigurationTest` clears global credential preferences and MUST NOT run on this emulator. Existing non-destructive configuration/runtime tests may run directly, never via Gradle connected-test uninstall cleanup.
- Full JVM/build/lint/metadata and relevant repetition/native gates follow any production fix. Update Standard validation, README/changelog and local trackers with actual findings and limits; broader Standard acceptance remains in review. Finish independent review and a local conventional commit.
- Confirmed red on merged baseline: `StandardProfileTest` ran 11 tests, one failed at line 159 because deleting a duplicate connection replaced the survivor's shared transcript/draft with empty history. Added last-owner/unrelated-identity/credential/restart-state assertions and a failed-deletion preservation case.
- Minimal production change: after successful Profile removal, compute the deleted history key once and remove its history only when the remaining collection has no matching key. No API, namespace, routing or session policy change.
- Added three native regressions to existing `StandardConfigurationRecoveryTest`: pending draft, active Standard work and uncertainty remove configuration selection/deletion controls; resolving the corresponding state restores them. Existing in-memory fixtures keep these tests independent of saved credentials.

## Review Triage Log

- **Ownership review — no supported findings:** independent review traced successful collection persistence, canonical shared keys, last-owner cleanup, unrelated identities, credentials and restart state; no patch-introduced defect found.
- **Native safety/coverage review — no supported findings:** independent review checked guard assertions and the throwaway fixture's in-process credential handling, explicit test-owned IDs, poisoning scope and cleanup checks. Native evidence remains separate from this static review.
- Full JVM suite, debug APK/test APK assembly and lint passed before native execution (4m 13s). The initial red is retained; no test was skipped or weakened to obtain green.

## Verification and delivery

- Real configured API 36 emulator exercised distinct Hermes identities, shared-identity alias deletion, pending draft/active/restarted-uncertain configuration guards, deliberate recovery without replay, invalid disposable credentials without fallback, and original-connection reauthentication. Detailed timestamps and boundaries are in the dated Standard validation addendum.
- Negative authentication remained safely disconnected but displayed stale Verifying/retry guidance; the disposable default profile's completed text survived a separate audio failure. Both observations are retained in `deferred-work.md`; neither is claimed fixed by this bounded history-ownership change.
- Full JVM/build/lint and metadata passed. Native gate: 25 tests passed; after removing the throwaway helper, rebuilt test APK and reran all five Standard configuration tests successfully. Relevant JVM repetition: 30 runs × 58 tests, zero failures. Final artifact rebuild initially lacked ANDROID_HOME; rerunning with the established SDK/JDK environment passed, without source/configuration changes.
- Cleanup fixture passed original credential authentication and owned-profile/credential/history/restart removal. Final non-secret Profile JSON exactly matched the starting configuration, original UI was Verified/Ready with an empty draft, accessibility/services/radio settings were unchanged, and the power service reported zero held wakelocks. Removed helper source, test APK and owned temporary captures; retained the main app and its original data.
- README, changelog, parent Standard specification, validation and local trackers updated; broader ANDROID-STD-01 remains in review. No physical-device, unattended-microphone, live cross-endpoint/Home isolation or audible default-profile acceptance is claimed.
- Completion hook: check-config URL encoding succeeded for dispatch and post-dev-complete; find-prd-key stopped because `_bmad-output/planning-artifacts/prd.md` is absent. No key was invented and no issue/PR/remote mutation occurred. Delivery is a reviewed local commit on `fix/standard-isolation`, not publication.
