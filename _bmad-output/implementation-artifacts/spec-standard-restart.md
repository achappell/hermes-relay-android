---
title: 'Preserve interrupted Standard turn safety across process restart'
type: bugfix
created: '2026-10-13'
status: done
route: oneshot
baseline_revision: 11edf120147fe3a330458e6142a977dca06dee8f
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A completed Standard turn survives force-stop/relaunch through the saved Profile, credential, Local History and draft. Killing an accepted reply while Thinking does not replay its prompt, but relaunch forgets the interrupted state, silently creates a conversation and shows Ready. This hides an unresolved prior turn.

**Approach:** Persist a minimal identity-scoped interrupted-turn safety marker before Standard prompt submission. A fresh client finding that marker reports the interruption, admits neither typed nor spoken turns, and requires successful deliberate New conversation before clearing it. Preserve completed-turn restart behavior, credential/history/draft storage, no replay, same-process reconnect behavior and existing read-on-focus presentation. Keep the documented limitation that session references are not restored across process restarts: this fix does not add session-reference persistence or a Resume feature.

**Verification:** Exercise completed and in-flight restart cases on the configured API 36 emulator without clearing app data. Prove fresh-client restoration, write-before-send, definite rejection/terminal clearing, failed/successful New conversation, identity isolation and persistence failure safety with deterministic tests. Never persist prompt text, reply text, credentials, session/turn references or audio in the marker; retain existing Local History behavior. No server changes, unattended microphone capture, physical-device acceptance, prolonged bake, automatic publication or merge.

</frozen-after-approval>

## Implementation Notes

