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

## Connection-guidance follow-up — 2026-10-13

The September checks and deferred statements above are historical, not rerun
claims. Current Android has microphone capture and response audio.
[Recovery-guidance implementation and failure history](spec-recovery-guidance.md).

### Current checks

- Red emulator regression reproduced the misleading verify-Profile instruction
  on the merged APK. It passes after the shared availability-classification fix.
- Full JVM suite (574 tests), debug/test APK assembly and lint passed. Recovery
  repetition gate: 30 consecutive runs of AndroidRecoveryControllerTest,
  ReconnectLadderTest, OkHttpStandardSessionClientTest and StandardRuntimeTest,
  50 tests per run, zero failures.
- Direct instrumentation on `hermes-std-20261012`, API 36: 48 tests passed.
  Covers connection and authorization guidance, read-on-focus semantics,
  capture/send gating, explicit recovery, preserved uncertainty, configuration
  recovery, Home lifecycle/notification behavior and header/overflow surfaces.
  The first batch's five failures were resolved: explicit substring assertions
  for retained refusal reasons, and keyboard dismissal before three synthetic
  send interactions. A screenshot confirmed the keyboard covered Send; neither
  timeouts nor production admission were weakened.
- APK metadata passed: min SDK 26, version 0.3.1 (301), backup/extraction rules,
  16 KB alignment. Signer verification skipped: EXPECTED_SIGNER_SHA256 unset.
- Independent production and verification reviews found no supported defect.

### Actual installed-app smoke

One controlled typed Standard turn completed and audio drained. Two bounded
Wi-Fi/mobile-data outages showed reconnect guidance, `Connect before sending.`
and `Connect before speaking.`, with disabled Send and Tap to speak rather than
Profile-repair instructions. The completed reply and unsent draft survived.
The content-free journal recorded exactly one accepted prompt across the smoke
and session.resume recovery, including additional observed reconnects after
radio restoration; no replay was introduced.

Both radios were restored in `finally`. Cleanup observed Ready, the original
empty draft, intact saved Profile/credential/history, original TalkBack-off
state and zero wake locks. Only the test APK and owned captures were removed;
the fixed main APK remains installed.

### Limits

Authorization rejection and session RPC refusal were exercised through
MockWebServer and emulator fixtures, not by changing the real saved credential.
No unattended microphone capture, fresh native TalkBack speech acceptance,
physical Pixel acceptance, release-signer verification or prolonged bake was
performed. No server behavior changed. Broader Standard gates remain unchanged.
