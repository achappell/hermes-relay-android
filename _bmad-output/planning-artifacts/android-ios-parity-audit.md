---
title: 'Android/iOS capability parity audit'
type: 'planning'
created: '2026-09-12'
status: 'proposed'
scope: 'hermes-relay-android'
context:
  - '../hermes-relay-tui/_bmad-output/implementation-artifacts/surface-coverage-matrix.md'
  - '../hermes-relay-tui/_bmad-output/planning-artifacts/epics.md'
  - '../hermes-relay-ios/_bmad-output/implementation-artifacts/deferred-work.md'
---

## Why this audit exists

The upstream story map assigns Android a 1:1 mirror of the iOS story
identities: twelve on each surface. At that level the surfaces are already at
parity, and Epic 4 correctly assigns no mobile row to either.

The audit was run because story-ID parity is not capability parity. iOS shipped
a material body of behavior that never received a story identity on either
surface. It lives in Swift source, in `docs/plans/IOS-nn` engineering plans,
and in local `deferred-work.md` tickets. An Android surface built by faithfully
mirroring the story map alone would land measurably thinner than the iOS app it
is supposed to match.

This artifact records the delta and proposes story identities to close it. It
does not change any existing story, and it is not authoritative over the
sibling `surface-coverage-matrix.md`.

> **Authority note:** This is a proposal-time audit and its status cells are
> historical context. Current Android story identity, status, and closure live
> in this repository's local story index, specifications, validation records,
> and tracker.

## Method

- Enumerated the iOS implementation surface: 34 Swift files, roughly 11,700
  lines across Models, Services, ViewModels, and Views.
- Enumerated every iOS BMad spec, the local `deferred-work.md` ticket set, and
  the seven `docs/plans/` engineering plans.
- Compared both against the Android story rows in `epics.md` and the Android
  rows of `surface-coverage-matrix.md`.
- Verified selected claims directly against source rather than documentation.

## Story-identity parity — no action required

| Epic | iOS | Android | Status |
|---|---|---|---|
| 1 — Have a reliable Hermes conversation | `I-1`–`I-3` | `A-1`–`A-3` | Android delivered |
| 2 — See and trust what the room is doing | `2-I-1`, `2-I-2` | `2-A-1`, `2-A-2` | Android pending |
| 3 — Control household doorway identity and access | `3-I-1`–`3-I-6` | `3-A-1`–`3-A-6` | Android pending |
| 4 — Know when the household needs to leave | none | none | Display-only by design |
| 5 — Carry the Hermes relationship with you | `5-I-1` | `5-A-1` | Android pending |

`3-I-4` (wake arbitration) has no iOS spec artifact, so Android's `3-A-4` is
not behind iOS on that story. Both are open.

## Capability gaps — iOS behavior with no Android story

Ordered by severity.

### 1. Accessibility has a named requirement and no owner

`UX-DR21` states the Android obligation explicitly: accessibility order
`Profile → state → response/Transcription → action` with focus restoration.
No Android story implements it.

- iOS: accessibility modifiers across all seven view files.
- Android: no `contentDescription` or `semantics` usage in `app/src/main`.

This is the clearest defect in the current map — a written requirement that
names the surface but assigns no story.

### 2. Relay configuration and secure credential storage

iOS owns an endpoint/token configuration surface, a Keychain-backed store, a
multi-profile collection with add/delete/switch, and three shipped UX tickets
(`IOS-UX-F1`–`F3`) covering unconfigured-state recovery, live reconciliation
after deleting the selected profile, and field-level validation.

- iOS: `RelayConfigurationStore`, `RelayConfigurationView`, `RelayProfileCollection`, `SecureValueStore`.
- Android: covered only by the phrase "secure profile storage" inside `5-A-1`.

`5-A-1` currently absorbs configuration, credentials, secure storage, transport,
and full-doorway parity in one story. That is more than one slice can carry, and
it is the reason the Android adapter has no delivery identity of its own.

### 3. Hands-free capture and barge-in

Roughly 400 lines of `VoiceSessionCoordinator` — arming, silence scheduling,
stream restart, echo-safe barge-in — delivered under the `IOS-16` plan document
with no story identity on either surface.

`FR5` assigns a bounded follow-up window to the Puck and the W/K browser
surface; it does not assign one to a mobile Client. Every Android story says
"typed and tap-to-speak". As written, Android is specified to remain less
capable than the shipped iOS app.

**This gap needs a product decision before it needs a story** — see Open
questions.

### 4. Interrupting an active turn

