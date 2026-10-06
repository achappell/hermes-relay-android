---
id: ANDROID-HOME-10
title: Idle-based Home control deadline driven by turn.alive keep-alives
status: backlog
product_epic: 1
created: 2026-10-06
depends_on:
  - android:ANDROID-HOME-05
  - android:ANDROID-HOME-06
  - home:HOME-NW-18
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

Part 2 — deadline (new Android capability; confirm with the owner before implementing because Android currently waits unboundedly):

- Without the `turn_keepalive` capability: a fixed `controlTerminal` of 120 s from acceptance (iOS value; matches Home's 120 s reconnect grace).
- With the capability: `controlIdle` 45 s (three missed 15 s beats) with no current-turn activity; the idle clock restarts on any current-turn Standard event, `turn.alive`, scoped activity, or Home audio start, PCM or terminal; it is **suspended** while a structured prompt is pending or the last keep-alive phase is `awaiting_input`; plus an overall `controlBackstop` of 1800 s from acceptance that is never extended (matches Standard's `agent.gateway_timeout`).
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

iOS: `spec-ios-home-07-slow-turn-control-deadline.md`, `validation-ios-home-07.md` ("Follow-up: activity-based control deadline…"); `HomeBridgeClientOptIn` in `HomeBridgeSessionClient.swift`; Home: `_bmad-output/specs/spec-home-bridge-route-roaming/bridge-contract.md`.
