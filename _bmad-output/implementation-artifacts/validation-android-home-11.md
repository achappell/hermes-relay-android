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

| Layer | Status | Why |
| --- | --- | --- |
| **A — synthetic rows** (`DoorwayLayoutFixtureTest`, a `Box` of the logical viewport inside the test window with a `LocalDensity` font override) | **Retired, counted as nothing.** Last full run: 6 of 6 phase tests failed (`layerA/layerA-final-2.txt`) | The synthetic viewport is larger than the host window for most rows (a 914 dp or 1280 dp viewport inside a 640 dp phone window), so the platform's `assertIsDisplayed` reports real, on-screen-sized controls as not displayed. Every failure was against the host window, not the modelled one; the same configurations pass on a real window. The source is preserved at `layerA/retired-source/` and removed from the test tree, so no failing or skipped class ships. |
| **B — the real window** (`DoorwayRealWindowMatrixTest`, `DoorwaySplitWindowMatrixTest`) | **Primary and only counted evidence** | The real `MainActivity` on an owned AVD with that device's native display, real system `font_scale`, real rotation, real navigation mode, the real OS keyboard and real WM Shell split-screen. Only the Home behind the screen is a fixture (`HermesRelayApplication.homeRuntimeBox`). |

The spec's acceptance line asks for a `LocalDensity` + simulated-insets Compose
test per row. That technique is what Layer A was, and it could not represent the
large viewports truthfully on a phone host. The real-window layer meets the
same assertions with real font scale and real insets instead; this substitution
is recorded here for the spec owner rather than presented as the literal
technique.

## 3. What each real-window test asserts

The fixture uses a deliberately long Profile name
(`Alexandra-Cassandra Montgomery-Worthington of the Northern Territories`) and
starts held down: reconnects settle as terminal `Unrecoverable(TransportUnavailable)`,
so the disconnected state is the settled one the user really sees.

| Test | State | Asserted (every check scrolls only the way the user can) |
| --- | --- | --- |
| `real_window_keeps_every_control_reachable_without_the_keyboard` | disconnected, then connected | bars do not overlap (header above rail above composer); doorway state; **Recover** enabled and a real press reaches the client (attempt counted); release fixture, press again → connected; Recover gone; prompt field; tap-to-speak; **menu entries Configure relay, Conversations, Approvals, History, Disconnect all displayed**; Conversations, Approvals and History **each open their sheet**, and Back closes it |
| `real_window_keeps_the_composer_above_the_open_keyboard` | disconnected, real OS keyboard up | OS reports a real IME inset; the prompt field, **Send** and the doorway state are each brought wholly into the part of the window the keyboard leaves, by real finger drags (`input swipe`), with the keyboard still up; bars ordered |
| `real_window_keeps_the_authorization_state_reachable` | Profile verified, authorization unavailable | long Profile name rendered; doorway state; the **four-line status block** (`This Profile is unavailable until relay authorization is verified…`, measured line count logged as `HOME11-WINDOW status-block lines=`); Edit relay; Recover correctly **absent** (`canAttemptClientConnection` blocks it for authorization); prompt; tap-to-speak; menu Configure/Conversations/Approvals/History displayed |
| `real_window_keeps_turn_controls_and_the_whole_transcript_reachable` | connected; a real typed turn | type into the real field, reach and press Send (real drags if the keyboard is up) → turn accepted; turn phase and **Interrupt** (enabled); a 40-line response: its top reachable in the rail, and its **last line** reachable inside the rail and above the composer; then the transport drops mid-turn → **unconfirmed turn, Recover, Resend and Discard** each reachable |
| `split_screen_grants_the_client_half_the_display_and_keeps_the_composer_reachable` | real WM Shell split, disconnected | window ≈ 50 % of the display on the split axis (ratio logged); bars ordered; doorway state; Recover reachable, a real press reaches the client; prompt; tap-to-speak; menu Configure/Conversations/Approvals/History displayed and each sheet opens; real keyboard up inside the split window; field and Send wholly above the keyboard |

