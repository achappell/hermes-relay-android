## Deferred from: code review of spec-5-a-3-night-console-validation.md (2026-09-14)

- Hardware acceptance is partially verified on the Pixel 6a: the 37-test
  non-live instrumentation run, default/130% dark configuration rendering,
  rail scrolling, and force-stop/relaunch check passed. Spoken TalkBack linear
  order and selected/live-turn focus restoration are deliberately deferred by
  product decision; the host suite and APK checks cannot settle those
  device-only claims.
- The Android navigation follow-up is implemented: configuration and Local
  History now open from the conversation header menu instead of rendering in
  the home rail. The unlocked post-change connected run passed all 37 non-live
  tests, and the installed build showed the menu and configuration sheet.

### Manual TalkBack traversal and focus restoration

- **Decision:** Defer the manual TalkBack spoken-navigation and
  selected/live-turn focus-restoration pass for the current family-only
  audience. This is a priority decision, not a claim that the behavior passed.
- **Evidence retained:** TalkBack bound on the Pixel 6a, produced TTS synthesis
  and accessibility focus for the overflow menu and configuration sheet, but
  injected traversal did not establish spoken order and no selected/live turn
  was available for post-turn focus restoration.
- **Reopen when:** the audience expands, an accessibility requirement is added,
  or a TalkBack user becomes a target.

## Deferred from: code review of spec-android-home-01-pairing-and-configuration-adapter.md (2026-09-20)

- Confirm the Home-issued credential shape and generation invariant against the Home issuer contract before changing Android validation.
- Decide whether an administrative credential must be bound to the approved Home route; the Android artifact does not define route-change semantics.
- Define an idempotent remote-consume or recovery contract for a one-time credential consumed before local secure storage succeeds; this crosses the Home API boundary.

## Deferred from: bmad-build review of spec-android-watch-f1-listening-without-binding.md (2026-10-10)

- source_spec: `_bmad-output/implementation-artifacts/spec-android-watch-f1-listening-without-binding.md`
  summary: Make `HomeRuntime.switchConversation` disarm hands-free and cancel capture (or keep conversation actions disabled while capture is live), and decide the composer/tap-to-speak behavior during live capture.
  evidence: A switch while the microphone is live leaves it listening with an Idle phase; the hole exists for manual capture today and the armed window is newly exposed by the `isTerminal` change. The fix lives in `HomeRuntime`/`MainActivity`, owned by the HOME-04 stack.
- source_spec: `_bmad-output/implementation-artifacts/spec-android-watch-f1-listening-without-binding.md`
  summary: Decide whether the microphone stops immediately or runs until the recognizer returns when the transport drops while hands-free is armed with no binding, then pin it with a test.
  evidence: The connection-loss callback calls `transportLost` and schedules recovery without cancelling capture; intentionally unchanged here.

## Deferred from: code review of spec-android-home-04-foreground-reconnect-2.md (2026-10-10)

