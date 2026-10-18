---
id: ANDROID-HOME-11
title: Keep the Home conversation reachable at large font scales, with the keyboard, and on every screen size
status: in-progress
spec: spec-android-home-11-hud-scroll-layout.md
baseline_commit: edac2f6ec7f25deaeca5650110c515ecc4cb852a
branch: home-11-emulator-layout
created: 2026-10-16
---

# ANDROID-HOME-11 validation — emulator layout matrix

Issue [#75](https://github.com/achappell/hermes-relay-android/issues/75).
Sections 1 and 4 were written **before** execution and fix the matrix and the
device manifest, so the coverage claim can be judged against a plan rather than
a result. Sections 2, 3 and 5–10 record what was actually executed, including
the evidence layer that was retired and a retracted finding.

**Outcome: no product defect was found; no production code changed.** The
branch adds instrumentation tests and this record only.

## 1. Matrix interpretation (fixed before execution)

Forced-pass interjection on 2026-10-16 requires that the matrix not be narrowed:

- The spec multiplies the two phone viewports by `{font scale 1.0, 1.3, 2.0} ×
  {no IME, IME up} × {portrait, landscape}` (24 cells) and then *adds* tablet,
  Fold inner and 50 %-height split-screen without naming their font/orientation
  dimensions. This record applies the **same** font × IME × orientation
  product to every added viewport instead of sampling it.
- Risk 2 of the spec calls out gesture vs three-button navigation explicitly, so
  each logical viewport is also exercised under both navigation modes.

| Dimension | Values | Count |
| --- | --- | --- |
| Logical viewport row | phone small 360×640 dp, phone large ≈411×914 dp, tablet 10", Fold inner, split-screen 50 % height (phone small), split-screen 50 % height (phone large) | 6 |
| Font scale | 1.0, 1.3, 2.0 | 3 |
| IME | off, up | 2 |
| Orientation | portrait, landscape | 2 |
| Navigation mode | gesture, three-button | 2 |

6 × 3 × 2 × 2 × 2 = **144 logical cells.**

A *logical cell* is one (viewport, font scale, IME, orientation, navigation
mode) tuple. It is not a claim about a physical device: every tablet and Fold
result here comes from an emulator AVD with that device's native profile, and
the physical-Pixel cells remain a separate open gate.

## 2. Evidence layers — what counts

| Layer | Status |
| --- | --- |
| **A — synthetic rows** (`LocalDensity` font override, a `Box` of the logical viewport inside the test window) | **Retired; counts for nothing.** A viewport larger than the host phone window cannot be judged by the platform's display checks (6/6 phase tests failed for that reason). Not in the test tree. |
| **B — the real window** (`DoorwayRealWindowMatrixTest`, `DoorwaySplitWindowMatrixTest`) | **The only counted evidence.** Real `MainActivity`, real font scale, rotation, navigation mode, OS keyboard and WM Shell split on owned AVDs; only the Home behind the screen is a fixture. |

The spec's acceptance line names the `LocalDensity` + simulated-insets
technique. It could not represent the large viewports truthfully, so the real
window carries the same assertions instead. This substitution is for the spec
owner to accept; it is not presented as the literal technique.

### Superseded evidence

Every result recorded before 2026-10-17 (commit `75fcc61` and earlier) is
superseded and not counted. Its receipts were taken **before** each cell's
configuration was applied, so they described the previous cell. No test
asserted navigation mode, and receipts are not evidence of it. The counted run
below uses new empty AVDs, the corrected driver and the declared-row checks.

## 3. What each test asserts

The fixture uses a long Profile name
(`Alexandra-Cassandra Montgomery-Worthington of the Northern Territories`) and
starts held down: reconnects settle as terminal `Unrecoverable`, which is the
disconnected state a person sees.

Every reached control is checked twice. First, the platform reports it
displayed inside its own region's visible part. Second,
`assertClearOfBars` requires its visible bounds to sit inside the window's
unobscured band (below the status bar, above the navigation bar and the real
keyboard, read live from the window insets) and to overlap no other region
(header, rail, composer surface). `assertBarsDoNotOverlap` requires the regions
to be ordered. When the doorway does not scroll as a whole, it also requires
the doorway to fill its viewport: header at the top, composer at the bottom,
and the rail's slot between them, so no gap is left mid-screen. A missing root
or scroller fails the cell; there is no infinite fallback window.

| Test | Covers cells | Asserts |
| --- | --- | --- |
| `real_window_keeps_every_control_reachable_without_the_keyboard` | full window, IME off | regions ordered and filling; doorway state; Recover enabled while disconnected, and a real press reaches the client; connect; prompt; tap-to-speak; menu Configure/Conversations/Approvals/History/Disconnect displayed; each of the three sheets opens and closes |
| `real_window_keeps_the_composer_and_newest_line_above_the_open_keyboard` | full window, IME up | real keyboard up. Prompt, Send and the idle doorway state are each brought into view above the keyboard by **real finger strokes** (`input swipe`; flings only for far targets; strokes kept out of the status bar, keyboard toolbar and system-gesture areas). Then a completed 40-line turn: the **bottom edge of the newest response** (its last line) above the keyboard, then prompt and Send again. Each is clear of bars. Keyboard still up; regions ordered. |
| `real_window_keeps_the_authorization_state_reachable` | full window, IME off | long Profile name rendered; doorway state; the status block, with its line count measured and logged, and **asserted ≥ 4 where the row claims it** (section 5); Edit relay; Recover correctly absent (`canAttemptClientConnection`); prompt; tap-to-speak; menu entries other than Disconnect |
| `real_window_keeps_turn_controls_and_the_whole_transcript_reachable` | full window, IME off | real typed turn; turn phase and Interrupt. When the doorway fits (outer range 0), the newest item is reachable by scrolling the **rail alone**, with the doorway itself never scrolling. The response's first line, and its last line inside the rail above the composer. After a mid-turn transport loss: unconfirmed turn, Recover, Resend and Discard (the keyboard the settled turn raises is closed with a real Back key first). |
| `split_screen_grants_the_client_half_the_display_and_keeps_the_composer_reachable` | split, IME off and up | real WM Shell split, asserted on the **axis Android splits**: height on a portrait display (≈ 50 %-height cells), width on a landscape display (**half-width cells**), with the other axis ≥ 90 %. Disconnected phase: regions, state, Recover (real press reaches the client), prompt, tap-to-speak, menu Configure/Conversations/Approvals/History, and each sheet opens. **Connected phase: menu Disconnect displayed.** Keyboard phase: prompt, Send and the idle state above the real keyboard, then a completed 40-line turn's newest line, prompt and Send, all by real strokes and clear of bars. The split is exited and verified gone afterwards. |

Not asserted: stop/cancel capture (needs a live microphone; the AVDs run
`-no-audio`). These are unverified, not passed.

## 4. Device configuration manifest (Layer B)

Every AVD is created new and empty for this ticket, under this worktree's
`evidence/` directory; the owner's emulator 5554, the original AVD/userdata and
`~/Library/Application Support/Hermes Recovery/home08b-20261014` are never
touched. Each `adb` invocation names its serial explicitly.

| AVD | Device profile | Native display | Port | Used for |
| --- | --- | --- | --- | --- |
| `hermes_home11_phone_20261017` | `small_phone` | 720×1280 @ 320 dpi = 360×640 dp | 5556 | R1, R5 |
| `hermes_home11_large_20261017` | `pixel_6` | 1080×2400 @ 420 dpi ≈ 411×914 dp | 5558 | R2, R6 |
| `hermes_home11_tablet_20261017` | `10.1in WXGA (Tablet)` | 1280×800 @ 160 dpi = 1280×800 dp (natural landscape) | 5560 | R3 |
| `hermes_home11_fold_20261017` | `pixel_fold` (inner display) | 2208×1840 @ 420 dpi ≈ 841×701 dp | 5562 | R4 |

Per-row receipts captured: `wm size`, `wm density`, `dumpsys window displays`,
`settings get system font_scale`, `settings get secure navigation_mode`,
`dumpsys input_method` IME insets, and `dumpsys activity activities` for the
split-screen task bounds. Split-screen rows must show **actual multi-window
bounds** (two resumed tasks, the app's task at ≈50 % of the display height) —
`wm size` and a synthetic `Box` do not count.

All four are API 36 `google_apis` arm64 (`sdk_gphone64_arm64` 16 /
`BE2A.250530.026.F3`). AVDs ran sequentially to avoid host contention and were
deleted at the end; the evidence stays (section 9).

The row manifest and driver are committed: `scripts/run-home11-avd.sh` boots an
AVD, installs the APKs and runs `scripts/run-home11-layout-matrix.sh`, whose
header holds the manifest (row → natural display size, and the cells where the
four-line status block is claimed). Per cell the driver:

1. applies navigation mode (`cmd overlay enable-exclusive --category
   com.android.internal.systemui.navbar.<gestural|threebutton>`), font scale,
   `show_ime_with_hard_keyboard`, and rotation (`accelerometer_rotation 0`,
   `user_rotation`, `wm user-rotation lock`, `wm fixed-to-user-rotation
   enabled` — a portrait-locked launcher otherwise keeps a phone at
   ROTATION_0); the commands' own output goes into the receipt;
2. reads back `font_scale`, `navigation_mode` and the display rotation and
   marks the cell `CONFIG-MISMATCH` (not run, not counted) if any differs;
3. writes the cell's receipt **after** applying it;
4. runs the instrumentation with the declared row as arguments
   (`home11_row`, `home11_font`, `home11_rotation`, `home11_nav`,
   `home11_display`, and `home11_status_lines_min` where claimed). Each test
   first asserts the running font scale, display rotation, `navigation_mode`
   and natural display size match the declaration (`HOME11-ROW` line).

## 5. Results (counted run, 2026-10-17)

One run of one APK pair on four new, empty, owned API 36 AVDs
(`hermes_home11_{phone,large,tablet,fold}_20261017`). Every cell is
`nav 2 × font 3 × rotation 2 × class 2` per AVD. There were **0 configuration
mismatches**.

| AVD row | Real-window runs (4 tests each) | Split runs | Result |
| --- | --- | --- | --- |
| small-phone (720×1280 @ 2.0) | 12/12 OK (48 tests) | 12/12 OK | pass |
| large-phone (1080×2400 @ 2.625) | 12/12 OK (48 tests) | 12/12 OK | pass |
| tablet-10in (1280×800 @ 1.0, natural landscape) | 12/12 OK (48 tests) | 12/12 OK | pass |
| fold-inner (2208×1840 @ 2.625) | 12/12 OK (48 tests) | 12/12 OK | pass |

**96 of 96 runs pass (240 test executions), 0 fail, 0 unexecuted.**

Manifest cells (viewport × font × IME × orientation × navigation):

| Row | Cells | Pass | Fail | Unexecuted |
| --- | --- | --- | --- | --- |
| Small phone, large phone, tablet, Fold inner — full window | 96 | 96 | 0 | 0 |
| Small phone split, portrait (≈ 50 % height: window 720×630 of 720×1280) | 12 | 12 | 0 | 0 |
| Large phone split, portrait (≈ 50 % height: window 1080×1187 of 1080×2400) | 12 | 12 | 0 | 0 |
| Small phone split, landscape — **half-width** (window 630×720 / 678×720 of 1280×720) | 12 | 12 | 0 | 0 |
| Large phone split, landscape — **half-width** | 12 | 12 | 0 | 0 |

The spec's "split-screen at 50 % height" row is the 24 portrait split cells.
The 24 landscape split cells are recorded separately as half-width windows,
because that is what Android grants in landscape. Beyond the manifest, tablet
and Fold split pass on both axes (24 + 24 cells, in the table below).

**Status block.** The status block is claimed to reach four or more lines only
where it was measured to: small phone portrait at 1.3 and 2.0, where the test
asserts `lineCount ≥ 4`. Everywhere else the count is logged and nothing is
claimed. Measured counts: small phone portrait 3/4/6 lines at 1.0/1.3/2.0,
landscape 2/2/3; large phone portrait 3/3/4, landscape 1/2/2; tablet 1–2;
Fold 1–3.

**Newest item in a fitting doorway.** In 30 runs the doorway fits its viewport.
In 20 of them the response is already displayed without scrolling; in the
other 10 it is reached by scrolling the rail alone (finding 3).

### Per-run table

`Status lines`, `Fitting` and `Split` come from each run's own log lines
(`HOME11-WINDOW`, `HOME11-SPLIT`). `Row checks` is the number of tests in the
run that asserted the declared row.

| Row | Nav | Font | Rot | Class | Result | Window (no IME) | IME px | Status lines | Fitting: newest item shown unscrolled | Split axis / window / ratio | Row checks |
|---|---|---|---|---|---|---|---|---|---|---|---|
| small-phone | gestural | 1.0 | 0 | RealWindow | OK 4 | 720x1280 | 598 | 3 | false |  | 4 |
| small-phone | gestural | 1.0 | 0 | SplitWindow | OK 1 |  | 157 |  | n/a | height 720x630 of 720x1280 (0.49) | 1 |
| small-phone | gestural | 1.0 | 1 | RealWindow | OK 4 | 1280x720 | 478 | 2 | n/a |  | 4 |
| small-phone | gestural | 1.0 | 1 | SplitWindow | OK 1 |  | 478 |  | n/a | width 630x720 of 1280x720 (0.49) | 1 |
| small-phone | gestural | 1.3 | 0 | RealWindow | OK 4 | 720x1280 | 598 | 4 | n/a |  | 4 |
| small-phone | gestural | 1.3 | 0 | SplitWindow | OK 1 |  | 157 |  | n/a | height 720x630 of 720x1280 (0.49) | 1 |
| small-phone | gestural | 1.3 | 1 | RealWindow | OK 4 | 1280x720 | 478 | 2 | n/a |  | 4 |
| small-phone | gestural | 1.3 | 1 | SplitWindow | OK 1 |  |  |  | n/a |  | 0 |
| small-phone | gestural | 2.0 | 0 | RealWindow | OK 4 | 720x1280 | 598 | 6 | n/a |  | 4 |
| small-phone | gestural | 2.0 | 0 | SplitWindow | OK 1 |  | 157 |  | n/a | height 720x630 of 720x1280 (0.49) | 1 |
| small-phone | gestural | 2.0 | 1 | RealWindow | OK 4 |  |  |  | n/a |  | 0 |
| small-phone | gestural | 2.0 | 1 | SplitWindow | OK 1 |  | 478 |  | n/a | width 630x720 of 1280x720 (0.49) | 1 |
| small-phone | threebutton | 1.0 | 0 | RealWindow | OK 4 | 720x1280 | 598 | 3 | n/a |  | 4 |
| small-phone | threebutton | 1.0 | 0 | SplitWindow | OK 1 |  | 157 |  | n/a | height 720x630 of 720x1280 (0.49) | 1 |
| small-phone | threebutton | 1.0 | 1 | RealWindow | OK 4 | 1280x720 | 400 | 2 | n/a |  | 4 |
| small-phone | threebutton | 1.0 | 1 | SplitWindow | OK 1 |  | 400 |  | n/a | width 678x720 of 1280x720 (0.53) | 1 |
| small-phone | threebutton | 1.3 | 0 | RealWindow | OK 4 | 720x1280 | 598 | 4 | n/a |  | 4 |
| small-phone | threebutton | 1.3 | 0 | SplitWindow | OK 1 |  | 157 |  | n/a | height 720x630 of 720x1280 (0.49) | 1 |
| small-phone | threebutton | 1.3 | 1 | RealWindow | OK 4 | 1280x720 | 400 | 2 | n/a |  | 4 |
| small-phone | threebutton | 1.3 | 1 | SplitWindow | OK 1 |  | 400 |  | n/a | width 678x720 of 1280x720 (0.53) | 1 |
| small-phone | threebutton | 2.0 | 0 | RealWindow | OK 4 | 720x1280 | 598 | 6 | n/a |  | 4 |
| small-phone | threebutton | 2.0 | 0 | SplitWindow | OK 1 |  | 157 |  | n/a | height 720x630 of 720x1280 (0.49) | 1 |
| small-phone | threebutton | 2.0 | 1 | RealWindow | OK 4 | 1280x720 | 400 | 3 | n/a |  | 4 |
| small-phone | threebutton | 2.0 | 1 | SplitWindow | OK 1 |  | 400 |  | n/a | width 678x720 of 1280x720 (0.53) | 1 |
| large-phone | gestural | 1.0 | 0 | RealWindow | OK 4 | 1080x2400 | 883 | 3 | true |  | 4 |
| large-phone | gestural | 1.0 | 0 | SplitWindow | OK 1 |  | 53 |  | n/a | height 1080x1187 of 1080x2400 (0.49) | 1 |
| large-phone | gestural | 1.0 | 1 | RealWindow | OK 4 | 2400x1080 | 686 | 1 | n/a |  | 4 |
| large-phone | gestural | 1.0 | 1 | SplitWindow | OK 1 |  | 686 |  | n/a | width 1123x1080 of 2400x1080 (0.47) | 1 |
| large-phone | gestural | 1.3 | 0 | RealWindow | OK 4 | 1080x2400 | 883 | 3 | true |  | 4 |
| large-phone | gestural | 1.3 | 0 | SplitWindow | OK 1 |  | 53 |  | n/a | height 1080x1187 of 1080x2400 (0.49) | 1 |
| large-phone | gestural | 1.3 | 1 | RealWindow | OK 4 | 2400x1080 | 686 | 2 | n/a |  | 4 |
| large-phone | gestural | 1.3 | 1 | SplitWindow | OK 1 |  | 686 |  | n/a | width 1123x1080 of 2400x1080 (0.47) | 1 |
| large-phone | gestural | 2.0 | 0 | RealWindow | OK 4 | 1080x2400 | 883 | 4 | false |  | 4 |
| large-phone | gestural | 2.0 | 0 | SplitWindow | OK 1 |  | 53 |  | n/a | height 1080x1187 of 1080x2400 (0.49) | 1 |
| large-phone | gestural | 2.0 | 1 | RealWindow | OK 4 | 2400x1080 | 686 | 2 | n/a |  | 4 |
| large-phone | gestural | 2.0 | 1 | SplitWindow | OK 1 |  | 686 |  | n/a | width 1123x1080 of 2400x1080 (0.47) | 1 |
| large-phone | threebutton | 1.0 | 0 | RealWindow | OK 4 | 1080x2400 | 883 | 3 | true |  | 4 |
| large-phone | threebutton | 1.0 | 0 | SplitWindow | OK 1 |  | 53 |  | n/a | height 1080x1187 of 1080x2400 (0.49) | 1 |
| large-phone | threebutton | 1.0 | 1 | RealWindow | OK 4 | 2400x1080 | 560 | 1 | n/a |  | 4 |
| large-phone | threebutton | 1.0 | 1 | SplitWindow | OK 1 |  | 560 |  | n/a | width 1186x1080 of 2400x1080 (0.49) | 1 |
| large-phone | threebutton | 1.3 | 0 | RealWindow | OK 4 | 1080x2400 | 883 | 3 | true |  | 4 |
| large-phone | threebutton | 1.3 | 0 | SplitWindow | OK 1 |  | 53 |  | n/a | height 1080x1187 of 1080x2400 (0.49) | 1 |
| large-phone | threebutton | 1.3 | 1 | RealWindow | OK 4 | 2400x1080 | 560 | 2 | n/a |  | 4 |
| large-phone | threebutton | 1.3 | 1 | SplitWindow | OK 1 |  | 560 |  | n/a | width 1186x1080 of 2400x1080 (0.49) | 1 |
| large-phone | threebutton | 2.0 | 0 | RealWindow | OK 4 | 1080x2400 | 883 | 4 | false |  | 4 |
| large-phone | threebutton | 2.0 | 0 | SplitWindow | OK 1 |  | 53 |  | n/a | height 1080x1187 of 1080x2400 (0.49) | 1 |
| large-phone | threebutton | 2.0 | 1 | RealWindow | OK 4 | 2400x1080 | 560 | 2 | n/a |  | 4 |
| large-phone | threebutton | 2.0 | 1 | SplitWindow | OK 1 |  | 560 |  | n/a | width 1186x1080 of 2400x1080 (0.49) | 1 |
| tablet-10in | gestural | 1.0 | 0 | RealWindow | OK 4 | 1280x800 | 368 | 1 | true |  | 4 |
| tablet-10in | gestural | 1.0 | 0 | SplitWindow | OK 1 |  | 368 |  | n/a | width 635x800 of 1280x800 (0.50) | 1 |
| tablet-10in | gestural | 1.0 | 1 | RealWindow | OK 4 | 800x1280 | 368 | 1 | true |  | 4 |
| tablet-10in | gestural | 1.0 | 1 | SplitWindow | OK 1 |  | 0 |  | n/a | height 800x635 of 800x1280 (0.50) | 1 |
| tablet-10in | gestural | 1.3 | 0 | RealWindow | OK 4 | 1280x800 | 368 | 1 | true |  | 4 |
| tablet-10in | gestural | 1.3 | 0 | SplitWindow | OK 1 |  | 368 |  | n/a | width 635x800 of 1280x800 (0.50) | 1 |
| tablet-10in | gestural | 1.3 | 1 | RealWindow | OK 4 | 800x1280 | 368 | 2 | true |  | 4 |
| tablet-10in | gestural | 1.3 | 1 | SplitWindow | OK 1 |  | 0 |  | n/a | height 800x635 of 800x1280 (0.50) | 1 |
| tablet-10in | gestural | 2.0 | 0 | RealWindow | OK 4 | 1280x800 | 368 | 2 | false |  | 4 |
| tablet-10in | gestural | 2.0 | 0 | SplitWindow | OK 1 |  | 368 |  | n/a | width 635x800 of 1280x800 (0.50) | 1 |
| tablet-10in | gestural | 2.0 | 1 | RealWindow | OK 4 | 800x1280 | 368 | 2 | true |  | 4 |
| tablet-10in | gestural | 2.0 | 1 | SplitWindow | OK 1 |  | 0 |  | n/a | height 800x635 of 800x1280 (0.50) | 1 |
| tablet-10in | threebutton | 1.0 | 0 | RealWindow | OK 4 | 1280x800 | 368 | 1 | true |  | 4 |
| tablet-10in | threebutton | 1.0 | 0 | SplitWindow | OK 1 |  | 368 |  | n/a | width 635x800 of 1280x800 (0.50) | 1 |
| tablet-10in | threebutton | 1.0 | 1 | RealWindow | OK 4 | 800x1280 | 368 | 1 | true |  | 4 |
| tablet-10in | threebutton | 1.0 | 1 | SplitWindow | OK 1 |  | 0 |  | n/a | height 800x635 of 800x1280 (0.50) | 1 |
| tablet-10in | threebutton | 1.3 | 0 | RealWindow | OK 4 | 1280x800 | 368 | 1 | true |  | 4 |
| tablet-10in | threebutton | 1.3 | 0 | SplitWindow | OK 1 |  | 368 |  | n/a | width 635x800 of 1280x800 (0.50) | 1 |
| tablet-10in | threebutton | 1.3 | 1 | RealWindow | OK 4 | 800x1280 | 368 | 2 | true |  | 4 |
| tablet-10in | threebutton | 1.3 | 1 | SplitWindow | OK 1 |  | 0 |  | n/a | height 800x635 of 800x1280 (0.50) | 1 |
| tablet-10in | threebutton | 2.0 | 0 | RealWindow | OK 4 | 1280x800 | 368 | 2 | false |  | 4 |
| tablet-10in | threebutton | 2.0 | 0 | SplitWindow | OK 1 |  | 368 |  | n/a | width 635x800 of 1280x800 (0.50) | 1 |
| tablet-10in | threebutton | 2.0 | 1 | RealWindow | OK 4 | 800x1280 | 368 | 2 | true |  | 4 |
| tablet-10in | threebutton | 2.0 | 1 | SplitWindow | OK 1 |  | 0 |  | n/a | height 800x635 of 800x1280 (0.50) | 1 |
| fold-inner | gestural | 1.0 | 0 | RealWindow | OK 4 | 2208x1840 | 841 | 1 | true |  | 4 |
| fold-inner | gestural | 1.0 | 0 | SplitWindow | OK 1 |  | 841 |  | n/a | width 1091x1840 of 2208x1840 (0.49) | 1 |
| fold-inner | gestural | 1.0 | 1 | RealWindow | OK 4 | 1840x2208 | 841 | 2 | true |  | 4 |
| fold-inner | gestural | 1.0 | 1 | SplitWindow | OK 1 |  | 78 |  | n/a | height 1840x1091 of 1840x2208 (0.49) | 1 |
| fold-inner | gestural | 1.3 | 0 | RealWindow | OK 4 | 2208x1840 | 841 | 2 | false |  | 4 |
| fold-inner | gestural | 1.3 | 0 | SplitWindow | OK 1 |  | 841 |  | n/a | width 1091x1840 of 2208x1840 (0.49) | 1 |
| fold-inner | gestural | 1.3 | 1 | RealWindow | OK 4 | 1840x2208 | 841 | 2 | true |  | 4 |
| fold-inner | gestural | 1.3 | 1 | SplitWindow | OK 1 |  | 78 |  | n/a | height 1840x1091 of 1840x2208 (0.49) | 1 |
| fold-inner | gestural | 2.0 | 0 | RealWindow | OK 4 | 2208x1840 | 841 | 2 | false |  | 4 |
| fold-inner | gestural | 2.0 | 0 | SplitWindow | OK 1 |  | 841 |  | n/a | width 1091x1840 of 2208x1840 (0.49) | 1 |
| fold-inner | gestural | 2.0 | 1 | RealWindow | OK 4 | 1840x2208 | 841 | 3 | false |  | 4 |
| fold-inner | gestural | 2.0 | 1 | SplitWindow | OK 1 |  | 78 |  | n/a | height 1840x1091 of 1840x2208 (0.49) | 1 |
| fold-inner | threebutton | 1.0 | 0 | RealWindow | OK 4 | 2208x1840 | 841 | 1 | true |  | 4 |
| fold-inner | threebutton | 1.0 | 0 | SplitWindow | OK 1 |  | 841 |  | n/a | width 1091x1840 of 2208x1840 (0.49) | 1 |
| fold-inner | threebutton | 1.0 | 1 | RealWindow | OK 4 | 1840x2208 | 841 | 2 | true |  | 4 |
| fold-inner | threebutton | 1.0 | 1 | SplitWindow | OK 1 |  | 78 |  | n/a | height 1840x1091 of 1840x2208 (0.49) | 1 |
| fold-inner | threebutton | 1.3 | 0 | RealWindow | OK 4 | 2208x1840 | 841 | 2 | false |  | 4 |
| fold-inner | threebutton | 1.3 | 0 | SplitWindow | OK 1 |  | 841 |  | n/a | width 1091x1840 of 2208x1840 (0.49) | 1 |
| fold-inner | threebutton | 1.3 | 1 | RealWindow | OK 4 | 1840x2208 | 841 | 2 | true |  | 4 |
| fold-inner | threebutton | 1.3 | 1 | SplitWindow | OK 1 |  | 78 |  | n/a | height 1840x1091 of 1840x2208 (0.49) | 1 |
| fold-inner | threebutton | 2.0 | 0 | RealWindow | OK 4 | 2208x1840 | 841 | 2 | n/a |  | 4 |
| fold-inner | threebutton | 2.0 | 0 | SplitWindow | OK 1 |  | 841 |  | n/a | width 1091x1840 of 2208x1840 (0.49) | 1 |
| fold-inner | threebutton | 2.0 | 1 | RealWindow | OK 4 | 1840x2208 | 841 | 3 | false |  | 4 |
| fold-inner | threebutton | 2.0 | 1 | SplitWindow | OK 1 |  | 78 |  | n/a | height 1840x1091 of 1840x2208 (0.49) | 1 |

## 6. Findings

1. **No product layout defect was found. Production code is unchanged.**
2. **Retraction (2026-10-16).** "Recover unreachable in landscape" was a
   fixture error: the fixture had auto-connected, and while connected
   `ConnectionRecoveryZone` correctly offers no Recover.
3. **For the spec owner: the rail does not auto-follow the newest item.**
   There is no `scrollToItem`/`animateScrollTo` in `app/src/main`. When a long
   response arrives, the rail keeps its position, so in 10 fitting-doorway runs
   the new response sits below the rail's first items until the user scrolls
   the rail. The spec requires the item to be "displayed or reachable"; it is
   reachable by the rail alone, and that is asserted. Whether to follow new
   content is a product decision. This is recorded as a finding, not a defect
   or a pass.
4. **A settled turn raises the keyboard.** `ClientRuntimeEffects` returns
   focus to the prompt. With the keyboard up on a 360×640 phone in landscape,
   about 97 dp of doorway remains; prompt, Send and the newest line are
   reachable there by real strokes (asserted).
5. **Tablet portrait split: real cause found and resolved in the test.** In a
   top/bottom split WM Shell moves the lower stage up above the keyboard.
   The client window therefore receives **no IME inset** while the keyboard is
   showing: the window origin moved from y = 645 to y = 277, exactly the 368 px
   keyboard. The earlier test waited for an inset that never arrives. It now
   asks the input-method service (`mInputShown`), and the cells pass.
6. **`ModalSheetBackTest` order dependence (pre-existing).**
   - Baseline `edac2f6` APK on the same owned AVD, portrait:
     `HomeRuntimeRecreationTest` then `ModalSheetBackTest` fails 2 of 3 runs
     (`back_dismisses_only_the_approvals_sheet`); `ModalSheetBackTest` alone
     passes.
   - Baseline full non-live suite: 100 run, 0 failures.
   - Branch full non-live suite: **105 run, 103 pass, 2 fail**
     (`ModalSheetBackTest.back_dismisses_only_the_approvals_sheet` and
     `…_history_sheet`).
   - The new real-window classes run earlier in the same process and add more
     preceding `MainActivity` launches, which trigger the existing order
     dependence in the full suite. The suite is red on this branch. The defect
     is in `ModalSheetBackTest`/its predecessors and is not fixed here.
7. `AccessibilityOrderTest` passes within the branch full suite.

## 7. Gates (final tree)

| Gate | Result |
| --- | --- |
| JVM `testDebugUnitTest --rerun-tasks` (XML archived first) | 57 suites, 784 tests, 0 failures/errors/skips |
| `assembleDebug lintDebug assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| `scripts/check-apk-metadata.sh` | "APK metadata is correct: min SDK 26, version 0.3.1 (301), backup off with extraction rules, 16 KB aligned, signing skipped (EXPECTED_SIGNER_SHA256 unset)." |
| Flake gate N=1 (four classes) | 1 consecutive run, 0 failures |
| Full non-live instrumentation, baseline `edac2f6` | 100 run, 0 failures |
| Full non-live instrumentation, branch | **105 run, 103 pass, 2 fail** (finding 6) — **not green** |

APK SHA-256 values:
- Counted matrix: debug `7bedc1cd…`, androidTest `d48938c1…`.
- Gate rebuild: androidTest `d48938c1…` (identical) and debug `8f1b26a5…`.
  No `app/src/main` file differs from `edac2f6`; the debug bytes differ only
  by rebuild.

## 8. Open gates and limits

- **Physical Pixel gate — open.** Everything here is emulator evidence.
- No live Home, pairing, microphone or audio claim. Stop/cancel capture is
  unverified.
- The spec's `LocalDensity` technique is replaced by real-window evidence
  (section 2); this needs the spec owner's acceptance.
- Full suite red (finding 6).
- After a drag with the keyboard up, the test clock can read two alternating
  layouts (logged as `unsettled-after-drag`). The real display was sampled as
  still: unchanged screenshots and stable semantics. Visibility after drags is
  therefore required on three consecutive reads.

## 9. Evidence location

`~/Library/Application Support/Hermes Recovery/home11-20261017/`:
- `matrix/<row>/`: per cell, the post-apply receipt, the instrumentation
  output, logcat with `HOME11-ROW/WINDOW/SPLIT` lines, start/end screenshots
  and the split keyboard screenshot; also `results-<row>.txt`,
  `native-profile-<row>.txt`, `apk-sha256.txt` and `per-run-table.md`.
- `final/`: baseline and branch full suites and the baseline control runs.
- `gates/`: gate logs and JVM XML.

The 2026-10-16 directory holds superseded runs only. Owned AVDs were deleted
after preservation. Owner emulator 5554 and `home08b-20261014` were never
touched; every `adb` call named its serial; no Gradle `connected*` task was
run.
