---
id: ANDROID-PARITY-01
title: 'Android parity with iOS: top-level index'
status: backlog
product_epic: 1
kind: epic
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-02
  - home:HOME-NW-18
github_issue: https://github.com/achappell/hermes-relay-android/issues/81
---

# ANDROID-PARITY-01 — Android parity with iOS (top-level index)

**Top-level index.** This ticket is the single entry point for Android parity with iOS. It contains wave 1 below (the tickets that mirror the specific iOS 0.7.0 changes) and links the two epics that close the remaining gap: [`ANDROID-PARITY-02`](https://github.com/achappell/hermes-relay-android/issues/109) (functional) and [`ANDROID-PARITY-03`](https://github.com/achappell/hermes-relay-android/issues/110) (design and polish). A read-only audit (`android-ios-parity-audit.md`, 2026-10-06) rates Android at roughly 35-40 % overall parity (about 40-45 % functional, 30 % design); both epics derive from it, and the audit's `PX-nn` identifiers are retained only as `Source` cross-references.

Wave 1 (this section) is the umbrella for the Android tickets that mirror what changed in `hermes-relay-ios` v0.7.0 (`755e03e`, merged PRs #122 `e7859db` and #123 `5aff151`) and the Home server contract those changes depend on. It tracks scope and order only; each child ticket owns its acceptance criteria. Status authority stays in `sprint-status.yaml`.

## Home server dependency

Deployed on CaticornQueen (2026-10-05): `hermes-relay-home` `0effbf9`, which includes PR #74 (`9b445bc`, response-audio timeout starts only after speech is requested), PR #75 (opt-in `turn.alive` keep-alive, header `X-Hermes-Home-Client-Features: turn_keepalive`), PR #76 (windows runner), PR #77 (deployment record) and PR #78 (header-parsing test, `790f59e`). Earlier, already deployed: HOME-NW-06 diagnostics correlation (PR #71, #73), device connection reports (PR #67), HOME-NW-18 claim list/close (PR #69, `082e593`), HOME-NW-17 client claims.

**Resolved Home change (2026-10-06):** Home's client-report validator originally rejected Android reports. Home PR #80 (`HOME-NW-06-android-platform`, merged 2026-10-06, `f1eeb94`) extends `home:HOME-NW-06-client-reports` to accept `platform: android` and a bounded `Build.MODEL`; the key exists in Home's tracker. Delivered by Home PR #80; deployed 2026-10-06 pending record. `ANDROID-DIAG-02/-03` retain this Home dependency until deployment is recorded.

## Wave 1 children (iOS 0.7.0 changes)

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
- **TestFlight automation:** Android stays on the signed-APK/sideloading path. `ANDROID-REL-01` is version/build identity only; the Play internal-testing proposal is declined in `ANDROID-REL-02` (2026-10-06).
- **Dropped:** macOS-only behavior (window closure, Settings sheet layout); iOS stamping of `CFBundleVersion`.

## Decisions before the affected tickets start

1. **Decided 2026-10-06 — background hands-free and microphone continuation (`ANDROID-HOME-08` D1/D2):** Android matches iOS (foreground-start only, mic continues while armed, 60 s idle timeout, interruption disarms and never re-arms). iOS's self-echo gap is not copied: wake suppression during replies plus a tail window is required (`ANDROID-HOME-08`, `ANDROID-VOICE-05`).
2. **Decided 2026-10-06 — idle control lease (`ANDROID-HOME-10` part 2):** defined separately from audio/response timeouts and driven by `turn.alive`; values mirror iOS (`controlIdle` 45 s, `controlBackstop` 1800 s, 120 s without the capability) and Home's 15 s cadence.
3. **Resolved 2026-10-06 — Play distribution:** stay out of scope per `README.md`; `ANDROID-REL-02` is closed as not planned.
4. **Resolved 2026-10-06 — Home Android report support:** Home PR #80 extends the existing `home:HOME-NW-06-client-reports` key; deployed 2026-10-06, deployment record filed via Home PR #82.

## Device gates (none are satisfied by this ticketing pass)

Every behavioral ticket ends with a physical-Pixel gate (lock-screen playback, route loss, call interruption, long background, rotation during a reply). The emulator is not accepted for audio route, microphone or foreground-service claims. iOS itself still carries open device gates: PR #122 recorded that the final Now Playing off-main change was verified by CI and the journal, not by a clean listening test.

## Acceptance

All wave-1 children and both epics (`ANDROID-PARITY-02`, `ANDROID-PARITY-03`) are `done` or explicitly deferred with a recorded decision, and their device gates are recorded separately from local acceptance.

## Audit PX identifiers to tickets (de-duplication record)

Items already covered by wave 1 were merged, not duplicated.

| Audit item | Ticket | Note |
|---|---|---|
| PX-01 runtime owner | `ANDROID-HOME-07` | merged |
| PX-02 split `AndroidClientScreen` | `ANDROID-ARCH-01` | new |
| PX-03 persist recovery state | `ANDROID-HOME-14` | new (with PX-29) |
| PX-04, PX-05 audio focus, route recovery | `ANDROID-HOME-09` | merged |
| PX-06, PX-07 foreground service, MediaSession | `ANDROID-HOME-08` | merged |
| PX-08 echo-safe route, barge-in | `ANDROID-VOICE-05` | new spike |
| PX-09 transport liveness | `ANDROID-NET-01` | new |
| PX-10 network-aware reconnect | `ANDROID-HOME-04` (trigger) + `ANDROID-NET-01` (source) | merged |
| PX-11 reconnect policy, off-tailnet | `ANDROID-HOME-04` (backoff) + `ANDROID-NET-01` (off-tailnet) | merged/split |
| PX-12, PX-13, PX-14 | `ANDROID-VOICE-01`, `-02`, `-03` | new |
| PX-15 playback latency | `ANDROID-HOME-09` | merged |
| PX-16 interrupt primary | `ANDROID-VOICE-04` | new |
| PX-17 tokens | `ANDROID-UX-01` | new |
| PX-18 orb and HUD | `ANDROID-UX-02` | new |
| PX-19 session header | `ANDROID-UX-03` | new |
| PX-20 Disconnect | `ANDROID-HOME-12` | new |
| PX-21 transcript rail | `ANDROID-UX-04` | new |
| PX-22 compact composer | `ANDROID-UX-05` | new |
| PX-23 connection details | `ANDROID-UX-06` | new |
| PX-24 adaptive layouts | `ANDROID-HOME-11` (reachability) + `ANDROID-UX-07` (two-pane) | merged/split |
| PX-25 copy/select | `ANDROID-UX-08` | new |
| PX-26 splash, predictive back | `ANDROID-UX-09` | new |
| PX-27 live motion, TalkBack | `ANDROID-UX-10` | new |
| PX-28 Refresh Profiles, Unpair | `ANDROID-HOME-13` | new |
| PX-29 Continue without resending | `ANDROID-HOME-14` | new |
| PX-30, PX-31 settings, Advanced | `ANDROID-UX-11` | new |
| PX-32, PX-33 journal, Share | `ANDROID-DIAG-01` | merged |
| PX-34 Play internal testing | `ANDROID-REL-02` | new, owner-gated |
| PX-35 R8, baseline, 16 KB | `ANDROID-REL-03` | new |
| PX-36 physical-device QA | `ANDROID-QA-01` | new |
| PX-37 entry points | `ANDROID-ENTRY-01` | new spike |
| Design findings with no PX (states, motion, haptics, pairing code card) | `ANDROID-UX-12` | new |
| NW-18 claim list/close (matrix C4) | `ANDROID-HOME-03` | wave 1 |

## Ambiguities recorded

- The audit's "NW-06/NW-17/NW-18" boundary was assumed as NW-06 = connection reports and correlation, NW-17 = pairing and claims, NW-18 = claim list/close; the journal and Share diagnostics (IOS-DIAG-01) are one ticket, `ANDROID-DIAG-01`.
- The audit's idea of a `configChanges` stop-gap for rotation was not ticketed: `ANDROID-HOME-07` fixes the cause, and a stop-gap would hide it.
- The Play-distribution policy in `README.md` conflicts with the audit's Play proposal; `ANDROID-REL-02` is gated on an owner decision.
