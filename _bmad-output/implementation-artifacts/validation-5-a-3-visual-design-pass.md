---
title: 'Validation — 5-A-3 Night Console visual design pass'
type: 'validation'
created: '2026-09-12'
status: 'in-progress'
story: '5-A-3'
baseline_commit: 'edd4c41'
context:
  - '_bmad-output/implementation-artifacts/android-visual-design-pass.md'
  - '_bmad-output/implementation-artifacts/validation-5-a-2-accessibility.md'
---

# Validation — 5-A-3 Night Console visual design pass

**The story is not done.** Steps 1–6 of the approved sequence are delivered and
code-verified; step 7 remains. This record exists so the next session starts
from what was actually proven rather than from what the commits imply.

## Delivered

### Step 1 — decomposition (`190976e`)

`AndroidClientScreen` went from 893 lines and one composable to a state owner
calling seven zones in `DoorwayZones.kt`. Structural only.

`5-A-2` is a settled requirement this refactor could have silently regressed,
so both of its guarantees were diffed against the previous revision: all 23
`testTag` values and every `a11yOrder`/`a11yHeading` band with its
`LiveRegionMode` are identical.

### Steps 2 and 3 — Night Console palette and state roles (`c675e06`)

Every palette value replaced. `identity` and `unavailable` map onto Material's
`primary` and `error`; `live` and `attention` have no near-enough slot and are
carried in `HermesStateColors` via `LocalHermesStateColors` rather than forced
into one.

The light adaptation does not reuse the dark state colours. `#62E6C7` live and
`#FFCF5C` attention cannot reach 4.5:1 as text on a light surface, so each has
a darkened same-hue variant measured at 5.21–5.22:1 on the lightest surface.
A first derivation landed them at 4.51–4.54:1 and was redone for headroom: a
value clearing AA by 0.01 fails the next time anything about rendering changes.
The target was never lowered.

Contrast coverage went from 16 pairs to 56.

### Step 4 — Material 3 shell and action hierarchy

`AndroidClientScreen` now uses a `Scaffold` with a fixed `TopAppBar`, a
scrolling `LazyColumn` conversation rail, and a bottom action surface. The
profile/connection identity stays visible while the rail owns the boundary,
recovery, live turn, configuration, and Local History content.

When a Profile is selected, the live doorway appears before the configuration
form in the rail, so relay setup no longer pushes the conversation below the
fold. When no Profile is selected, configuration remains near the top so the
first-run path is still reachable without inventing a separate screen.

The action surface keeps the typed composer and voice controls together, adds
IME/navigation-bar protection, and caps its own height with an internal scroll.
Primary send/reconnect/stop actions remain filled; tap-to-speak, interrupt, and
resend use outlined treatment; hands-free, cancel, discard, history, and share
use text treatment. Relay profile save is now a filled-tonal action rather than
the strongest filled button on the doorway.

The existing test tags and accessibility traversal bands were preserved. A new
instrumentation assertion covers the fixed header and action surface markers.

### Step 5 — state hierarchy

The doorway now has one explicit stable state owner for idle and recovery:

- No Profile selected shows `Configure a relay to begin` and a primary
  `Configure relay` action. The configuration form is visible on the first-run
  path.
- A selected but disconnected or unauthorized Profile shows `Unavailable`,
  the affected connection/authorization path, and recovery actions. `Retry`
  reconnects only; `Edit relay` opens configuration without replaying a turn.
- A connected idle Profile shows `Ready`; the bottom action surface remains the
  owner of the dominant `Tap to speak` action.
- Permission and recognizer gates also resolve to `Unavailable`, while the
  typed path remains available. An accepted turn or live capture owns its own
  phase label, so a stale `Ready` state cannot cover active work. A retained
  unconfirmed turn remains unavailable after reconnect until the user chooses
  to resend or discard it.

