---
title: 'Validation — 2-A-1 capture acknowledgement and live Transcription'
type: 'validation'
story_id: '2-A-1'
created: '2026-09-12'
status: 'done-with-environment-limitation'
---

## Scope

Validate capture acknowledgement, live provisional transcript presentation,
clearing on cancel, and refusal to submit an utterance whose originating
Session was replaced.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21. 88 unit tests, 0 failures (4 new); debug APK
  assembled; lint clean.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed on
  `hermes-relay-api36` (Android 16 / API 36). 18 instrumentation tests, 0
  failures, 0 skipped (1 new).
- `scripts/check-apk-metadata.sh`, `git diff --check` — passed.

## Evidence notes

- The Compose test drives the full participant path: connect, start capture,
  assert no provisional text before any words, then two successive partials
  each rendering live with the participant label, then cancel — asserting the
  provisional text disappears and no turn was submitted.
- `a_session_replaced_mid_capture_refuses_to_attach_the_utterance_to_it`
  changes the live Session id between `Started` and `Final` and asserts the
  transcript is refused with `SessionReplaced` and nothing is sent. Its
  companion test proves an unchanged Session still submits normally, so the
  rule cannot pass by refusing everything.
- iOS ran its equivalent story as verify-only because its HUD already rendered
  `provisionalText`. Android genuinely needed both the presentation and the
  identity correlation, so this was not a verify-only slice.

## Environment limitation

The emulator has no microphone, so the provisional transcripts in these tests
come from the deterministic speech fake rather than from real recognition.
Partial-result cadence, endpointing, and how quickly real partials arrive are
unverified until a physical device runs the same path — the same limitation
recorded for `A-9`.

## Deferred

- Room-scoped participant state for other speakers is not in scope; this story
  covers the doorway's own participant.
- Hands-free continuation (`A-6`) and interrupt (`A-5`) remain pending.
