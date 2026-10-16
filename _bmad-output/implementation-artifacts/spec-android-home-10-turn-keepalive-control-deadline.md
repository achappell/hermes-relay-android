---
id: ANDROID-HOME-10
title: Idle-based Home control deadline driven by turn.alive keep-alives
status: in-progress
route: dispatch
baseline_commit: 526ecbaeaf3a3f494aeeaa7afa5ed38ea7827c0a
review_loop_iteration: 0
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-01
parity_stream: S2
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-05
  - android:ANDROID-HOME-06
  - home:HOME-NW-18
  - android:ANDROID-HOME-04
  - android:ANDROID-HOME-07
ios_reference: 'IOS-HOME-07 slow-turn control deadline (185418a, 676192d; PR #122)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/74
---

# ANDROID-HOME-10 — `turn.alive` keep-alives and an idle-based control deadline

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): matrix V17. The audit confirms Android must add the whole deadline layer, not tune an existing one; liveness at the socket level is `ANDROID-NET-01`.

Parity with iOS `185418a` (30 s → 120 s) and `676192d` (idle deadline with `turn.alive`), and the Home server change `hermes-relay-home` PR #75 (`0effbf9`, "opt-in per-turn turn.alive keep-alive for bridge clients"; header parsing covered by PR #78), deployed on CaticornQueen 2026-10-05.

## Background

