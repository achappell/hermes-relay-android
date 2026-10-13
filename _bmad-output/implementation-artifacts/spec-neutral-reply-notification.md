---
title: 'Keep reply-notification text truthful after delayed delivery'
type: bugfix
created: '2026-10-13'
status: done
route: oneshot
baseline_revision: fa96838a0e924a1b95f52fe80cb39880e998c247
review_loop_iteration: 1
context: []
---

<frozen-after-approval reason="unattended implementation authorized by Amanda">

## Intent

A delayed screen-reader announcement of the media notification can say Hermes is replying after the reply has already finished. Describe the notification's purpose with the localized, phase-neutral label “Reply playback controls” instead of claiming the current playback phase. Real media-session state and Pause/Play/Stop actions remain authoritative and unchanged.

Given plain or media reply notifications, when playback moves through Playing → Paused → Playing, then their descriptive text stays phase-neutral while real media state and the corresponding controls remain accurate. Given retention ends, when the existing service teardown runs, then its notification and media session disappear as before. Do not change runtime ownership, retention timing, audio focus, network behavior, credentials/history, or the separate in-app TalkBack announcement policy. Preserve the user's emulator setup; do not merge or deploy, and do not claim physical-phone or human-audibility acceptance.

Keep this independent of the focus PR and the blocked polite-speech investigation. Add a real-service instrumentation regression for notification-text stability across phase transitions and teardown; inspect actual posted notifications rather than asserting source text or a mock builder. Verify the actual app's background playback controls on the existing emulator and restore the starting setup.

</frozen-after-approval>

## Implementation Notes

- No intent gap or irreversible production change; small, independent bugfix.
  `HomeVoiceService.kt` uses one localized controls description for both plain
  and media notifications. The unused state argument and plain-notification
  wrapper are removed, with all callsites migrated. `strings.xml` removes both
  obsolete phase-only labels. Playback state, actions and lifecycle are unchanged.
- `HomeVoiceNotificationTest.kt` uses the real foreground service, posted Android
  notifications and media controller. It checks a stable description across
  plain/Playing/Paused/Playing, action changes, preserved Stop and session identity,
  and final notification/session removal. The session-destroyed assertion waits
  on a callback latch; no arbitrary test sleeps are added. Cleanup restores the
  original static service state and stops only the service the test started.
- The pre-fix regression ran and failed at the intended invariant:
  `expected:<[Hermes is replying]> but was:<[Paused]>`. The test fixture did not
  require a live relay or read/change user credentials. The app's original
  notification permission was already granted with
  `USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED`; retain it.

## Review and Verification

- Blind implementation review found one verified low-severity wording issue:
  the plain foreground notification has no inline controls. Refined the caption
  to **Reply audio**, which describes both plain and media forms truthfully.
  This refines the proposed wording in the frozen intent above, not its
  phase-neutral behavior or scope. No phase/status or runtime behavior changed.
- Independent real-service regression/isolation review found no actionable
  issue. No deferred implementation changes or unresolved intent questions.
- Full JVM tests, debug app/test assembly and lint passed, including a fresh
  run after the resource-only wording refinement. Final APK metadata passed;
  signer verification was explicitly skipped.
- 35 focused emulator tests passed in 52.001 s. The new lifecycle test passed
  30 consecutive runs without failures before the wording refinement, then
  passed again on the final APK in 3.426 s. Its pre-fix failure is recorded above.
- Actual authenticated Standard playback on the API 36 emulator exercised
  background notification Pause/Play, observed session states 3 → 2 → 3,
  kept the **Reply audio** caption and drained naturally. Exact timing and the
  redundant shell Play after the real Play tap are recorded in
  `validation-android-home-08.md`; the screenshot was inspected then removed.
- Final cleanup observed no service or Hermes media session, zero active
  wake locks, Ready, the original selected Standard profile and an empty draft.
  TalkBack remained off; rotation, network settings and notification permission
  matched their starting values. Media volume was already 0 and remained 0.
  Only the test APK and this run's temporary captures/scripts were removed;
  no main-app removal, credential reset, server deployment or merge.
- Physical acceptance, manual Stop interaction, human audibility and the
  separate queued in-app TalkBack speech blocker remain outside this fix.

## Review Triage Log

- Verified low / patched: “Reply playback controls” implied inline controls on
  the plain notification, which has none. Replaced it with **Reply audio** and
  verified the final APK; the independent isolation review found no further issue.
