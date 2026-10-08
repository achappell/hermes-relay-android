---
id: ANDROID-HOME-13
title: 'Paired Homes screen: Refresh Profiles and Unpair Home'
status: review
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S4
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-02
parity_source: 'PX-28 (matrix O4)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/91
---

# ANDROID-HOME-13 — Paired Homes: Refresh Profiles and Unpair Home

Source: PX-28 (matrix O4) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P1, size M.

## Background

After an owner approves a grant, an iOS user taps **Refresh Profiles** and the new Profile appears; **Unpair this Home** removes the pairing deliberately and keeps the profiles for a later re-pair. Android's `validation-android-home-02.md` lists the limitation: "A grant that later becomes active does not get a Profile until the same Home is paired again", and the only unpair is deleting the last paired Profile (`RelayProfileStore.delete` then `releasePairingIfUnused`).

## Android today (checked against `main` unless marked [INFERENCE])

- No `Refresh Profiles` or `Unpair` string or code (grep of `app/src/main`).
- Pairing records are in `FileHomeClientPairingStore`; credentials in `KeystoreRelayCredentialStore`; Profiles are created at pair time from the grants that were active.

## Required behavior

- A paired-Homes list in settings: per Home, status (active, pending, expired), granted Profiles, expiry, and the actions below.
- **Refresh Profiles:** re-read the device's grants from Home and create a Profile for each newly active grant, without duplicating existing ones; report "No new profiles" or the count.
- **Unpair this Home:** confirmation dialog; revoke/forget in the order iOS uses (Keystore credential first, then the pairing record); the Profiles stay and show as needing re-pairing; a re-pair of the same Home reattaches them.
- Failure of the credential step leaves the pairing intact and says so.

## Acceptance criteria

- Fake Home service: a grant that turns active after pairing becomes a Profile after Refresh; running Refresh twice creates one.
- Unpair removes the credential then the record (assert order); Profiles remain; re-pair reattaches by Home identity.
- A failed credential removal does not delete the record.
- No handles, credentials or grant IDs appear in journal or logs (`ANDROID-DIAG-01`).

## Android design notes

- Put the screen in the restructured settings (`ANDROID-UX-11`); until then add it to `HomePairingSection.kt`.
- Reuse `HomeClientPairingCoordinator`; add `refreshGrants` and `unpair` there, not in Compose.

## Dependencies

`ANDROID-HOME-02` (done). Placement benefits from `ANDROID-UX-11`.

## Test notes

JVM with the existing fake `HomeClientService` and temp stores (`HomeClientPairingTest` patterns).

## Device verification

Pixel against a Home: pair, have the owner approve a second grant, Refresh Profiles, then Unpair and re-pair.

## Device acceptance review — 2026-10-07

Story remains `review`. The non-destructive Pixel run in
`validation-android-home-13.md` rendered the existing Home and three saved
Profiles, but one Refresh Profiles tap immediately dismissed configuration,
preventing the result from remaining visible. Runtime evidence is from restored
APK SHA-256 `5692a179b2347214b7e31cc1010f650ec8a224fe00439b8533ecbb5437ddeb0b`,
not a latest-main build. Source at current main `d30182e` still routes the refresh
notification into the selected-Profile sheet-dismissal callback. The narrow
recommended repair is to retain the sheet/result during a data refresh while
preserving deliberate setup-completion dismissal; production changes await
authorization. All three grants, Profiles, credentials and stored session
references were preserved. Only destructive Unpair + re-pair is owner-waived;
newly-active-grant device acceptance and HOME-13 TalkBack remain unverified.
