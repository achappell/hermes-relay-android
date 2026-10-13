---
title: 'Validation — 5-A-2 Android accessibility order and focus restoration'
type: 'validation'
story_id: '5-A-2'
created: '2026-09-12'
status: 'done-with-environment-limitation'
---

## Scope

Validate the `UX-DR21` reading order, live-region announcement behavior,
heading exposure, and focus restoration. Contrast ratios, reduced-motion
behavior, and real screen-reader usability are explicitly out of scope and
unverified.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21. 88 unit tests, 0 failures; debug APK assembled; lint
  clean, including its accessibility checks.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed on
  `hermes-relay-api36` (Android 16 / API 36). 21 instrumentation tests, 0
  failures, 0 skipped (3 new).
- `scripts/check-apk-metadata.sh`, `git diff --check` — passed.

## Evidence notes

- The tests read the **real semantics tree** via `fetchSemanticsNode()` rather
  than asserting that modifiers were written. Traversal indices are compared
  directly: state before response, response before action.
- Live-region modes are asserted by value, not merely present: connection state
  and response text are `Polite`; the audio-unavailable notice is `Assertive`.
- The Profile label is asserted to carry the `Heading` semantics property.

## What was NOT verified

This is the important part of this record.

- **No screen reader was run.** TalkBack was not enabled, and no real
  navigation pass was performed. The semantics tree being correct is necessary
  but not sufficient for usability; announcement wording, verbosity, and
  gesture navigation are unproven.
- **No contrast measurement.** `UX-DR21` names WCAG 2.2 AA contrast targets.
  Nothing here measured a contrast ratio, and the app still uses the default
  Compose theme. That obligation remains open.
- **No reduced-motion work.** `UX-DR21` names reduced-motion static states. The
  app currently has no animation to suppress, so nothing was done — this should
  be revisited when animation is introduced rather than treated as satisfied.
- **Focus restoration is proven only by construction**, not by an assertion:
  the effect requests focus when an accepted turn settles, but no test verifies
  the focused node afterwards. A real device pass should confirm it.

The story's claim is the reading order, announcement behavior, and headings.
The remaining `UX-DR21` obligations are named here so they are not assumed
closed.

## Deferred

- WCAG 2.2 AA contrast verification and any palette work it requires.
- A TalkBack navigation pass on a physical device.
- Reduced-motion handling, once the surface has motion.

## 2026-10-13 — Clipped TalkBack focus follow-up

The historical September record above remains unchanged. This follow-up fixes
the separate defect where native TalkBack focused a completed-turn label
partially hidden beneath the composer. The focus-only implementation is in
[spec-talkback-focus-visibility.md](spec-talkback-focus-visibility.md); it does
not include the blocked status-speech or notification changes.

- The original regression failed before the bridge: label bottom
  `274.66666 dp` exceeded viewport bottom `256 dp`. That earlier reproduction
  was not rerun merely to reconfirm it.
- On the isolated focus-only build, JVM tests, debug/test APK assembly and
  lint passed. `AccessibilityOrderTest`, `MainActivityTest`,
  `MicrophoneCaptureTest`, `MicrophonePermissionTest` and
  `HomeRuntimeRecreationTest` passed **35 instrumentation tests** in
  **51.469 seconds** on the existing API 36 emulator.
- The new regression uses the actual Compose accessibility provider and
  asserts full short-label visibility, retained accessibility focus, unchanged
  composer keyboard focus, no scrolling after declined focus, and no jump
  when refocusing an already visible label.
- Actual TalkBack `16.0.0.738667889 (60149341)` activated a synthetic Standard
  turn at `01:56:07.927`; the content-free journal recorded audio drained at
  `01:56:12.158`. The reply text was visible. Native Alt-arrow traversal
  revealed the initially clipped Complete label entirely above the composer;
  its green focus bounds remained on that label while the composer retained
  its caret. The next navigation revealed the response heading/text.
- Two actual Activity recreations were observed at `01:59:01.747` and
  `01:59:02.253` with `config_change=true`, the same app process, retained
  response and working native traversal after returning to portrait.
- APK metadata passed: minimum SDK 26, `0.3.1 (301)`, backups off with
  extraction rules, 16 KB alignment. Signing verification was skipped because
  `EXPECTED_SIGNER_SHA256` was unset. The first metadata invocation selected
  the host's non-SDK-bound analyzer and could not locate build tools; explicitly
  selecting the installed SDK's `cmdline-tools/latest/bin` resolved it.
- TalkBack was disabled again, its notification permission remained denied
  with only the original `USER_SENSITIVE_WHEN_GRANTED` flag, rotation returned
  to accelerometer enabled/user rotation zero, and Wi-Fi/data remained enabled.
  The selected profile was Verified/Ready with the response retained and an
  empty draft. Only the test APK and test-owned temporary captures were removed;
  the authenticated emulator keyboard channel was closed. No main-app data,
  credentials or history were cleared.

