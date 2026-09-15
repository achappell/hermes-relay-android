---
id: 5-A-3
title: Finish Android Night Console validation
status: in-progress
baseline_commit: '9dd5dc8324508c754e60f98c5b9e5a6e40ea2815'
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

## Review outcome — 2026-09-14

The merged doorway implementation was reviewed against this spec. The direct
review fixed the recomposition-sensitive speech-input owner, restored local
draft editing while a selected Profile is unavailable, and disarmed hands-free
on an empty final transcript. Host verification and the 37-test non-live Pixel
6a instrumentation run are green, and the dark configuration surface was
observed at both the default and 130% font scales. A follow-up UX pass now keeps
the conversation rail as the home surface and presents relay configuration and
Local History from the header menu as native sheets, matching the iOS
information architecture. The host gate is green; the post-change connected
run passed all 37 non-live tests on the unlocked Pixel, and the installed debug
build showed the header menu and configuration sheet. The story remains
`in-progress` because reliable spoken TalkBack traversal and selected/live
focus-restoration evidence are still open.
