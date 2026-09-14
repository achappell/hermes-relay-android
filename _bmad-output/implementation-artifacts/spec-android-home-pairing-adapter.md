---
id: ANDROID-HOME-01
title: Add the Android Home pairing and configuration adapter
status: backlog
github_issue: https://github.com/achappell/hermes-relay-android/issues/33
---

# ANDROID-HOME-01 — Home pairing adapter

## Scope

Connect Android Device administration to Home enrollment, secure credential
storage, and revisioned Room/Wake Mapping configuration.

## Acceptance

- Discovery is separate from authorization; seeing or scanning a Device never
  grants access.
- Approval stores an opaque Home credential in Android secure storage and
  preserves local Profile and history identity.
- Ordered Room, Wake Mapping, Ready state, stale revision conflicts, expiry,
  revocation, and re-enrollment are explicit.
- Android consumes Home arbitration results and does not become a second
  authority.

## Dependencies

Home HOME-NW-02 and the existing Android Epic 3 stories.
