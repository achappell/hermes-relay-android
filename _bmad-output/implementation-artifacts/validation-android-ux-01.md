---
story: ANDROID-UX-01
spec: spec-android-ux-01-design-tokens.md
status: review
story_status: review
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
- `OverflowMenuSemanticsTest` (instrumented) asserts the button exposes exactly one content description and a click action and that its glyph adds none; it passed via direct `am instrument` on the Pixel.
- State washes adopted. `Palette.stateInkWashPairs()` measures each state-role ink on its own 16% wash over the page base and the card panel (2 appearances x 4 roles x 2 surfaces = 16 pairs) at the spec's 4.5:1 text threshold; all 16 pass with **no token value changed**. Pairs over `consoleSurface` and `raisedPanel` do not all pass (several state inks fall to about 4.1-4.5:1), so state-role ink on a wash is confined to base and panel; the comment on `stateInkWashPairs()` records that restriction. No non-text (3:1) element is introduced here, so no non-text pair was added.
- Callsites: the doorway state card draws its state title and connection line on the state wash (description and action buttons stay on the plain panel, because a `TextButton` in the primary colour on a wash is unmeasured); the unconfirmed-turn notice uses the unavailable wash and the unresolved-Home-turn notice the attention wash, both with explicit primary ink (`onSurface`, measured as primaryInk over the washes).

## Scope change: header status dot moves to ANDROID-UX-03

The spec's "header dot" wash callsite is removed from UX-01. The header status dot does not exist yet and is created by `ANDROID-UX-03` (session header card with status dot, profile, title and duration); it will consume `HermesStateWashes` and add its 3:1 non-text pair there. UX-01's acceptance on this point is satisfied by the card and notices above.

## Still deferred

- `HomeConversationsSheet.kt` stays untouched: HOME-03 (#130) is open and owns it.
- Pairing/configuration UI (`RelayConfigurationScreen.kt`, `HomePairingSection.kt`, `HomePairingScanner.kt`, `HomeDeviceAdministration.kt`, `HomeApprovalsSheet.kt`) and runtime files were not migrated; the dp literals there are unreviewed.

## Device pass (Pixel 6a, Android 17, 2026-10-07; head `f37080d`)

Installed with `adb install -r` only (app data hashes unchanged after each install); system font scale and night mode were set per run through `settings put system font_scale` / `cmd uimode night` and restored afterwards (original: font scale 0.85, night mode `auto`).

- **Instrumented via `am instrument`:** `OverflowMenuSemanticsTest` + `AccessibilityOrderTest`: **OK (7 tests)** (1 + 6; `AccessibilityOrderTest` unchanged).
- **Screenshots (32 images, kept off-repo):** a throwaway instrumented harness (not committed) rendered the real `DoorwayHeaderZone`, `DoorwayStateZone` and `ConnectionRecoveryZone` with fake state and screenshotted the display with `UiAutomation`: `NoProfile`, `Unavailable` with the connection line (`Reconnect failed: transport_unavailable`), `Ready` (each with the overflow menu closed and open), the unconfirmed-turn notice and the Home-unresolved-turn notice, at font scale 1.0 and 2.0, light and dark. Main (`b865c0b`) was shot with the same harness at 1.0 and 2.0 (light) for comparison.
- **Wash blocks, notices and buttons:** the state card title and connection line on the identity/live/unavailable washes, the unavailable-wash unconfirmed notice and the attention-wash unresolved notice are fully readable at 2.0x in light and dark, with no clipped text inside the card or notices; the overflow icon renders as the 17th vector icon and opens the menu (`Configure relay`, `Local History`) at 1.0x.
- **Fails the spec's "no text clipped at 2.0" check — in the header, and identically on main:** with a real Profile label (`Spark · caticornqueen.taila59979.ts.net`) the header row runs out of width. At 1.0x the title reads `Hermes conve…`; at 2.0x the title and subtitle are truncated or dropped and the overflow icon is pushed out of view, so Configure/Conversations/History/Disconnect are unreachable at 2.0x with a long Profile label. The same harness on main shows the same header at 2.0x, so this is not a UX-01 regression; it is the large-font reachability risk owned by `ANDROID-HOME-11` (and the header rebuild in `ANDROID-UX-03`).

## Current acceptance decisions and review readiness

- **Independent header OWNER EXEMPTION (2026-10-07):** “Exempt pre-existing header clipping from #127 acceptance.” This exception covers only the pre-existing session-header clipping documented above, reproduced unchanged on `main`, and tracked for UX-03 (#134) with HOME-11 reachability coordination; it is not a UX-01 regression. The exemption remains intact and is separate from the TalkBack waiver.
- **Amanda's TalkBack waiver (2026-10-07):** manual physical TalkBack spoken-output/focus-gesture acceptance for PR #127 is waived. Physical TalkBack was not run; spoken output and focus gestures remain unverified, with known residual spoken-label, focus-order and gesture risk. Automated semantics and focus-order tests do not establish manual spoken-output or gesture acceptance.
- **Superseded history:** before this owner decision, physical TalkBack was not waived and remained an open device-verification gate. That requirement no longer blocks review; it is not a historical pass.
- **Status:** `review`, ready for review only after final exact-head CI succeeds, not `done` or fully TalkBack verified. The recorded automated/device/layout evidence and independent header exemption cover the other UX-01 gates; no additional unmet gate is recorded. Deferred callsites and the header correction remain owned by their separate stories, not silently accepted here.

## Verification

- Historical exact-head CI: GitHub Actions run `37641920909` for superseded head `fb26d10de9976e2c7fd6a596cbecb2e30a4042c0` passed both `Build, test, lint, and inspect APK` and `Validate BMAD issue tracking`. The legacy commit-status endpoint had `pending` with zero contexts; it was not a failed check.
- Pre-waiver exact-head CI: GitHub Actions run `37650526534` for `41a1d7e2a9d36b777329fc7cc331bbafca9dc938` passed both checks. It is historical evidence, not final waiver-commit CI; the final head/run is recorded in [PR #127](https://github.com/achappell/hermes-relay-android/pull/127).
- Existing local evidence at tested code head `f37080d`: `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon --console=plain` passed; `scripts/run-flake-gate.sh PaletteContrastTest,HermesDesignTokensTest 30` passed 30/30 runs (14 tests/run); 18 issue-tracking tests and the overrides check passed. No local tests/build/lint/format or device scenarios were rerun for this metadata-only waiver.