On the pilot iPhone an accepted Home turn was abandoned before its reply arrived. The phone closed its Home socket about 32 s after submission, while Standard finished the reply 62.7 s after accepting the prompt: the 30 s `controlTerminal` deadline counted from acceptance fired first, the app marked the submission uncertain and reconnected, and the reply was never delivered. iOS first raised the fixed deadline to 120 s (matching Home's 120 s client reconnect grace). Because Home forwards text, reasoning, status, terminal and prompt events but not tool calls, a long tool call or model wait is silent to the client, so Home now sends `turn.alive` keep-alives for opted-in clients and iOS uses them as liveness.

## Wire contract (Home `bridge-contract.md`, "Turn keep-alive")

- Opt-in: request header `X-Hermes-Home-Client-Features: turn_keepalive` (comma-separated token list, trimmed, case-insensitive) **on the WebSocket upgrade**, per transport — including a reconnect that adopts a parked endpoint. Clients that do not send it get a byte-identical ready reply and never receive `turn.alive`. Home rejects unknown `open`/`reconnect` params, so the opt-in is a header, not a param.
- For an opted-in client the ready reply `capabilities` adds `"turn_keepalive": true`.
- While the client's turn is active (from `prompt.submit` acceptance until the terminal event is forwarded, the turn otherwise ends, or Home closes) Home sends one notification every 15 s (`TURN_KEEPALIVE_INTERVAL_SECONDS`):
  `{"jsonrpc":"2.0","schema":1,"method":"event","params":{"schema":1,"conversation_handle":"…","event":{"type":"turn.alive","payload":{"phase":"running"}},"turn_id":"…","correlation_id":"…"}}`
  `phase` is `awaiting_input` while a structured prompt is pending, else `running`. The payload never carries tool names, arguments or content. Keep-alives are never parked or replayed across a disconnect, never sent after the terminal, and do not change turn, grace or claim state.

## Android today (verified against `main` at `3e10ae2`)

- The upgrade `Request` (`OkHttpRelaySessionClient.reconnectOnce`) sets only `Authorization: Device …` (plus the live-gate trace headers). No opt-in header is sent, so Home never sends `turn.alive` to Android.
- `parseCapabilities` reads `heartbeat`, `timing`, `commands`, `interrupt`, `audio` and ignores other keys, so a `turn_keepalive` key would not break ready (add a regression test; do not assume).
- `HermesEventNormalizer.standardEvents` maps an unrecognized `event.type` to `AndroidNormalizedEvent.Unknown`, which `AndroidTurnState` ignores, so a `turn.alive` frame is harmlessly dropped today but carries no liveness meaning.
- **Android has no control-terminal or idle deadline at all** (`readTimeout(0)`, no timers). A turn whose Home or network silently dies stays "Thinking" until a TCP error surfaces. The iOS 30 s bug cannot happen on Android; the converse gap does.

## Required behavior

Part 1 — opt-in and decode (independently shippable, no behavior change otherwise):

- Send `X-Hermes-Home-Client-Features: turn_keepalive` exactly once on every Home bridge upgrade (open and reconnect).
- Decode capability `turn_keepalive` (exact boolean, optional; a malformed value decodes as absent and never fails ready).
- Decode `turn.alive` into an internal liveness event carrying `phase` (`running` | `awaiting_input`), `turn_id` and `correlation_id`. An event for a different `turn_id` or conversation is ignored under the existing mismatch rules. A malformed `turn.alive` is dropped as liveness-only (diagnostic only) and never fails the turn. `turn.alive` is never rendered, never recorded in Local History, never put in the transcript.

Part 2 — idle control lease (approved by Amanda, 2026-10-06; Android has no control deadline today):

**Definition.** The *idle control lease* is the single timer that decides whether an accepted Home turn is still alive on the control channel. It is granted at `prompt.submit` acceptance, renewed by turn evidence driven by `turn.alive` keep-alives, and expires into the existing uncertain-turn path. It is defined separately from, and must not be merged with or substituted for: the post-text audio-start wait (`ANDROID-HOME-05`, armed only after text/speech is requested, never at acceptance), audio and playback timeouts during reply delivery (`ANDROID-HOME-09`, Home's per-sentence speak-stream timeout), socket-level liveness (`ANDROID-NET-01`), and the 60 s background idle timeout (`ANDROID-HOME-08`). Each of those keeps its own owner and values; none renews or expires the lease.

**Values (mirrored, not chosen here).** iOS and Home already define them, so Android mirrors them and Amanda's approval does not introduce an Android-specific number:

| Value | Meaning | Source |
|---|---|---|
| `controlIdle` 45 s | lease with the capability: expires after this long with no current-turn activity (three missed 15 s beats) | iOS `HomeTurnAudioDeadlines` (`HermesRelay/Models/HomeBridgeModels.swift`, `controlIdle: .seconds(45)`) at iOS `origin/main` `dbe4bdd`; `spec-ios-home-07-slow-turn-control-deadline.md` follow-up |
| `controlBackstop` 1800 s | overall cap from acceptance, never extended | same type (`controlBackstop: .seconds(1800)`) |
| `controlTerminal` 120 s | fixed deadline from acceptance without the capability | same type (`controlTerminal: .seconds(120)`); matches Home `DEFAULT_CLIENT_RECONNECT_GRACE_SECONDS` |
| 15 s | Home keep-alive cadence | Home `TURN_KEEPALIVE_INTERVAL_SECONDS` (`bridge/endpoint.py`) and `bridge-contract.md` "Turn keep-alive"; Home PR #75 |

Changing any of these is an owner decision and a matching iOS change, not an Android-only edit.

**Behavior.**

- Without the `turn_keepalive` capability: a fixed `controlTerminal` of 120 s from acceptance.
- With the capability: the lease is `controlIdle`. It renews on any current-turn Standard event, `turn.alive`, scoped activity, or Home audio start, PCM or terminal; it is **suspended** while a structured prompt is pending or the last keep-alive phase is `awaiting_input`; and it is bounded by `controlBackstop` from acceptance, which is never extended.
- Every expiry takes the existing "uncertain, reconnect, never replay" path: the turn is marked unconfirmed, the transport is closed and reconnected through the `ANDROID-HOME-04` ladder, the prompt stays offered for Resend/Discard, and exactly one submission has been made. Never arm an audio-start deadline at acceptance (`ANDROID-HOME-05`).
- All values come from one injectable `HomeTurnDeadlines` value type.

## Acceptance criteria (fake clock + fake Home)

- Upgrade request carries the header exactly once; ready with `turn_keepalive: true` decodes; ready without it decodes; ready with a malformed value decodes as absent.
- `turn.alive` decodes with exact envelope shape (`params.event.type`, `payload.phase`, `params.turn_id`, `params.correlation_id`); wrong turn/handle ignored; never reaches UI state or history.
- Keep-alive turn running 5 minutes with beats every 15 s delivers once, without replay (iOS `testKeepaliveTurnRunningFiveMinutesDeliversOnceWithoutReplay`).
- Keep-alive turn silent for 45 s expires (no timeout at 44 s) and is marked uncertain (`testKeepaliveTurnSilentForTheIdleDeadlineIsMarkedStuck`).
- A pending structured prompt (or `awaiting_input`) suspends the idle clock: no timeout at 300 s; the 1800 s backstop still fires; the backstop fires even with continuous keep-alives.
- Without the capability: a turn finishing after 63 s delivers without replay; no terminal fires `controlTerminalMissing` at 120 s (not 119 s).
- Expiry never produces a second `prompt.submit`.
- Against an older Home (no capability) behavior equals the 120 s fixed deadline; a Home that ignores the header is not an error.

## Android design notes

- Header: add to the `Request.Builder` in `reconnectOnce` next to `Authorization`. Parse the capability in `parseCapabilities` into `AndroidHomeCapabilities.turnKeepalive` (default false).
- Liveness: extend the normalizer with a `TurnAlive` variant (or handle it before normalization in `OkHttpRelaySessionClient.dispatch`) so that `AndroidTurnState` ignores it but the client's deadline timer sees it. Do the idle-clock bookkeeping in the client, where the generation/terminal flags already live, not in Compose.
- Timers: one single-thread `ScheduledExecutorService` owned by the runtime (`ANDROID-HOME-07`), cancelled on terminal/close/generation change; inject a clock for tests. Do not use `Handler.postDelayed` on the main thread for the idle clock.
- Foreground service retention (`ANDROID-HOME-08`): a keep-alive every 15 s keeps the socket warm during a backgrounded reply; the deadlines apply while backgrounded too.
- Structured prompts: `approval.request`-style events currently normalize to `Unknown/protocol_error`; if/when prompt UI lands, "prompt pending" must suspend the idle clock. Until then, only the `awaiting_input` phase can suspend it.

## Dependencies

Home `0effbf9` (PR #75) for end-to-end keep-alives; part 1 is safe against older Homes. `ANDROID-HOME-05` (arming rule), `ANDROID-HOME-06`/`04` (uncertain-turn reconnect path), `ANDROID-HOME-07` (timer owner).

## Test notes

JVM only; manual clock; a fake WebSocket that scripts `turn.alive` at 15 s intervals. One instrumented smoke against the live Home gate (`scripts/run-live-home-gate.sh`) only after the JVM suite passes; record separately from local acceptance.

## Device verification and expected journal lines

With Home `0effbf9`: submit a prompt that runs >60 s (and one with a long silent tool call if available). Expected: text and audio arrive once; Home diagnostics show the 15 s cadence; journal `home capability turn_keepalive=true`, no `store Home deadline expired`. To exercise the failure path, put the device in airplane mode mid-turn for >45 s: `store Home deadline expired kind=controlIdle`, `home client close …`, reconnect on return, unconfirmed prompt still offered, Home shows one submission. Do not journal each beat.

## References

iOS: `spec-ios-home-07-slow-turn-control-deadline.md`, `validation-ios-home-07.md` ("Follow-up: activity-based control deadline…"); `HomeTurnAudioDeadlines` in `HermesRelay/Models/HomeBridgeModels.swift` and `scheduleHomeControlDeadline` in `ConversationStore.swift`; `HomeBridgeClientOptIn` in `HomeBridgeSessionClient.swift`; Home: `_bmad-output/specs/spec-home-bridge-route-roaming/bridge-contract.md`, `src/hermes_home/bridge/endpoint.py` (`TURN_KEEPALIVE_INTERVAL_SECONDS`).

## Implementation scope — 2026-10-15

Both specified parts are approved for local software implementation. Hardware acceptance and live Home evidence remain separate from deterministic and disposable-emulator acceptance. No push or PR is authorized.

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/OkHttpRelaySessionClient.kt`: Home upgrade, capability parsing, acceptance, event dispatch, transport generation and existing uncertain-disconnect path.
- `app/src/main/java/com/achappell/hermesrelay/HermesEventNormalizer.kt`: strict Home envelope normalization; isolate malformed liveness from protocol failure.
- `app/src/main/java/com/achappell/hermesrelay/AndroidClientPort.kt` and `AndroidTurnState.kt`: capability and internal event types; no liveness presentation.
- `app/src/main/java/com/achappell/hermesrelay/HermesRelayApplication.kt` and `HomeRuntime.kt`: process-runtime timer ownership and teardown.
- `app/src/test/java/com/achappell/hermesrelay/ManualAudioScheduler.kt`: deterministic scheduled-executor precedent.

## Tasks & Acceptance

- [x] Implement Home-only opt-in, optional strict capability and scoped typed liveness without UI/history delivery.
- [x] Implement injectable control deadlines, acceptance-time scheduling, matching activity renewal, input suspension and immutable backstop.
- [x] Integrate expiry with existing uncertain/reconnect path and cancellation with terminal/transport/runtime lifecycle.
- [x] Cover every software acceptance criterion above, plus stale callback and terminal races, with deterministic tests.
- [x] Verify actual production-client changed path on a newly created empty disposable emulator; assess live gate prerequisites separately.
- [x] Complete three formal reviews, final local verification, evidence and delivery-record updates.

## Design Notes

iOS `ConversationStore.swift` control-lease implementation confirms correlation IDs belong to individual events: conversation handle and turn ID identify the response. Control completion cancels the lease even while the distinct post-text audio wait remains active. A runtime-owned single-thread scheduled executor runs lease callbacks off-main; client inbound serialization and identity/generation checks arbitrate stale callbacks and terminal races. No existing audio, background-retention or socket timeout changes.

## Implementation Notes

Both software parts are delivered. `HomeControlLease` uses the client-owned off-main scheduler, owned transitively by the single process `HomeRuntime`; runtime port teardown shuts it down. Frequent activity updates timestamps without allocating replacement alarms. Structured-prompt pending state and heartbeat input-wait state are independent. Control is consumed at ingress, even without a UI observer; buffered presentation cannot renew from stale arrival evidence or resurrect a completed turn. A recovered unresolved turn remains uncertain rather than receiving a fabricated new acceptance anchor.

The story remains `in-progress` solely because live Home/physical acceptance below is unverified. No HOME09 hardware/acoustic/Pixel/natural-OS-death/unknown-route gate is closed by this work. Fresh-driver zero-head behavior, no pre-first-play query, and 1000/1500 ms audio defaults are unchanged.

## Spec Change Log

## Review Triage Log

| Finding | Verdict | Evidence and disposition |
|---|---|---|
| B1: reconnect restores an unresolved binding without a new lease | false | A lease is granted by accepted `prompt.submit`, not by recovery of an already uncertain turn. iOS `ConversationStore.swift` reconnect lines 918–940 restores `.uncertain`; only accepted submission at 2273 schedules a lease. Restarting here would invent an acceptance anchor and could cause repeated expired-turn reconnects. Recovered unresolved turns are not claimed as newly leased. |
| B2: observer absence prevents heartbeat renewal and terminal cancellation | high | Accepted binding exists before `observeTurn`; raw-frame buffering deferred control consumption. Normalize and consume control on arrival, buffer only presentation events, and process pre-ack frames at acknowledgement. Production-client regression covers five minutes before observer attachment and pre-ack terminal delivery. |
| B3: scoped audio fallback does not renew an active control lease | medium | `AudioFailed` can arrive before control terminal, so it is current-turn evidence. Matching audio start/end/fallback now renew at ingress; canceled post-terminal leases remain canceled. Production transport test sends fallback at 44 seconds and expires at renewed 89-second boundary. |
| Edge1: control processing is incorrectly coupled to observer registration | high | Same verified path as B2; retained as its own finding before grouping. Normalization/control processing now precede optional observer delivery. |
| G1: production text/PCM renewal coverage missing | medium | Accepted verification gap. Added real TLS WebSocket client tests for text and active PCM at 44 seconds, survival past 45/88 seconds and expiry at 89 without heartbeats. |
| G2: post-text audio wait lacks control-deadline separation proof | medium | Accepted verification gap. Added both capability modes: text completes, audio remains pending past 45/120 seconds without disconnect or lost text, then real PCM/end delivers once with one submission. |
| P1: activity cancels/allocates scheduled work per PCM chunk | medium | Verified allocation churn. One outstanding alarm now rechecks the latest activity timestamp; only an earlier deadline (input-wait exit) requires replacement. A thousand activity calls retain the same alarm and expiry still follows the renewed boundary. |
| P2: capability constructor/default-only test is incidental | low | Deleted constructor-only assertions; real ready-capability parsing covers true/false/absent/malformed values and transport tests exercise negotiated behavior. |
| Parent fast-terminal concern after B2 correction | medium | Prevented by buffering normalized presentation terminal until observer attachment while canceling control immediately; no `clearTurn` occurs during observer-free control consumption. A server terminal preceding its acknowledgement must still yield Accepted, remain non-disconnected past the backstop, then render one preserved terminal when observed. |
| Focused blind: buffered event replay retains a stale active-binding snapshot | high | A terminal can clear `activeTurn` while the replay loop still holds its initial binding. Recheck live binding for every buffered presentation event. Pre-ack terminal followed by late audio and duplicate terminal must render once and never start the sink. |
| Focused edge: buffered terminal permits late queued presentation events | high | Same replay defect as focused blind, retained independently before grouping. Live per-event binding prevents replay after a terminal winner. |
| Focused blind: out-of-stream binary renews idle | medium | Renewal was ahead of stream validation. Track validated wire audio-start independently from observer attachment; only PCM in that stream renews. Binary at 30 seconds without start must still expire at 45 with zero sink bytes. |
| Focused edge: old-socket callback can rebound to a new turn after its early listener guard | high | Listener identity checks happened before acquiring inbound serialization. Dispatch now revalidates source socket and generation under that lock. Regression replays the controlled delayed-callback cut point into a real newly accepted TLS session; it asserts no audio bytes and no idle extension, not a naturally reproduced race. |
| Parent: Running heartbeat overwrites pending structured-prompt suspension | medium | iOS independently checks `pendingHomePrompt` and last keepalive phase. Android stores a prompt and displays an unavailable surface, with no response/clear operation. Lease now retains a separate pending-prompt flag until terminal/cancel/new acceptance; Running cannot clear it. Regression remains suspended at 300 seconds and expires at the 1800-second cap. |
| Parent: duplicate/rejected native audio start leaves wire PCM eligibility active | medium | Duplicate wire starts now invalidate ingress stream eligibility; playback admission success/rejection and audio end/failure keep it aligned when buffered events are presented. Tests cover duplicate starts both before and after observer attachment, plus native admission rejection; later rejected PCM must not extend the deadline or write bytes. |
| Final coverage: duplicate-start test attaches observer before rejected PCM | medium | Accepted gap: native presentation rejection could hide a missing wire-side guard. Added a variant that keeps observation detached through two starts and PCM at 44 seconds, uses the existing buffer-admission cut point as a nonrenewing receipt barrier, and asserts expiry at 55 seconds and zero sink bytes before attaching. No production test hook was added. |

## Verification history

- Initial implementation: full JVM 756 tests / 54 suites passed, debug and instrumentation APK assembly and lint passed. Original XML/log preserved under `/tmp/hermes-home10-20261015-evidence/initial`.
- First review correction: compilation failed before tests because a nullable observer guard could compare two null bindings. This is compile-only evidence, not a red behavioral test. Full log and explicitly labeled preexisting XML preserved under `targeted-compile-failure`; explicit non-null guard corrected the cause.
- Next targeted run: 142 tests, one new fixture failure waiting for `AudioStarted` before sending any PCM. `RecordingAudioSink` signals audible start on first write, so admission correctly produces `AudioBuffering`. Full failing XML/log preserved under `targeted-pcm-failure`; corrected the causal fixture barrier, not production behavior.
- Corrected first-review targeted run: 142 tests passed; full XML/log preserved under `targeted-first-review-pass`. Subsequent focused-review changes require their own verification.
- Focused ingress run: 145 tests, one terminal-fixture oracle failure expecting one total event. `completeEvents` legitimately emits final response text followed by a terminal. Full failing XML/log preserved under `focused-terminal-failure`; the corrected assertion requires exactly that text-plus-terminal sequence and still excludes duplicate terminal, duplicate text and late audio. No production change was made for this failure.
- Corrected focused ingress run: 145 tests passed; full XML/log preserved under `focused-ingress-pass`. All three formal reviewers closed their prior findings; the final parent audio-admission eligibility correction is separately tested and reviewed.
- Final eligibility targeted run: 147 tests passed; full XML/log in `audio-eligibility-pass`. The additional detached-observer wire-admission case passed independently in `detached-wire-pass`, using a nonrenewing queue-admission barrier.
- Approved mutation proof: temporarily removing only the binary ingress socket/generation guard made the saved-old-socket regression fail on real sink bytes (`expected 0, actual 2`). One test ran and failed as expected; full XML/log in `expected-origin-mutation-red`. The exact guard was restored before final verification. This proves the controlled callback cut point, not a naturally reproduced race.
- First actual Android smoke: runner reported **2 tests, 3 failures**. One fixture incorrectly expected final text on the terminal rather than its preceding text event; two teardown failures came from calling client-side `cancel()` on MockWebServer's server-side WebSocket. Raw runner output and APK identities are preserved in `native-first-failure`. Corrected only the text oracle and invalid teardown call; production client/server cleanup remains. Rebuilt instrumentation APK and reran lint, then actual Android smoke passed **2 tests**.

## Final local verification — 2026-10-15

Baseline: `526ecbaeaf3a3f494aeeaa7afa5ed38ea7827c0a` (fresh `origin/main`, PR167 merged). Worktree: `/Users/amanda/Development/hermes/hermes-relay-android/.worktrees/home-next-emulator-slice`, branch `home-next-emulator-slice`.

Evidence root: `/tmp/hermes-home10-20261015-evidence`.

| Gate | Observed result | Evidence |
|---|---|---|
| Full JVM suite | 767 tests, 54 suites, zero failures/errors/skips | `final-full/jvm-xml/`, `final-full/gradle.log` |
| Debug build, instrumentation build, lint | Passed; instrumentation fixture correction separately rebuilt and linted | `final-full/gradle.log`, `final-full/native-fixture-build-lint.log` |
| APK metadata | min SDK26, version0.3.1/code301, backup disabled/extraction rules, 16KB alignment passed; signer comparison skipped because expected signer unset | `final-full/apk-metadata.log` |
| Actual Android runtime | Production `OkHttpRelaySessionClient` over loopback TLS: exact feature upgrade, invisible scoped/malformed/stale keepalives, real response/terminal once, idle expiry and one submission. 2 tests passed | `final-full/android-production-tls-smoke-corrected.log` |
| Repetition | 30 separate fail-fast `N=1` invocations; 174 tests each, 5220 executions, zero failures | `repetition/01` through `repetition/30`, each with full XML and both logs; `repetition/summary.json` |
| Formal review | Blind, edge-case and verification-gap reviews closed, including focused follow-ups and parent findings | Per-finding triage above |

Repetition classes: `HomeControlLeaseTest`, `OkHttpRelaySessionClientTest`, `HermesEventNormalizerTest`, `AndroidTurnStateTest`, `AndroidRecoveryControllerTest`, `HomeRuntimeBackgroundTransportTest`. Full-suite XML was archived before repetition; every repetition's XML was archived before the next invocation. The historical Standard iteration23 failure remains unattributed and is not rewritten by these results.

Native device was a newly created **empty** API36 Google APIs arm64 AVD `hermes_home10_20261015`, private home `/tmp/hermes-home10-20261015-avds`, exclusively claimed free port5562 / explicit serial `emulator-5562`. Owner emulator5554, its userdata and protected HOME08B recovery state were not touched or copied. After proof, only this owned emulator was stopped and its private AVD directory/port claim removed. No `Gradle connected*` task ran.

Final installed APK SHA256:
- App: `6ef809126db4517a7e54a2c762a96f370afcd266ac9b391d01731b3885b582b8`.
- Corrected instrumentation: `b92612233f555293e35e4a8672e44a8619fe5b3c2ea10aa1ab2b9db5f12df80a`.
- Exact paths, lengths and hashes: `final-full/final-apk-identities.json`; earlier pre-correction identities remain preserved separately.

### Acceptance limits

The live Home gate was **not run**. Its wrapper requires a physical Pixel6a (rejects emulators), signed deployment/trace provenance and an authorized disposable Home binding, and uses `connectedDebugAndroidTest`, prohibited for this assignment. Pixel is unavailable; no owner credentials or AVD state were inspected/copied to bypass that limitation. The TLS emulator fixture is not live Home evidence. Real >60-second Home turn, live silent-tool cadence, airplane-mode recovery and physical audio remain unverified. This is complete local software delivery, not full live/hardware story acceptance. Local commit only; no push, PR, issue mutation or deployment was authorized.
