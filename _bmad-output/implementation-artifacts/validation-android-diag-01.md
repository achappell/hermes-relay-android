---
story: ANDROID-DIAG-01
spec: spec-android-diag-01-share-diagnostics.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-07
---

# ANDROID-DIAG-01 validation record

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM) | Passed | Below |
| Repetition gate (30 runs) | Passed | Below |
| Instrumented share test on a real device | **Passed**, Pixel 6a, Android 17 (API 37) | `DiagnosticsShareTest` 2/2 after a real-device fix, below |
| Release-signed build, share to another device, read the export | **Unverified** | Only debug builds were installed; nothing was shared off the phone |
| Journal lines for a long background / airplane mode | **Unverified** | See the device section |

## What changed

- `DiagnosticsJournal` (interface) with `FileDiagnosticsJournal`: append-only JSON lines in `noBackupFilesDir/diagnostics/connection-journal.jsonl`, capped at 2,000 entries (oldest dropped; the file is rewritten after an eighth of slack), a bounded 512-entry queue drained by one daemon writer thread, `journal dropped=N` marker on overflow, torn trailing lines skipped on load. `record()` only enqueues; it never touches the disk on the caller's thread. Injected, no global singleton; `DiagnosticsJournal.None` is the explicit no-op for code with no journal wired.
- Debug builds echo every line to logcat (`HermesDiag`) through `LogcatEchoJournal`.
- `DiagnosticsHeader`: kind, app version, version code, `Android <release> (API n)`, device model, time. No serial, account, profile or network identity (asserted by key set).
- Settings -> Troubleshooting -> Share diagnostics (`RelayConfigurationScreen`, offered with or without a Profile, works offline): writes one text file to `cacheDir/diagnostics-export/` and opens the share sheet through a `FileProvider` (`<applicationId>.diagnostics`, `cache-path` only). A leftover copy is deleted at app start.
- Lines wired: `runtime created` (once, `HomeRuntimeBox`), `runtime teardown reason=activityFinished|replySettled`, `runtime teardown deferred reply=inFlight`, `app activity created/destroyed ...`, `app phase=started|stopped`, `home connect <method> result=... reason=... reused_claim=... duration_ms=`, `home claim created`, `home claim released reason=...`, `home bridge request completed|failed|rejected method=prompt.submit ...`, `home bridge transport lost`, `websocket closed by peer code=N`, `websocket failed error=<ExceptionClass>`, `home client close`. Reasons are enum names; the websocket failure carries the exception class name only.
- HOME-07's deferred gate is now covered in JVM (`runtime_created_is_journaled_once_across_rotations`); its validation note is updated.

Not wired here, by design: lines owned by `ANDROID-HOME-03/04/06/08/09/10` (each ticket adds its own and asserts it through `RecordingJournal`), and per-frame audio.

## Local gate

- `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon`: passed; 307 unit tests, 0 failures (289 on `main` + 18: 14 `DiagnosticsJournalTest`, 2 in `OkHttpRelaySessionClientTest`, 2 in `HomeRuntimeTest`). Lint caught `PackageInfo.getLongVersionCode` (API 28; min SDK 26): now `PackageInfoCompat`.
- `scripts/check-apk-metadata.sh`, `git diff --check`, `python3 -m unittest discover -s tests -p "test_bmad_issue_tracking*.py"`, `scripts/apply_repo_issue_tracking_overrides.sh --check`: passed.
- Repetition gate: `scripts/run-flake-gate.sh DiagnosticsJournalTest,OkHttpRelaySessionClientTest,HomeRuntimeTest 30`: **30 consecutive runs, 0 failures** (64 tests per run). A first attempt recorded 1 failure that was not a test failure: gradle exit status 143, i.e. the build process was killed by another agent's `pkill`; the gate was rerun in full.

### Content safety

`the_connection_trace_reaches_the_journal_and_carries_no_content` and `a_refused_connect_is_journaled_by_reason_code_only` drive the real client against the TLS MockWebServer and assert no line contains `token`, `wss://`, `bearer`, `pcm`, `cref-`, `corr-`, `req-`, the conversation handle, the Device credential, the Profile id or the route id. `teardown_names_its_initiator_in_the_journal` asserts the runtime lines carry no prompt, reply, handle or turn id. This is a forbidden-substring check on lines the code actually writes, not a scrubber: the journal does not filter what callers pass (see `AGENTS.md`).

## Device results (2026-10-06/07)

Pixel 6a, Android 17, API 37, debug build, run with `adb install -r` and `am`/Gradle instrumented runs (no uninstall).

- `DiagnosticsShareTest` (chooser intent, `FileProvider` URI served by the content resolver, header present, Troubleshooting control displayed without a Profile): **2/2 pass**.
- **Real-device finding:** the first run failed. Android's `org.json` writes `/` as `\/`, so the header kind appeared as `hermes-relay-diagnostics\/1`; the JVM `org.json` does not escape, which is why the JVM tests were green. The line encoder now unescapes slashes (escape-aware, so a literal backslash before a slash survives; `an_event_containing_a_backslash_and_slash_survives_the_line_encoding`), matching the iOS export.
- `HomeRuntimeRecreationTest` passed with this build installed.
- **Rotation / font scale on the real app, journal read through `run-as`** (second run, a debug build of this branch plus the #119 paired-Profile fix, paired Spark Profile, no prompt sent): launch connected the Profile, then four `user_rotation` flips (portrait/landscape/portrait/landscape) and a font scale 0.85 -> 2.0 -> 0.85 were applied. Content-free journal lines, in order: `runtime created` (once) -> `app activity created attached=1` -> `app phase=started` -> `home claim created` -> `home connect conversation.open result=connected reason=none reused_claim=false duration_ms=633` -> `home connect conversation.reconnect result=connected reason=none reused_claim=true duration_ms=69` -> then four pairs of (one per rotation flip; the font-scale change fell after the last journal line was read, and the process was killed by another install before it could be re-read, so a font-scale recreation line is **not** evidenced)  `app phase=stopped` / `app activity destroyed finishing=false config_change=true remaining=0` / `app activity created attached=1` / `app phase=started`. **No second `runtime created`, no `runtime teardown`, no `home client close`, no `home claim released`** across the recreations: HOME-07's "one runtime across rotation" holds on a Pixel 6a (Android 17, API 37). Rotation/font settings were restored (accelerometer_rotation=1, user_rotation=0, font_scale=0.85).
- **Finding for `ANDROID-HOME-03`/`-04`:** the connect appears twice within 100 ms of launch (`conversation.open` then `conversation.reconnect` reusing the claim). Two paths ask for a connect at start (the paired-Profile `LaunchedEffect` and the `ON_RESUME` observer) with no single-flight guard. It does not open a second claim, but it is the race the HOME-03/04 single-flight guard exists for.

## Unverified

Release-signed build, sharing the file to another device, airplane-mode export, and the long-background journal read (that belongs to `ANDROID-HOME-04`'s device gate). Audio continuity during rotation (needs a streaming reply) was not exercised.
