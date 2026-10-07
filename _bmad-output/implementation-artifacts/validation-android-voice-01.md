---
story: ANDROID-VOICE-01
spec: spec-android-voice-01-recognizer-language-pack.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-06
---

# ANDROID-VOICE-01 validation record

Gates are kept separate. The recogniser was exercised on a physical Pixel with a real utterance, but **not** end to end through a Home turn: the device was not paired, so a spoken Home turn is still open. The "pack removed / airplane mode" scenario was **not** run.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | 308 unit tests, 0 failures/errors/skipped (this ticket adds 14: `SpeechRecognitionPolicyTest` 11, `AndroidCaptureControllerTest` 3) |
| Repetition gate | Passed | `scripts/run-flake-gate.sh SpeechRecognitionPolicyTest,AndroidCaptureControllerTest 30`: 30 consecutive runs, 0 failures (30 tests per iteration) |
| Instrumented UI on a physical device | Passed | Pixel 6a, Android 17 (API 37): `SpeechLanguagePackTest` 3/3, `MicrophoneCaptureTest` 7/7, `MicrophonePermissionTest` 5/5, `AccessibilityOrderTest` 5/5 |
| Real recogniser, one spoken phrase | Passed (recogniser only) | `LiveSpeechRecognizerTest` on the Pixel 6a: on-device recogniser (`network=false`), a neutral phrase spoken by the host (`say`) near the device; `ready` at 264 ms, final transcript matched the spoken phrase. Transcript text is never logged. |
| Spoken turn end to end through Home | **Not run** | Needs a paired Home on the device |
| Pack removed / airplane mode on the Pixel | **Not run** | Would disrupt the shared device; the missing-pack path is covered by JVM tests only |
| CI | Pending the pull request | `ci.yml` |

The first live attempt failed with `NoSpeechHeard` after about 13 s (recogniser listening, nothing heard): host speaker volume was too low for the distance. Kept here because it shows a no-speech result is not a recogniser fault.

## Which recogniser and locale (documented, as the spec requires)

- Default: `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+) when `isOnDeviceRecognitionAvailable` is true, i.e. on-device by construction. Otherwise `createSpeechRecognizer` with `EXTRA_PREFER_OFFLINE=true`, which is a request, not a guarantee. After the user allows network recognition: `createSpeechRecognizer` with `EXTRA_PREFER_OFFLINE=false` (`chooseRecognizer`).
- Locale: the device locale (`Locale.getDefault()`), passed explicitly as `EXTRA_LANGUAGE`. iOS hard-codes `en-US`.
- Pre-flight (API 33+): `checkRecognitionSupport` before the microphone opens. Installed on-device pack: listen on device. Missing pack without consent: `LanguageUnavailable` immediately. Missing pack with consent: listen online. Pre-flight unavailable or errored: the recogniser's own error 12/13 stays the signal.
- Online use is never silent: the failure state offers one action ("Allow network recognition and try again"); once chosen a visible indicator stays on screen with "Use on-device recognition only". The choice lasts for the process (the Home runtime's lifetime) or until turned off. The privacy copy was updated to say audio is not uploaded unless network recognition is allowed.

## What changed

- `SpeechRecognitionPolicy.kt`: `speechFailureFor` maps every `SpeechRecognizer.ERROR_*` constant explicitly (a reflection test fails when a new SDK constant is added unmapped); `chooseRecognizer`; `planRecognition`; `sameLanguage`.
- `AndroidSpeechFailure.LanguageUnavailable` and `AudioUnavailable` (error 3 previously fell to `Unknown`).
- `AndroidSpeechInput.networkRecognitionAllowed` (observable); `AndroidCaptureController.allowNetworkRecognitionAndRetry` / `useDeviceRecognitionOnly`.
- `PlatformSpeechInput` takes the injected `AndroidPlatform` (`ANDROID-TEST-01` rule), applies the policy, runs the pre-flight.
- UI: failure copy plus one action; indicator in idle and active capture zones.
- Tests: `SpeechRecognitionPolicyTest`, controller tests, `SpeechLanguagePackTest` (instrumented), `LiveSpeechRecognizerTest` (skipped unless `-e liveSpeech true`).

## Running the live gate

`adb install -r` the app and test APKs (never uninstall), then
`am instrument -w -e class com.achappell.hermesrelay.LiveSpeechRecognizerTest -e liveSpeech true -e liveSpeechPhrase what_time_is_it com.achappell.hermesrelay.test/androidx.test.runner.AndroidJUnitRunner`
and speak the phrase once the `HermesVoiceLive` log line reads `ready`. The `GrantPermissionRule` leaves `RECORD_AUDIO` granted; it was revoked afterwards with `pm revoke`.