- source_spec: `spec-android-home-04-foreground-reconnect-2.md`
  summary: With a stale claim and an unconfirmed turn the user cannot Resend (`NotConnected`) until Discard clears the uncertainty, after which the next reconnect opens a fresh claim.
  evidence: `shouldReplaceReusedClaim` withholds a fresh claim while `uncertainDelivery` is set (the ticket's `unresolvedTurn == false` gate, which a refused reconnect cannot carry); `resendUnconfirmedTurn` requires Connected. Dropping the uncertain guard would let Resend work on a fresh claim at the cost of possibly duplicating a prompt Home saw; owner decision.
- source_spec: `spec-android-home-04-foreground-reconnect-2.md`
  summary: The reconnect ladder holds the single worker thread for up to about 65 s, delaying approvals refresh, conversation listing and `endSession` queued behind it.
  evidence: `HomeRuntime.recover()` runs `recoveryController.recover()` on `workExecutor`; the spec requires reusing it. Disconnect, stop and Profile switch cancel the ladder at once; unverified how often something queues behind it in practice.
- source_spec: `spec-android-home-04-foreground-reconnect-2.md`
  summary: `switchConversation` calls `recoveryController.recover()` directly, bypassing the lifecycle-generation and coalescing guards of `HomeRuntime.recover()`.
  evidence: `HomeRuntime.switchConversation`; pre-existing call that now runs the paced ladder; a stop that arrives before the ladder starts is not honored.
- source_spec: `spec-android-home-04-foreground-reconnect-2.md`
  summary: A configuration change calls `appStarted()` and bumps the lifecycle generation, which can drop a reconnect queued just before the rotation (resume reconnects paired Profiles anyway).
  evidence: `HomeLifecycleCoordinator.enterStarted`; needs a rotation inside the worker queue delay; operator-handle Profiles have no resume reconnect.
- source_spec: `spec-android-home-04-foreground-reconnect-2.md`
  summary: `MainActivity` lifecycle/Profile wiring and `ConnectivityNetworkEvents` have no automated test.
  evidence: only instrumented tests (outside the CI recipe) can drive them; device checks are pending in `validation-android-home-04.md`.

## Deferred from: code review of spec-android-home-06-backgrounded-transport-disconnected-2.md (2026-10-10)

- source_spec: `spec-android-home-06-backgrounded-transport-disconnected-2.md`
  summary: `latestResumeConnection` and the `MainActivity` stop wiring have no automated test; the runtime seam (`needsReconnect`, `appBackgrounded`) is covered.
  evidence: only instrumented tests outside the CI recipe can drive the Compose lambda; device check pending.
- source_spec: `spec-android-home-06-backgrounded-transport-disconnected-2.md`
  summary: A half-open socket while the app stays foregrounded still shows `Connected` until `ANDROID-NET-01` adds probing; `hasLiveTransport()` is structural only.
  evidence: decision 2 of the build spec; stop-close removes the background case.

## Deferred from: code review of spec-android-home-08-background-voice-2.md, 08a (2026-10-11)

- source_spec: `spec-android-home-08-background-voice-2.md`
  summary: A failed foreground-service start (`startForegroundService` or `startForeground` throwing) is journaled but not fed back to the runtime, which keeps the socket and audio open on a stop without the service it counts on.
  evidence: `ServiceForegroundController.sync` and `HomeVoiceService.onStartCommand` log `fgs start failed`; `HomeRuntime.pushServiceState` records the state as applied. Settle on a device whether a start from a visible Activity ever fails.
- source_spec: `spec-android-home-08-background-voice-2.md`
  summary: A held reply's next stream starts unpaused until the runtime's re-pause (event, main thread, shared worker) lands.
  evidence: `AudioTrackAudioSink.start` cancels the previous stream, which clears the hold; the 1 s startup buffer normally covers the gap unless the worker is busy. Keeping the hold in the sink across `start` is the alternative; revisit with `ANDROID-HOME-09`.
- source_spec: `spec-android-home-08-background-voice-2.md`
  summary: Audio focus denied (for example during a call) is journaled and the reply plays on; delayed gain is not requested.
  evidence: `HomeRuntime.syncFocus`; `ANDROID-HOME-09` owns delayed gain and the focus nuance.
- source_spec: `spec-android-home-08-background-voice-2.md`
  summary: `HomeRuntime.outputPaused` is not shown in the app; a pause while the app is visible has no in-app control.
  evidence: only the notification and focus gain resume; add an in-app indicator when HOME-09 or the UX pass touches the reply controls.
- source_spec: `spec-android-home-08-background-voice-2.md`
  summary: A Stop pressed while a submit is still in flight (no accepted binding yet) cannot latch a binding, so the accepted turn re-enters retention.
  evidence: `HomeRuntime.stopReply` latches `acceptedBinding`; a submit in flight has none. Needs a submit-scoped latch.
- source_spec: `spec-android-home-08-background-voice-2.md`
  summary: No JVM test covers `HomeVoiceService`, `ServiceForegroundController` or `PlatformAudioFocusController`, or the resend path's retention entry.
  evidence: platform classes are device-verified by design; `resendUnconfirmedTurn` shares `syncRetention` with `initiate`, which is covered.
## Deferred from: bmad-build review of spec-android-rel-03-r8-baseline-16kb.md (2026-10-10)

- source_spec: `_bmad-output/implementation-artifacts/spec-android-rel-03-r8-baseline-16kb.md`
  summary: Add a shell-level contract test for `scripts/check-apk-metadata.sh` failure branches (missing manifest attributes, misaligned library) using a stubbed `apkanalyzer` and fixture APKs.
  evidence: No test runs the script; CI only exercises the passing path, so loosening a grep or dropping the alignment call would stay green (verification-gap review, 2026-10-10).
- source_spec: `_bmad-output/implementation-artifacts/spec-android-rel-03-r8-baseline-16kb.md`
  summary: Run the minified release APK on an owner-approved device (pair, QR scan, typed and spoken turn, lock-screen playback, cold-start before/after) before any release tag.
  evidence: R8 runtime correctness is unproven by static checks; install on the Pixel 6a is forbidden for this work (verification-gap review, 2026-10-10).

## Deferred from: compact doorway layout interaction review (2026-10-13)

- source_spec: `spec-compact-doorway-layout.md`
  summary: Add a deterministic real-window IME regression when an isolated keyboard fixture is available; the current permanent test covers composition focus across viewport resize, not Android window pan.
  evidence: Interaction review correctly noted that a synthetic Box resize cannot catch removal of adjustResize. Actual API 36 full-IME smoke reproduced adjustPan and verified the fix at 2x in portrait/landscape, including a fully reachable Send target. Automating that gate must isolate and restore keyboard selection/settings instead of depending on the user's installed IME.

## Deferred from: Standard restart verification (2026-10-13)

- source_spec: `spec-standard-restart.md`
  summary: Investigate the intermittent AudioSinkPauseTest drain-deadline failure without weakening pause semantics or skipping the regression.
  evidence: The first full restart-fix run executed 592 tests and failed only AudioSinkPauseTest.kt:129 (paused reply's failure latch became 0). Audio sink/test source was unchanged. Thirty isolated runs on the prior recovery-guidance baseline passed, and the next full run passed without an audio fix. A pause arriving between the worker's paused check and its fresh deadline clock reads is a source-level candidate, not a confirmed root cause; reproduce with controlled barriers before changing the implementation. Runtime severity is medium if confirmed.

## Resolved: Standard restart audio-pause observation (2026-10-13)

- source_spec: `spec-audio-pause-deadline.md`
  summary: Confirmed and fixed the paused-time deadline race; retain the earlier observation as historical evidence.
  evidence: A latch-controlled pause after the drain pause check reproduced failure on merged baseline a192e0cb25d8024eaadde314f2a2e9ebc8c10686. Transition-based active-time accounting passes that regression and protects write/drain budgets across unseen holds. Final full JVM suite passed; 30 runs of 58 audio/lifecycle tests had zero failures. The real emulator held the same AudioTrack over 35 s via notification Pause, resumed and drained without failure. No timeout increases or assertion suppression.
