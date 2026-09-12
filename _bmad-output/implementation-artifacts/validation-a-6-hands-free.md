---
title: 'Validation — A-6 hands-free continuation'
type: 'validation'
story_id: 'A-6'
created: '2026-09-12'
status: 'done-with-environment-limitation'
---

## Scope

Validate continuation after a completed turn, the exact-"stop" exit, refusal to
continue after a turn that did not complete, and every other exit path.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21. 128 unit tests, 0 failures (11 new); APK assembled;
  lint clean.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed on
  `hermes-relay-api36` (Android 16 / API 36). 26 instrumentation tests, 0
  failures, 0 skipped (1 new).
- Live gate — 3 tests, 0 failures, unchanged by this slice.
- `scripts/check-apk-metadata.sh`, `git diff --check` — passed.

## Evidence notes

- The continuation test asserts **two** consecutive reopenings, not one, so a
  single follow-up cannot pass as continuous behaviour — which is precisely the
  distinction the `FR5` amendment exists to make.
- `stop_only_counts_when_it_is_the_whole_utterance` guards the exact-match rule
  from becoming a substring match, which would silently swallow "stop the
  timer" as a command.
- The refusal test loops over `Unavailable` and `Interrupted` and asserts the
  recogniser start count is unchanged, so "did not reopen" is proven by the
  microphone not being opened rather than by a flag.
- The Compose test drives the real settle path: arm, speak, complete a turn
  through `AudioStarted`/`AudioEnded`/`TurnCompleted`, and assert the window
  reopened without another tap — then say "stop" and assert nothing was
  submitted.

## Environment limitation

The emulator has no microphone, so continuation is proven against the
deterministic speech fake. What a real recogniser does between windows — its
endpointing timing, whether reopening is fast enough to feel conversational,
and whether the device's own speaker leaks into the next window despite the
structural guarantee — is unverified until a physical device runs it.

The structural claim is sound: `Complete` requires drained audio, so capture
cannot begin while the response is still playing. The *acoustic* claim, that no
echo is picked up in practice, has not been tested.

## Deferred

- Barge-in in the strict sense — interrupting the response by speaking over it
  — is not implemented. `A-5` provides interruption by control. Speaking over a
  response would require capture during playback, which is exactly what this
  story avoids for echo safety, and would need acoustic echo cancellation.