The old bootstrap header copy was also replaced with neutral conversation copy;
it no longer contradicts a verified Profile or a live Session. The pure state
resolver has unit coverage, and the Compose fixtures cover no-Profile,
disconnected recovery, and connected idle states.

### Step 6 — composer, history, voice controls, and motion

The typed composer now resolves an explicit blocking reason instead of asking a
disabled button to explain itself through opacity. A selected, authorized
Profile may keep editing while disconnected; the draft is persisted through the
per-Profile history store and the UI says `Saved locally — connect before
sending.` Send remains disabled with a separate plain-language reason. Prompt
recall is grouped under the labelled `Recent prompts` affordance and remains
hidden until history exists.

Local History now opens in a native Material bottom sheet using the calm panel
surface. The sheet keeps the Profile scope visible, renders role and time as
secondary metadata, keeps the newest entry at the bottom, gives response text
the visual centre, and moves both export formats behind one `More` menu. Clear
remains a secondary text action.

Active microphone capture and response playback have a restrained activity
indicator alongside their readable phase label. The indicator reads the three
system animation scales; when Android has animations disabled it renders a
static shape and no looping transition. Streaming response text and partial
transcription are no longer live regions, so TalkBack announces phase changes
once rather than narrating every frame. Capture, phase, error, and connection
state labels retain their explicit live-region semantics.

### Emulator regression follow-up — 2026-09-13

The first host-audio emulator run recorded 23 of 34 non-live instrumentation
tests passing and 11 Compose-node failures. Investigation found three separate
testability defects rather than a microphone failure: the IME and fixed
composer hid nodes in the lazy conversation rail, `clearAndSetSemantics` erased
the voice-activity test marker, and Local History did not recompose after a
clear because its recorder is not observable state. The tests now scroll the
actual rail, close the IME before rail assertions, and keep the indicator
decorative without erasing its marker; the history item is keyed by its
revision so a clear refreshes the sheet.

### Device-found defect — container slots colliding (`edd4c41`)

The first hardware pass showed the boundary card's plain informational text
rendered in unavailable pink. Material resolves a container's content colour by
matching the container value against each scheme slot, so `surfaceVariant` and
`errorContainer` both holding `PANEL` made every ordinary `Card` resolve to
`onErrorContainer`.

**Measured contrast could not have caught this, and it is worth understanding
why.** Every pair involved was individually fine — pink on panel is 7.66:1. The
defect was the mapping, not a ratio. Light mode was unaffected because its two
slots already differed, so the appearance that looked correct was correct by
accident.

## Verified

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon --console=plain` — green.
- The unit-test reports contain 150 tests with zero failures or errors,
  including composer blocking, animation-scale, and doorway-state coverage.
- `./gradlew assembleDebugAndroidTest --no-daemon` — green; instrumentation
  sources and the shell/state assertions compile.
- `scripts/check-apk-metadata.sh` — min SDK 26, 0.3.1 (301).
- The release-signed app and test package were reset, then the ordinary debug
  main and test APKs were installed over paired ADB Wi-Fi on a Pixel 6a
  running Android 17. Both APKs carry the standard Android Debug certificate;
  no 1Password-backed release secrets were used for this install.
- Installed release-signed on a Pixel 6a via `scripts/install-release.sh`,
  captured in both appearances, defect confirmed fixed on screen.
- The API 36 `hermes-relay-api36` emulator was started without `-no-audio` and
  with `-allow-host-audio`; its boot log explicitly confirmed host microphone
  forwarding. A dummy `relay.invalid` profile rendered the unavailable state,
  and the capture action stayed disabled because no live Session existed.
- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest --rerun
  --no-daemon --no-configuration-cache --console=plain` ran 34 non-live tests
  on the emulator alone with 34 passed after the regression fixes above. The
  run was forced to the emulator because the Pixel 6a also remained visible to
  ADB but was behind its secure keyguard.
- The same 34 non-live tests passed with the emulator at `font_scale=1.3` and
  all three Android animation scales set to `0`. The AVD was restored to
  `font_scale=1.0` and normal animation scales afterward.

