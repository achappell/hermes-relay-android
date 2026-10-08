---
title: 'ANDROID-HOME-12: attribute confirmed user Disconnect without labeling programmatic closes'
type: bugfix
created: '2026-10-07'
status: in-progress
route: oneshot
review_loop_iteration: 0
context:
  - '_bmad-output/implementation-artifacts/spec-android-home-12-disconnect-action.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

HOME-12 requires a deliberate Disconnect to be journaled as `initiator=user`. Current code only records generic `home claim released reason=disconnect`, which is also used for programmatic teardown and therefore does not establish user attribution. Amanda explicitly selected the fix, not a revision removing that requirement.

Add a fixed content-free user-attribution event at the confirmed user-disconnect boundary. Preserve existing reason=disconnect evidence, claim-release/socket-close path, interrupt-before-close order, blocked-submit guard, cancellation behavior and no-replay/reconnect rules. Generic `endSession()` and programmatic close must not acquire user attribution merely because they release a claim. Reuse injected journal and test patterns; no unnecessary enum or new abstraction.

Prove the missing attribution before the fix using the runtime/log seam, then prove confirmed user disconnect emits exactly once, cancellation emits none, and nonuser close is not labeled user. Preserve observable close/order assertions rather than testing only literal wording. Request independent review and fix actionable findings. Run narrow local gates and the authorized actual-app Disconnect → Home's bound WebSocket `conversation.close` closed tombstone (`stopped`, per Home v1 contract README lines 528–530 and 546, corroborated by read-only Home observer proof) → Connect and neutral bounded reply → midreply Disconnect acceptance. Use compatible install-r only, no connectedAndroidTest/uninstall/clear-data, Unpair/re-pair/grant changes, Home deployment or automatic merge. Restore the original phone APK/settings and preserve private configuration/history; finish with capture/drivers stopped.

Publish a separate code PR with documentation/changelog and precise validation, leaving PR141 status-only and untouched. No CI watch; report CI as unchecked/running rather than green. Whole HOME-12 is done only when all nonwaived acceptance criteria pass; physical TalkBack speech/focus/gesture checks are explicitly waived for HOME-12 only and remain unverified. Real household test turns are authorized and retained as legitimate history; no migration or irreversible product change is introduced.

</frozen-after-approval>

## Implementation Notes

- Chosen seam: `HomeRuntime.disconnect()` after `disconnectEnabled` and the one-shot `userDisconnected` gate. Generic client `endSession()`/`close()` remain unattributed and retain `reason=disconnect`. No API or enum added.
- Baseline red: three selected runtime cases executed; both accepted-disconnect attribution assertions failed (expected one, observed zero); programmatic-teardown negative case passed. Original Pixel APK `5692a179…deb0b` also performed actual idle Disconnect at `2026-10-08T03:30:04.736Z`: UI offered Connect, journal recorded `home claim released reason=disconnect`, and user-attribution count was zero.
- Owner waived only HOME-12 physical TalkBack speech, focus, and gesture checks on 2026-10-07 CDT / 2026-10-08 UTC. They remain unverified and are not claimed as passed; no HOME-13 waiver is implied.
- Close-ACK fix: the user `endSession()` path now awaits a valid `conversation.close` acknowledgement using the existing RPC timeout, logs release only on `status=closed`, and records timeout/error as unacknowledged while still cleaning transport. Programmatic close remains non-blocking and truthful.
- Red/green and final local gates: the delayed-ack regression failed before the fix; targeted regression suites passed after it. The final `testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin` command passed. Independent close-ACK review found no actionable defects.
- Corrected active-device proof: Android user attribution at 04:42:37.843Z; ACK-backed release at 04:42:38.167Z; exact Home claim persisted `closed/stopped` at 04:42:36.560Z before peer close 04:42:36.607Z. The earlier pre-ACK `client_disconnected` failure and the intermediate driver mis-tap are retained in validation and are not represented as passes.
- Original APK and authoritative fresh pairing bytes were restored after the smoke; Home deployment/configuration was not changed.
