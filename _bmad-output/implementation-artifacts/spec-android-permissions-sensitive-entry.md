---
id: ANDROID-PERM-01
title: Enforce Android endpoint permissions and masked Sensitive Entry
status: backlog
github_issue: https://github.com/achappell/hermes-relay-android/issues/34
---

# ANDROID-PERM-01 — Permissions and Sensitive Entry

## Scope

Implement Android capability presentation, secure handling, and confirmation
for sensitive inputs and risky actions.

## Acceptance

- Permission categories are endpoint-scoped and rendered as explicit grants.
- Sensitive values are masked and excluded from Local History, transcript,
  Watch, and ordinary diagnostics.
- Denied, stale, expired, and revoked grants fail closed; risky actions require
  confirmation.
- No route or reconnect path widens authority or replays an action.

## Dependencies

Home HOME-NW-10 and ANDROID-HOME-01.