Each new guard was verified by regression rather than trusted because the suite
was green:

| Guard | Broken by | Result |
|---|---|---|
| `the_light_state_roles_are_darkened_rather_than_exempted` | reverting light `live` to the bright dark value | fails |
| `every_text_pair_meets_wcag_aa_for_body_text` | same | fails |
| `both_appearances_and_every_state_role_are_actually_covered` | dropping the light appearance from the measured set | fails |
| `container_slots_with_different_ink_do_not_share_a_value` | restoring `errorContainer` to `PANEL` | fails |

## Not verified

- **The Pixel connected instrumentation suite did not produce a usable UI
  verdict.** `connectedDebugAndroidTest` reached the Pixel 6a over ADB Wi-Fi
  and ran 34 tests: 7 passed and 27 failed with `No compose hierarchies found in
  the app`. The handset was behind its secure lock screen at the manual
  checkpoint, and Gradle removed the target package after the run, so no clean
  unlocked rerun or Pixel UI verdict is accepted here.
- **A green emulator suite is not a real-microphone verdict.** The
  host-audio-enabled emulator completed 34 non-live tests, and its boot log
  confirms host microphone forwarding, but no live relay profile was loaded.
  The emulator therefore did not produce a real recognizer transcript through
  a Hermes Session.
- **End-to-end host-microphone capture is not verified.** Host audio forwarding
  is enabled, but the manual profile used an unresolved endpoint, so the app
  correctly refused to open capture. No real recognizer transcript was
  produced.
- **TalkBack** has still never been exercised on this surface. `5-A-2`'s
  environment limitation is untouched by this work.
- **Hardware font scaling** remains unverified. The emulator now covers
  `font_scale=1.3` with animations disabled, including a 34/34 instrumentation
  run and a static screenshot; the Pixel's rendered result — including whether
  the fixed header's intentional ellipsis is acceptable at that viewport —
  still needs observation.
- **Steps 4–6 have not been captured on hardware.** The fixed header, scrolling
  rail, bottom action surface, button hierarchy, explicit idle/recovery states,
  composer persistence, history sheet, activity indicator, and reduced-motion
  rendering are verified by compilation and test artifacts only in this
  environment; the debug APK is installed, but the locked handset prevented a
  manual visual pass.
- **Unattended UI input is conditional.** The ADB Wi-Fi pairing is retained and
  can drive the app while the handset is unlocked and reachable. Android's
  secure keyguard currently blocks UI automation; no attempt was made to
  bypass it.
- **The light adaptation was seen once**, before the container fix. The
  post-fix capture is dark only. Light mode is believed correct — its slots
  never collided — but that is inference, not observation.

## Remaining sequence

Step 7 of the approved design pass remains:

7. Re-verify TalkBack order and focus restoration on hardware, closing the
   `5-A-2` environment limitation in the same pass.

## What the hardware pass showed about the design

The palette is working. Step 4 now addresses the structural problems the design
pass diagnosed, but the result has not yet been captured on hardware:

- The fixed header and bottom action surface keep the conversation doorway
  present while the configuration rail scrolls independently.
- Profile and authorization now live in the compact Material header rather than
  as unstyled lines between boundary copy and the form.
- `Save relay profile` now uses filled-tonal treatment.
- The doorway now names `No Profile selected`, `Unavailable`, and `Ready`
  directly instead of asking connection state, authorization, and disabled
  controls to imply the answer.

The next hardware pass must confirm those changes at the Pixel viewport and at
large font scaling.

## Resolved finding — `ANDROID-BUG-F4`

Step 5 replaced the stale `Android Client bootstrap` / "transport is not
connected yet" copy with `Hermes conversation` and a neutral description of the
typed and spoken doorway. The header no longer makes a connection claim that
can contradict the state card below it. A hardware re-capture is still needed
for the rendered result, but the content defect is fixed in code.
