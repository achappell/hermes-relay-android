---
title: 'Validation — 2-A-2 honest Android disconnected and unavailable state'
type: 'validation'
story_id: '2-A-2'
created: '2026-09-12'
status: 'done'
---

## Scope

Validate visible disconnected and unavailable presentation, retained cached
context labelled as not live, turn submission blocked without a live Session,
fresh Session identity on reconnect, explicit-only resend, and isolation of a
superseded socket's late frames. No live Hermes endpoint was required for this
slice; the transport behaviors are proven against a local `MockWebServer`.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21. 67 unit tests, 0 failures (1 new); debug APK
  assembled; lint clean.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed on
  `hermes-relay-api36` (Android 16 / API 36). 12 instrumentation tests, 0
  failures, 0 skipped (1 new).
- `scripts/check-apk-metadata.sh`, `git diff --check` — passed.

## Evidence notes

- **The audit found real gaps, as iOS's did.** Two user-visible
  (retained response text and an unsent draft both reading as live during an
  outage) and one coverage gap (nothing proved replacement-socket isolation at
  the transport level). All three are resolved; the rest of the contract was
  already satisfied by `A-2`, `A-3`, and `A-7` and was left unchanged.
- `a_replacement_session_ignores_late_frames_from_the_superseded_socket`
  connects twice against a `MockWebServer` that issues `relay-session-1` then
  `relay-session-2`, asserts the reconnect adopted the second identity, submits
  a turn bound to it, then has the *first* socket send a `text_final` naming the
  dead Session. The recovered turn's response stays empty and its phase stays
  `Idle`.
- `retained_context_is_labelled_as_cached_while_disconnected` asserts the
  cached labels are absent while connected, then present after a disconnect,
  with the response text still displayed and the turn button disabled.
- The labelling is gated on connection state, not turn phase. A turn that ends
  `Unavailable` on a live Session is a completed answer whose audio never
  arrived — not cached context — and is correctly left unlabelled.

## Deferred

- iOS's third `2-I-2` finding, capture lingering in `Listening` during an
  outage, has no Android analogue: there is no microphone path yet. It was not
  simulated, and will need re-checking when capture lands.
- Response audio playback still does not exist, so a text-only turn ends
  `Unavailable` with its text retained, per the `A-2` contract.
