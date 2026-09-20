---
title: 'Validation — Android Home pairing and configuration adapter'
type: 'validation'
story_id: 'ANDROID-HOME-01'
created: '2026-09-19'
status: 'done-with-environment-limitation'
spec: '_bmad-output/implementation-artifacts/spec-android-home-01-pairing-and-configuration-adapter.md'
---

# Validation — Android Home pairing and configuration adapter

## Evidence

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed.
- `./gradlew compileDebugAndroidTestKotlin --no-daemon` — passed; only the
  existing Compose test-rule deprecation and an unnecessary assertion warning
  remain.
- `scripts/check-apk-metadata.sh` — passed; min SDK 26, version 0.3.1 / code
  301; signing was skipped because no expected signer was configured.
- `git diff --check` — passed after implementation and review fixes.
- JVM tests cover discovery without authorization, separate Home authorities,
  every lifecycle HTTP route, raw revision-conflict mapping, secure credential
  transitions, expiry, revocation, re-enrollment, revision conflicts, Device
  mapping verification, Profile deletion, and Profile/history identity.
- Bridge readiness tests cover the new Setup Pending gate and unavailable state.
- An instrumentation test mounts the Home administration Compose surface and
  drives manual discovery without creating a Home client or writing a Device
  credential.

## Review patch verification — 2026-09-20

- `./gradlew testDebugUnitTest --no-daemon` — passed; 209 tests, zero failures
  and zero errors.
- `./gradlew assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon`
  — passed.
- `scripts/check-apk-metadata.sh` — passed; min SDK 26, version 0.3.1 / code
  301; signing was skipped because no expected signer was configured.
- `git diff --check` — passed after all review patches.

## Environment limitation

`./gradlew connectedDebugAndroidTest --no-daemon` was attempted on the connected
Pixel 6a. The run failed before usable Compose assertions: the existing
Compose-backed suite reported `No compose hierarchies found in the app`,
including the new mounted-surface test. This is not claimed as a UI pass.

No live Home deployment, approved route, real admin credential, real Device
credential, or physical end-to-end enrollment was exercised. The local adapter
and contract fixtures are verified; live Home proof remains a separate
environment gate.

## Acceptance summary

| Requirement | Result |
| --- | --- |
| Discovery is identity-only | verified by JVM and instrumentation tests |
| Approval stores opaque Device material securely | verified |
| Home owns revisioned Room/Wake configuration | verified |
| Stale publish never auto-merges | verified |
| Revoked/expired/re-enrolled state requires explicit recovery | verified |
| Ready requires Device verification and bridge readiness | verified |
| Physical UI and live Home endpoint | environment-limited |
