---
id: ANDROID-HOME-12
title: Disconnect from Home deliberately
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-07
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
- TalkBack: the action has a role and label; the overflow item order is covered by `AccessibilityOrderTest`.

## Android design notes

- Reuse the single close path from `ANDROID-HOME-06`; Disconnect is one more initiator, journaled as `initiator=user`.
- Do not add the action to the foreground-service notification (that is Stop, `ANDROID-HOME-08`).

## Dependencies

`ANDROID-HOME-07`; `ANDROID-HOME-06` for the shared close path; `ANDROID-UX-03` for the final placement.

## Test notes

JVM with fake port; one instrumented tap test.

## Device verification

Pixel: connect, Disconnect, observe Home claim closed `client_closed`; Connect again; mid-reply Disconnect.
