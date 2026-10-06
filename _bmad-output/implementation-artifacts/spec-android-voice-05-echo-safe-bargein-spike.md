---
id: ANDROID-VOICE-05
title: Echo-safe route classifier, capture audio focus and a barge-in spike
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-09
parity_source: 'PX-08 (matrix V4) plus the capture-focus obligation from audit section 4'
github_issue: https://github.com/achappell/hermes-relay-android/issues/89
---

# ANDROID-VOICE-05 — Echo-safe route classifier, capture focus and a barge-in spike

Source: PX-08 (matrix V4) plus the capture-focus obligation from audit section 4 (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P2, size M.

## Background

iOS lets speaking interrupt Hermes ("Keep listening" with `voice.interruptByTalking`, default off) only on an echo-safe route (wired headphones, Bluetooth HFP/LE) so the TV or the speaker cannot cut Hermes off. Android hands-free reopens the recognizer only after a completed turn and has no barge-in, no route check and no setting. Whether `SpeechRecognizer` can run during playback without echo is unproven.

## Android today (checked against `main` unless marked [INFERENCE])

- No `AudioManager.getDevices` use, no setting, no mic level (`onRmsChanged` is ignored, `PlatformSpeechInput.kt:95`).
- Capture does not request audio focus at all; the platform recognizer manages its own.

## Required behavior

- Spike (about 2 days) with a written result: can capture run during reply playback on Pixel hardware with a wired headset and with Bluetooth, and does the recognizer self-trigger on speaker output? Outcome is a go/no-go recorded in `validation-android-voice-05.md`.
- If go: an `EchoSafeRoute` classifier over `AudioDeviceInfo` (`TYPE_WIRED_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_USB_HEADSET`, `TYPE_BLUETOOTH_SCO`, `TYPE_BLE_HEADSET`; decide A2DP) and a setting "Interrupt Hermes by talking (headphones only)", default off, gated on the classifier.
- Capture takes audio focus (`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`) so a reply, call or navigation prompt is not talked over, and releases it on stop (shared with `ANDROID-HOME-09`).
- If no-go: document it, hide the setting, and keep tap-to-interrupt only.

## Acceptance criteria

- Classifier table test over fake `AudioDeviceInfo` types.
- Spike report with device models, route types tried and observed self-trigger rates; no content recorded.
- Capture focus acquire/release asserted with a fake focus controller; a transient loss ends capture without re-arming.

## Android design notes

- `BLUETOOTH_CONNECT` (API 31+) is only needed to enumerate or choose Bluetooth communication devices; avoid it if the spike allows.
- Decision belongs to the owner after the spike; the ticket is not a commitment to ship barge-in.

## Dependencies

`ANDROID-HOME-09` (focus and route plumbing); `ANDROID-VOICE-03`.

## Test notes

JVM for the classifier and focus; the spike is device-only.

## Device verification

Pixel with wired headset, Bluetooth earbuds and speaker: record whether capture during playback self-triggers.