The same unchanged bridge had an earlier actual 40-line response traversal
check in the combined attempt; that oversized-response check was not rerun on
the isolated APK. Failed-reveal return preservation and replacement-delegate
ownership were inspected directly, not claimed as separately executed tests.
Physical Pixel, human-audibility, gesture-only navigation, contrast and full
landscape layout acceptance are not established by this follow-up.

**Still open at this focus-only checkpoint:** immediate post-reply network recovery can leave obsolete polite
speech queued in TalkBack. The combined attempt remains blocked and unpublished
in the separate `fix/talkback-focus-announcements` worktree. Do not treat this
focus fix as speech acceptance or a waiver of any broader story gate.

## 2026-10-13 — Recovery status on focus

**Decision:** Amanda chose read-on-focus ordinary availability/retry status.
This supersedes the old polite connection-status requirement, not TalkBack's
queue implementation. See [the approved spec](spec-talkback-recovery-announcements.md).
The separate combined attempt remains parked; its counterexample is historical
evidence, not a new pass.

- Based on merged main `d108cf3b076bf05d9ab0c675137400dec531fc84`, with the
  focus-reveal, notification and compact-layout fixes retained.
- The first two policy regressions failed on unchanged production (2 failures,
  1.614 s). The initial fix passed 38 focused tests but failed native smoke:
  Standard reports `Verifying` when its transport is absent, so authorization
  status and both Profile-block instructions still queued live speech.
- A real `Verifying` snapshot fixture failed before refinement (1.149 s).
  Header authorization and composer/capture availability duplicates now follow
  the same on-focus policy. Permission guidance and assertive audio failures
  retain their existing semantics and regressions.
- JVM tests, debug/test assembly and lint passed (24 s). The final 39 focused
  instrumentation tests passed in 69.301 s: `MainActivityTest`,
  `AccessibilityOrderTest`, `HomeVoiceNotificationTest`, `MicrophoneCaptureTest`,
  `MicrophonePermissionTest` and `HomeRuntimeRecreationTest`. The final run also
  covers the review-requested displayed/readable disconnected explanations.
- Actual API 36 emulator, TalkBack `16.0.0.738667889 (60149341)`, native Alt-arrow
  navigation and inspected screenshots; no UIAutomator while TalkBack ran.
  **Idle:** at `08:10:49.453`, reading focus stayed on the long header description
  through Unavailable/retry/Verifying and recovery. The session resumed at
  `08:10:50.950`. During the 30.607 s observed window, speech contained the
  requested description and preserved empty-prompt hint, but no automatic
  availability, retry or Profile-verification claim.
- **Immediately after reply:** one typed prompt was accepted at `08:17:28.469`;
  real response audio drained at `08:17:36.879`. Radio loss began at
  `08:17:36.923` (44 ms later), with accessibility focus on Send, not status.
  Unavailable/retry/Verifying was visible; session resume was recorded at
  `08:17:37.657` and again at `08:18:12.021` as networking settled.
  The 38.976 s post-drop window contained no automatic availability/retry/Profile
  claim. Existing turn-phase feedback and the empty-prompt hint still spoke.
  Only one accepted prompt was recorded; no resend was observed. Native traversal
  subsequently revealed and read the retained response with the composer caret
  and empty draft intact.
- **On demand:** native focus read Ready and its explanation, then on another
  bounded outage read `Connection: Reconnecting (attempt 2 of 6)` at
  `08:21:17.246` and Unavailable at `08:21:18.592`. The retry counter continued
  changing while focused; this is a focus-time reading, not queue cancellation.
  After recovery, explicit focus read Ready again at `08:22:03.658`.
- APK metadata passed: minimum SDK 26, version `0.3.1 (301)`, backup disabled
  with extraction rules, 16 KB alignment. Signer comparison was skipped because
  `EXPECTED_SIGNER_SHA256` was unset.
- Restored TalkBack off/no enabled services and ERROR logging; its notification
  permission stayed denied with only `USER_SENSITIVE_WHEN_GRANTED`. Wi-Fi/data
  enabled; font 1.0, automatic rotation/user rotation 0 and hardware-keyboard IME
  setting 0 unchanged. Final app was Verified/Ready with an empty focused draft
  and zero wake locks; keyboard dismissed afterward. Removed only the test APK
  and owned captures/XML, and closed the authenticated keyboard channel.
  No main-app data, credential or history reset; no unattended microphone capture.

Two independent read-only reviews found no remaining supported production
defect; the readable-connection-explanation coverage finding was fixed.
Runtime/reconnect/voice timing code did not change, so this is not a new
30-run timing acceptance claim. Physical Pixel, human-audibility and gesture-only
acceptance remain unverified. Already-requested speech and focused-node updates
remain TalkBack-owned. Existing Profile-edit wording during transport loss is
unchanged; these checks did not establish a credential fault.
