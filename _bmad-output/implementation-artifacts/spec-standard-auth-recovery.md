---
title: 'Preserve Standard authentication rejection through recovery'
type: bugfix
created: '2026-10-13'
status: done
route: oneshot
baseline_revision: 7c379ed86fd1979595ff5eaa0215da948cd454b6
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A saved Standard connection with an invalid credential remains labelled Verifying and receives disconnected/retry guidance instead of reporting rejected authorization. The emulator observation is already recorded; failed-handshake callbacks appear to compete with the authoritative nonretryable recovery result.

**Approach:** Preserve typed authentication failure through the existing recovery path and display unavailable authorization with deliberate configuration repair, not futile automatic retries. Keep normal established-transport loss recovery, credential isolation, uncertain-turn safety and no replay. Verify on the configured emulator with only disposable test-owned credentials; preserve the original connection/data. No server changes, microphone, Pixel, main-app reset/uninstall, audio investigation or automatic publication.

</frozen-after-approval>

## Implementation Notes

- No unresolved user choice or irreversible migration. Bounded Standard transport state/callback repair with existing runtime/presentation contracts; no new exported API. Continue from the preceding local isolation commit on `fix/standard-auth-recovery`; do not imply that commit was published or merged.
- `OkHttpStandardSessionClient.kt`: distinguish failure to establish a transport from loss of a live one; an initial handshake must finish via its own typed result. Retain rejected authorization only for the relevant selected identity and clear it on deliberate repair/success. Preserve existing established-disconnection notifications, generation guards and restart markers.
- `OkHttpStandardSessionClientTest.kt`: reproduce HTTP401/403 rejection with an installed connection observer before production edits. Assert nonretryable outcome, unavailable authorization, no unsolicited loss callback/retry/fallback and successful explicit repair. Keep genuine transport-loss cases. Runtime integration coverage will use existing injected worker/main and recovery patterns if needed.
- Reuse existing authorization guidance instead of introducing parallel UI rules. Diagnostics remain fixed, content-free decision events; no tokens, authenticated URLs or session IDs in output.
- Verification: red baseline regression, full JVM/build/lint, relevant 30-run repetition, direct non-destructive instrumentation and native saved-credential rejection/repair smoke. Do not run global-clearing RelayConfigurationTest or connectedDebugAndroidTest. Delete temporary fixture and owned profiles/slots only; leave original app Verified/Ready.
- Update README/changelog, Standard specification/validation and local story/sprint records, preserve the original deferred observation as history, and record review dispositions. Broader Standard acceptance remains in review. Finish a conventional local commit only.
- Baseline red: 24 transport tests ran; two failed at initial-handshake Disconnected delivery and the proposed unavailable snapshot expectation. The real native failure had already been observed in the preceding isolation task and was not rerun merely to confirm it.
- Further integration evidence: `MainActivity.kt:624–627` already renders Verifying plus typed authorization rejection as Unavailable. A separate remembered client authorization flag is unnecessary and would duplicate existing recovery ownership; retain the typed failure instead. Snapshot enum assertions are incidental, not the user-facing contract.
- Final production change is limited to failed-upgrade callback ownership and a fixed `standard authorization result=rejected` journal event. No snapshot/presentation/runtime API changed. `onFailure` after a ready transport retains established-loss recovery; generation checks and no-replay markers are unchanged.
- Minimal-change full JVM suite, debug/test APK assembly and lint passed (4m 2s). The earlier broader candidate also passed, but its redundant rejection cache was removed before final native testing.

## Review Triage Log

- Blind transport/concurrency review: no supported patch-introduced findings after inspecting upgrade versus established-loss handling and regression coverage. The reviewer declined to invent findings to meet its numeric floor.
- Native safety/presentation review: no supported findings. The fixture owns only an explicit probe ID; existing availability/header/action derivation consumes retained Unauthorized and clears on successful connection. This static review is not native acceptance.

## Verification

- Native real-server rejection at `2026-10-14T00:16:59.584Z`: one failed attempt, explicit rejected-auth reason, displayed Unavailable, no Retry, functional Edit relay, no transport-loss callback or accepted prompt/replay. Repairing only the owned credential and relaunching restored Verified/Ready at `00:18:09.258Z`; original connection subsequently authenticated independently.
- Direct instrumentation: 25 permanent tests passed. Relevant repetition: 30 × 60 JVM tests, zero failures. Metadata passed with the SDK-local analyzer; signer comparison was skipped. Initial metadata tool discovery failed with the other analyzer; no tool installation or workstation change was needed.
- Original Profile JSON and all history checksums exactly matched after owned-profile deletion. Private cleanup fixture passed original authentication and absence of probe credential slots. Original app ended Verified/Ready, device settings unchanged and zero held wakelocks. Removed temporary helper source, test APK and captures.
- Limits: native repair used an in-process credential write followed by relaunch; same-client explicit recovery is covered deterministically. No microphone, audio acceptance, server changes, Pixel, main-app uninstall, or broad Standard completion. Existing default-profile audio finding remains open.
- Final fixture-free test APK assembly passed (46 s). No production code changed after native verification.
- Completion hook executed configuration/URL-encoding steps, then stopped at missing `_bmad-output/planning-artifacts/prd.md` in find-prd-key. No PRD/key was fabricated and no issue/PR/remote mutation occurred. Delivery remains local on `fix/standard-auth-recovery`, stacked on the preceding unpushed isolation commit.
