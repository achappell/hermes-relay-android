---
title: 'Keep intentional interruption distinct from audio failure'
type: 'bugfix'
created: '2026-10-14'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context: []
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** A real Home emulator turn displayed the response-audio failure warning after an explicit Interrupt. `AndroidTurnStateReducer` maps every confirmed interruption to `AndroidAudioDelivery.Unavailable`, contradicting the existing contract that deliberate interruption is not failure. Independent live probes verified valid speech from the selected Profile and successful Home playback through Android AudioTrack.

**Approach:** Represent deliberately stopped in-flight audio distinctly from unavailable audio. A confirmed interruption must stop the turn, preserve partial text, and avoid manufacturing an audio failure. Preserve an actual earlier audio failure and its reason; do not erase it when the user interrupts. Preserve not-started and already-delivered audio facts. Late events must not revive or relabel an interrupted turn. Keep the existing UI interruption notice and genuine audio-failure warning; no copy-only suppression, protocol changes, server configuration changes, automatic retries, or new permissions.

</frozen-after-approval>

## Implementation Notes

- Baseline: `fe807c11293be0b0173337397b8b4c6ea2062ee2` in the isolated Android worktree. No user-owned main-checkout changes are included.
- No unresolved intent choices, irreversible source changes, or external API additions. Small reducer/enum change with boundary regression coverage; existing `DoorwayZones.kt` warning condition should remain unchanged.
- Source map: `AndroidTurnState.kt` owns audio delivery and terminal-event projection; `AndroidTurnStateTest.kt` owns pure transition coverage; `DoorwayZones.kt` renders interruption and audio failure independently. Kotlin language-server support is unavailable; direct symbol-reference search located the exhaustive audio-state consumer in the reducer.
- Evidence before fix: native Home Interrupt showed both interrupted state and unavailable-audio warning. Direct Standard speech for the selected shared Profile produced 120,576 bytes of 24-kHz mono PCM. The disposable real Home enrollment produced 102,400 bytes through emulator AudioTrack, drained with no failures or underruns. A separate emulator speaker mute was discovered and corrected using native volume controls; it is not the reducer defect.
- Verification will retain failing-before/passing-after reducer regressions, run repository-required checks and repeated lifecycle coverage, and exercise the corrected native Home interruption surface. Disposable enrollment, instrumentation, and local test history will be removed; the original Standard configuration/history must remain unchanged. No physical-device or human-heard audio claim.
- Implemented `AndroidAudioDelivery.Stopped` for interrupted buffering/speaking; preserved `NotStarted`, `Delivered`, and genuine `Unavailable` facts. The UI warning condition and copy remain unchanged. An existing real failure retains its original reason instead of the interruption reason.
- Baseline reducer run: 13 tests, 2 failures in the no-false-audio-failure and preserve-real-failure-reason regressions. Final fixture-free verification: `testDebugUnitTest assembleDebug lintDebug :app:assembleDebugAndroidTest --no-daemon` passed; APK metadata passed (signer matching skipped because the expected signer was unset); the final 13-test reducer suite passed 30 consecutive runs with zero failures.
- Corrected-app live Home playback: 142,592 bytes / 71,296 frames accepted by AudioTrack, started and fully drained, zero underruns/failures, `Complete` / `Delivered`. Native Speaking → Interrupt → Interrupted preserved text and showed no false response-audio warning, including the response footer.
- The native Interrupt check temporarily denied microphone permission to prevent auto-listen capture. Its microphone-unavailable guidance was expected, not response-audio failure. Original permission and flags were restored exactly. Earlier harness readiness attempts incorrectly required aggregate Ready despite that deliberate denial, then sampled authorization before it settled; neither submitted a turn.
- The actual emulator speaker was unmuted and left at the observed 4/15 level. A shell request for 5/15 affected the inactive default device, not the active speaker, so it is not recorded as a successful speaker-volume setting. Current process flags do not disable emulator audio; human-heard output remains unverified.
- Disposable Home device revoked, temporary Profile/credential/history and test package removed, diagnostic source/captures removed. Original saved Profile JSON and history hashes exactly matched the preserved baseline; Amanda Standard returned Verified / Ready. No server configuration or existing ownership was changed.
- Review was performed inline for this bounded change; no independent patch-review claim. Publication/PR/CI dispatch remains deferred until the user requests publication, honoring the workflow's no-push-without-request rule.

## Review Triage Log

- **medium, patched:** interruption also overwrote a genuine prior audio failure's reason. The reducer now preserves that reason; the regression failed before the fix and passes afterward.
- **low, patched:** the existing acknowledgement test asserted literal telemetry values without exercising acknowledgement behavior. Removed it and added the distinct delivered-audio-before-interruption boundary.
- **low, patched verification gap:** existing coverage checked late text but not late audio after interruption. The production terminal guard already rejected those events; added start/chunk/end/failure regressions without changing that guard. Native interruption also retained the response without the false warning.
- **false:** a new speech-provider configuration was needed. Both direct live providers returned valid PCM, and real Home AudioTrack playback completed without changing server settings.