- No intent gap: the existing README/Standard validation explicitly excludes session-reference restoration. Keep that policy rather than introduce a new continuity feature. No destructive migration or credential mutation. Small internal store/transport/factory/configuration integration; route oneshot.
- Fresh worktree `fix/standard-restart` preserves unrelated main-checkout edits. Merged baseline debug APK assembly passed (19 s), installed with `adb install -r`; no app reset/uninstall.
- Native completed-turn baseline: a controlled typed turn reached Complete and audio drained. After force-stop, PID changed, saved authorization verified, Ready returned, the unsent draft matched, and the controlled prompt/reply were visible in Local History. An initial history check was at the old scroll position; stable sheet scrolling located both entries. No history was cleared.
- Native mid-reply baseline: journal accepted the controlled prompt at 19:34:56.798Z; UI showed Turn phase: Thinking. After force-stop/relaunch, new runtime created at 19:34:59.327Z and session.create completed at 19:35:00.189Z with uncertain=false. UI showed Ready without interruption guidance; exactly one prompt acceptance proves no automatic replay, not truthful recovery.
- Reuse existing Standard New conversation, typed failure guidance, uncertainty gates and mode/endpoint/Hermes-Profile identity. A restored marker must be available before initial automatic connect/admission; refuse silent session.create after an interrupted restart. Same-process recovery may still resume its in-memory reference and must retain uncertainty.
- Add a small app-private/no-backup file-backed marker store with an in-memory test implementation. Key by Profile ID and history identity, use atomic write-before-send semantics, fail closed on storage errors, and delete marker files with explicit Profile deletion. Local History clear must not authorize replay or dismiss unresolved safety state.
- In OkHttpStandardSessionClient, persist before prompt.submit; restore once per selected identity/client lifetime (do not mistake the current live turn's write-ahead marker for a restart). Clear only on authoritative safe resolution or successful explicit New conversation; failed New conversation, transport loss and unsupported prompts retain it. Journal fixed event codes only, never error contents or identifiers.
- Production factory shares the persistent store with configuration deletion. Connection-check probes use explicit ephemeral storage. Existing text/voice admission, retry pacing, session negotiation and Home behavior otherwise remain unchanged.
- Add focused persistence and real-transport restart regressions, an emulator UI guard/recovery regression if needed, run the full JVM/build/lint/metadata gates and 30-run recovery repetition, then exercise the installed fixed APK through both actual process-restart scenarios. Record review and native limits in this spec and the Standard validation/tracker/changelog; broader Standard acceptance remains open.
- Implemented `StandardRestartStore` with a shared production instance under no-backup storage, opaque Profile/identity paths and synced atomic marker replacement. The setup connectivity probe uses explicit ephemeral storage. Configuration deletion removes the owning Profile's markers; Local History clear remains independent of safety state.
- Authoritative completion received before a missing-submit-ACK timeout is accepted without manufacturing uncertainty; the added branch now records the ordinary content-free accepted event. Once uncertainty has already been raised, the existing New-conversation-only resolution policy remains in force, including late terminal events.
- First full JVM run: 592 tests, one failure in unchanged `AudioSinkPauseTest.a_reply_paused_while_draining_is_not_failed_by_the_drain_deadlines` (expected failure latch count 1, observed 0). New restart/transport tests passed. Thirty isolated runs of that unchanged audio class on the prior recovery-guidance baseline all passed; the subsequent full 592-test run also passed. No audio change or confirmed root-cause fix is claimed. A separate investigation is recorded in the deferred ledger rather than weakening/skipping the test.
- APK/test assembly and lint passed; metadata passed min SDK 26, version 0.3.1 (301), backup/extraction rules and 16 KB alignment. Release-signer verification was skipped because EXPECTED_SIGNER_SHA256 was unset.

## Review Triage Log

- **False, withdrawn — endpoint isolation:** verification reviewer claimed the marker key omitted endpoint. `RelayProfile.historyKey` explicitly hashes the canonical endpoint and normalized Hermes Profile; the existing identity-isolation test actually passed in the full run. No code or assertion was changed to fit the claim.
- **False, withdrawn — late terminal must clear existing uncertainty:** production reviewer proposed automatically clearing an already-raised uncertainty latch on late terminal. Baseline transport and the approved Standard contract require successful deliberate New conversation to clear that latch. The marker preserves that policy rather than weakening it. Added a receipt-barrier regression for late terminal followed by restart; the pre-timeout terminal case remains distinct.
- **Low, patched — missing journal event:** inline integration found the new authoritative-terminal-before-ACK acceptance branch omitted `standard prompt result=accepted`. Added the fixed event and a regression assertion; no prompt content or identifiers are logged.
- Both independent reviewers withdrew their initial findings after checking the computed identity and existing resolution contract. No supported remaining restart defect was reported. Static review is not native acceptance.

## Final verification

- Full JVM suite passed after the late-terminal regression; build, test-APK assembly, lint and metadata passed. Direct configured-emulator instrumentation passed 48 tests. The five-class persistence/transport/runtime/recovery gate passed 30 consecutive runs of 69 tests (2,070 executions).
- Actual fixed-APK completed restart preserved Verified authorization, selected Profile, unsent draft and native Local History prompt/reply. PID changed. Completed text/audio did not leave a stale restart marker.
- Actual accepted mid-reply force-stop produced a durable unfinished-turn warning, disabled input, zero automatic session creation and no prompt replay on two successive relaunches. Native recovery screenshot inspected. Deliberate New conversation restored Ready; one explicit next turn completed and drained; the following restart remained Ready/Verified.
- Restored the original empty draft. Accessibility, enabled service, Wi-Fi and mobile-data settings matched their starting values. Final wake-lock count was zero. Removed only the test APK and owned temporary captures; retained the configured main app, saved credentials and all Local History. No physical-phone or new audible-playback acceptance claim.
- README, changelog, Standard implementation/validation and local tracking updated; ANDROID-STD-01 remains in review for its broader acceptance. The isolated audio-pause observation remains explicitly deferred, not fixed or suppressed.
- Post-build dispatcher executed through review-finish configuration and stopped at the existing missing `_bmad-output/planning-artifacts/prd.md` prerequisite. No fabricated PRD/key, push, issue mutation or PR was performed. Delivery remains a local commit as approved.
