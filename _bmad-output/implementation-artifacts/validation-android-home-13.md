---
story: ANDROID-HOME-13
spec: spec-android-home-13-paired-homes-refresh-unpair.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-07
---

# ANDROID-HOME-13 validation record

Gates are kept separate. Refresh Profiles and Unpair were **not** run against a real Home, and the Paired Homes screen was **not** driven on the device: the device holds the owner's real pairing, and Unpair would remove it. Owner waiver 2026-10-07: the destructive Unpair + re-pair device leg is waived.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | Rebased onto `main` `3fd3d76`: 364 unit tests, 0 failures/errors/skipped (`HomePairedHomesTest` adds 14) |
| Repetition gate | Passed | Rebased onto `main` `3fd3d76`: `scripts/run-flake-gate.sh HomePairedHomesTest,HomeClientPairingTest 30`: 30 consecutive runs of 55 tests, 0 failures |
| Build, lint, `compileDebugAndroidTestKotlin` | Passed | Rebased onto `main` `3fd3d76`: `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon`; `check-apk-metadata.sh`, 18 issue-tracking tests and `git diff --check` pass. The one rebase conflict was `sprint-status.yaml` (HOME-12 `review` from main plus HOME-13 `review`) |
| Device smoke (Pixel 6a) | Passed, limited | Installed with `adb install -r`; `AccessibilityOrderTest` 4/4. It does not open the Paired Homes section |
| Device: pair, owner approves a second grant, Refresh Profiles, Unpair, re-pair | **Not run** | Waived by owner 2026-10-07. Needs a Home and the owner; the device's real pairing must not be unpaired. Non-destructive Paired Homes render and Refresh Profiles device checks remain required. |
| Instrumented test of the Paired Homes screen | **Not written / not run** | The spec asks for JVM tests; the Compose section has no instrumented test |
| TalkBack | **Not run** | The section uses text, buttons with labels and a polite live region; no TalkBack session was exercised |
| CI | Pending the pull request | `ci.yml` |

## What changed

- `HomeClientPairingCoordinator.pairedHomes()`, `profilesNeedingPairing()`, `refreshGrants(pairingId)` and `unpair(pairingId)` (spec: logic in the coordinator, not Compose). Typed outcomes `HomeRefreshOutcome` and `HomeUnpairOutcome`; `PairedHome` carries host, status (active, pending, expired), credential expiry and per-grant label/status/has-Profile, and no handle, credential or grant ID.
- Refresh re-reads the grants from Home (`configuration`), stores them on the pairing record and creates a Profile for each active grant that has none (`addHomeClientProfiles` already matches on pairing and grant, so a second refresh adds none). It does not renew a credential that is due: the claim path renews on connect. An expired or refused credential reports "pair again".
- Unpair removes the Keystore credential first, then the record. If the credential cannot be removed the record is untouched and the outcome says so; if only the record save fails the credential is already gone and Unpair can be retried.
- `HomeClientPairings.unpairedIds` (new optional `unpaired_ids` JSON key, older files still load) remembers the pairing identity of an unpaired Home. The Profiles keep naming it, so pairing the same Home again (`finish` reuses it) reattaches them with no duplicates. Deleting the last Profile of that Home forgets the identity.
- `PairedHomesSection` (new file, shown under the pairing section in the relay configuration sheet, which is where `HomePairingSection` lives until `ANDROID-UX-11`): per-Home status, expiry and grants, Refresh Profiles, Unpair this Home with a confirmation dialog, one polite live-region message, and a count of saved Profiles that need pairing again.

## iOS divergence (deliberate, from the spec)

The spec says Unpair keeps the Profiles ("show as needing re-pairing") and a re-pair reattaches them. iOS `RelayProfileListModel.unpair` instead deletes the Home's Profiles, their admin credentials and transcripts first, then calls the coordinator's `unpair` (credential, then record). Android follows its spec. The credential-then-record order matches iOS's coordinator.

## Acceptance criteria

- Grant becomes active after pairing, Refresh creates one Profile, a second Refresh none: `a_grant_that_turns_active_after_pairing_becomes_a_profile_and_a_second_refresh_adds_none`; "No new profiles" and count messages: `a_refresh_with_nothing_new_reports_no_new_profiles_and_a_pending_grant_gets_none`.
- Credential removed then record, asserted by observing the other store's state at the moment of each write; Profiles remain: `unpair_removes_the_credential_then_the_record_and_keeps_the_profiles`.
- Re-pair reattaches by Home identity: `pairing_the_same_home_again_reattaches_the_kept_profiles_without_duplicates` (also checks the reattached Profile reads the new credential).
- Failed credential removal does not delete the record: `a_failed_credential_removal_leaves_the_pairing_intact`; failed record save is retryable: `a_failed_record_removal_can_be_retried_and_unpair_is_then_complete`.
- No handles, credentials or grant IDs in the list model: `the_list_shows_status_grants_and_expiry_without_secrets`. Nothing in this change writes a journal or log line (`ANDROID-DIAG-01` does not exist).

## Open items

- Unpair does not end a live session: if a Profile of that Home is connected, its socket continues until Disconnect or the next reconnect, which, by reading the claim path (not run), reports "Pair with Home again". Ending it needs the runtime (`ANDROID-HOME-12` provides `disconnect()`); the spec does not ask for it.
- The pairing's `lastSessionRefs` are dropped with the record, so a re-paired Profile starts a new conversation rather than continuing the last.
- Home keeps listing the device until an owner removes it; Home offers no device self-revoke (stated in the dialog).
