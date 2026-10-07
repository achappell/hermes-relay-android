---
story: ANDROID-HOME-03
spec: spec-android-home-03-claim-lifecycle.md
status: local-pass-device-partial
updated: 2026-10-07
---

# ANDROID-HOME-03 validation record

## PR base

Rebased onto `origin/main` at `3fd3d765b66d9d3d8c7e250fb3e5e17e8ccf682d` (after PRs #118–#121, #123, #124, #126 and the build hotfix #131 merged), targeting `main` directly. The diff contains only HOME-03 paths/hunks, with no Diagnostics, other-slice, or HOME-04 files. `main` has no `Unreleased` changelog heading, so this change adds one section rather than duplicating an existing one. Rebase conflicts resolved in `MainActivity.kt` (kept REL-01's `versionLabel` and HOME-03's claim messages), `OkHttpRelaySessionClient.kt` (kept HOME-03's binding-mismatch refusal ahead of #119's `pairedGrant == null` administration gate; kept HOME-05's resumed-turn `audioSeenThisTurn`/`awaitingAudio` reset inside HOME-03's readiness commit), and `OkHttpRelaySessionClientTest.kt` (`pairedClient` takes `homeAdministration`, `claimBlock`, `claimFailure` and `journal`).

## Slice status

| Slice | Status | Evidence |
| --- | --- | --- |
| A — identity and reconnect | `already done` | The identity/reconnect acceptance is already complete. `a_reconnect_uses_the_same_conversation_when_ready_capabilities_change` proves capability differences do not change conversation identity and reconnect resumes the same handle; `a_different_conversation_handle_is_refused_locally_with_field_names_only` proves local refusal with field names only. In the final cleanup pass, the capability assertions were unchanged; only deterministic WebSocket close-callback coordination was added. |
| B — release and single-flight | Passed | `concurrent_reconnects_join_one_in_flight_claim`; `a_claim_response_after_disconnect_is_released_by_claim_ref`; `a_claim_response_after_profile_switch_is_released_by_claim_ref`; `a_lost_claim_create_response_is_never_retried`. |
| C — list and close | Passed locally; live list/close passed on device; `claim_limit` pilot not reproduced | `claim_management_survives_cold_start_when_first_claim_hits_claim_limit`; `older_pairing_files_default_claim_management_support_to_false`; `open_claims_are_hidden_until_a_successful_claim_returns_claim_ref`; `a_not_found_claim_route_hides_open_claim_management`; `claim_close_submits_only_explicit_refs_and_not_open_is_a_safe_no_op`; `close_and_list_refreshes_after_success_and_close_error`; `delayed_claim_responses_cannot_replace_a_newer_profile_list`; `missing_session_titles_do_not_block_claim_listing_or_open_time_display`; `client_claim_listing_parses_all_states_and_nullable_open_time`. The UI test `open_claims_show_server_count_current_marker_and_only_close_non_current_refs` now runs on the device (see Device verification). |

## Local checks

- Passed on the code rebased onto `main` `3fd3d76` (code tree identical to the verified commit `5521af503abc7432f39249ac3090d94ef28dab99`; this record and the changelog tidy are the only later edits): `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon` — `BUILD SUCCESSFUL`, 369 JVM unit tests, 0 failures/errors/skipped, debug APK assembled, lint passed, instrumentation Kotlin compiled. `scripts/check-apk-metadata.sh`, `git diff --check origin/main..HEAD`, and the 18 issue-tracking tests also passed.
- Passed: `scripts/run-flake-gate.sh OkHttpRelaySessionClientTest,HomeClientPairingTest 30` — 30 consecutive runs, 100 tests per run, 0 failures.
- An earlier shared-worktree repetition run exposed a MockWebServer teardown race in the capability-change reconnect test. The test now asserts the old socket closes during replacement, completes the current socket close handshake, and waits for its terminal callback before teardown; the gate above passes.
- The redundant cast warning in `HomeClientPairingTest.kt` was removed. The `server.takeRequest()!!` warning is pre-existing on `origin/main` (line 351 there; line 410 now). `HomeDeviceAdministration.kt`'s safe-call warning is pre-existing per main-checkout review. The new UI test retains the repository's legacy `createComposeRule` convention and emits that matching deprecation warning; no broad migration was made.

## Device verification

Pixel 6a (Android 17), already paired to `caticornqueen.taila59979.ts.net` with the Spark, Amanda and Jensen Profiles; Spark selected. Installed with `adb install -r` only (no uninstall, clear-data, re-pair or `connectedAndroidTest`); `relay-profiles.json` and `home-client-pairings.json` were backed up via `run-as` before each install and were byte-identical (SHA-256) after each install. Build under test: PR head code at `ef4c2b8` plus the test-only assertion fix in this commit.

- **Instrumented, `am instrument -w -e class com.achappell.hermesrelay.HomeConversationsSheetTest`:** first run 2 of 3 passed. `open_claims_show_server_count_current_marker_and_only_close_non_current_refs` failed on the device (it had only been compile-checked): the fixture has two untitled claims, so `onNodeWithText("Untitled conversation")` matched 2 nodes. This was a test bug, not a product bug; the assertion now expects exactly 2 untitled rows. Rerun: **OK (3 tests)**.
- **Live gate, Spark Profile, Conversations sheet:** the sheet showed `Open on Home (2 open · max 8)` (Home's `max_claims`, not a constant), the current row marked `Current conversation` with its `Close` control absent, and one non-current row (`Waiting to reconnect`, left by an earlier launch) with a `Close` button. `Close all others` was offered. Tapping that row's `Close` produced `1 conversation closed; open conversations refreshed.` and the header became `Open on Home (1 open · max 8)`. The closed Hermes session stayed in the conversation list (closing never deletes a session).
- **Journal (content-free) for that run:** `home claim created`; `home connect conversation.open result=connected reason=none reused_claim=false`; `home connect conversation.reconnect result=connected reason=none reused_claim=true`; `home claims listed count=2 max=8`; `home claims closed requested=1 closed=1`; `home claims listed count=1 max=8`. No claim refs, handles, Session refs, prompts or replies appear.
- **Not reproduced:** the spec's pilot (fill the device to `max_claims`, observe `claim_limit`, Manage open conversations, Close all others, connect succeeds). Home reports `max_claims` 8 and this phone holds one live claim per connect (a switch or new conversation releases the old one), so reaching 8 would require leaking seven claims; that was not attempted because it would harm the user's conversations. The `claim_limit` path stays covered by `claim_management_survives_cold_start_when_first_claim_hits_claim_limit` only. Screenshots are kept off-repo because they show conversation titles.
