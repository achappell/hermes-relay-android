---
id: ANDROID-HOME-14
title: 'Unconfirmed-turn continuity: Continue without resending and persisted recovery state'
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-01
parity_stream: S1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-07
  - android:ANDROID-HOME-06
  - android:ANDROID-ARCH-01
parity_source: 'PX-29 (matrix C11) and PX-03 (matrix C12)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/92
---

# ANDROID-HOME-14 — Unconfirmed-turn continuity

Source: PX-29 (matrix C11) and PX-03 (matrix C12) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P2, size S.

## Background

When a connection drops mid-turn the prompt is uncertain. iOS offers Resend, and, when Home reports no active turn, **Continue without resending** (`continue-without-resending-unconfirmed-turn`); it persists `unconfirmedTurnText` per conversation so the choice survives a relaunch. Android offers Resend/Discard only, and keeps the unconfirmed turn in `remember`, so it is lost on recreation or process death.

## Android today (checked against `main` unless marked [INFERENCE])

- `ConnectionRecoveryZone` shows Resend and Discard; `android_home_unresolved_turn` covers the unresolved-turn copy.
- `AndroidRecoveryState.unconfirmedTurn` lives in composition state; only the draft is persisted (Local History).

## Required behavior

- Add the "Home has no active turn" branch: after reconnect, when Home reports no unresolved turn for the conversation, offer **Continue without resending** (clears the marker, keeps the transcript) alongside Resend and Discard, with iOS-equivalent copy.
- Persist the unconfirmed prompt text (never handles or refs) and the unresolved-turn marker per Profile; restore after Activity recreation (covered by `ANDROID-HOME-07`) and process death.
- Never resend automatically; a restored unconfirmed turn is offered, not sent.

## Acceptance criteria

- Unit tests of the recovery state for: Home has no active turn → Continue offered; Home has an active turn → resumed, no Resend; restored-after-restart → offered once.
- Persistence test: write, kill, read; the stored file contains the prompt text but no handle/ref/token (assert forbidden substrings).
- Resend still sends exactly once; Discard clears the persisted copy.

## Android design notes

- Store beside Local History per Profile (`FileAndroidHistoryStore`); do not widen `RelayProfile` JSON.
- Prompt text is user content: protect it as Local History is protected (app-private, no backup).

## Dependencies

`ANDROID-HOME-07`, `ANDROID-HOME-06`.

## Test notes

JVM with fake port and temp store.

## Device verification

Pixel: submit, kill the app mid-turn from Recents, reopen; the prompt is offered for Resend/Continue/Discard and Home shows one submission at most.
