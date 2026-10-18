---
id: ANDROID-HOME-11
title: Keep the Home conversation reachable at large font scales, with the keyboard, and on every screen size
status: in-progress
baseline_commit: edac2f6ec7f25deaeca5650110c515ecc4cb852a
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-03
parity_stream: S7
created: 2026-10-06
depends_on:
  - android:5-A-3
ios_reference: 'PR #123 (78185b4)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/75
---

# ANDROID-HOME-11 — Scroll instead of overflowing under the top and bottom bars

Source (audit cross-reference, `android-ios-parity-audit.md` 2026-10-06): PX-24 in part (matrix X6): reachability and scroll rules are here; two-pane and posture layouts are `ANDROID-UX-07`; the orb and its compression rule arrive with `ANDROID-UX-02`.

Parity check for iOS PR #123 ("scroll the Home HUD instead of spilling it under the top and bottom bars", `78185b4`, merged `5aff151`, v0.7.0). On Android this is an **audit-and-harden** ticket, not a port: the mechanism that broke iOS does not exist in the same form.

## Background (iOS)

The iOS Home HUD was pinned to the full viewport height (`.frame(height: proxy.size.height)`) with scrolling disabled. When content was taller than the viewport — a wrapped profile name, the four-line Home bridge status block, a multi-line transcript, or large Dynamic Type — the header slid under the Disconnect toolbar capsule and the live transcript rail and History button disappeared behind the bottom composer bar, with no way to reach them. The fix is a layout that fills the viewport when the content fits and sizes to content (and scrolls) when it does not, always inside safe-area insets, with the orb compressing to about 60% before scrolling starts. Evidence was simulator-only (iPhone and iPad Air 11"/13" variants, keyboard up, large text); no device test and no XCTest layout test exist (PR #123 "Limitations").

## Android today (verified against `main` at `3e10ae2`)

- `AndroidClientScreen` uses `Scaffold(topBar = DoorwayHeaderZone, bottomBar = action surface)` with the conversation rail as a `LazyColumn` inside `Box(Modifier.fillMaxSize().padding(innerPadding))`. Content therefore scrolls and cannot be positioned under the bars by construction; edge-to-edge is enabled (`enableEdgeToEdge()`), the bottom bar applies `navigationBarsPadding().imePadding()`, and its inner `Column` is capped at `heightIn(max = 280.dp)` with its own `verticalScroll`.
- There is **no orb** and no fixed-height HUD in the Android UI (no `orb` symbol anywhere in `MainActivity.kt`/`DoorwayZones.kt`), so the "compress the orb to ~60%" rule has nothing to apply to today.
- There are no layout tests at large font scale, with a software keyboard, in landscape, in multi-window, or on tablets. `AccessibilityOrderTest` covers traversal order only.

## Risks to prove or retire

1. **Large font scale (1.3×, 2.0× via `fontScale`) on small phones:** the top app bar `title` + action column (profile label/name, menu) can grow; `TopAppBar` does not scroll, so a wrapped header can consume most of the screen height, and the bottom bar's `heightIn(max = 280.dp)` + header may leave little or no room for the rail.
2. **Bottom bar cap interacts with IME:** `imePadding()` on the bottom bar plus a 280 dp cap inside a `Scaffold` that also insets content: verify the last transcript line stays reachable above the composer with the keyboard up, in portrait and landscape, and with gesture vs 3-button navigation.
3. **Landscape and multi-window/foldable/tablet widths:** `widthIn(max = 720.dp)` is centered; check split-screen (small window height), Pixel Fold inner display and a 10" tablet emulator for clipped content and unreachable actions.
4. **Live text and long status blocks:** a multi-line Home status or unavailable-state message (`DoorwayStateZone`, connection recovery zone) must scroll with the rail, never overlap the bars, and the **Disconnect/recover** control and the **History/Conversations** entries must stay reachable at all scales (the Android analog of "behind the Disconnect capsule").
5. **Future HUD (5-A-3 Night Console / any orb):** if a fixed-size visual is added, it must compress to a minimum proportion before the layout scrolls, never pin to viewport height, and never disable scrolling.

## Required behavior

- At every combination in the matrix below, every primary control (Configure/menu, Conversations, History, Approvals, the composer, Send, tap-to-speak/stop/cancel, Resend/Discard, recover/Disconnect) is reachable without being obscured by a bar, the status bar, the navigation bar or the IME, and the full transcript is reachable by scrolling.
- The content area scrolls when and only when it exceeds the space between the bars; it fills the viewport when it fits (no mid-screen gap that hides the newest line).
- Insets are respected by the system (`WindowInsets.safeDrawing`/`navigationBars`/`ime` handled once, no double padding; no hard-coded status bar heights).
- Fix only the defects the audit finds. Record "no defect" per matrix cell otherwise, so the ticket can close as verified.

## Acceptance criteria

Matrix (each cell: pass/fail with a screenshot or semantics assertion in `validation-android-home-11.md`): phone small (360×640 dp) and large (≈411×914) × font scale {1.0, 1.3, 2.0} × {no IME, IME up} × {portrait, landscape}; tablet 10" and Fold inner display; split-screen at 50% height.

- Compose UI test (androidTest) per matrix row with `LocalDensity` `fontScale` override and `WindowInsets` simulated, asserting: the newest transcript item is displayed or reachable via `performScrollToIndex`; the composer's text field and Send are `assertIsDisplayed`; header actions are `assertIsDisplayed`/clickable; no tagged control's bounds intersect the bar bounds (`android_doorway_header`, `android_action_surface`, `android_conversation_rail` tags already exist).
- A deliberately long profile name and a four-line Home status block are in the fixtures.
- Screenshots on a physical Pixel at 2.0× font scale and with the keyboard open, attached to the validation record.
- Existing `AccessibilityOrderTest` still passes (order unchanged).

## Android design notes

- iOS `ViewportFillLayout`/`CompressibleOrb` → Compose: keep `LazyColumn` (or one `Column(Modifier.verticalScroll())`) with `Modifier.heightIn(min = viewport)` via `BoxWithConstraints` if a fill-the-viewport region is ever needed; never `fillMaxHeight` + `scrollDisabled`. If the top bar proves too tall at 2.0×, use `TopAppBar` with `scrollBehavior` (`enterAlways`) or collapse the profile block into the menu at large scales rather than shrinking text.
- The bottom bar cap may need to be `heightIn(max = fraction of the window)` rather than a fixed 280 dp at large scales (finding to confirm).
- Adaptive widths: prefer `WindowSizeClass` over `sw`-qualified resources to pick the centered max width.

## Dependencies

`5-A-3` (Night Console adaptation, done) is context only. No Home dependency.

## Test notes

androidTest only (needs a device/emulator; layout and insets cannot be judged on the JVM). The emulator is sufficient for the matrix; the 2.0× + keyboard screenshots on a physical device are the device gate. Do not claim tablet results from a phone emulator.

## Device verification and journal lines

No journal lines. Evidence = screenshots/semantics output in the validation record. Pass criteria are visual: no header text under the status bar or action icons, no composer hiding the last line, rail scrolls.

## References

iOS PR #123 (`5aff151`); iOS `AmbientHUD.swift`, `ContentView.swift` (`ViewportFillLayout`, `CompressibleOrb`); Android `MainActivity.kt` (`Scaffold`), `DoorwayZones.kt`.

## 2026-10-13 — compact-viewport follow-up

`spec-compact-doorway-layout.md` records an independently reproduced defect:
Scaffold's header and composer exceeded the landscape window and reduced the
conversation rail to zero height. `DoorwayLayout` now fills the safe viewport
when possible and scrolls overflow, with finite rail measurement. MainActivity
explicitly requests `adjustResize` so IME insets do not combine with window pan.

The focused 36-test emulator suite passed. Actual API 36 Standard checks
covered the normal portrait arrangement, restored landscape rail, 2× editing
and Send above the full landscape keyboard, 2× portrait editing, and a 30-line
response whose final line remained visible above the composer after rotation.
These are targeted bugfix checks, not completion of the matrix above.
The physical Pixel, tablet/Fold, split-screen and unsampled matrix cells remain
open; no acceptance requirement or historical evidence is replaced.

## 2026-10-17 — emulator layout matrix

`validation-android-home-11.md` records the counted emulator matrix. It ran on
the final APK on the real window of four new owned API 36 AVDs (2026-10-18) (360×640 dp phone, pixel_6,
10.1" WXGA, pixel_fold inner). Every cell's font scale, rotation, navigation
mode and display were read back by the driver and asserted by the tests. All
96 runs pass, covering 144/144 manifest cells. The portrait split cells are the
≈ 50 %-height row; the landscape split cells are recorded separately as
half-width. No layout defect was found and no production code changed. An
earlier "landscape Recover unreachable" note was a fixture error and is
retracted there; every run before 2026-10-17 is superseded.

For the spec owner:

- The acceptance line's `LocalDensity` + simulated-insets technique was
  retired: a synthetic viewport larger than the host phone window cannot be
  judged by display checks. The real-window tests carry the same assertions
  with real font scale and insets. This substitution needs acceptance.
- The rail does not auto-follow the newest item. A new response is reachable
  by scrolling the rail alone (asserted) but is not always shown without
  scrolling. This is a product decision, recorded as a finding and not as a
  defect.
- A settled turn returns focus to the prompt and raises the keyboard; with it
  up on a small landscape phone, about 97 dp of doorway remains, in which the
  composer, Send and the newest line are reachable.
- The four-line status block is claimed only where measured: small phone
  portrait at 1.3× and 2.0×.

Still open: the physical Pixel 2.0× / keyboard screenshots. The branch's full
non-live instrumentation suite is 105/105. A keyboard request that the new
classes left behind was taking `ModalSheetBackTest`'s first Back; this was
repaired in test cleanup and is recorded in the validation record.
