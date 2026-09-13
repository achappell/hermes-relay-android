---
title: 'Android visual design pass'
type: 'design'
created: '2026-09-12'
status: 'approved'
story: '5-A-3'
direction: 'Night Console Android adaptation'
approved: '2026-09-12'
upstream_design: '../hermes-relay-ios/_bmad-output/implementation-artifacts/ios-visual-design-pass.md'
baseline_commit: 'afe99aa'
reviewed_surfaces:
  - conversation idle and no-profile state
  - unavailable and cached-draft state
  - relay configuration and saved profiles
  - household Device discovery
  - voice and hands-free controls
  - Local History
context:
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
  - '_bmad-output/implementation-artifacts/android-local-tickets.md'
  - '_bmad-output/implementation-artifacts/validation-5-a-2-accessibility.md'
---

# Android visual design pass

**Direction approved 2026-09-12.** Implementation follows the sequence at the
end of this document. Step 1, the decomposition, was completed before approval
because it commits to nothing visual.

## Design question

How should the native Android doorway express Hermes' shared Night Console
identity while remaining recognisably Android, calm during idle, and explicit
about every connection, capture, response, and recovery state?

## Constraints and settled principles

Inherited from the approved iOS pass, because they are properties of Hermes
rather than of a platform:

- Hermes remains the authority for sessions, Profiles, answer content, and
  protocol phases.
- The selected Profile is visible before capture and remains identifiable
  through completion.
- Every meaningful state has readable text. Colour, motion, and elevation are
  supporting signals, never the only meaning.
- Retry reconnects only. It never silently reopens capture or replays an
  uncertain turn.
- Local History is deliberate and Profile-scoped; no audio, prompts, tokens, or
  raw frames enter the visual design artifacts.

Added for this surface:

- Android keeps native navigation, Material 3 components, `sp` type scaling,
  TalkBack order, and Android touch-target expectations. It copies neither the
  TUI's geometry nor iOS's.
- The accessibility order delivered under `5-A-2` is a settled requirement, not
  a thing this pass may renegotiate. Any recomposition preserves it.

## Current-state diagnosis

Measured against `MainActivity.kt` at `afe99aa`.

**1. The app has a palette but not a state language.** `ANDROID-DESIGN-F1`
delivered a contrast-tested light-first Material scheme and closed. It never
adopted Night Console. iOS carries four semantic roles that mean something
across every Hermes surface; Android has none of them:

| Role | iOS | Android today |
|---|---|---|
| Base | `#0B101B` midnight canvas | `#FCF8FF` near-white |
| Live — active/healthy | `#62E6C7` | *absent* |
| Attention — current phase, pending work | `#FFCF5C` | *absent* |
| Identity — Profile, interactive focus | `#7C8CFF` | `#54468B` generic primary |
| Unavailable — transport/permission/identity failure | `#FF7D9C` | generic Material `error` |

Because the roles are missing, a healthy live signal, pending work, and a failed
identity are all rendered through the same generic Material slots. The surface
cannot say what it means.

**2. The entire UI is one function.** `AndroidClientScreen` is the only
`@Composable` in a 893-line file. There are no components, so there is no
spacing scale, no shared state surface, and nowhere for good colour tokens to
land.

**3. Nothing establishes hierarchy.** Zero `Scaffold`, zero `TopAppBar`, zero
`LazyColumn`. The screen is one flat `Column` inside a scroll. Eleven default
filled `Button` calls and no tonal or outlined variants anywhere, so `Send`,
`Interrupt`, `Recover`, `Discard unconfirmed turn`, and `Clear history` all
present as equally urgent. Three `Card`s carry the only grouping in the app.

**4. The foundations under the mess are sound.** All user-visible text is in
`strings.xml`. `MaterialTheme.typography` is used throughout rather than
hardcoded sizes. `traversalIndex`, `isTraversalGroup`, `heading`, and
`liveRegion` semantics are present from `5-A-2`. This pass is a recomposition,
not a rewrite, and it must not regress any of that.

The design problem is hierarchy and state language, not a shortage of colour.

## Recommended direction: Night Console Android adaptation

Dark-first, system-adaptive Night Console for the live conversation surface,
with native Material 3 structure for configuration, Device setup, and history.
One visual vocabulary; platform-owned navigation and controls stay familiar.

This is the third adaptation of one shared identity, not a port of the second.
The TUI has its own; iOS explicitly declined to copy the TUI's geometry; Android
declines to copy either.

### What this protects

- Cross-surface Hermes identity and state semantics.
- Quiet idle presentation, with activity emerging only when a turn is real.
- Clear separation between identity, live phase, response content, and recovery
  action.
- Native Android accessibility and interaction conventions.
- The measured contrast guarantee `ANDROID-DESIGN-F1` established.

