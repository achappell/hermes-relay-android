---
id: ANDROID-HOME-04
title: Reconnect to Home after a long background
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-02
  - home:HOME-NW-18
ios_reference: 'IOS-HOME-04'
github_issue: https://github.com/achappell/hermes-relay-android/issues/68
---

# ANDROID-HOME-04 — Reconnect to Home after a long background

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): PX-10 (network-aware reconnect; the event source is `ANDROID-NET-01`), PX-11 (reconnect policy; off-tailnet classification moved to `ANDROID-NET-01`), matrix V14/V15.

Parity with `IOS-HOME-04` (`hermes-relay-ios` commit `d199525`, PR #122, v0.7.0). Home contract: reconnect grace and `stale_conversation` handling from `HOME-NW-18` (Home `082e593`).

## Background

On the pilot iPhone, after more than two minutes in the background, the app stayed "Disconnected · transport_unavailable" and never reconnected. Home recorded no refusal: the app's single foreground connect attempt failed at the transport layer and was never retried. Scene-phase tasks could also interleave, so a stale deactivate undid a newer activate. Separately, a `stale_conversation` refusal on reconnect was treated as final although the open path already replaces the claim.

## Android today (verified against `main` at `3e10ae2`)

Partly done in PR #61 (`36b9582`, "reconnect a paired Profile after Android cuts it off"):

- `MainActivity` observes `ON_RESUME` and calls `latestResumeConnection()` → `recover()` when the selected Profile is paired, `canAttemptConnection` is true and `recoveryState.connection != Connected`. A drop reported while resumed also reconnects (`reconnectIfForeground`), **except while an unconfirmed turn exists** (`!hasUnresolvedTurn`).
- `OkHttpRelaySessionClient.reconnect()` retries once with a fresh claim when reusing the held claim fails (Home closed it after its 120 s grace). This is the Android equivalent of iOS "stale_conversation → open a fresh claim" for the generic `Unrecoverable` case.
- `AndroidRecoveryController.recover()` runs a ladder of `DEFAULT_MAX_ATTEMPTS = 3` attempts **with no delay between attempts**, then ends in `Failed`. Each attempt fails fast on a transport error ("Software caused connection abort"), so the three attempts finish in milliseconds and the Profile lands in `Failed` while the radio is still waking up.
- Nothing cancels or supersedes a ladder on background, disconnect or Profile change; there is no Activity `ON_STOP` handling at all.

## Delta — what this ticket delivers

1. **Paced retry while foreground.** When an open or reconnect fails with `transport_unavailable` or `transport_timeout` while the app is resumed, retry on the iOS `ReconnectPolicy.default` schedule (0.5 s, 1 s, 2 s, 4 s, 8 s: five attempts, each attempt limited to 10 s, 60 s overall — `HomeOperationDeadlines.reconnectOverall`; keep all values injectable) reusing the held claim. Also retry immediately when connectivity returns (`ConnectivityManager.NetworkCallback.onAvailable` for the default network), because on Android the network usually returns *after* the Activity resumes.
2. **Stop conditions.** The retry stops on `ON_STOP` / app background (see `ANDROID-HOME-06`), explicit disconnect, Profile switch or deletion, a newer connect generation, and any non-retryable reason. A cancelled retry returns the state to `Disconnected` (never leaves `Reconnecting` showing).
3. **Serialized lifecycle inputs.** `ON_START`/`ON_RESUME`/`ON_STOP` handling is applied in order through one serialized executor (reuse `workExecutor`) with a generation counter, so a superseded stop cannot close the client a newer start opened, and a superseded start does not reconnect after a stop. Only the newest lifecycle input wins.
4. **Stale conversation on reconnect.** A reconnect refused `stale_conversation` with no pending Home recovery (`unresolvedTurn == false`) releases the old claim where possible and opens a fresh claim in the same attempt, in both the foreground path and the transport-loss path. Confirm the existing single fresh-claim retry covers the `stale_conversation` code specifically (it currently triggers on any `Unrecoverable` after a reused claim); add the test.
5. **Automatic reconnect even with an unconfirmed turn.** The `!hasUnresolvedTurn` guard on `reconnectIfForeground` is dropped for the transport only: reconnect (never resend). The unconfirmed prompt stays offered for explicit Resend/Discard exactly as today.

## Acceptance criteria

- Given the app resumed after >120 s in the background with the held claim intact, then it reaches Connected without a tap; if Home ended the claim, it shows the fresh conversation (Home `ContinueLast`), not a stuck "unavailable".
- Given the first reconnect attempt fails `transport_unavailable`, then attempts continue on the backoff schedule until Connected, the deadline, or a stop condition; the ladder no longer burns its attempts in under a second (test with a fake clock: attempt times asserted).
- Given connectivity returns after the Activity resumed, then a reconnect starts within one second of `onAvailable` without waiting for the next backoff step.
- Given the user disconnects, switches Profile, or the app stops, then no further attempt is made and the state is `Disconnected`.
- Given interleaved `STOP` then `START` (and `START` then `STOP`) lifecycle inputs, then the end state matches the newest input and exactly one client is live.
- Given a transport failure during an unconfirmed turn, then the transport reconnects automatically, the prompt is **not** resubmitted (assert a single `prompt.submit`), and the unconfirmed turn is still offered.
- `reconnect` refused `stale_conversation` with no recovery opens a fresh claim once; with `unresolvedTurn == true` it does not.

## Android design notes

- iOS serialized scene-phase inputs with a chained task in `AppleLifecycleCoordinator`. Android has no scene phases; the equivalent inputs are `ProcessLifecycleOwner`/Activity `ON_START`, `ON_STOP` and the network callback. Prefer a small `HomeLifecycleCoordinator` owned by the Home runtime (`ANDROID-HOME-07`) over more `DisposableEffect`s in `AndroidClientScreen`.
- The ladder belongs in `AndroidRecoveryController` (pure, with an injected clock/sleeper), not in the Compose layer. Keep `maxAttempts` bounded for UI messaging but make pacing injectable.
- OkHttp does not tell you the socket died while the process was frozen; see `ANDROID-HOME-06` for liveness validation before trusting a cached `Connected`.
- Doze/App Standby may delay timers while backgrounded; the retry is a foreground behavior, so no `AlarmManager`/`WorkManager` is needed or wanted.

## Dependencies

`ANDROID-HOME-02` (done). Shares the single-flight connect guard with `ANDROID-HOME-03`. Ordering: land before `ANDROID-HOME-06` and `ANDROID-HOME-08`.

## Test notes

JVM tests with a fake `AndroidClientPort`, a manual clock, and a scriptable reconnect outcome list (the `OkHttpRelaySessionClientTest` pattern). Add the three iOS regression shapes: interleaved lifecycle, foreground transport failure retries held claim until ready (and Disconnect stops it), stale reconnect opens fresh claim. Run each new test against current `main` first and record that it fails.

## Device verification and expected journal lines

Pixel physical device: background >120 s (lock screen, Wi-Fi on), return; also with airplane mode toggled during return. Expected (see `ANDROID-DIAG-01`): `app phase=started`, `home connect reconnect result=unavailable code=transport_unavailable`, `home connect retry scheduled attempt=N delay_ms=D`, `home connect reconnect result=ready unresolved_turn=false`; or `home connect reconnect result=unavailable code=stale_conversation` followed by `home connect open result=ready`. Home side: parked claim reused or a single fresh claim; no duplicate submit.

## References

iOS: `spec-ios-home-04-foreground-reconnect.md`, `validation-ios-home-04.md`; Android PR #61.
