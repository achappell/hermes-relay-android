---
story: ANDROID-UX-01
spec: spec-android-ux-01-design-tokens.md
status: in-progress
story_status: in-progress
updated: 2026-10-07
---

# ANDROID-UX-01 validation record

## Implemented in the isolated scope

- Added Night Console spacing, shape, typography, and state-wash tokens. `HermesRelayTheme` now supplies the semantic Material typography and shared card/field shapes.
- Extended `PaletteContrastTest` to measure primary/secondary copy over 16% state washes, all text roles over 12% HUD tints, and a known-bad wash pair.
- Added 16 named `ImageVector` resources with common 24 dp viewports, plus token and icon-set tests.

## Callsite adoption (after HOME-12/13, DIAG-01 and VOICE-03 merged)

- `DoorwayZones.kt` and `MainActivity.kt`: every raw `dp` that was a spacing value now uses `HermesSpacing` (4/8/12/16/24/32). Reviewed by hand, not by a source-text test: 20 dp card padding became `lg` (16 dp), 6 dp and 2 dp gaps became `sm`/`xs`. Deliberately left as literals because they are sizes, not spacing: `widthIn/heightIn` limits, `tonalElevation`, and the 5 dp/3 dp activity bars that `ANDROID-UX-02` deletes.
- The overflow "⋮" text is now an `Icon` (`HermesIcons.overflow`, added as the 17th icon) inside the existing `IconButton`. The button keeps the single `contentDescription`; the glyph is decorative (`contentDescription = null`).
- `OverflowMenuSemanticsTest` (instrumented) asserts the button exposes exactly one content description and a click action and that its glyph adds none. It compiled; it was not run (no device).
- State washes adopted. `Palette.stateInkWashPairs()` measures each state-role ink on its own 16% wash over the page base and the card panel (2 appearances x 4 roles x 2 surfaces = 16 pairs) at the spec's 4.5:1 text threshold; all 16 pass with **no token value changed**. Pairs over `consoleSurface` and `raisedPanel` do not all pass (several state inks fall to about 4.1-4.5:1), so state-role ink on a wash is confined to base and panel; the comment on `stateInkWashPairs()` records that restriction. No non-text (3:1) element is introduced here, so no non-text pair was added.
- Callsites: the doorway state card draws its state title and connection line on the state wash (description and action buttons stay on the plain panel, because a `TextButton` in the primary colour on a wash is unmeasured); the unconfirmed-turn notice uses the unavailable wash and the unresolved-Home-turn notice the attention wash, both with explicit primary ink (`onSurface`, measured as primaryInk over the washes).

## Scope change: header status dot moves to ANDROID-UX-03

The spec's "header dot" wash callsite is removed from UX-01. The header status dot does not exist yet and is created by `ANDROID-UX-03` (session header card with status dot, profile, title and duration); it will consume `HermesStateWashes` and add its 3:1 non-text pair there. UX-01's acceptance on this point is satisfied by the card and notices above.

## Still deferred

- `HomeConversationsSheet.kt` stays untouched: HOME-03 (#130) is open and owns it.
- Pairing/configuration UI (`RelayConfigurationScreen.kt`, `HomePairingSection.kt`, `HomePairingScanner.kt`, `HomeDeviceAdministration.kt`, `HomeApprovalsSheet.kt`) and runtime files were not migrated; the dp literals there are unreviewed.

## Pending-device (queued for the device worker, after #129)

Screenshots, Pixel, home state, each in light and dark, at font scale 1.0 and again at 2.0 (8 images), before (main) and after (this branch) where comparison is wanted:
1. Doorway state card, `NoProfile` state (identity wash on the title).
2. Doorway state card, `Unavailable` with the connection line (unavailable wash).
3. Doorway state card, `Ready` (live wash).
4. Unconfirmed-turn notice and Home-unresolved-turn notice (needs a disconnected unconfirmed turn).
5. Header with the overflow icon button, menu closed and open.
Check at 2.0x: no clipped text in the wash block, card or overflow button; icon readable.

Instrumented classes to execute (connected): `OverflowMenuSemanticsTest` (new), `AccessibilityOrderTest` (must remain unchanged and passing). Both compile today; neither has been run.

## Verification

See the PR body for the head SHA, full Gradle, the 30-run gate and CI.
