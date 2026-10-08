# ANDROID-STD-01 — Slice 1 validation

**2026-10-08 — implemented for draft review; whole story in-progress.** Local verification is green. Household Hermes 0.21.5 and physical Android acceptance are **unrun**, not a provider/product blocker to publishing this slice. No merge, deployment, Pixel/ADB operation, device installation or live household traffic was performed.

## Scope and provenance

- Approved readiness: PR #66, merged 2026-10-06. Saved implementation baseline: `e38b83e61902734435509f60efd6ebac41e7bb9c`; recovered without restarting after the original worker's provider 429.
- Slice 1: explicit HomeBridge/Standard choice; distinct encrypted Standard credential slot; checked setup; direct typed streaming; legacy profiles remain Legacy; history and recall keyed by mode/endpoint/Hermes Profile; deliberate New conversation; local-stop finishing state; no-replay uncertainty; minimum switch/identity guards. Home, admin and rollback credentials are not reused.
- Integrated user-merged #127/#129 code from `df78efdd02c5f8541f2cf4f51102048c3f83c13f` in `47007bb36dde20592466a341ddeaff66d008b807`. Conflicts in `DoorwayZones.kt`, `HomeRuntime.kt`, and `MainActivity.kt` were combined: preserve UX tokens/state washes and Home interrupt-and-listen/capture ownership, while Standard stays typed-only with local Stop and mode-specific text. Later owner-acceptance metadata from #135 is preserved separately.
- Exact publication head, base and CI result are in the draft PR body; CI builds the final head. The local APK is verification evidence only, not an installed or household-accepted build.

## Authentication reconciliation

The initial spec's “bearer auth” phrase did not establish HTTP Authorization-header support. Actual local Home `_authenticated_url` (`src/hermes_home/bridge/standard.py:3122–3149`) and TUI `gateway_url_with_token` (`gateway_client.py:46–62`) use a URL-encoded `token` query parameter. Home also supplies `profile` in the URL and session parameters. Android follows that actual client seam; stored endpoints reject query/userinfo/credentials. Tokens are added only to in-memory requests and excluded from diagnostics/history/Profile JSON.

Those implementations are not Android verification of target Hermes 0.21.5 (`f97608f178d1ffeca59860195ab7da295f7c8e5f`). No locally available pinned server authentication source established whether Bearer headers are accepted. Neither acceptance nor rejection of Bearer is claimed; the actual-baseline gate remains unrun by authorization, not silently substituted by fixtures.

## Exercised gates

| Gate | Observed result |
| --- | --- |
| Full JVM suite | **610 cases: 609 passed, 1 skipped, 0 failures/errors.** The single skip is the explicitly disabled live baseline probe. |
| Standard-focused JVM classes | Transport 59; runtime 20; router 10; setup/controller 32; profile/migration/history identity 22; credential slots 10; state 9; local baseline probe 13. All 175 passed. |
| Debug assembly and lint | `assembleDebug` and `lintDebug` passed in the successful aggregate Gradle run. |
| Instrumentation compilation | `compileDebugAndroidTestKotlin` passed, including the new Standard setup-choice/form/save/credential-isolation Compose test. **No instrumentation was executed.** |
| Lifecycle/protocol repetition | `OkHttpStandardSessionClientTest,StandardRuntimeTest,ProfileModeClientPortTest,StandardBaselineProbeTest`: **30 consecutive runs, 0 failures; 102 tests per run** (3,060 executions). |
| Repository tracking | 18 Python issue-tracking tests passed; override shell syntax and `--check` passed. |
| Local APK metadata | Installed `aapt2 dump badging` confirmed package `com.achappell.hermesrelay`, version 0.3.1 (301), min SDK 26, target/compile SDK 37. The formal metadata script could not run locally because `apkanalyzer` is absent; its exact-head CI result is reported in the PR. Release signing was not accepted here. |

Commands (JDK: Android Studio `Contents/jbr/Contents/Home`; SDK: `~/Library/Android/sdk`; every shell prepended `/opt/homebrew/bin` to PATH):

```bash
export PATH=/opt/homebrew/bin:$PATH
./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon --console=plain
scripts/run-flake-gate.sh OkHttpStandardSessionClientTest,StandardRuntimeTest,ProfileModeClientPortTest,StandardBaselineProbeTest 30
python3 -m unittest discover -s tests -p 'test_bmad_issue_tracking*.py'
bash -n scripts/apply_repo_issue_tracking_overrides.sh
scripts/apply_repo_issue_tracking_overrides.sh --check
```

The live-probe environment variables were explicitly unset for all local verification.

## Real local adapter/runtime smoke — not household or Android-device acceptance

