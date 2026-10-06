---
id: ANDROID-DIAG-02
title: Automatically send opted-in connection reports to Home
status: backlog
product_epic: 6
created: 2026-10-06
depends_on:
  - android:ANDROID-DIAG-01
  - home:HOME-NW-06-client-reports
  - home:HOME-NW-06-android-platform
ios_reference: 'IOS-DIAG-02'
github_issue: https://github.com/achappell/hermes-relay-android/issues/77
---

# ANDROID-DIAG-02 — Automatic Home connection reports (schema 1)

Parity with `IOS-DIAG-02` (`spec-ios-diag-02.md`), against the Home endpoint delivered by the HOME-NW-06 client-reports slice (`hermes-relay-home` PR #67 `79b2b4c`, "accept and review bounded device connection reports"; contract in `spec-home-client-diagnostics.md`, validator `src/hermes_home/observability/client_reports.py`).

## Background

On 2026-10-03/04 the pilot iPhone's automatic diagnostics stopped uploading after 18:41:23 while the app was active, and reports carried no build SHA; both hid the incident telemetry (`validation-ios-home-06.md` follow-up). Android has no way to put connection evidence in front of the Home owner at all; the household members' phones are the ones that fail.

## BLOCKER — Home does not accept Android reports today (verified, Home `main` at `790f59e`)

`validate_report` requires `platform ∈ {"ios", "macos"}` and `model` matching `(iPhone|iPad|Mac)N,N | arm64 | x86_64 | unknown`. An Android report with `platform: "android"` is rejected `400 invalid_request`, and no value of `platform` that Android could truthfully send passes. (Device credentials already allow endpoint type `android`: `CLIENT_ENDPOINT_TYPES = {"tui","ios","macos","android"}`.) Required Home change, **not part of this repository and not yet ticketed there**: accept `platform: "android"` and a bounded Android model vocabulary (propose `unknown` or a sanitized `[A-Za-z0-9 _.-]{1,40}` Build.MODEL), extend the `/pair` viewer label, and add tests. Until Home ships that, this ticket can implement and test everything locally but cannot pass device acceptance. Tracked here as the dependency `home:HOME-NW-06-android-platform`; the owner of `hermes-relay-home` must raise the matching story.

## Required behavior

- Off by default, with explicit per-paired-Home consent in Settings. No special build, no silent enablement. Copy states exactly what is sent.
- Only typed connection/request outcomes: schema-1 event names (`launch`, `active`, `inactive`, `background`, `connection_lost`, `connection_ready`, `connection_failed`, `request_started`, `request_completed`, `request_failed`), allowlisted failure codes (`unknown`, `transport_unavailable`, `transport_timeout`, `hermes_unavailable`, `protocol_error`, `conversation_mismatch`, `stale_conversation`, `request_rejected`, `reconnect_required`, `unauthorized`, `forbidden`, `capability_unavailable`, `invalid_request`), phases (`open`, `reconnect`, `submission`, `lifecycle`), timestamps, durations, launch IDs, and app/build/OS/model metadata. No raw journal strings, messages, audio, handles, credentials or free text. Map `AndroidHomeUnavailableReason` onto the closed code set in one table with a test that every reason maps.
- Report envelope: `schema` (int 1), `report_id` (UUID), `created_at` (epoch seconds, within now−7 d..now+300 s), `app_version` (`0.3.1` form), `build` (the numeric `versionCode`), `os_version` (Android release such as `16`; must match `^[0-9]{1,8}(\.[0-9]{1,8}){0,3}$`), `platform`, `model`, `events` (1–100). Body ≤ 65,536 bytes. Exact key set; unknown fields are rejected by Home.
- `POST /api/v1/client-diagnostics` with the existing Device credential (Administrator tokens do not substitute), to the approved paired HTTPS origin only. Refuse redirects (`followRedirects(false)`) and require an acknowledgement `{"schema": 1, "report_id": …}` naming the exact report. A schema-2 report still gets a schema-1 receipt (do not "upgrade" this check).
- Limits: at most 100 context events per report and ten queued reports per Home, sixteen Homes, seven-day retention, one new report per minute (Home enforces 30 s per device; `429 rate_limited` keeps the report). One upload at a time, retries at most once per 30-second foreground pass. No recursive reporting of upload failures. The queue survives process death and relaunch (file-backed, `noBackupFilesDir`).
- Disabling reporting cancels any upload in flight and deletes unsent local reports. Unpairing/re-pairing or changing the Home binding never reroutes old reports. Uploaded copies expire on Home after seven days.
- Settings shows opt-in state, queued count and last successful upload time. Manual Share diagnostics (`ANDROID-DIAG-01`) stays available.
- Retry only while the app is foreground (parity with iOS and a privacy-conservative default); do **not** add WorkManager or background uploads in this ticket. Revisit only with an explicit owner decision.
- Include the build identity that the pilot lacked: `build` is `versionCode`; add the git revision to the journal header (not to the report, which Home schema 1 cannot carry).

## Acceptance criteria

- Persistence/relaunch, offline retry and idempotency (same `report_id` re-sent yields 200 and no duplicate), consent gating (nothing queued while off), expiry and bounds (101 events, 11 reports, 17 Homes, 8-day-old events), identity isolation (report for Home A never sent to Home B after re-pair), cancellation on disable, redirect refusal, ack mismatch handling, 429 handling, and exact-bytes retry (sorted-key compact JSON) are covered by JVM tests with a fake Home HTTP service.
- Content safety: a test with prompts, handles, refs and tokens in fixtures proves none can reach a report; every `AndroidHomeUnavailableReason` is mapped.
- Wire test against a fixture generated from Home's `validate_report` (copy the validator's accepted/rejected examples as test vectors; include `platform: android` vectors marked pending the Home change).
- Device acceptance (needs the Home change): enable for a paired Home, force a connection failure, background and return, see the report in the signed-in `/pair` viewer (`#client-reports`) with the Android device label.

## Android design notes

- Reuse the `HttpHomeClientService` OkHttp stack in `HomeClientPairing.kt` for the POST; no new HTTP library. Reuse the paired Home origin validation (`RelayProfileValidator.validateApprovedHomeRoute`) so reports can only go to the approved HTTPS origin.
- The report queue is separate from the journal (`ANDROID-DIAG-01`): the journal is human-readable lines, the report queue holds typed events. Do not derive reports by parsing journal text.
- Consent storage: per Home, in the pairing record or a sibling file, never in `RelayProfile` JSON that is exported or shown.
- Settings UI: a switch row per paired Home in `RelayConfigurationScreen.kt`/`HomePairingSection.kt` with the queued count and last-upload time; Compose semantics for the switch state.

## Dependencies

`ANDROID-DIAG-01` (journal/UI home); Home client-reports endpoint (deployed with HOME-NW-06); **Home accepting `platform: android`** (blocker above).

## Test notes

JVM: fake clock, temp directory for the queue, fake HTTP service. Keep upload timing deterministic (no `Thread.sleep`; `ANDROID-TEST-01`). Record local and deployed acceptance separately.

## Device verification and expected journal lines

`diagnostics report queued events=N`, `diagnostics upload result=ok|rate_limited|failed code=…`, `diagnostics consent enabled=true|false`. Never the report ID.

## References

iOS: `spec-ios-diag-02.md`, `validation-ios-diag-02.md`, issue achappell/hermes-relay-ios#114; Home: `spec-home-client-diagnostics.md`, `client_reports.py`.
