---
id: ANDROID-PARITY-01
title: Android parity with iOS 0.7.0 Home reliability and diagnostics
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-02
  - home:HOME-NW-18
github_issue: https://github.com/achappell/hermes-relay-android/issues/81
---

# ANDROID-PARITY-01 — Android parity with iOS 0.7.0 (umbrella)

Umbrella for the Android tickets that mirror what changed in `hermes-relay-ios` v0.7.0 (`755e03e`, merged PRs #122 `e7859db` and #123 `5aff151`) and the Home server contract those changes depend on. It tracks scope and order only; each child ticket owns its acceptance criteria. Status authority stays in `sprint-status.yaml`.

## Home server dependency

Deployed on CaticornQueen (2026-10-05): `hermes-relay-home` `0effbf9`, which includes PR #74 (`9b445bc`, response-audio timeout starts only after speech is requested), PR #75 (opt-in `turn.alive` keep-alive, header `X-Hermes-Home-Client-Features: turn_keepalive`), PR #76 (windows runner), PR #77 (deployment record) and PR #78 (header-parsing test, `790f59e`). Earlier, already deployed: HOME-NW-06 diagnostics correlation (PR #71, #73), device connection reports (PR #67), HOME-NW-18 claim list/close (PR #69, `082e593`), HOME-NW-17 client claims.

**Known Home gap that blocks Android diagnostics reports:** Home's client-report validator accepts only `platform ∈ {ios, macos}` and an Apple model vocabulary, so no Android report can pass (verified in `client_reports.py` at Home `790f59e`). `ANDROID-DIAG-02` and `-03` depend on a Home change that is not ticketed in this repository.

## Children

| Order | Ticket | Title | iOS reference | Home dependency | Android state found |
|---|---|---|---|---|---|
| 0 | [`ANDROID-TEST-01`](https://github.com/achappell/hermes-relay-android/issues/80) | Platform-independent, flake-free lifecycle tests | `b46e8fc`, `31d8a2e` | — | seams absent; CI has no emulator job |
| 0 | [`ANDROID-REL-01`](https://github.com/achappell/hermes-relay-android/issues/79) | Version/build identity label | `19669cf`, `89c40a4`, `27139b6` | — | no version shown; Play build intentionally out of scope |
| 0 | [`ANDROID-DIAG-01`](https://github.com/achappell/hermes-relay-android/issues/76) | On-device journal + Share diagnostics | IOS-DIAG-01 | — | none (no logging at all) |
| 1 | [`ANDROID-HOME-07`](https://github.com/achappell/hermes-relay-android/issues/71) | One process-scoped Home runtime | `8482051` | — | **Activity-scoped; recreation drops the socket and the turn** |
| 1 | [`ANDROID-HOME-05`](https://github.com/achappell/hermes-relay-android/issues/69) | Audio after a slow text turn | IOS-HOME-05, `8c3f163` | `0effbf9` (#74) | no deadline exists; probable bug: audio starting after the control terminal is dropped |
| 2 | [`ANDROID-HOME-04`](https://github.com/achappell/hermes-relay-android/issues/68) | Reconnect after a long background | IOS-HOME-04, `d199525` | HOME-NW-18 | partly done (#61); ladder has no pacing |
| 2 | [`ANDROID-HOME-06`](https://github.com/achappell/hermes-relay-android/issues/70) | Backgrounded transport is disconnected; single close | IOS-HOME-06, `feeb475`, `b46e8fc`, `aabb275` | HOME-NW-18 | no `ON_STOP` handling |
| 2 | [`ANDROID-HOME-03`](https://github.com/achappell/hermes-relay-android/issues/67) | Claim lifecycle, list and close | IOS-HOME-03 | HOME-NW-18 (slice C) | tolerant of `claim_ref`; no list/close |
| 3 | [`ANDROID-HOME-08`](https://github.com/achappell/hermes-relay-android/issues/72) | Background reply/voice retention (FGS, MediaSession) | IOS-HOME-07, PR #122 | — | no service, session or notification |
| 3 | [`ANDROID-HOME-09`](https://github.com/achappell/hermes-relay-android/issues/73) | Audio focus, route change, track restart, cushion, lead | `a776a6d`, `efff91d`, `1235243`, `a7bd524` | — | no focus, no route handling; 1 s cushion with no cap |
| 3 | [`ANDROID-HOME-10`](https://github.com/achappell/hermes-relay-android/issues/74) | `turn.alive` and idle control deadline | `185418a`, `676192d` | `0effbf9` (#75, #78) | header not sent; **no deadlines at all** |
| 4 | [`ANDROID-HOME-11`](https://github.com/achappell/hermes-relay-android/issues/75) | HUD/layout reachability audit | PR #123 `78185b4` | — | Scaffold + LazyColumn already scroll; no orb; unproven at 2.0× |
| 4 | [`ANDROID-DIAG-02`](https://github.com/achappell/hermes-relay-android/issues/77) | Opted-in connection reports (schema 1) | IOS-DIAG-02 | PR #67 + **Android platform change** | none |
| 4 | [`ANDROID-DIAG-03`](https://github.com/achappell/hermes-relay-android/issues/78) | Correlation handshake and schema 2 | IOS-DIAG-03, PRs #119/#120/#121 | PR #71/#73 + **Android platform change** | none |

Order rationale: `ANDROID-HOME-07` is the prerequisite for everything lifecycle-related and fixes a user-visible defect on its own. `ANDROID-TEST-01` and `ANDROID-DIAG-01` are cheap and make every later device check provable. Reconnect and teardown semantics (04/06) precede retention (08), which precedes output resilience (09) and the deadline (10).

## iOS items checked and adjusted for Android

- **IOS-HOME-05 audio-start deadline:** the iOS bug cannot occur (Android has no such deadline); replaced by a regression-and-defect ticket (`ANDROID-HOME-05`).
- **Playback cushion 300 ms/500 ms cap:** Android already waits 1000 ms with no cap; ticket tunes and caps rather than adds (`ANDROID-HOME-09`).
- **Now Playing off the main thread/750 ms deferral:** mapped to MediaSession/notification work on a private thread (`ANDROID-HOME-08`).
- **HUD scroll (PR #123):** Android has no orb and a Scaffold layout; scoped as audit-and-harden (`ANDROID-HOME-11`).
- **TestFlight automation:** Android policy is "Play Store distribution remains intentionally outside this repository"; scoped to a visible version/build identity, with Play listed as an optional, owner-gated follow-up (`ANDROID-REL-01`).
- **Dropped:** macOS-only behavior (window closure, Settings sheet layout); iOS stamping of `CFBundleVersion`.

## Decisions needed before the affected tickets start

1. Background hands-free and background mic continuation (`ANDROID-HOME-08` D1/D2): iOS decisions were approved for iOS only.
2. Add an Android control/idle deadline layer (`ANDROID-HOME-10` part 2): Android currently waits unboundedly.
3. Whether to reverse the Play-distribution policy (`ANDROID-REL-01` optional follow-up).
4. Who raises the Home story for Android report platform support.

## Device gates (none are satisfied by this ticketing pass)

Every behavioral ticket ends with a physical-Pixel gate (lock-screen playback, route loss, call interruption, long background, rotation during a reply). The emulator is not accepted for audio route, microphone or foreground-service claims. iOS itself still carries open device gates: PR #122 recorded that the final Now Playing off-main change was verified by CI and the journal, not by a clean listening test.

## Acceptance

All children are `done` or explicitly deferred with a recorded decision, and their device gates are recorded separately from local acceptance.