Not asserted, by design: `android_stop_capture`/`android_cancel_capture` need a
live microphone (`-no-audio` AVDs; this ticket opens none) — **unverified, not
passed**. Split-screen cells assert the disconnected doorway; the connected
menu's Disconnect entry is asserted in the full-window cells only.

A turn that settles returns focus to the prompt (`ClientRuntimeEffects`), which
raises the real keyboard. The lost-turn controls are asserted after closing it
with a real Back key; with the keyboard up, reachability is the keyboard test's
job. In landscape on the small phone the keyboard leaves ≈ 97 dp of doorway; a
manual probe with real drags showed Send scrolling into that space
(`layerB/manual-ime-landscape/probe-2.png`).

## 4. Device configuration manifest (Layer B)

Every AVD is created new and empty for this ticket, under this worktree's
`evidence/` directory; the owner's emulator 5554, the original AVD/userdata and
`~/Library/Application Support/Hermes Recovery/home08b-20261014` are never
touched. Each `adb` invocation names its serial explicitly.

| AVD | Device profile | Native display | Port | Used for |
| --- | --- | --- | --- | --- |
| `hermes_home11_phone_20261016` | `small_phone` | 720×1280 @ 320 dpi = 360×640 dp | 5556 | R1, R5 |
| `hermes_home11_large_20261016` | `pixel_6` | 1080×2400 @ 420 dpi ≈ 411×914 dp | 5558 | R2, R6 |
| `hermes_home11_tablet_20261016` | `10.1in WXGA (Tablet)` | 1280×800 @ 160 dpi = 1280×800 dp (natural landscape) | 5560 | R3 |
| `hermes_home11_fold_20261016` | `pixel_fold` (inner display) | 2208×1840 @ 420 dpi ≈ 841×701 dp | 5562 | R4 |