### What this gives up

- The current light-first scheme cannot remain the default live surface.
- Every value in `Palette.kt` is replaced, so `PaletteContrastTest` must be
  extended to the new pairs before the new palette lands — the guarantee must
  never lapse mid-change.
- Existing Compose UI tests that assert on layout structure will need updated
  fixtures.

## Visual language

### Colour roles

Adopt the Night Console semantic roles, keeping ARGB-integer definitions in
`Palette.kt` so contrast stays measurable:

| Role | Value | Meaning |
|---|---|---|
| Base | `#0B101B` | midnight canvas |
| Console surface | `#101725` | header and bottom control grouping |
| Panel | `#0D1320` | transcript, recovery, and setup cards |
| Raised panel | `#182338` | selected Profile or important context |
| Primary ink | `#EAF7FF` | body and state text |
| Secondary ink | `#B3C0D2` | metadata and supporting text |
| Live | `#62E6C7` | active/healthy signals |
| Attention | `#FFCF5C` | current phase and pending work |
| Identity | `#7C8CFF` | Profile and interactive focus |
| Unavailable | `#FF7D9C` | transport, permission, and identity failure |

Map these onto the Material 3 `ColorScheme` slots rather than bypassing the
scheme, so stock components inherit them. The four state roles additionally need
a home outside `ColorScheme`, which has no slot meaning "live" or "attention" —
expose them through a `HermesStateColors` holder published via
`CompositionLocal`, the conventional Material 3 way to extend the system without
fighting it.

A light adaptation maps the same roles to readable light surfaces. It does not
substitute arbitrary accents, and status text stays explicit in both appearances.

Dynamic colour remains deliberately unused, for the reason
`ANDROID-DESIGN-F1` already recorded: a wallpaper-derived scheme replaces
verified values with unverified ones at runtime.

### Typography

- Material 3 type scale via `MaterialTheme.typography`; no hardcoded `sp`.
- The current state is the strongest text on the live surface: `Ready`,
  `Listening`, `Thinking`, `Speaking`, `Complete`, `Unavailable`.
- A monospace family, sparingly, for endpoint metadata, session duration, and
  compact diagnostic labels.
- Keep technical protocol words secondary to child-readable action language.
- Preserve font scaling; never make the state legible only through the
  visualizer.

### Shape, spacing, and elevation

- 4/8/12/16/24/32dp spacing rhythm.
- 12–16dp corners for cards; the largest radius reserved for the outer
  conversation container or a platform-owned sheet.
- Group with tonal layering and hairline outlines. Do not elevate every card as
  if equally urgent.
- Touch targets at least 48dp, with visible focus preserved.
- Where iOS reaches for Liquid Glass, Android uses tonal surface elevation.
  Translucency is not the Android idiom and is not substituted for it.

## Surface decisions

### 1. Conversation shell

Four readable zones, in the order `5-A-2` already establishes for TalkBack:

1. Profile and connection header.
2. Current state and visualizer.
3. Response/transcription rail.
4. One action surface: voice controls plus typed composer.

The header owns connection recovery. The centre owns phase truth. The bottom
owns capture and submission. No zone repeats another's message in different
words.

Structurally: a `Scaffold` with the header as a top app bar, the action surface
as a bottom bar, and the rail as the scrolling body — replacing the single flat
`Column`. The rail becomes a `LazyColumn` so content and controls stop competing
for the same scroll.

### 2. Idle and no-profile state

- No Profile: show `No Profile selected`; `Configure relay` is the primary
  action; replace the microphone instruction with `Configure a relay to begin`.
- Profile selected but disconnected: show the Profile, `Unavailable`, and a
  recovery card offering `Retry` and `Edit relay`.
- Connected idle: show `Ready`, with tap-to-speak dominant.
- Keep the visualizer quiet and static when idle. Active glow belongs to real
  capture or playback.

### 3. Active voice turn

- Profile name stays visible above the current phase.
- One large state label plus restrained synchronized motion.
- Live transcription or response text in one rail; the visualizer never competes
  with readable content.
- `Cancel` only while local capture is active; `Interrupt` only while a
  supported response is active. Never both as generic stop controls.
- Hands-free is a mode switch with an explicit state, not a second microphone
  action.

### 4. Unavailable and recovery

The unavailable surface must visibly stop pretending to be ready: state label
`Unavailable`, a plain-language explanation naming the affected path, `Retry` as
primary, `Edit relay` or `Open Settings` as secondary. An unresolved turn is
preserved, labelled, and never silently replayed.

The recovery card carries the explanation. Do not bury the only useful action
while the centre still advertises capture.

Two open defects touch this surface directly and should be read alongside it:
`ANDROID-BUG-F1` (off-tailnet state unreachable) and `ANDROID-BUG-F2` (the
reconnect ladder discards its reason). Design cannot make a state honest that
the transport never reports.