`StandardBaselineProbeTest` drives the **shipping `OkHttpStandardSessionClient`** against a loopback TLS/WebSocket endpoint. Only the upstream gateway is a fixture; transport, JSON-RPC, normalization, session ownership and recovery are real adapter code. The redacted report is generated at `app/build/standard-local-probe.md`.

The aggregate-run report generated at `2026-10-08T01:09:03.918963Z` recorded **5 PASS, 1 NOT-REPRODUCIBLE, 0 FAIL**:

| Scenario | Observed local evidence |
| --- | --- |
| Create | Session reference returned, fresh session; 13 ms. |
| Typed stream | ResponseTextDelta/Thinking/TurnCompleted; one text event, no unknown event names, completed; 0 ms recorded at millisecond resolution. No response content retained in the report. |
| Structured prompt | Not produced by the main smoke fixture. A separate always-running fixture produced recognized `approval.request` and passed the unsupported-prompt path. |
| Audio boundary | Three binary frames, 60 bytes total; mono 24 kHz, 16-bit little-endian PCM, clean end. This proves the probe's format contract only; **no app voice or speaker playback was enabled**. |
| Interrupt boundary | Positive correlated RPC acknowledgement plus explicit remote interrupted terminal. Local stop alone, natural completion and negative/missing acknowledgement cannot pass. Production user Stop remains local until actual baseline verification. |
| Drop/recover | Cancelled the actual WebSocket HTTP Call, observed transport loss, resumed the held durable session, zero replayed prompts; 6 ms. Server-side assertions observed two deliberate prompts total and no recovery submission. |

OkHttp 5.5.0's cached sources explain the drop seam: `RealWebSocket.connect` replaces EventListener with NONE, and `RealCall` omits network interceptors for WebSockets. The test therefore captures the actual Call through an application interceptor and calls `cancel()`, the same operation used by `RealWebSocket.cancel`; it does not simulate loss using adapter `endSession()`.

## Review, failures and fixes retained

Three independent review layers completed. All four actionable findings were addressed: changed identity retained an old session; New conversation permitted Send/switch while in flight; a failed live probe could appear green; and Standard setup lacked a Compose-route test. The spec contains individual triage rows.

Recovery preserved the original compile-red/new-API tests and previously green saved evidence (including 54 transport tests); it did not treat that older evidence as final verification. Integration then exposed and fixed:

1. A saved router test needed an explicit Unit return type for Kotlin inference.
2. The probe fixture contained odd-length PCM (21-byte frames), correctly rejected by sample-alignment checks. Fixed the fixture to ten complete 16-bit mono samples per frame; did not weaken audio requirements.
3. An older failed-New-conversation test changed endpoint to simulate an outage, which now correctly changes identity. Replaced that with a same-identity refused WebSocket upgrade; uncertainty and durable resume remain required.
4. EventListener-based socket capture never ran for WebSockets; replaced it with the real Call-cancellation seam above.
5. An actual aggregate failure exposed terminal publication before active-turn settlement. Commit state and redacted terminal journal **before** publishing Completed/Failed/Interrupted; a deterministic callback-boundary regression covers all three outcomes.

Observed intermediate aggregates were 609 cases/9 failures and then 609 cases/5 failures; the final aggregate and 30-run gate above supersede them. Failures were investigated and repaired, not rerun until green. No physical/manual pass was inferred.

## Remaining acceptance and slices

- **Unrun actual-baseline gates:** 0.21.5 auth/create, typed event compatibility, structured-prompt reproducibility, audio usability, generation interruption, dropped-connection resume/no replay. A local fixture is not proof of upstream capability.
- **Pending-device:** actual setup UI, secure storage, typed streaming, failure/uncertain recovery, New conversation, profile isolation/endpoint re-entry, accessibility and real layout. No TalkBack waiver from other stories is applied to this story.
- **Slice 2 not delivered:** tap-to-speak, response audio, hands-free/barge-in and verified remote interruption. Q1 still requires an owner decision if the actual baseline provides unusable audio; this recovery did not observe that condition.
- **Slice 3 not delivered:** remaining queued-input/attachment/hands-free switch guards and broader cross-mode/identity acceptance. Minimum typed-turn guards are included now.

The optional live runner requires explicit `HERMES_STANDARD_PROBE_LIVE=1`, endpoint/token environment variables, and `--tests 'com.achappell.hermesrelay.StandardBaselineProbeLiveTest'`. It writes `app/build/standard-baseline-probe.md` and fails on STOP/REVIEW after emitting only redacted evidence. **It was not run here.** No credential, transcript or capture file belongs in publication artifacts.
