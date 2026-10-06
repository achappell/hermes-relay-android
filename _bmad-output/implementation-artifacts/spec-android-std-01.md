---
id: ANDROID-STD-01
status: ready-for-dev
product_epic: 2
created: 2026-09-23
---

# ANDROID-STD-01 — Offer explicit Standard-only Android setup

## Approved scope

Offer HomeBridge/Standard setup and supported direct Standard authentication without Home. Require typed streaming, private local history, deliberate sessions and honest no-replay recovery. Voice is optional until verified. Keep modes and credentials separate; never auto-fallback.

## Acceptance

- Deliver the owner-specific behavior above using unmodified Standard Hermes and the approved [delivery contract](course-correction-2026-09-23.md).
- Preserve existing story evidence and supported adapters; no automatic mode switch or replay of an uncertain turn.
- Record applicable setup, capability limits, privacy, failure and recovery behavior against the actual supported baseline.
- Record implementation, merge and physical/live acceptance separately; do not declare an unexercised gate complete.

## Dependencies

- android:ANDROID-HOME-02

## Readiness

Approved backlog scope. Owning BMAD specification/readiness review must settle API details and a bounded execution plan before implementation. No implementation or runtime acceptance is claimed.

## Readiness review (approved by Amanda 2026-10-03)

Modeled on the settled TUI-STD-01 spec (`hermes-relay-tui` `spec-tui-std-01.md`, merged in #219). Amanda approved this readiness review on 2026-10-03; it settles the API details and bounded execution plan required by the Readiness section above. No implementation or runtime acceptance is claimed.

### Key difference from TUI

The TUI reused an already-verified direct Standard adapter. Android has none: `OkHttpRelaySessionClient` is the Home bridge transport only ("Home owns the Standard Hermes bearer and the Standard-side sockets"). `HermesEventNormalizer` already maps Standard event names, which is reusable. This story therefore includes a **new direct `/api/ws` transport**, which makes it larger than TUI-STD-01.

### Boundaries carried over from TUI-STD-01

- Standard means unmodified Hermes at `/api/ws`, with response audio at `/api/audio/speak-stream` (the two boundaries Home's Standard bridge pins). Target baseline **0.21.5** (the household deployment as of 2026-10-03): release tag `v2026.9.24`, commit `f97608f178d1ffeca59860195ab7da295f7c8e5f`. TUI-STD-01 and Home's bridge were verified against 0.21.1 (`2237be355906fbe6065ce1815711eee52b2d646e`); Android must verify against 0.21.5 itself rather than inherit that result.
- Typed streaming is required. Full voice is in scope (decision Q1). Structured prompts are unsupported and shown as such, never treated as text.
- Never auto-switch modes, fall back, suggest the other mode as outage recovery, replay an uncertain turn, or migrate history, session IDs or transcripts across modes or Standard identities.
- Reconnect restores transport but does not clear uncertainty; only a successful deliberate new session does.
- Do not change the wire protocol, retire legacy/fork paths (ANDROID-RETIRE-01), or add Home grants/rooms to Standard.

### Household endpoint

- The household Standard endpoint is `wss://media-server.taila59979.ts.net:8443/api/ws` (tailnet only). Per Home's records the chain is Tailscale Serve `:8443` → pilot proxy `127.0.0.1:9121` → `hermes serve` `127.0.0.1:9120`. The app's no-cleartext manifest policy is kept; Standard endpoints must be `https`/`wss`.
- Android connects through the same pilot proxy Home uses. Home has a known idle-upstream disconnect issue in that chain (`spec-pilot-dead-upstream-reconnect-loop.md`); Android Standard recovery must treat such drops as ordinary transport loss (reconnect, uncertainty preserved), and any proxy fix belongs to Home, not this story.
- **Confirmed 2026-10-03** (`tailscale serve status` on media-server): `https://media-server.taila59979.ts.net:8443` is tailnet only; `/` → `127.0.0.1:5001`, `/api/ws` → `127.0.0.1:9121/api/ws`, `/api/audio/speak-stream` → `127.0.0.1:9121/api/audio/speak-stream`. Both Standard boundaries are reachable through the pilot proxy.

### Credentials

- Add a **distinct Standard credential slot** to `RelayCredentialStore` (`putStandardCredential`, `readStandardCredential`, `deleteStandardCredential`), keyed per Profile. Do not use the legacy `put`/`read`/`hasToken` calls: those are the rollback slot (`RelayCredentialStore.kt:22-29`), which Standard must never read or write (Q4).
- `delete(profileId)` removes the Standard slot too; update its "both slots" doc.
- The token stays out of Profile JSON, history, logs and diagnostics, and is never shared with or reused from Home pairing or admin credentials.
- Changing a Standard Profile's endpoint clears its token and requires re-entry (TUI BH-02).
- Tests prove the Standard, Home, admin and rollback slots never read each other's values.

### Code map

- `RelayProfile.kt`, `RelayProfileStore.kt` — add an explicit mode (HomeBridge, Standard, Legacy) to Profiles. Standard Profiles carry endpoint and Hermes Profile name only; a blank Hermes Profile normalizes to `default` (TUI EC-02). Old JSON keeps loading; Profiles with no Home link load as Legacy (Q4).
- `RelayConfigurationScreen.kt` plus a **new `StandardSetupSection.kt`** — first setup asks HomeBridge or Standard. The Standard path takes endpoint and token, validates them (`https`/`wss`, host required, no credentials or token-like query keys, as with TUI EC-01) and runs a connection check (`session.create`) before saving. Setup never alters other Profiles or credentials.
- **New `OkHttpStandardSessionClient.kt`** — implements the base `AndroidClientPort` only: `/api/ws` with bearer auth, `session.create`, typed streaming via `HermesEventNormalizer` standard events, the existing uncertain-delivery guard, and `supportsInterrupt()` set from the slice-1 check. Home-only surfaces stay unavailable and hidden in Standard mode: `AndroidHomeConversations`, `AndroidHomeApprovals`, Device administration, and the Home readiness assertions (`assertLiveHomeCapabilities`, `assertNewTurnReadiness`). Where shared code calls those assertions, generalize or branch on mode rather than faking Home results. Budget this port refactor inside slice 1.
- `AndroidLocalHistory.kt`, `AndroidPromptHistory.kt` — **from slice 1**, Standard history is keyed by mode + endpoint + Hermes Profile, not `profileId` alone, so no later re-keying strands history (TUI BH-06). Nothing is imported from other modes.
- `AndroidRecovery.kt`, `AndroidInitiationController.kt`, `MainActivity.kt` — show the mode in status; add a Standard **New conversation** action; enforce switch guards (see slices).
- Tests alongside each, plus `validation-android-std-01.md`, `story-index.yaml`, `sprint-status.yaml`.

### Sessions and busy state

- **New conversation (Standard).** A visible action calls `session.create`. Success starts a fresh Hermes session, clears the uncertain state, and inserts a local "New conversation" divider; earlier local history stays visible but is never sent. Failure keeps the turn uncertain and sending/switching blocked (TUI BH-08).
- **Hermes still generating.** After a local-only stop (Q2 fallback), the app tracks that Hermes may still be producing the previous response. Until Hermes signals that turn ended, or the user starts a New conversation, the app shows "Hermes is finishing the previous response" and **does not send** a new turn, typed or spoken. It never queues and silently sends later. Hands-free capture does not re-arm while this state holds. If remote interrupt is verified, a confirmed interrupt ends this state.

### Proactively carrying TUI review findings

- Endpoint change never reuses the old token (BH-02) — see Credentials.
- Blank Hermes Profile → `default` (EC-02) — see Code map.
- Wake / hands-free capture blocked during Standard uncertainty (VG-04) — slice 2.
- Failed New conversation leaves the turn uncertain and switching blocked (BH-08) — slice 1.

### Decisions

- **Q1 Voice in Standard mode — decided 2026-10-03: full voice (option C).** Standard mode targets tap-to-speak via on-device transcription (A-9), response audio, and hands-free with echo-safe barge-in (A-6), matching Home mode. Response audio comes from `/api/audio/speak-stream`, which Home's bridge uses on 0.21.1 but is unverified on 0.21.5, so slice 1 starts with the baseline check below. If 0.21.5 does not provide usable response audio, stop and bring the choice back to Amanda (on-device Android TTS vs text-only responses) rather than picking one silently. The approved scope line "Voice is optional until verified" still holds for the gate: voice is not claimed done until live-verified.
- **Q2 Remote interrupt — decided 2026-10-03: remote if verified (option B).** Local stop (playback, rendering, capture) always applies. The baseline check also verifies whether 0.21.5 accepts an interrupt over `/api/ws`. If it does, stop and barge-in send it. If it does not, stop and barge-in stay local and the busy state above applies; never claim the turn was cancelled. Either outcome is recorded as its own live gate.
- **Q3 Slicing — decided 2026-10-03: three slices** (see below). Each slice records its own implementation, merge and live gates.
- **Q4 Existing pre-Home Profiles — decided 2026-10-03: legacy, needs setup (option A).** A Profile with no `homeBinding` or `homeClientGrant` loads as Legacy and is shown as needing setup; the user chooses HomeBridge or Standard and enters fresh details. It is never classified as Standard automatically. Standard never reads the rollback slot. Legacy Profiles and rollback credentials are left untouched until ANDROID-RETIRE-01.

### Slices

**Slice 1 — connection and typed chat**
1. Baseline check (first; see procedure below).
2. Profile mode, Standard credential slot, setup choice and `StandardSetupSection`.
3. `OkHttpStandardSessionClient` and the port refactor; typed streaming; no-replay recovery.
4. History keyed by mode + endpoint + Hermes Profile.
5. New conversation action and the busy state.
6. **Minimum switch guard:** block Profile or mode switches while a Standard turn is active, uncertain or still finishing. (Moved forward from slice 3 so slice 1 is safe to use on its own.)

**Slice 2 — voice**
Tap-to-speak, response audio via `/api/audio/speak-stream`, hands-free barge-in, remote or local-only interrupt, and wake/hands-free blocked during uncertainty (VG-04).

**Slice 3 — remaining guards and isolation**
Full switch-guard coverage (queued input, staged attachments, hands-free armed; cf. TUI VG-03), cross-mode and cross-identity isolation tests, and any remaining TUI findings.

### Baseline check procedure (slice 1, step 1)

A small scripted probe (kept under the repo's test tooling, not shipped in the app) run against the household 0.21.5 endpoint with Amanda's Standard token. It records to `validation-android-std-01.md`, with no token, transcript or audio content:
- `session.create` succeeds and returns a session reference.
- A typed turn streams events in the shapes `HermesEventNormalizer` maps; note any new or changed event names against 0.21.1.
- A structured prompt arrives in a recognizable form, or is noted as not reproducible.
- `/api/audio/speak-stream` returns streamable audio; record format, sample rate and whether it is chunked. **Usable** means the existing A-8 player can play it with at most a decoder or format change.
- An interrupt sent mid-response is acknowledged and stops generation (evidence: the turn ends early). Otherwise record it as unsupported.
- A dropped socket followed by reconnect preserves the session reference and sends no prompt.

If audio is unusable, stop at this step per Q1.

### Verification

- Unit tests for setup, Profile store and mode, credential-slot isolation, transport, history keying, New conversation success and failure, busy state, and switch guards; full `./gradlew test`.
- Live gates recorded separately: baseline check; Standard typed streaming; unsupported prompt; connection failure; uncertain-turn recovery; New conversation; other Profiles intact after Standard setup; endpoint change forces token re-entry; tap-to-speak; response audio; hands-free barge-in; and remote interrupt (or, if unsupported, the busy-state message).