`interruptActiveTurn`, `stopPlayback`, and server-interrupt semantics,
delivered under the `IOS-26` plan. Real capability, no story on either surface.

### 5. Transcript export

`TranscriptExportFormatter` produces plain text and Markdown with a share
sheet. No story, no spec, and no ticket on either surface.

### 6. Prompt history

`PromptHistory` — 50-entry draft recall with cursor navigation and
draft-before-navigation restore. Code only, undocumented on both surfaces.

### 7. Visual design and brand

`IOS-DESIGN-F1` (a deliberate design pass, with its own
`ios-visual-design-pass.md`) and `IOS-BRAND-F1` (app icon set). Android has
neither and currently ships the default Compose theme.

### 8. Audio internals

WAV fallback writing, amplitude/activity diagnostics, and playback-position
observation, plus an open iOS `deferred-work` item to bound buffered audio
before accepting arbitrarily large fallback payloads. Android inherits that
unbounded-buffer question fresh when it grows real audio, and should not
re-derive the answer independently.

## Proposed story identities

New Android surface stories, following the existing surface-keyed convention.
None of these modify an existing story.

| Proposed | Epic | Scope | Blocking for |
|---|---|---|---|
| `A-4` | 1 | Configure a Hermes relay endpoint and store its credential in the Android Keystore, with multi-profile add/delete/switch and honest unconfigured-state recovery. | `2-A-1` onward |
| `A-5` | 1 | Interrupt an active Android turn and stop response playback without a false phase claim. | `5-A-1` |
| `A-6` | 1 | Bounded hands-free follow-up capture and echo-safe barge-in on Android. | — (needs decision) |
| `5-A-2` | 5 | Android accessibility order `Profile → state → response/Transcription → action` with focus restoration, satisfying `UX-DR21`. | — |

Retroactive iOS identities, so the pair is symmetric and the matrix has
something to point at. iOS already set this precedent with
`spec-1-4-recover-without-replaying-an-uncertain-ios-turn.md`, which documented
existing `IOS-25` behavior rather than rewriting it.

| Proposed | Epic | Scope |
|---|---|---|
| `I-4` | 1 | Retroactive: interrupt an active iOS turn (`IOS-26`). |
| `I-5` | 1 | Retroactive: iOS hands-free capture and barge-in (`IOS-16`). |
| `5-I-2` | 5 | Retroactive: iOS accessibility order and focus restoration. |

Local tickets rather than upstream stories, matching how iOS already tracks
design and brand work:

- `ANDROID-DESIGN-F1` — deliberate visual design pass across the Android doorway.
- `ANDROID-BRAND-F1` — Hermes Relay Android app icon and adaptive icon set.
- `ANDROID-UX-F1` — transcript export (plain text and Markdown) via the Android share sheet.
- `ANDROID-UX-F2` — prompt history with draft recall.

Transcript export and prompt history are undocumented on iOS too. Whichever
surface records them first should record them for both.

## Recommended sequence

1. `A-4` — before `2-A-1`. Every Android story to date is verified against
   fakes. `2-A-1` is the first story whose correctness depends on real event
   timing, and `3-A-1`–`3-A-6` cannot validate a fail-closed revocation path
   against a port that was never open.
2. `2-A-1`, `2-A-2` — participant capture and disconnected state, now against
   a live relay.
3. `5-A-2` — accessibility, once the doorway's states are stable enough to
   order and announce.
4. `A-5`, then `3-A-1`–`3-A-6`.
5. `A-6` — only after the `FR5` decision below.
6. Local tickets, opportunistically.

## Open questions

1. **Does a mobile Client get a bounded follow-up window?** `FR5` currently
   grants one to the Puck and the browser surface only, while iOS shipped
   hands-free anyway. Either `FR5` is amended to cover mobile Clients, or the
   iOS hands-free capability is recorded as out-of-requirement behavior. `A-6`
   and `I-5` both depend on that answer.
2. **Which endpoint does `A-4` target?** Whether the Android device reaches
   Hermes directly or through the media-server host determines the transport
   and the trust boundary `A-4` has to describe.
3. **Should `surface-coverage-matrix.md` be amended?** The matrix is a
   read-only cross-surface context index in the TUI repository. Amend it only
   for applicability, ownership, evidence, or shared dependencies; this audit
   does not make it an Android status authority.

## Boundary

This artifact is a proposal. An Android story identity becomes actionable when
it is recorded in the local story index and tracker; shared applicability may
also be reflected in the coverage index. Nothing in this audit closes, reopens,
or reinterprets an existing story on any surface.
