---
id: ANDROID-PARITY-02
title: 'Android parity: functional'
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-PARITY-01
github_issue: https://github.com/achappell/hermes-relay-android/issues/109
---

# ANDROID-PARITY-02 — Android parity: functional (epic)

Closes the functional gap between the Android client and iOS 0.7.0 beyond the specific iOS-change tickets of `ANDROID-PARITY-01`. Source: `android-ios-parity-audit.md` (2026-10-06), which rates Android at roughly 40-45 % functional parity (Home pairing/claim/conversation/approval protocol is done; background voice, audio-session handling, resilient reconnect, diagnostics and voice-input robustness are not). The audit's PX numbers are kept only in each child's `Source` line. The audit was a read-only static review without an emulator; the key claims behind these tickets (Activity-scoped runtime, no focus/service/session, no ping or network callback, recognizer errors 12/13 unmapped, no Disconnect caller, no icons, no IME action, light theme parent) were re-checked against the code before filing.

## Ordering (P0 first)

### A. Foundation (P0, first)

Process-scoped runtime first, then split the screen; nothing else lands cleanly before these.

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-HOME-07`](https://github.com/achappell/hermes-relay-android/issues/71) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |
| [`ANDROID-ARCH-01`](https://github.com/achappell/hermes-relay-android/issues/83) | Decompose AndroidClientScreen into testable state holders | P0 | M | PX-02 |

### B. Audio and background (P0)

Already ticketed in PARITY-01; listed for ordering. Focus and route recovery before the foreground service and lock-screen controls.

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-HOME-09`](https://github.com/achappell/hermes-relay-android/issues/73) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |
| [`ANDROID-HOME-08`](https://github.com/achappell/hermes-relay-android/issues/72) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |
| [`ANDROID-HOME-10`](https://github.com/achappell/hermes-relay-android/issues/74) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |

### C. Connection resilience (P0/P1)

Paced reconnect and teardown semantics (PARITY-01) plus transport liveness and network awareness.

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-HOME-04`](https://github.com/achappell/hermes-relay-android/issues/68) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |
| [`ANDROID-HOME-06`](https://github.com/achappell/hermes-relay-android/issues/70) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |
| [`ANDROID-NET-01`](https://github.com/achappell/hermes-relay-android/issues/84) | Transport liveness, network awareness and off-tailnet classification | P0 | M | PX-09, PX-10 |

### D. Voice input (P1)

Make a spoken turn work, recoverable and automatic, then make interrupt primary.

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-VOICE-01`](https://github.com/achappell/hermes-relay-android/issues/85) | Recognizer language-pack handling, error mapping and a confirmed spoken turn | P1 | S | PX-12 |
| [`ANDROID-VOICE-02`](https://github.com/achappell/hermes-relay-android/issues/86) | Microphone permission rationale and Open Settings after permanent denial | P1 | S | PX-13 |
| [`ANDROID-VOICE-03`](https://github.com/achappell/hermes-relay-android/issues/87) | Endpointing parity and auto-send on tap-to-talk | P1 | M | PX-14 |
| [`ANDROID-VOICE-04`](https://github.com/achappell/hermes-relay-android/issues/88) | Interrupt as the primary voice action with a 2 s acknowledgement | P1 | S | PX-16 |

### E. Home management and recovery (P1/P2)

Disconnect, Refresh Profiles/Unpair, unconfirmed-turn continuity; claim list/close is `ANDROID-HOME-03`.

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-HOME-12`](https://github.com/achappell/hermes-relay-android/issues/90) | Disconnect from Home deliberately | P1 | S | PX-20 |
| [`ANDROID-HOME-13`](https://github.com/achappell/hermes-relay-android/issues/91) | Paired Homes screen: Refresh Profiles and Unpair Home | P1 | M | PX-28 |
| [`ANDROID-HOME-14`](https://github.com/achappell/hermes-relay-android/issues/92) | Unconfirmed-turn continuity: Continue without resending and persisted recovery state | P2 | S | PX-29 |
| [`ANDROID-HOME-03`](https://github.com/achappell/hermes-relay-android/issues/67) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |

### F. Diagnostics (P1)

Journal and share first; automatic reports need a Home change.

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-DIAG-01`](https://github.com/achappell/hermes-relay-android/issues/76) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |
| [`ANDROID-DIAG-02`](https://github.com/achappell/hermes-relay-android/issues/77) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |
| [`ANDROID-DIAG-03`](https://github.com/achappell/hermes-relay-android/issues/78) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |

### G. Release and verification (P1/P2)

R8/16 KB before any Play work; Play is owner-gated; the QA pass follows the behavioral tickets; barge-in and entry points are spikes.

| Ticket | Title | Pri | Size | Source |
|---|---|---|---|---|
| [`ANDROID-REL-01`](https://github.com/achappell/hermes-relay-android/issues/79) | (PARITY-01 ticket) | — | — | iOS 0.7.0 |
| [`ANDROID-REL-03`](https://github.com/achappell/hermes-relay-android/issues/94) | R8 shrinking, baseline profile, 16 KB page-size check and data-extraction rules | P1 | M | PX-35 |
| [`ANDROID-REL-02`](https://github.com/achappell/hermes-relay-android/issues/93) | Play internal testing: AAB, Play App Signing, Data safety and foreground-service declarations | P1 | M | PX-34 |
| [`ANDROID-QA-01`](https://github.com/achappell/hermes-relay-android/issues/95) | Physical-device QA pass with a validation record | P1 | M | PX-36 |
| [`ANDROID-VOICE-05`](https://github.com/achappell/hermes-relay-android/issues/89) | Echo-safe route classifier, capture audio focus and a barge-in spike | P2 | M | PX-08 |
| [`ANDROID-ENTRY-01`](https://github.com/achappell/hermes-relay-android/issues/96) | Launcher shortcut and Quick Settings tile spike | P2 | S | PX-37 |

## Acceptance

All children `done` or explicitly deferred with a recorded owner decision; `ANDROID-QA-01` records the physical-device pass separately from local acceptance. No child claims device behavior from the emulator.
