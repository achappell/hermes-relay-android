---
story: ANDROID-DIAG-01
spec: spec-android-diag-01-share-diagnostics.md
status: done-with-environment-limitation
story_status: review
updated: 2026-10-08
---

# ANDROID-DIAG-01 validation record

**Current story status: `review`.** Actual debug-device evidence now includes offline export, background/reconnect observations and one explicitly authorized diagnostic email observed in Gmail **Sent** with its attachment. Delivery and recipient inspection remain unverified; the separate release-signed physical gate remains unmet. No waiver or completion is inferred.

| Gate | Status | Evidence |
| --- | --- | --- |
| Local (JVM) | Passed | Below |
| Repetition gate (30 runs) | Passed | Below |
| Instrumented share test on a real device | **Passed**, Pixel 6a, Android 17 (API 37) | `DiagnosticsShareTest` 2/2 after a real-device fix, below |
| Release-signed physical acceptance | **Unverified** | No DIAG-01-inclusive genuinely release-signed APK on a compatible physical device |
| Share to another device and inspect received export | **Sent; recipient inspection unverified** | One authorized Gmail send; matching subject and diagnostics attachment observed in Sent, not proof of delivery or recipient reading |
| Background journal and airplane-mode export | **Observed on debug build only** | 130.005 s locked interval, actual reconnect journal and offline UI export below; not release-build or HOME-04 acceptance |

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

## Bounded debug-device evidence — 2026-10-07 CDT / 2026-10-08 UTC

Device owner `android-diag01-acceptance` tested the existing Pixel 6a / Android 17 (API 37), app `0.3.1 (301)` **debug**, embedded revision `b865c0b`, APK SHA-256 `5692a179b2347214b7e31cc1010f650ec8a224fe00439b8533ecbb5437ddeb0b`. No app or test APK was installed or changed for this run. The earlier no-Profile/chooser tests remain retained **2/2** evidence; they were not rerun.

- **Locked background, Wi-Fi on:** measured **130.005 s**. Content-free journal observed `app phase=stopped` at `02:30:22.719Z`, `home bridge transport lost` at `02:30:27.691Z`, `app phase=started` at `02:32:31.762Z`, then `home connect conversation.reconnect result=connected` at `02:32:32.400Z` (458 ms request duration). This records this bounded debug lifecycle/reconnect only; it does not close HOME-04.
- **Actual offline Share UI:** with airplane mode `1` and Wi-Fi `0`, Share diagnostics generated a **38,971-byte, 471-event** export at `02:34:47.819Z`, SHA-256 `473f0ce76b7313aee0cfc2d17b630e21adfbae392393e98d725f5f76458f12ae`. Its header identified `hermes-relay-diagnostics/1`, version/code/build/revision and Android/model/time, not a serial, account or IP. Forbidden-substring hits were **0**; sensitive profile/pairing/credential matches were **0**; checks of **82** local-history content fields found **0** matches. One fixed nonprivate phase-enum match was not a sensitive-value leak.
- **Earlier recipient boundary:** Android Share → Tailscale opened an attachment, but no Taildrop transfer occurred; the available alternative recipient was not authorized. The subsequent Gmail draft lost its attachment after Hermes was stopped. Neither attempt is counted as a send. Amanda then explicitly authorized the current configured sender to email only her specified address.
- **Actual single email send:** the integration recovery owner re-established **Hermes Share diagnostics → Android Share → Gmail**, keeping Hermes alive until sending. A fresh **40,593-byte, 491-event** export (SHA-256 `ee1077db64f0b2cd108523715ab879fc998f95b9da3f7670e561ce05be440b4e`) passed forbidden-substring checks and checks against **209** baseline fields, including **169** history fields: **0** sensitive matches, with one harmless fixed phase-enum match. Before sending, the UI showed the unchanged explicitly authorized current sender, only the authorized recipient, empty Cc/Bcc, exact subject **Hermes Relay diagnostics verification**, blank body and one diagnostics attachment. The cached attachment still matched the checked bytes.
- **Send versus receipt:** Send was pressed **exactly once at `2026-10-08T02:58:26Z`**. The compose closed; Gmail's **Sent** folder then contained the matching subject, and opening that message showed the diagnostics attachment. No duplicate email was sent; existing drafts/mail were preserved. This is observed sender-side send evidence only: delivery and recipient attachment inspection remain unverified.
- **Safe conclusion:** the resumed baseline matched the prior captured baseline before action. All **8** settings stayed unchanged; all **3** profile/pairing/credential file hashes and the original APK hash matched after conclusion. Only the optional pairing capability flag removed by this launch was restored, after confirming it was the sole data difference. The first stdin transfer timed out; the bounded `shell -T` retry restored exact bytes while the app was stopped. No APK installation, account/login change, microphone use or settings mutation occurred. Phone returned unlocked to the launcher, Hermes stopped, and the temporary UI dump was removed.

Sanitized local records: `/tmp/diag01-acceptance-20261007/sanitized-evidence.json` for the earlier debug observations and `email-sent-evidence.json` for the completed email send/restoration. The observed Gmail send supersedes the earlier pending/blocked recipient route; it does not establish recipient inspection. No private export, addresses, credentials or sensitive values are committed.

## Remaining acceptance prerequisites

1. **Release-signed physical acceptance:** obtain a DIAG-01-inclusive genuinely release-signed APK and a compatible separate physical device. The available official September 12 `v0.3.1` predates DIAG-01; its signer (`f99a4999…`) differs from the installed debug signer (`59743ca2…`). The documented local `release.jks` is absent. No incompatible install, keystore change, debug-signing fallback claim or deployment was attempted.
2. **Recipient inspection:** the owner must open the email with subject **Hermes Relay diagnostics verification** in the authorized receiving mailbox and inspect the attached export/header and expected content-free connection outcomes. Sent-folder evidence is not delivery or recipient acceptance. Do not resend or use another recipient without a new instruction.

These gates remain unwaived; spec, sprint and story status stay `review`. Audio continuity during rotation was not exercised and is not implied by the bounded journal run. Next action is recipient attachment inspection plus an owner-provided compatible release artifact/device, not another debug rerun.
