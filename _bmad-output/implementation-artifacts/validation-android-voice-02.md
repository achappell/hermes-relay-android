---
story: ANDROID-VOICE-02
spec: spec-android-voice-02-mic-permission-ux.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-06
---

# ANDROID-VOICE-02 validation record

Gates are kept separate. The hand-run device scenario (deny twice, Open settings, grant in Settings, return, capture works) was **not** run: it needs a connected Home session, and the device was not paired.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | 294 unit tests, 0 failures/errors/skipped (`RuntimePermissionTest` adds 5) |
| Repetition gate | Passed | `scripts/run-flake-gate.sh RuntimePermissionTest 30`: 30 consecutive runs, 0 failures |
| Instrumented on a physical device | Passed | Pixel 6a, Android 17 (API 37): `MicrophonePermissionTest` 5/5, `MicrophonePermissionGrantedTest` 1/1, `MicrophoneCaptureTest` 7/7, `AccessibilityOrderTest` 5/5 (one new) |
| Hand-run denial sequence on the device | **Not run** | Needs a paired Home; see above |
| CI | Pending the pull request | `ci.yml` |

## What changed

- `RuntimePermission.kt`: `RuntimePermissionState` (`NotAsked`, `Granted`, `DeniedWithRationale`, `PermanentlyDenied`), the pure `resolveRuntimePermissionState`, a `RuntimePermissionSource` seam (platform facts, faked in tests) and `applicationDetailsSettingsIntent`. "Has asked" is persisted in a private preference file because `shouldShowRequestPermissionRationale` is false both before the first request and after a permanent denial.
- `PermissionPrompt.kt`: `RuntimePermissionController` (state, `request()` that records the ask then launches, `openSettings()`, refresh on `ON_RESUME`), `rememberRuntimePermission`, and the shared `OpenSettingsButton`.
- Microphone: `IdleCaptureZone` shows the request button and one-sentence copy in the first two states, and Open settings with explanatory copy in the permanently denied state. Nothing launches a request without a tap.
- Camera (QR scanner) reuses the same helper (spec design note). The scanner used to launch the camera request on open every time; it now does so only while the state is `NotAsked` and otherwise offers an explicit Allow camera or Open settings action. The old `android_home_pair_scan_denied` string is removed.
- Test hooks: `AndroidClientScreen` takes optional `microphonePermissionSource` and `openApplicationSettings`.
- `androidx.test:rules` added for `GrantPermissionRule`.

## Copy

Rationale: "Hermes transcribes your speech on this device and sends only the text." This is still true today. `ANDROID-VOICE-01` may allow an explicit online retry; it owns changing this copy when it does.

## Acceptance criteria

- Unit tests of the state function with a fake source for all four states: `RuntimePermissionTest`.
- Instrumented: grant via `GrantPermissionRule` (`MicrophonePermissionGrantedTest`); denial shows rationale, and a permanently denied fake shows Open settings whose Intent action equals `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` with data `package:<applicationId>` (`MicrophonePermissionTest`); not-asked never launches a request (same class); returning from Settings with the permission granted clears the prompt on `ON_RESUME` (same class).
- TalkBack: `AccessibilityOrderTest.a_permanently_denied_microphone_reads_its_state_then_its_open_settings_action` asserts the state text precedes the action in traversal order, the state is a polite live region, and both are exposed as text. Actual TalkBack speech was not exercised.

## Device handling

Run with `adb install -r` and `am instrument` only (never an uninstall or `pm clear`; the device holds a pairing). `GrantPermissionRule` leaves `RECORD_AUDIO` granted, and revoking would kill the instrumentation process, so the grant was revoked with `pm revoke` afterwards; `MicrophoneCaptureTest` was changed to accept either grant state so it does not depend on class order.