### 5. Typed composer and cached drafts

- Composer stays available when disconnected so local drafts remain useful.
- Show `Saved locally — connect before sending` as a compact status row.
- Disable send with an explanation, not merely reduced opacity.
- Hide prompt-history controls when there is no history; when present, group
  them under a labelled `Recent prompts` affordance rather than bare arrows.
- A focused composer owns the emphasis; voice controls recede to avoid
  competing input modes.

### 6. Configuration and profiles

Keep native structure, strengthen the information architecture:

- `Profiles`: active Profile, connection status, add, delete.
- `Household Devices`: approved and discovered Devices, with explicit pending
  and inert states.
- `Relay`: Endpoint, then a labelled `Device identity` group for Client ID,
  Device ID, Display name.
- `Credentials`: token state, replacement, removal.

Required fields show their requirement before Save. Errors attach to the field,
move focus to the first invalid field, and explain the fix. The selected marker
is a checkmark or equivalent; a connection-status dot must not be asked to mean
selection.

### 7. Device discovery and setup

This surface does not exist yet — it is Epic 3, `3-A-1`–`3-A-6`, all `backlog`.
It is specified here so those six stories build onto a settled language instead
of inventing one each.

Canonical sequence `Discover → Connect → Room → Wake Mappings → Ready`. The
current step is attention-coloured, completed steps live-coloured, locked steps
visibly pending. Discovery is not approval; approval is not Ready. Empty and
failure states each get one explanation and one labelled next action, with
`Retry discovery` available as text and not only as an icon.

### 8. Local History

- A native sheet on a calm panel surface.
- Newest content at the bottom; local scope visible in the empty state.
- Role and time metadata as secondary structure; response text is the visual
  centre.
- Keep Export in an overflow menu. It should not compete with the conversation.

## Accessibility and motion

- TalkBack order: Profile → current state → response/transcription → primary
  action → secondary actions. This is `UX-DR21` and `5-A-2`; the existing
  `traversalIndex` and `isTraversalGroup` semantics move with the components
  they annotate and are re-verified after recomposition.
- Pair every colour and icon with readable text.
- Honour the system reduced-motion setting: freeze the visualizer and drop
  looping transitions while retaining the state label.
- Announce state transitions once, via `liveRegion`, not per audio or
  transcription frame.
- Keep unavailable and pending states distinguishable without colour.
- Verify font scaling at large accessibility sizes in configuration, recovery
  cards, and the composer.

## Alternatives considered

### Keep the current light Material scheme, fix only layout

Cheapest, and it would genuinely improve the app. Rejected as the primary
direction because it leaves the parity gap exactly where it is: the surface
still cannot distinguish live from pending from failed, and the next surface
(Epic 3) inherits the same deficit.

### Port the iOS views directly

Strong brand consistency and the least design thinking required. Rejected: it
would overrule Material navigation, component, and type conventions, and
Liquid Glass has no honest Android equivalent. iOS itself declined this move
against the TUI.

### Night Console Android adaptation — recommended

Dark-first semantic identity on the conversation surface, native Material 3
structure for setup and history, tonal elevation in place of glass, and a real
light adaptation. Keeps the relationship coherent without making the phone
pretend to be a different platform.

## Implementation sequence after direction approval

1. **Decompose `AndroidClientScreen`** into the four zones, behaviour
   unchanged. Verify against the existing `A-2`, `2-A-1`, and `5-A-2`
   validation records before anything visual changes.
2. **Extend `PaletteContrastTest`** to the Night Console pairs, then replace
   `Palette.kt`, so the contrast guarantee never lapses.
3. **Add `HermesStateColors`** and route every state render through the four
   semantic roles.
4. **Introduce the `Scaffold` structure** — header, rail, action surface — and
   fix action hierarchy with tonal and outlined button variants.
5. **Fix the state hierarchy**: no-Profile, disconnected, unavailable, ready.
6. **Recompose the composer, history, and voice controls**, including
   reduced-motion behaviour.
7. **Re-verify TalkBack order and focus restoration** on hardware, closing the
   `5-A-2` environment limitation in the same pass.

Step 1 is safe to begin before approval of the visual direction; it makes every
later step tractable and commits to nothing.

## Verification obligations

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` stays green
  throughout.
- The contrast test covers every text pair in both appearances, and its
  known-bad guard is retained so the check cannot pass vacuously.
- Material 3 component and API choices are verified against the resolved
  `material3` artifact from Compose BOM `2026.08.00` at implementation time.
  No API in this document should be taken as confirmed; it is a design
  intention, not a verified call signature.
- A real-device pass is required for TalkBack, font scaling, and the rendered
  appearance of the palette. The emulator cannot close those.
