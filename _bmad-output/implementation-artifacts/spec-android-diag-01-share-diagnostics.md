---
id: ANDROID-DIAG-01
title: Share content-free connection diagnostics from the app
status: review
product_epic: 6
release_scope: migration
parity_epic: ANDROID-PARITY-01
parity_stream: S5
created: 2026-10-06
depends_on: []
ios_reference: 'IOS-DIAG-01 (journal and Share diagnostics), plus the PR #122 journal lines'
github_issue: https://github.com/achappell/hermes-relay-android/issues/76
---

# ANDROID-DIAG-01 — On-device journal and Share diagnostics

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): PX-32 (connection journal), PX-33 (Share diagnostics), matrix D1/D2. Single ticket for both; no duplicate filed.

Parity with `IOS-DIAG-01` (`spec-ios-diag-01-share-diagnostics.md`). It is the evidence layer every other Android ticket in `ANDROID-PARITY-01` uses for device acceptance. On iOS the journal is what turned weeks of guesses about background audio into a code-established root cause: the build-17 export named the initiator (`lifecycle deactivate trigger=backgroundWithoutRetention reply=none` followed by `home client close`).

## Background

A household member's phone runs a signed release build. When a connection failure happens there, nothing records it: Android has no persistent journal and no `android.util.Log` calls at all (grep finds none in `app/src/main`; `OkHttpRelaySessionClient` carries only an internal `diagnostic: String` field on its outcome type). Diagnosing a fault needs a cable and `adb logcat`, and logcat ages out. The person seeing the fault should be able to send the evidence.

## Required behavior

- Every build (debug and release) keeps a small on-device journal of Home connection events: open/reconnect outcomes, failure codes and phases, the name of any local check that refused a result, bridge request outcomes, transport loss, app foreground/background changes, retention/teardown initiators (the lines listed in `ANDROID-HOME-08`), audio output events (`ANDROID-HOME-09`), deadline expiry (`ANDROID-HOME-10`).
- The journal survives relaunch and process death, is capped at 2,000 entries (oldest dropped first), and is excluded from backup (`allowBackup="false"` already; keep it out of any future backup rules and use `noBackupFilesDir`/internal storage).
- **Settings → Troubleshooting → Share diagnostics** opens the Android share sheet with one text file (`ACTION_SEND` with a `FileProvider` content URI or text extra): a header (app version, version code, Android release + API level, device model) and the journal entries. Works offline and without a Hermes Profile.
- Entries contain fixed event names, codes, phases, check-site names, durations and timestamps only. **Never** prompts, replies, transcripts, titles, tokens, credentials, conversation handles, `claim_ref`/session refs, audio, or correlation IDs.
- Per-frame audio and per-event-received notices are not journaled (lead sampling lines from `ANDROID-HOME-09` are the explicit, bounded exception: 21 lines per background entry).
- Debug builds also write the same lines to `android.util.Log` with a single tag.
- No new server operation: delivery is the share sheet only (automatic upload is `ANDROID-DIAG-02`).
- The journal write path never blocks the audio/feed threads or the main thread (single background writer, bounded queue, drop-oldest on overflow with a `journal dropped=N` marker).

## Acceptance criteria

- Unit tests: append, 2,000-entry cap, persistence across instances, export format with header, queue overflow marker, and that the connection trace (open/reconnect/close) reaches the journal.
- A content-safety test: feed the journal a fixture that includes a handle, a `claim_ref`, a prompt string, a token-shaped string and a Session ref in places a careless `toString()` would leak them; assert none appears in any journal line or export (mirror `TranscriptExportTest`'s forbidden-substring approach: `token`, `wss://`, `bearer`, `pcm`, plus `cref-`, `corr-`, `req-`).
- Export works with airplane mode and with no Profile configured.
- On a release-signed build on a physical device: reproduce a connect, background the app, return, tap Share diagnostics, send it to another device, and confirm it contains the connect/reconnect outcomes and no content.

## Android design notes

- Implement as a small `DiagnosticsJournal` interface in the runtime (`ANDROID-HOME-07`) with a file-backed implementation (append-only JSON lines or the same atomic-file pattern used by `FileAndroidHistoryStore`) and an in-memory fake for tests. Inject it into `OkHttpRelaySessionClient`, the lifecycle coordinator, the sink and the service; avoid a global singleton.
- Line grammar: reuse the iOS names so one grep works on both platforms. Journal lines recorded by `ANDROID-HOME-03/04/06/08/09/10` are specified in those tickets.
- Share UI belongs in `RelayConfigurationScreen.kt` (the existing configuration sheet) beside the version row from `ANDROID-REL-01`.
- `FileProvider` needs a manifest provider entry and a `file_paths` resource; the shared file is a copy in cache, deleted after sharing.

## Dependencies

None. Unblocks device evidence for `ANDROID-HOME-03` to `-10`; precedes `ANDROID-DIAG-02`.

## Test notes

JVM with a fake clock and temp directory. One instrumented test for the share intent (assert the chooser intent, not a UI). Keep the journal API small and stable because other tickets depend on it.

## Device verification and expected journal lines

Release build, reproduce `ANDROID-HOME-04`'s long background; the exported file shows `app phase=stopped` … `app phase=started`, `home connect reconnect result=…`. Confirm the export header has no device serial, account or IP.

## References

iOS: `spec-ios-diag-01-share-diagnostics.md`; `DiagnosticsHeader` and the journal in `HermesRelay/Services`; `validation-ios-home-07.md` "Reading the new journal lines".
