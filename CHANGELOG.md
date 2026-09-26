# Changelog

## 0.4.0 (2026-09-26)

<!-- Release notes generated using configuration in .github/release.yml at main -->

## What's Changed
### Dependencies
* chore(deps): bump androidx.compose:compose-bom from 2026.08.00 to 2026.09.00 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/49
* chore(deps): bump com.android.application from 9.4.0 to 9.4.1 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/48
* chore(deps): bump okhttp from 4.12.0 to 5.5.0 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/47
* chore(deps): bump actions/setup-python from 6.3.0 to 7.0.0 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/45
* chore(deps): bump org.json:json from 20240303 to 20260814 by @dependabot[bot] in https://github.com/achappell/hermes-relay-android/pull/46
### Other Changes
* Fix/stereo drain frames by @achappell in https://github.com/achappell/hermes-relay-android/pull/26
* 5-A-3: Night Console palette and doorway decomposition by @achappell in https://github.com/achappell/hermes-relay-android/pull/28
* chore: make Android BMad delivery status local by @achappell in https://github.com/achappell/hermes-relay-android/pull/29
* feat: migrate Android client to Home bridge by @achappell in https://github.com/achappell/hermes-relay-android/pull/36
* feat: complete Android 5-A-3 doorway pass by @achappell in https://github.com/achappell/hermes-relay-android/pull/30
* chore: standardize project-local worktrees by @achappell in https://github.com/achappell/hermes-relay-android/pull/37
* chore: register Android next-wave aliases by @achappell in https://github.com/achappell/hermes-relay-android/pull/38
* feat(android): align conversation navigation with iOS by @achappell in https://github.com/achappell/hermes-relay-android/pull/39
* docs(android): defer manual TalkBack validation by @achappell in https://github.com/achappell/hermes-relay-android/pull/40
* chore: configure BMAD issue tracking by @achappell in https://github.com/achappell/hermes-relay-android/pull/41
* fix(android): resume Home turns after reconnect by @achappell in https://github.com/achappell/hermes-relay-android/pull/43
* Harden Android BMAD issue tracking by @achappell in https://github.com/achappell/hermes-relay-android/pull/42
* fix(android): complete signed live Home gate on Pixel by @achappell in https://github.com/achappell/hermes-relay-android/pull/44
* feat(android): add Home device administration lifecycle by @achappell in https://github.com/achappell/hermes-relay-android/pull/50
* chore(status): sync Android sprint status with merged PRs by @achappell in https://github.com/achappell/hermes-relay-android/pull/51
* docs(planning): apply approved nine-epic course correction by @achappell in https://github.com/achappell/hermes-relay-android/pull/55
* ANDROID-HOME-02 slice 1: pair with Home and claim per connect by @achappell in https://github.com/achappell/hermes-relay-android/pull/56
* ANDROID-HOME-02: in-app pairing QR scanner and decision review by @achappell in https://github.com/achappell/hermes-relay-android/pull/57
* ANDROID-HOME-02 slice 2: list, resume, rename, and continue Home conversations by @achappell in https://github.com/achappell/hermes-relay-android/pull/58
* ANDROID-HOME-02 slice 3: owner approvals and Profile holders by @achappell in https://github.com/achappell/hermes-relay-android/pull/59
* Mark ANDROID-HOME-02 done; Android Epic 1 stays open by @achappell in https://github.com/achappell/hermes-relay-android/pull/60
* Reconnect a paired Profile after Android cuts it off by @achappell in https://github.com/achappell/hermes-relay-android/pull/61

## New Contributors
* @dependabot[bot] made their first contribution in https://github.com/achappell/hermes-relay-android/pull/49

**Full Changelog**: https://github.com/achappell/hermes-relay-android/compare/v0.3.1...v0.4.0

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
