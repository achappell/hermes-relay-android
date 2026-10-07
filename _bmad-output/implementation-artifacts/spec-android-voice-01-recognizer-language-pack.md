---
id: ANDROID-VOICE-01
title: Recognizer language-pack handling, error mapping and a confirmed spoken turn
status: review
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S3
created: 2026-10-06
depends_on:
  - android:5-A-3
parity_source: 'PX-12 (matrix V2)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/85
---

# ANDROID-VOICE-01 — Recognizer language-pack and error mapping, and a confirmed spoken turn

Source: PX-12 (matrix V2) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size S.

## Background

Voice is the product, and a spoken Home turn on Android has never been confirmed end to end: the 2026-09-25 emulator run died on `LANGUAGE_PACK_ERROR (13)` with `EXTRA_PREFER_OFFLINE = true` and no online fallback (`validation-android-home-02.md`, "Voice not exercised"). iOS maps recognizer errors to specific copy and recovers.

## Android today (checked against `main` unless marked [INFERENCE])

- `PlatformSpeechInput.failureFor` maps no-match, timeout, network, busy, client/server, but `ERROR_LANGUAGE_NOT_SUPPORTED` (12) and `ERROR_LANGUAGE_UNAVAILABLE` (13) fall to `else -> AndroidSpeechFailure.Unknown`.
- The intent sets `RecognizerIntent.EXTRA_PREFER_OFFLINE = true` (`PlatformSpeechInput.kt:106`); there is no online retry and no `checkRecognitionSupport` pre-flight (API 33).

## Required behavior

- Add `AndroidSpeechFailure.LanguageUnavailable` with its own copy ("Install the offline speech pack or allow network recognition") and map errors 12/13 to it.
- Pre-flight on API 33+ with `SpeechRecognizer.checkRecognitionSupport`; offer an explicit, user-initiated online retry (never silent fallback, because audio would leave the device: the existing text says audio is transcribed on device and never uploaded, so the copy must state the change).
- Document and test which recognizer is used (`createSpeechRecognizer` vs `createOnDeviceSpeechRecognizer` on API 31+) and the locale (device locale today; iOS hard-codes en-US).
- Record one genuinely spoken turn on a physical Pixel with a real language pack, end to end through Home, in a validation record.

## Acceptance criteria

- Unit tests: every `SpeechRecognizer.ERROR_*` constant has an explicit mapping; 12 and 13 produce `LanguageUnavailable`; no constant falls to `Unknown` except a truly unknown code.
- UI test: the failure state shows the new copy and one action; choosing the online retry sets a visible per-session indicator.
- `validation-android-voice-01.md` records a physical-device spoken turn (transcript content not recorded) with the device model, Android version and recognizer package.

## Android design notes

- Keep the privacy statement honest: if online recognition is used, audio is processed by the system recognizer, not by Hermes; update the app privacy strings accordingly. Play's Data safety form was part of declined `ANDROID-REL-02` (2026-10-06), not this ticket.
- The emulator has no microphone and no speech pack (`AGENTS.md`); do not claim recognition from it.

## Dependencies

None hard. Informs `ANDROID-VOICE-03` and `ANDROID-QA-01`.

## Test notes

JVM for the mapping table via a fake `SpeechRecognizer` seam; the live recognition is a device gate.

## Device verification

Pixel with the English pack installed and with it removed (airplane mode): expect success in the first case and the new language-pack state in the second.
