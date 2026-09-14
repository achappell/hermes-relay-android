---
id: ANDROID-NOTIFY-01
title: Add opt-in Android notifications and quiet hours
status: backlog
github_issue: https://github.com/achappell/hermes-relay-android/issues/35
---

# ANDROID-NOTIFY-01 — Notifications

## Scope

Deliver endpoint-scoped Android notifications without interfering with an
active conversation.

## Acceptance

- Categories and quiet hours are independently configurable for Android.
- Notifications never create a turn, switch Profile, or interrupt
  capture/playback.
- Revocation and expiry stop future delivery.
- Content follows the Home permission and safe-display policy.

## Dependencies

Home HOME-NW-02, HOME-NW-10, and the Android Home pairing adapter.
