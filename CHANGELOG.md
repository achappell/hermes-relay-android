# Changelog

## Unreleased

### Features
- Add deliberate Home Disconnect with user-only attribution and mid-reply confirmation.
- Add 2-second interrupt acknowledgement and interrupt-and-listen behavior to Android voice controls.
- Tell Home when Interrupt stops speech still buffered on the phone after the text turn ended, so Home releases the reply audio and accepts the next prompt.
- Interrupt speech that starts after Home's text terminal, without treating in-flight PCM or the stopped sidecar's end as an audio failure.
- Let typed Send take ownership from interrupt-and-listen capture, preventing a late transcript from replacing the accepted next turn.
- Add day/night-aware Android splash styling and enable platform predictive-back support for modal sheets.
- Add durable Home claim-management capability detection and an "Open on Home" list/close flow for the conversations sheet.

### Fixes
- Await Home's `conversation.close` acknowledgement before recording a claim released; report unacknowledged close truthfully.
- Reflow the conversation header title and Profile block to preserve the accessible overflow action at large font scales.
- Treat unbound hands-free listening as terminal for turn actions, so an ended turn is not interrupted or treated as a live reply when capture reopens. A finishing Activity in that window now tears down immediately and releases the microphone instead of waiting on a turn that has already ended.

## 0.3.1 (2026-09-12)

<!-- Release notes generated using configuration in .github/release.yml at main -->

## What's Changed
### Other Changes
* fix: pass secrets to the reusable release workflow by @achappell in https://github.com/achappell/hermes-relay-android/pull/23
* fix: clear the Speaking phase when response audio ends by @achappell in https://github.com/achappell/hermes-relay-android/pull/25


**Full Changelog**: https://github.com/achappell/hermes-relay-android/compare/v0.3.0...v0.3.1

## 0.3.0 (2026-09-12)

<!-- Release notes generated using configuration in .github/release.yml at main -->

## What's Changed
### Other Changes
* feat: sign release APKs with a developer keystore by @achappell in https://github.com/achappell/hermes-relay-android/pull/19
* fix: derive versionCode from the release version name by @achappell in https://github.com/achappell/hermes-relay-android/pull/21
* fix: repair the release version check and assert APK metadata by @achappell in https://github.com/achappell/hermes-relay-android/pull/22


**Full Changelog**: https://github.com/achappell/hermes-relay-android/compare/v0.2.0...v0.3.0

## 0.2.0 (2026-09-12)

<!-- Release notes generated using configuration in .github/release.yml at main -->

## What's Changed
### Other Changes
* feat: recover an Android turn without replaying it (A-3) by @achappell in https://github.com/achappell/hermes-relay-android/pull/4
* feat: connect Android to a live Hermes relay (A-4) by @achappell in https://github.com/achappell/hermes-relay-android/pull/6
* feat: hold a live Hermes conversation on Android (A-7) by @achappell in https://github.com/achappell/hermes-relay-android/pull/7
* feat: honest Android disconnected state without stale-turn replay (2-A-2) by @achappell in https://github.com/achappell/hermes-relay-android/pull/9
* docs: front the Hermes relays with Caddy instead of tailscale serve by @achappell in https://github.com/achappell/hermes-relay-android/pull/10
* feat: actually play Hermes response audio on Android (A-8) by @achappell in https://github.com/achappell/hermes-relay-android/pull/11
* feat: capture and transcribe speech on device for tap-to-speak (A-9) by @achappell in https://github.com/achappell/hermes-relay-android/pull/12
* feat: show the participant's live transcript while speaking (2-A-1) by @achappell in https://github.com/achappell/hermes-relay-android/pull/13
* feat: give the Android doorway an accessible reading order (5-A-2) by @achappell in https://github.com/achappell/hermes-relay-android/pull/14
* feat: keep a deliberate per-Profile conversation on the device (5-A-1) by @achappell in https://github.com/achappell/hermes-relay-android/pull/16
* feat: let the user stop a running Hermes answer (A-5) by @achappell in https://github.com/achappell/hermes-relay-android/pull/15
* feat: palette, app icon, transcript export, and prompt recall (local tickets) by @achappell in https://github.com/achappell/hermes-relay-android/pull/17
* feat: hold a continuous hands-free conversation on Android (A-6) by @achappell in https://github.com/achappell/hermes-relay-android/pull/18


**Full Changelog**: https://github.com/achappell/hermes-relay-android/compare/v0.1.0...v0.2.0

## 0.1.0 (2026-09-12)

<!-- Release notes generated using configuration in .github/release.yml at main -->

## What's Changed
### Other Changes
* ci: add Android actions and release automation by @achappell in https://github.com/achappell/hermes-relay-android/pull/1
* fix: bootstrap Release Please without a phantom tag by @achappell in https://github.com/achappell/hermes-relay-android/pull/2

## New Contributors
* @achappell made their first contribution in https://github.com/achappell/hermes-relay-android/pull/1

**Full Changelog**: https://github.com/achappell/hermes-relay-android/commits/v0.1.0

## Changelog

All notable changes to Hermes Relay Android will be documented here by
Release Please.
