---
id: ANDROID-HOME-12
title: Disconnect from Home deliberately
status: done
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S4
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-07
  - android:ANDROID-ARCH-01
  - android:ANDROID-HOME-06
parity_source: 'PX-20 (matrix C2)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/90
---

# ANDROID-HOME-12 — Disconnect from Home deliberately

Source: PX-20 (matrix C2) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size S.

## Background

iOS has a toolbar **Disconnect** (`home-disconnect`) shown in Home mode while connected and disabled while a prompt is sending. Android users cannot end a session deliberately.

## Android today (checked against `main` unless marked [INFERENCE])

- `OkHttpRelaySessionClient.disconnect()` exists (`= close()`, line 1029) and has no caller (grep for `disconnect(`).
- `releaseHeldClaim()` sends `conversation.close` and is used on Profile switch only.

## Required behavior

- A Disconnect action in the session header (`ANDROID-UX-03`) or, until then, the overflow menu, visible only when connected to Home.
- It ends the session deliberately: sends `conversation.close` for the held claim (this ends the claim; it is not the lifecycle teardown of `ANDROID-HOME-06`, which parks), stops audio and capture, clears hands-free, leaves the state `Disconnected` with a `Connect` action, and never auto-reconnects until the user chooses Connect.
- Disabled while a prompt submission is in flight; during a reply it asks for confirmation and interrupts first.
- An unconfirmed turn survives Disconnect and stays offered after reconnect (no replay).

## Acceptance criteria

- Fake client: Disconnect sends exactly one `conversation.close`, closes the socket once, clears the held claim, and the lifecycle/retry logic of `ANDROID-HOME-04` does not reconnect afterwards.
- Disabled mid-submit; confirmation mid-reply interrupts then closes.
- Connect after Disconnect opens a fresh claim (continue-last) and works.
- TalkBack: the action has a role and label; the overflow item order is covered by `AccessibilityOrderTest`. Physical-device TalkBack speech, focus, and gesture checks are waived for HOME12 only and remain unverified.

## Android design notes

- Reuse the single close path from `ANDROID-HOME-06`; Disconnect is one more initiator, journaled as `initiator=user`.
- Do not add the action to the foreground-service notification (that is Stop, `ANDROID-HOME-08`).

## Dependencies

`ANDROID-HOME-07`; `ANDROID-HOME-06` for the shared close path; `ANDROID-UX-03` for the final placement.

## Test notes

JVM with fake port; one instrumented tap test.

## Device verification

Pixel: connect, Disconnect and observe Home's `stopped` closed tombstone for the bound WebSocket `conversation.close`; Connect again; submit a neutral prompt and confirm Disconnect during the accepted active reply before terminal/audio completion. Verify Android's `initiator=user` attribution and that `home claim released` follows a validated Home `status=closed` acknowledgement.
The Home v1 contract README (lines 528–530 and 546) reserves literal `client_closed` for REST claim closure and prefers the bound WebSocket `conversation.close` path; that path persists `stopped`.
Corrected active Disconnect verification passed 2026-10-08 with Android's validated close-ACK release and the exact Home claim tombstoned `closed/stopped` before the peer WebSocket close; the earlier pre-ACK `client_disconnected` result is retained in the validation record as the failure that motivated this fix.
