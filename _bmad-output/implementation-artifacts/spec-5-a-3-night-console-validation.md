---
id: 5-A-3
title: Finish Android Night Console validation
status: in-progress
github_issue: https://github.com/achappell/hermes-relay-android/issues/31
---

# 5-A-3 — Night Console validation

## Scope

Close the Android Night Console visual and accessibility pass with real-device
evidence.

## Acceptance

- Hardware validation covers TalkBack order, focus restoration, rendered
  appearance, 130% scale, and lifecycle teardown.
- JVM, APK, lint, and metadata checks remain green.
- Host-audio, emulator, and instrumentation limitations are recorded rather
  than treated as proof.
- The issue closes only with a reviewable validation record.

## Dependencies

The current Android visual implementation and an available physical validation
path.
