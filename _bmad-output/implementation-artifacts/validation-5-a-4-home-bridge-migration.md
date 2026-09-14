---
title: 'Validation — 5-A-4 Android Home bridge migration'
type: 'validation'
story_id: '5-A-4'
created: '2026-09-14'
status: 'done-with-environment-limitation'
upstream_contract: 'hermes-relay-home@f659980'
---

# Validation — 5-A-4 Android Home bridge migration

## Scope

Validate the Android migration from the fork-only `/voice-session` route to
the paired Home bridge. The migration keeps Profile and local-history identity,
uses a distinct Device credential, normalizes Home events below the Android
port, handles audio and interruption, and preserves uncertain turns without
automatic replay.

## Evidence

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed;
  166 JVM tests, 0 failures and 0 errors; debug APK assembled; lint clean.
- `scripts/check-apk-metadata.sh` — passed; min SDK 26, version 0.3.1 (301),
  signing skipped because `EXPECTED_SIGNER_SHA256` was unset.
- `./gradlew compileDebugAndroidTestKotlin --no-daemon` — passed; the
  instrumentation sources, including the real Home Keystore corruption check,
  compile. One existing Compose test API deprecation warning remains.
- `git diff --check` — passed after the review fixes.
- Home contract fixtures use a TLS MockWebServer and verify the planned,
  deterministic versioned `/api/v1/bridge/ws` path,
  `Authorization: Device` upgrade, JSON-RPC
  `conversation.open`/`prompt.submit`/`session.interrupt`, opaque-handle
  redaction, cumulative text previews, raw signed-16 little-endian PCM,
  audio fallback, interrupt acknowledgement, explicit rejection, timeout
  uncertainty, reconnect without prompt replay, and insecure-route rejection.
- Source-level guards and focused reducer/transport assertions cover delayed
  audio drain ordering, stale connection/turn binding, strict Home-only frame
  parsing, timeout terminal statuses, structured-event sensitivity, and
  profile-save rollback. These are deterministic implementation checks, not
  production Home or physical-device evidence.
- Source inspection confirms the Android adapter opens only the planned Home
  bridge route; it does not send Home methods to vanilla Hermes `/api/ws` or
  `/api/audio/speak-stream`, and no Home live-route claim is made.
- Profile fixtures verify 32-byte unpadded base64url Device credentials,
  same-Profile migration, selected state and rollback credential preservation,
  idempotent replacement, serialization redaction, and secure-write failure.
- Initiation and recovery fixtures verify that a prompt whose delivery is
  uncertain before Home returns a turn ID remains retained without a fabricated
  binding and blocks a new composer submission until an explicit choice.
- `./gradlew connectedDebugAndroidTest --no-daemon` — environment-limited on
  Pixel 6a / Android 17 over ADB Wi-Fi: 34 tests ran, 7 passed and 27 failed.
  The 27 failures all report `No compose hierarchies found in the app`; the
  test Activity is replaced by
  `InstrumentationActivityInvoker$EmptyActivity` before Compose assertions.
  The non-Compose storage and platform checks passed. No UI pass is claimed.
- A final connected-test rerun was attempted after the review fixes, but ADB
  reported no connected devices and Gradle stopped before installing or
  running tests. It adds no test evidence.

## Acceptance matrix

| Requirement | Evidence | Result |
| --- | --- | --- |
| Home-first path and Device auth | TLS MockWebServer transport fixtures | verified |
| Opaque handles and redaction | JSON-RPC request/serialization assertions | verified |
| Profile/history-preserving pairing | migration and round-trip fixtures | verified |
| Cumulative text and structured events | normalizer fixtures and reducer | verified |
| PCM metadata, delivery, fallback, and drain | transport/audio sink fixtures | verified |
| Interrupt acknowledgement versus terminal event | transport fixture and reducer | verified |
| Uncertain delivery and no automatic replay | initiation/recovery/reconnect fixtures | verified |
| Android UI doorway and unavailable states | existing instrumentation sources compile; connected UI run is blocked by the device harness | partial |

## Not verified

- The connected device run did not produce a usable Compose UI verdict because
  its hierarchy disappeared before each UI assertion. This is an environment
  limitation, not evidence that the doorway UI passed.
- The deterministic suite does not replace a deployed Home adapter or exercise
  every asynchronous device callback under a real `AudioTrack`; those remain
  explicit environment/deployment gates below.
- No public Home adapter is live in the upstream revision, so no production
  Home endpoint, approved public route, real Device credential,
  microphone, speaker, or live relay session was available. The live handshake
  tests remain excluded by `@LiveRelay` and were not run.
- The emulator/device configuration cannot prove production TLS, real
  recognizer timing, microphone capture, speaker output, or Home-side
  Standard-session behavior.

## Deferred

- Run the connected UI suite after the test Activity/Compose harness is restored.
- Run the `@LiveRelay` handshake and an audio/capture pass against an approved
  Home deployment with a real Device credential.
- Revisit the local legacy configuration form when the Android pairing flow is
  brought into this repository; this story provides the typed migration seam
  but does not invent Home route discovery or approval.