Per-row receipts captured: `wm size`, `wm density`, `dumpsys window displays`,
`settings get system font_scale`, `settings get secure navigation_mode`,
`dumpsys input_method` IME insets, and `dumpsys activity activities` for the
split-screen task bounds. Split-screen rows must show **actual multi-window
bounds** (two resumed tasks, the app's task at ≈50 % of the display height) —
`wm size` and a synthetic `Box` do not count.

All four are API 36 `google_apis` arm64 (`sdk_gphone64_arm64` 16 /
`BE2A.250530.026.F3`). AVDs ran sequentially to avoid host contention and were
deleted at the end; the evidence stays (section 9).

Per-cell driver configuration (`run-real-window-matrix.sh`): navigation mode by
`cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.<gestural|threebutton>`;
`settings put system font_scale`; rotation by `accelerometer_rotation 0`,
`wm user-rotation lock <r>` **and `wm fixed-to-user-rotation enabled`** (a
portrait-locked launcher otherwise keeps a phone at ROTATION_0 — see section 7);
a receipt of the display size after rotation per cell.

## 5. Results — manifest cells (commit-candidate APK)

All rows below come from one run of the final test APK on all four AVDs
(`real/<avd>/final-real-commit/`, `real/<avd>/split-rerun-commit/`). Each
full-window run executes the four real-window tests; each split run executes
the split test. A run passes only when every test in it passes.

| Viewport row | AVD / real window | Runs (nav 2 × font 3 × orientation 2) | Tests | Result |
| --- | --- | --- | --- | --- |
| R1 small phone 360×640 dp | phone, 720×1280 @ 2.0 | 12 / 12 OK | 48 / 48 | **Pass** |
| R2 large phone ≈411×914 dp | pixel_6, 1080×2400 @ 2.625 | 12 / 12 OK | 48 / 48 | **Pass** |
| R3 tablet 10" | 10.1" WXGA, 1280×800 @ 1.0 | 12 / 12 OK | 48 / 48 | **Pass** |
| R4 Fold inner | pixel_fold, 2208×1840 @ 2.625 | 12 / 12 OK | 48 / 48 | **Pass** |
| R5 split, small phone | real WM Shell split on the phone AVD | 12 / 12 OK | 12 / 12 | **Pass** |
| R6 split, large phone | real WM Shell split on the pixel_6 AVD | 12 / 12 OK | 12 / 12 | **Pass** |

Logical cells (viewport × font × IME × orientation × navigation): every
full-window run covers IME off (tests 1, 3, 4) and IME up (test 2); every split
run covers IME off then IME up. **144 of 144 cells pass; 0 fail; 0 unexecuted.**

| | Pass | Fail | Unexecuted |
| --- | --- | --- | --- |
| R1–R4 (4 × 3 fonts × 2 IME × 2 orientations × 2 nav) | 96 | 0 | 0 |
| R5–R6 (2 × 3 × 2 × 2 × 2) | 48 | 0 | 0 |
| By font 1.0 / 1.3 / 2.0 | 48 / 48 / 48 | 0 | 0 |
| By IME off / up | 72 / 72 | 0 | 0 |
| By orientation portrait / landscape | 72 / 72 | 0 | 0 |
| By navigation gesture / three-button | 72 / 72 | 0 | 0 |

Receipts that make the cells real rather than nominal:

- **Rotation.** Every landscape full-window run logs a landscape window
  (`HOME11-WINDOW … window=1280x720` small, `2400x1080` large, `1280x800`
  tablet, `2208x1840` Fold) — 24 landscape lines per AVD. Each cell's
  `receipts-<cell>.txt` records the display size after rotation.
- **Font scale.** `HOME11-WINDOW … fontScale=` matches the driver's setting in
  every run.
- **Keyboard.** The OS-reported IME inset is logged per run, e.g. 478 px small
  landscape, 883 px large portrait.
- **Split.** Portrait phone splits are the spec's 50 %-height cells:
  small `window=720x630 of 720x1280` (ratio 0.49), large `1080x1187 of
  1080x2400` (0.49). In landscape Android splits side by side, so those cells
  are **half-width** windows (small `630x720`/`678x720`, large
  `1123x1080`/`1186x1080` of the rotated display); they pass, and are recorded
  as half-width rather than relabelled as half-height.
- **Four-line status block.** Measured line counts on the small phone:
  portrait 3 / 4 / 6 lines at 1.0 / 1.3 / 2.0; landscape 2 / 2 / 3. The block
  reaches four or more lines at 1.3× and 2.0× in portrait.
- Screenshots: `<cell>-start.png` / `-end.png` per run, plus
  `split-ime-up` captures; device profile `native-profile-<avd>.txt`.

### Beyond the manifest

| Extra cells | Result |
| --- | --- |
| Fold inner in split (12 runs) | 12 / 12 pass (window `1091x1840 of 2208x1840`) |
| Tablet in split, natural landscape (6 runs) | 6 / 6 pass (window `635x800 of 1280x800`) |
| Tablet in split, rotated to portrait (6 runs) | **6 / 6 fail — harness, unresolved.** The granted window is `800x635` at y=277 of an 800×1280 display; four real taps on the prompt never raise the IME (`DoorwaySplitWindowMatrixTest.kt:257`). The keyboard-free part of the same runs reached every asserted control before the IME step. Not a product claim either way; not counted. |

## 6. Findings

1. **No product layout defect was found.** No production file changed.
2. **Retraction (2026-10-16).** An earlier hand-off recorded "Recover is
   unreachable in landscape" as a product finding. It was wrong: the fixture
   had auto-connected, and while connected `ConnectionRecoveryZone` correctly
   renders no Recover (`!isConnected && canAttemptConnection`). With a held-down
   fixture, Recover is composed, enabled and pressable in the landscape rail.
3. **A settled turn raises the keyboard.** `ClientRuntimeEffects` returns focus
   to the prompt when a turn settles, so after a lost turn the real keyboard
   opens. On a 360×640 phone in landscape that leaves ≈ 97 dp of doorway above
   the keyboard; the composer and Send are still reachable there by real drags
   (`manual-ime-landscape/probe-2.png`). Recorded as product behaviour, not as
   a defect, for the spec owner.
4. **Existing test ordering defect (not caused here).** The full non-live
   instrumentation suite on the final APK: **105 run, 104 pass, 1 fail** —
   `ModalSheetBackTest.back_dismisses_only_the_approvals_sheet`. The failure
   needs only pre-existing classes to reproduce: `HomeRuntimeRecreationTest`
   followed by `ModalSheetBackTest` fails the same way, while
   `ModalSheetBackTest` alone and after `AccessibilityOrderTest` passes. Any
   earlier `MainActivity` in the process makes its first Back miss the sheet.
   Left unchanged (out of scope); `final/control-*-modal.txt` hold the runs.
5. `AccessibilityOrderTest` passes unchanged (10 / 10) on the final APK.

### Harness defects found and fixed along the way (test code only)

- A portrait-locked launcher kept phones at ROTATION_0 despite a locked user
  rotation; phone "landscape" runs before `wm fixed-to-user-rotation enabled`
  were portrait and are superseded (`real/*/final-real`, `final-real-v4`,
  `split-rerun` and `split-rerun-rotfix` directories are not counted).
- Reachability is measured against the doorway's own scroll viewport, which
  ends above a real keyboard, not against the window root.
- Lazy rail regions are rewound by index; the displayed copy of a recycled
  lazy item is the one checked.
- In a split the companion task can own input focus: the test gives its own
  window focus with a real tap on its header and uses a real Back key.
- Each real-window class restores `HermesRelayApplication.homeRuntimeBox` and
  ends its fixture runtime, and closes the keyboard, so later classes start
  clean.

## 7. Gates (final tree)

| Gate | Result | Log |
| --- | --- | --- |
| JVM `testDebugUnitTest --rerun-tasks` | 57 suites, 784 tests, 0 failures / errors / skips (XML archived) | `gates/commit/jvm.log`, `gates/commit/jvm-xml/` |
| `assembleDebug lintDebug assembleDebugAndroidTest` | BUILD SUCCESSFUL | `gates/commit/assemble-lint.log` |
| `scripts/check-apk-metadata.sh` | "APK metadata is correct: min SDK 26, version 0.3.1 (301), backup off with extraction rules, 16 KB aligned, signing skipped (EXPECTED_SIGNER_SHA256 unset)." | `gates/commit/apk-metadata.log` |
| `scripts/run-flake-gate.sh AndroidDoorwayStateTest,AndroidRecoveryControllerTest,AndroidAvailabilityTest,HomeRuntimeReconnectTest 1` | 1 consecutive run, 0 failures | `gates/commit/flake-gate-N1.log` |
| Full non-live instrumentation (emulator-5556) | 105 run, 104 pass, 1 pre-existing order failure (finding 4) | `final/full-instrumentation-commit.txt` |
| APK identity | the gate build's debug and androidTest APKs are byte-identical (SHA-256) to those installed for the counted matrix run | `layerB/commit-apk-sha256.txt` |

## 8. Open gates and limits

- **Physical Pixel gate — open.** The spec's 2.0× and keyboard screenshots on
  a physical Pixel were not taken; no device was available. Every result here
  is an emulator AVD with a device's native profile, not a physical claim.
- No live Home, pairing, microphone or audio claim: the Home behind every
  screen is the in-memory fixture.
- Capture-only controls (stop/cancel capture) unverified (no microphone).
- The connected menu's Disconnect entry is asserted in full-window cells only;
  split cells assert the disconnected menu.
- Tablet-in-portrait split with the keyboard: unresolved harness failure
  (section 5), outside the manifest.
- `DoorwayMatrix.kt` still carries a dead `layout_viewport` lookup and two
  comments from the retired synthetic layer. They were left in place so the
  committed source rebuilds the exact APKs the counted matrix ran (Gradle
  reports the build up to date against the same SHA-256s); removing them is a
  safe follow-up.

## 9. Evidence location

Evidence is kept outside the repository at
`~/Library/Application Support/Hermes Recovery/home11-20261016/` (screenshots,
per-cell receipts, logcat, instrumentation output, gate logs, JUnit XML,
retired Layer-A source, driver scripts `run-real-window-matrix.sh`,
`run-avd-config.sh`, `probe-split-screen.sh`). Paths in this record are
relative to that directory. The four owned AVDs were deleted after the
evidence was preserved. Owner emulator 5554, the original AVD/userdata and
`home08b-20261014` were never touched; every `adb` call named its serial; no
Gradle `connected*` task ran; no credentials or state were copied.
