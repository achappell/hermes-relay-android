---
title: 'Reconnect Standard after configuration changes'
type: 'bugfix'
created: '2026-10-12'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Saving or deleting another connection, or reselecting the current Standard profile, closes the selected transport without restarting connection recovery. The emulator remained Disconnected/Verifying until an explicit New conversation.

**Approach:** Make connection-affecting configuration changes invalidate the existing automatic connection effect even when the selected profile ID and mode remain unchanged. Preserve the existing reset, switch guards, credential separation, no-replay rules and deliberate legacy-connect behavior. Administrative UI refreshes alone must not initiate recovery. Validate in the emulator without clearing installed app data.

</frozen-after-approval>

## Implementation Notes

- Reproduced by the preceding actual-emulator acceptance check: save/delete a disposable nonselected Standard profile, then reselect Amanda; no connection until New conversation.
- Root cause: MainActivity onChanged calls runtime.configurationChanged for Standard, but the connection LaunchedEffect keys contain only selected ID, paired state and mode. Runtime deliberately disconnects; none of those keys changes.
- Add a connection-specific revision local to MainActivity, changed only alongside runtime configuration reset, and key the existing effect with it. General configurationRevision also covers Home administrative refreshes, so do not reuse it as a recovery trigger.
- Footprint: MainActivity, focused in-memory Compose regression, existing README/CHANGELOG, this spec. No public API, migration or credential mutation. No unresolved intent choice.
- Verify a real user action through the configuration sheet, then a typed turn accepted after reconnect. Include same-profile reselection and nonselected-profile deletion. Tests use only in-memory profile/credential stores.
- Build APKs, install with adb install -r, and invoke only reviewed instrumentation classes directly; never Gradle connectedDebugAndroidTest on the configured emulator. Preserve Amanda Standard, existing history, endpoint and token.
- Worktree baseline: be3a6c7b0967dc2c0cbf82055641c8240d8a507a. The user approved isolation from existing main-checkout evidence and Gradle edits.
- Separate follow-on investigation: observed default-profile response-audio failure, with text retained; do not conflate it with the connection fix.
- Implemented four production-line changes in MainActivity: dedicated connection revision, effect dependency and increments at both runtime-reset callbacks. Existing Home-administration refresh callback is unchanged.
- Added StandardConfigurationRecoveryTest with two consumer-behavior cases: same-profile reselection and nonselected-profile deletion, each followed by a successful typed submission. Fixtures use in-memory configuration/credential stores; no user profile/preferences/history reset.
- Verification: `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug` succeeded; 556 JVM tests, zero failures. Direct instrumentation passed four cases (two new configuration tests plus the two existing capture-recovery tests). The two new tests then passed 30 consecutive direct runs, 60 executions, zero failures.
- Live API 36 emulator, 21:41–21:44 CDT: reselect Amanda, save a disposable default connection, then delete it while Amanda stays selected. Each mutation was followed by one successful `session.resume` (21:41:23, 21:42:15, 21:43:25), without tapping New conversation. Deliberately submitted CONFIGURATION_RECOVERY_OK at 21:43:31; response completed and audio drained at 21:43:39. Screenshot verified Ready, Verified and the exact completed reply.
- Disposable connection and test APK removed. Amanda's real profile and credential remained available after all 30 regression runs; final UI at 21:49 showed Amanda Standard, Verified and Ready. No radios, server grants or original credentials changed. Main checkout edits remain untouched.
- Audio investigation: direct authenticated speak-stream probes returned `fallback` with zero PCM bytes for default, versus start/end with 69,888 PCM bytes at 24 kHz mono for amanda. A second default probe also returned only `fallback`. Under the server's actual profile-scoping helper, default resolved provider=openai, no streaming override, OpenAIStreamer.available=false and no streamer; amanda resolved its OpenAI streamer with availability=true. The upstream audio route sends fallback when provider resolution yields no streamer. This establishes a missing usable OpenAI audio credential in default's effective configuration, not emulator playback failure. No keys were logged and no shared configuration was changed.
- README now explains automatic configuration recovery and the existing requirement for profile-scoped streaming TTS capability; CHANGELOG records the fix. Physical-device and cross-mode acceptance are not claimed.

## Review Triage Log

- Inline review only; no independent-review claim. Checked effect invalidation, the two reset callbacks, selection changes, legacy eligibility, and administrative refresh separation.
- False — broad configuration refresh could reconnect after an unrelated administration action: the new connection revision changes only with runtime reset, not onHomeAdministrationChanged.
- False — adding an immediate connect could duplicate the selection effect: no immediate connect was added; the existing keyed effect owns recovery. The live three-mutation window showed exactly one successful resume per mutation.
- Investigation resolved, no Android patch — default-profile audio failure originates in upstream provider eligibility; Android already preserves text and reports unavailable. Enabling TTS for that shared server profile is a separate configuration decision, not part of this fix.
