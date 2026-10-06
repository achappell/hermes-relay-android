---
id: ANDROID-DIAG-02
title: Automatically send opted-in connection reports to Home
status: backlog
product_epic: 6
release_scope: later
parity_epic: ANDROID-PARITY-01
parity_stream: S5
created: 2026-10-06
depends_on:
  - android:ANDROID-DIAG-01
  - home:HOME-NW-06-client-reports
ios_reference: 'IOS-DIAG-02'
github_issue: https://github.com/achappell/hermes-relay-android/issues/77
---

# ANDROID-DIAG-02 — Automatic Home connection reports (schema 1)

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): matrix D3.

Parity with `IOS-DIAG-02` (`spec-ios-diag-02.md`), against the Home endpoint delivered by the HOME-NW-06 client-reports slice (`hermes-relay-home` PR #67 `79b2b4c`, "accept and review bounded device connection reports"; contract in `spec-home-client-diagnostics.md`, validator `src/hermes_home/observability/client_reports.py`).

## Background

On 2026-10-03/04 the pilot iPhone's automatic diagnostics stopped uploading after 18:41:23 while the app was active, and reports carried no build SHA; both hid the incident telemetry (`validation-ios-home-06.md` follow-up). Android has no way to put connection evidence in front of the Home owner at all; the household members' phones are the ones that fail.

## Home dependency — Android reports are accepted by Home (resolved 2026-10-06)

Home's client-report validator originally accepted only `platform ∈ {"ios", "macos"}` and an Apple `model` vocabulary, so no Android report could pass (verified at Home `790f59e`). That gap was closed by Home PR #80 (`HOME-NW-06-android-platform`, merged 2026-10-06, `f1eeb94`), which extends `home:HOME-NW-06-client-reports`: `platform: "android"` with `model` matching `[A-Za-z0-9]([A-Za-z0-9 _.+()-]{0,38}[A-Za-z0-9_.+()-])?` (1–40 ASCII characters, `Build.MODEL` only; a `manufacturer` key and any other platform spelling are rejected `400 invalid_request`; Apple models are rejected on `android`). The dependency is recorded as `home:HOME-NW-06-client-reports`, the key that exists in Home's tracker; `home:HOME-NW-06-android-platform` was never a Home story key. Delivered by Home PR #80; deployed 2026-10-06 pending record. Device acceptance needs a Home that carries it.

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

`ANDROID-DIAG-01` (journal/UI home); Home client-reports endpoint on a deployment containing Home PR #80 (2026-10-06 delivery; deployment record pending).

## Test notes

JVM: fake clock, temp directory for the queue, fake HTTP service. Keep upload timing deterministic (no `Thread.sleep`; `ANDROID-TEST-01`). Record local and deployed acceptance separately.

## Device verification and expected journal lines

`diagnostics report queued events=N`, `diagnostics upload result=ok|rate_limited|failed code=…`, `diagnostics consent enabled=true|false`. Never the report ID.

## References

iOS: `spec-ios-diag-02.md`, `validation-ios-diag-02.md`, issue achappell/hermes-relay-ios#114; Home: `spec-home-client-diagnostics.md`, `client_reports.py`.

## Release scope decision (2026-10-06)

`later`, mirroring `IOS-DIAG-02`, which the iOS tracker holds at `release_scope: later`. `ANDROID-DIAG-01` (share content-free diagnostics) stays `migration`, matching `IOS-DIAG-01`.
