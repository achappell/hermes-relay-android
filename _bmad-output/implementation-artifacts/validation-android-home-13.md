---
story: ANDROID-HOME-13
spec: spec-android-home-13-paired-homes-refresh-unpair.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-07
---

# ANDROID-HOME-13 validation record

Gates are kept separate. The original implementation checks below remain valid at their recorded scope. A non-destructive Pixel run on 2026-10-07 local time (2026-10-08 UTC) rendered Paired Homes and tapped Refresh Profiles once, but found that the configuration sheet immediately closes and does not retain the result for the user. This is **not accepted**; the story remains `review`. Owner waiver 2026-10-07 covers only the destructive Unpair + re-pair device leg, not Refresh Profiles or TalkBack.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM, deterministic) | Passed | Rebased onto `main` `3fd3d76`: 364 unit tests, 0 failures/errors/skipped (`HomePairedHomesTest` adds 14) |
| Repetition gate | Passed | Rebased onto `main` `3fd3d76`: `scripts/run-flake-gate.sh HomePairedHomesTest,HomeClientPairingTest 30`: 30 consecutive runs of 55 tests, 0 failures |
| Build, lint, `compileDebugAndroidTestKotlin` | Passed | Rebased onto `main` `3fd3d76`: `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon`; `check-apk-metadata.sh`, 18 issue-tracking tests and `git diff --check` pass. The one rebase conflict was `sprint-status.yaml` (HOME-12 `review` from main plus HOME-13 `review`) |
| Device smoke (Pixel 6a) | Passed, limited | Installed with `adb install -r`; `AccessibilityOrderTest` 4/4. It does not open the Paired Homes section |
| Device: Paired Homes render and one Refresh Profiles | **Render passed; refresh feedback failed** | Original installed APK `5692a179…deb0b`, not a current-main build; one Home and three saved grants rendered correctly. One Refresh tap dismissed the sheet to Ready, hiding the outcome. See the dated run below. |
| Device: new grant approval, Unpair, re-pair | **Not run** | Only destructive Unpair + re-pair is waived by the owner on 2026-10-07. No grant approval or permission change was authorized or performed; newly-active-grant device behavior remains unverified. |
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

## Non-destructive Pixel acceptance — 2026-10-07 local / 2026-10-08 UTC

### Provenance and procedure

- Fresh evidence worktree based on current `origin/main` `d30182ebb2ec14188c0dd29a8a9f6c06c215f871`, after PR #135 merged. Accepted HOME-03/UX-09 records are unchanged.
- Sole Pixel 6a ownership was coordinated with Main and the ANDROID-STD-01 worker. Actual installed package `com.achappell.hermesrelay`: version `0.3.1` / code `301`, SHA-256 `5692a179b2347214b7e31cc1010f650ec8a224fe00439b8533ecbb5437ddeb0b`. This is the restored original APK, **not evidence of a current-main runtime reproduction**. No APK was installed.
- Before the action, captured the original APK and held private data/settings baselines without printing credentials, Home address, Profile labels, grant IDs or session references. Opened the real app using `am start`, then the actual conversation menu → Configure relay → Paired Homes. UI hierarchy observations and coordinate taps drove the application, not a fake service or instrumentation substitute.
- Read-only SSH verification immediately before Refresh: Home deployment metadata `d803994d1d47c63bb1b3c92cff42695de19a4434`; all 34 installed Python sources byte-match that revision. `Hermes Home` task Running; loopback ports 8780 and 8766 owned by PID 37536. Authenticated configuration GET returned 200. No deployment, restart or other-session action occurred.

### Actual result

- Paired Homes displayed the existing Home host, `Paired`, `Pairing valid until Jan 4, 2027`, and all three stored grant labels, each with `Profile saved`. The three stored grants were active and already mapped to three saved Profiles. Private labels and identities were compared in memory and omitted here.
- Exactly one actual `Refresh Profiles` tap at **2026-10-08T01:02:51.506874Z**. The sheet disappeared and the app returned to `Ready` / `Ready for a new turn.` No durable refresh outcome was visible. No second Refresh was attempted after this defect.
- Stored pairing and grant records remained byte-identical: **three active grants before and after**, with unchanged labels/status/availability and mappings. The three complete Profile objects, selected Profile and stored session references remained unchanged. Home configuration GET returned 200 before and after with identical snapshots; its three available Profile names matched the three stored grant labels.
- No grant was approved or newly created. An individual device-refresh HTTP receipt could not be established from the available Home logs: the inspected runtime log had no matching request-window/configuration-GET lines. Therefore neither the control-plane success of that specific tap nor a `No new profiles` result is claimed from the unchanged stores alone. The independently authenticated configuration reads establish server/client snapshot consistency, not a substitute refresh receipt.

### Current-main source review and narrow recommendation

Current-main source still contains the path consistent with the installed-build observation:

1. `PairedHomesSection.kt:179-185` sets the refresh outcome message, reloads, then calls `onChanged()`.
2. `RelayConfigurationScreen.kt:66-69,131-134` routes that notification through `refresh()` to its parent `onChanged()`.
3. `MainActivity.kt:870-874` sets `configurationVisible = false` whenever a Profile is selected, dismissing the sheet that owns the message.

This is source evidence at `d30182e`, **not a latest-main device run**. Recommended narrow fix, pending authorization: separate collection/paired-Home refresh notification from deliberate setup-completion dismissal so Refresh leaves the section and its truthful result visible. Add a focused UI regression at the parent-sheet callback boundary, then rerun the authorized non-destructive device check; retain existing close-on-completed-setup behavior where intended. No production fix was made.

### Preservation and remaining gates

- No Unpair, re-pair, grant approval, permission change, data clearing, uninstall, `connectedAndroidTest`, prompt submission, listening/capture action or Home deployment.
- Final original APK hash unchanged. `relay-profiles.json`, `home-client-pairings.json`, encrypted credential preferences and runtime-permission-request preferences all byte-identical to this run's baseline. Pairing, credentials and stored session references retained; no valid app state was rolled back.
- Font scale, enabled accessibility services/accessibility switch, all three animation scales and night mode unchanged. No settings restoration or APK reinstall was necessary. The app was safely force-stopped at the end, matching the prior stopped baseline.
- Refresh feedback is a confirmed installed-build acceptance defect; a current-main runtime rerun and individual refresh receipt remain unverified. Newly-active-grant creation on a real Home was not exercised. Destructive Unpair + re-pair remains explicitly waived, not passed. TalkBack remains unverified and **not waived for HOME-13**. The original deterministic/unit/repetition evidence was reused rather than rerun; no story-completion transition is made.
