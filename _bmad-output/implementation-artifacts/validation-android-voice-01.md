---
story: ANDROID-VOICE-01
spec: spec-android-voice-01-recognizer-language-pack.md
status: done
story_status: done
updated: 2026-10-08
---

# ANDROID-VOICE-01 validation record

**Done under owner acceptance (2026-10-07 CDT / 2026-10-08 UTC).** Amanda confirmed the actual spoken Home turn and explicitly selected option 2 to waive only the English-pack-missing/offline live check, accepting retained automated coverage. That physical scenario remains **unverified, waived, not passed**; no other story or TalkBack waiver is implied.

The shared actual turn and exact journal/UI/restoration evidence are recorded in [VOICE-03 validation](validation-android-voice-03.md#actual-spoken-home-turn--2026-10-08-utc). The prior recognizer-only test below remains separate historical evidence, not a claim it exercised Home. No pack, permission, network or account changes were made for the accepted Home turn.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | 308 unit tests, 0 failures/errors/skipped (this ticket adds 14: `SpeechRecognitionPolicyTest` 11, `AndroidCaptureControllerTest` 3) |
| Repetition gate | Passed | `scripts/run-flake-gate.sh SpeechRecognitionPolicyTest,AndroidCaptureControllerTest 30`: 30 consecutive runs, 0 failures (30 tests per iteration) |
| Instrumented UI on a physical device | Passed | Pixel 6a, Android 17 (API 37): `SpeechLanguagePackTest` 3/3, `MicrophoneCaptureTest` 7/7, `MicrophonePermissionTest` 5/5, `AccessibilityOrderTest` 5/5 |
| Real recogniser, one spoken phrase | Passed (recogniser only) | `LiveSpeechRecognizerTest` on the Pixel 6a: on-device recogniser (`network=false`), a neutral phrase spoken by the host (`say`) near the device; `ready` at 264 ms, final transcript matched the spoken phrase. Transcript text is never logged. |
| Spoken turn end to end through Home | **Passed: owner-confirmed, journal/UI corroborated** | One successful prompt.submit, text terminal, audio completion and UI Complete; owner confirmed spoken reply worked |
| Pack removed / airplane mode on the Pixel | **Owner-waived; unverified, not passed** | Amanda explicitly selected option 2; existing missing-pack JVM coverage retained |
| Historical implementation CI note | Pending at original validation | Not current-head CI evidence; this status-only PR does not watch CI |

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
