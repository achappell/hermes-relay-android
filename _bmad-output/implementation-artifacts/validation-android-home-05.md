---
story: ANDROID-HOME-05
spec: spec-android-home-05-audio-start-after-turn.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-07
---

# ANDROID-HOME-05 validation record

Stacked on `ANDROID-DIAG-01` (#120): the journal lines asserted here need it. Device runs also include the #119 paired-Profile fix.

| Gate | Status | Evidence |
| --- | --- | --- |
| Baseline-red | Done | The two new client tests failed on the pre-change code (below) |
| Local (JVM) | Passed | Below |
| Real turn on a Pixel 6a against the deployed Home | Passed for the ordinary shape; **slow (>30 s) shape not exercised** | Below |
| Reply slower than 30/60 s end to end | **Unverified** | Needs a prompt that takes that long to generate |

## The defect (reproduced, not just read)

Spec predicted it from the code; it reproduced as red tests. `AndroidTurnStateReducer` turned a `TurnCompleted` that arrived with audio `NotStarted` straight into terminal `Unavailable` ("Response audio was not delivered"), and `OkHttpRelaySessionClient.handleEvent` set `terminalObserved` and cleared the active turn on that terminal, so a later `audio start` was dropped twice. Home PR #74 now sends the control terminal (text complete) and only afterwards starts audio, so a slow reply that had text first showed a playback failure while Home was about to speak.

Baseline-red: `a_slow_reply_still_plays_audio_that_starts_after_the_turn_completed` and `home_audio_unavailable_after_a_long_pre_speech_wait_keeps_the_text` both FAILED before the change (AssertionError: the turn was terminal/failed while waiting). The reducer tests use a new event, so they are compile-red against the old code.

## What changed

- New event `TextCompleted`: the text turn is done but Home advertised audio that has not started. The reducer keeps the turn non-terminal (`Buffering`, `turnCompleteObserved = true`, text kept), then `AudioStarted` -> `Speaking`, `AudioEnded` -> `Complete`, `AudioFailed` (Home `unavailable`/`fallback`) -> `Unavailable` with the text kept, a dropped connection -> `Disconnected`.
- `OkHttpRelaySessionClient`: on `TurnCompleted` with audio advertised and not yet seen this turn, it delivers `TextCompleted` and holds the turn open (`awaitingAudio`; no `terminalObserved`, no `clearTurn`); audio start/end/failure settle it. Resumed (unresolved) turns never wait. No timer is armed anywhere: nothing runs from `prompt.submit` acceptance, and HOME-10's idle rules own any later expiry.
- `HomeRuntime` treats `TextCompleted` like `TurnCompleted` for resolving the Home turn and learning the conversation reference, and a reply waiting for late audio still counts as in flight (teardown is deferred until the audio outcome).
- Journal (DIAG-01): `home turn terminal completed audio=pending|none`, `home audio started after_text_ms=N`, `home audio completed`, `home audio failed phase=afterText|duringTurn|drain`.

## Spec decisions recorded

- **Post-text deadline:** none exists and none was added ("do not add a deadline solely to satisfy this test"). A turn whose text is complete and audio never starts stays in the waiting state until Home sends `unavailable`, the transport drops, the user interrupts, or `ANDROID-HOME-10`'s idle deadline (not yet built). **Risk:** until HOME-10, a Home that advertises audio but never sends either audio or `unavailable` leaves the turn waiting.
- **HOME-10 guard test** ("no audio-start timer armed at acceptance"): `HomeTurnDeadlines` does not exist yet; the guard belongs in HOME-10's PR.
- The tests use a latch-held test double in place of 40 s/60 s of fake time: there is no timer in the code under test, so how long the gap is cannot matter; a test that sleeps would prove nothing extra.

## Local gate

- `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin`: passed; 315 unit tests, 0 failures (307 on the DIAG-01 branch + 2 client tests + 5 reducer tests + 1 runtime test).
- Repetition gate and tracking checks: see the PR.

## Device (Pixel 6a, Android 17 API 37, paired Spark Profile, debug build via `adb install -r`)

A typed, neutral prompt ("Reply with the single word ok") was sent through the UI; device media volume was set to 3 for the run and restored to 12. Journal lines, in order (content-free): `home connect conversation.open result=connected ...`, `home bridge request completed method=prompt.submit duration_ms=119`, `home turn terminal completed audio=pending`, `home audio started after_text_ms=629`, `home audio completed`. UI phases read from the screen (text of the reply not recorded here): `Turn phase: Speaking` then `Turn phase: Complete`, no playback-failure message. On the baseline code the same sequence (terminal 629 ms before audio) shows the failure state. A second identical turn reproduced the phases.

Not exercised: a reply that takes more than 30 s to produce (Home `0effbf9` path), `audio unavailable` from the real Home, and microphone/spoken turns (no microphone permission was granted to the app).

## Observations for other tickets (not changed here)

- Two connects within 100 ms of launch (`conversation.open` then `conversation.reconnect`): needs the single-flight guard (`ANDROID-HOME-03/04`).
- After `am force-stop` (no `conversation.close`), the next launch shows "Your last conversation is still held by another connection" and opens a fresh conversation: the parked claim was not released (`ANDROID-HOME-03`/`-06`).

## Bounded follow-up — 2026-10-08 UTC (not slow-audio acceptance)

Story remains `review`. The shared HOME-07 acceptance used one neutral bounded
household prompt, source `98ee22932036aff9871dcfebd9f62995bf82417b`, APK
`b8c91c3be1a95a3b5d2c931a1472051aaf01066d2e3e69cecf4ceba6b6440cdc`, Pixel API 37,
against source-verified Home `d803994d1d47c63bb1b3c92cff42695de19a4434`.
`prompt.submit` response at `01:34:59.260Z`; AudioTrack creation at
`01:35:18.474Z`; text terminal at `01:35:21.888Z`; audio completed at
`01:41:35.882Z`, final UI Complete. Acceptance-response to actual output-track
creation is **19.214 seconds**, not >30 or ≥60 seconds. Long playback
(377.408 seconds) is not a slow-start substitute.

`home turn terminal completed audio=none` in this run means no *post-text
audio wait*: speech had already started. The client emits `home audio started
after_text_ms=...` only for a post-text start. Actual UI and AudioFlinger proved
ongoing speech; absence of that particular journal event is not evidence of
no audio. No second prompt was sent merely to seek an uncontrollable delay.

PR #129's recorded post-text starts (375 ms and 2,705 ms), plus this story's
earlier 629 ms result, support ordinary late-audio ordering, not a measured
>30/60-second start. Existing deterministic delay/failure tests remain valid;
the required slow live shape remains unverified. Next owner: Android/Home
acceptance operator must obtain a legitimate measured slow producer response,
or the product owner must explicitly decide acceptance scope. No producer
delay, Home configuration/deployment or new waiver was introduced here.
Phone APK/settings/media volume and mute state restored; pairing/credentials
and saved Profile/session references preserved, legitimate history retained.
