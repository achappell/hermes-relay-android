---
id: ANDROID-NET-01
title: Transport liveness, network awareness and off-tailnet classification
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-04
  - android:ANDROID-HOME-07
parity_source: 'PX-09, PX-10 (reconnect trigger lives in ANDROID-HOME-04), PX-11 remainder (off-tailnet; ANDROID-BUG-F1)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/84
---

# ANDROID-NET-01 — Transport liveness, network awareness and off-tailnet classification

Source: PX-09, PX-10 (reconnect trigger lives in ANDROID-HOME-04), PX-11 remainder (off-tailnet; ANDROID-BUG-F1) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P0, size M.

## Background

iOS detects a dead Home socket with `bridge.ping` (5 s deadline, `HomeOperationDeadlines.ping`), classifies it `transport_unavailable`, and reconnects on its paced ladder. Android has no liveness check, so after Doze, a Wi-Fi to cellular handoff or a silent drop the socket looks open, the UI says Connected, and the first tap fails. `android-open-defects.md` `ANDROID-BUG-F1` records a second gap: the live Home name resolves publicly to a Tailscale address, so off the tailnet DNS succeeds and the user waits out a 10 s connect timeout and a generic `Disconnected`.

## Android today (checked against `main` unless marked [INFERENCE])

- `OkHttpClient` is built with `readTimeout(0)` and no `pingInterval` (`OkHttpRelaySessionClient.kt` ~1819); no `bridge.ping` call exists (grep).
- No `ConnectivityManager` use anywhere in `app/src/main` (grep).
- `AndroidRecoveryController` keeps the last reason into `Failed(lastReason)` (BUG-F2 is fixed), but nothing classifies a connect timeout to a CGNAT/Tailscale range.

## Required behavior

- Detect a half-open socket: enable OkHttp `pingInterval` (start at 15-30 s, tune on device) and send Home `bridge.ping` (5 s deadline) on resume, on network change and before a turn when the last frame is older than a threshold; a failed probe is `transport_unavailable` and enters the `ANDROID-HOME-04` paced reconnect.
- Observe the default network (`ConnectivityManager.registerDefaultNetworkCallback`): `onLost` while foreground shows a `Waiting for network` state (not `Failed`), `onAvailable` triggers the reconnect immediately (the trigger is owned by `ANDROID-HOME-04`; this ticket supplies the event source and the visible state).
- Classify a connect timeout to a `100.64.0.0/10` address, with no default network VPN transport active, as `off tailnet`, with its own message and without exhausting the retry ladder.
- The reason shown after the ladder ends is the last real reason (keep the BUG-F2 fix) and includes the attempt count.
- Nothing here resends a prompt.

## Acceptance criteria

- Fake clock/socket: no inbound frame for the ping interval and a failed pong marks the transport lost within the deadline and starts exactly one recovery.
- Fake `NetworkCallback`: `onLost` then `onAvailable` produces `Waiting for network` then one reconnect; repeated flaps produce no parallel recoveries.
- Connect timeout to a Tailscale-range address with no VPN transport maps to the off-tailnet reason and message; to a public address it stays `transport_timeout`.
- A turn in flight when the probe fails becomes an unconfirmed turn (`ANDROID-HOME-06`), never replayed.
- Journal lines (`ANDROID-DIAG-01`): `home ping result=ok|timeout`, `network event=lost|available transport=wifi|cellular|vpn`, `home connect classified=off_tailnet`.

## Android design notes

- OkHttp pings need an unfrozen process; a foreground service (`ANDROID-HOME-08`) is what keeps them running during a retained reply.
- `NetworkCapabilities.TRANSPORT_VPN` is the signal for Tailscale being up; no permission beyond `ACCESS_NETWORK_STATE` (normal permission, add it).
- Do not request `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (Play policy).
- Keep probe intervals and thresholds injectable; Home advertises `heartbeat: true`, so confirm what Home does with its own pings before choosing the interval.

## Dependencies

`ANDROID-HOME-04` (reconnect ladder), `ANDROID-HOME-07` (runtime that owns the callback). Complements `ANDROID-HOME-06` (liveness before trusting `Connected`) and `ANDROID-HOME-10` (turn-level liveness).

## Test notes

JVM: fake `Clock`, `NetworkEvents`, `HomeTransport`. Instrumented: one smoke that registers the real callback on an emulator (no route claims).

## Device verification

Pixel: start a reply, toggle airplane mode for 20 s, return; switch Wi-Fi to cellular mid-session; leave the phone idle 30 min screen-off, then talk. Off a tailnet (VPN off): the off-tailnet message appears after one timeout, not after three.
