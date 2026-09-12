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
