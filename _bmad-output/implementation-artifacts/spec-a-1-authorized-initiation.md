---
title: 'A-1 — Authorized typed or tap-to-speak initiation'
type: 'story'
story_id: 'A-1'
created: '2026-09-11'
status: 'done'
route: 'dispatch'
baseline_commit: '54069ba'
context:
  - '../hermes-relay-tui/_bmad-output/implementation-artifacts/surface-coverage-matrix.md'
  - '../hermes-relay-tui/_bmad-output/implementation-artifacts/epic-1-context.md'
  - '../hermes-relay-tui/_bmad-output/planning-artifacts/epics.md'
  - '../hermes-relay-tui/_bmad-output/implementation-artifacts/spec-android-repository-bootstrap.md'
---

## Intent

**Problem:** The Android repository has an honest bootstrap shell, but no
typed boundary for an authorized user-initiated Hermes turn. Without that
boundary, a future UI could capture or submit before the selected Profile and
verified Session identity are known.

**Approach:** Add a small Android-owned initiation controller and typed client
port. The controller gates typed and tap-to-speak initiation on a selected
Profile and verified authorization, passes one immutable request to the
adapter, and renders only the adapter's accepted or rejected result. Transport,
credentials, capture, and Hermes wire details remain behind the adapter.

## Boundaries & Constraints

**Always:** Show the selected Profile before initiation; require verified
authorization; bind an accepted request to Profile, Session, and turn IDs;
allow the adapter to own platform capture and transport; use fakes in tests.

**Never:** Parse Hermes frames in Compose; select a fallback Profile; invent a
response; automatically retry an uncertain request; add response audio,
Local History, Device administration, or a shared credential store to A-1.

## Acceptance Criteria

- Given a selected Profile with verified authorization, when Amanda starts a
  typed or tap-to-speak turn, then the Android Client sends one typed request
  through its own adapter and the accepted binding retains that Profile.
- Given no selected Profile or authorization that is not verified, when a turn
  is initiated, then the controller fails closed before the adapter is called,
  shows an unavailable state, and selects no fallback Profile.
- Given a typed prompt is blank, when a typed turn is initiated, then no
  request is sent.
- Given an accepted initiation, then the UI reports that it is waiting for
  normalized Session events and does not invent response text or audio state.
- Given the adapter rejects an initiation, then the rejection is reported once
  and the request is not automatically retried.

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/AndroidClientPort.kt` — Profile,
  authorization, input, request, binding, and typed adapter result models.
- `app/src/main/java/com/achappell/hermesrelay/AndroidInitiationController.kt` —
  pre-capture Profile and authorization gate.
- `app/src/main/java/com/achappell/hermesrelay/MainActivity.kt` — selected
  Profile display and typed/tap-to-speak initiation controls.
- `app/src/main/res/values/strings.xml` — resource-backed user-visible copy.
- `app/src/test/.../AndroidInitiationControllerTest.kt` — contract and
  fail-closed tests.
- `app/src/androidTest/.../MainActivityTest.kt` — authorized Compose path.

## Implementation Notes

- `BootstrapClientPort` remains unavailable by default; the launchable app
  does not pretend that Hermes transport or authorization exists.
- The controller accepts `TapToSpeak` as a typed initiation intent only. The
  future adapter owns microphone permission and capture; A-1 does not fake
  audio or a Hermes response.
- The initiation controls close after an accepted binding so one active
  doorway cannot submit a duplicate initial turn before a later lifecycle
  story supplies terminal-event handling.
- The project build baseline is JDK 21 for Gradle/Kotlin execution, with Java
  17-compatible Android bytecode retained for the API 26 support floor.

## Verification

See `validation-a-1-authorized-initiation.md` for command evidence and the
connected-device limitation.

## Closure

The A-1 contract is implemented and verified with fake adapters. Android 16
(API 36) and Android 17 (API 37) remain within the repository's API 26 minimum
and API 37 compile/target range. The connected instrumentation execution is
environment-blocked because the local API 36 AVD has no installed system image.
