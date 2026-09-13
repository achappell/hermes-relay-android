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

**The story is not done.** Steps 1–3 of the approved sequence are delivered and
verified; steps 4–7 are not started. This record exists so the next session
starts from what was actually proven rather than from what the commits imply.

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

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — green.
- `scripts/check-apk-metadata.sh` — min SDK 26, 0.3.0 (300).
- Instrumentation sources compile.
- Installed release-signed on a Pixel 6a via `scripts/install-release.sh`,
  captured in both appearances, defect confirmed fixed on screen.

Each new guard was verified by regression rather than trusted because the suite
was green:

| Guard | Broken by | Result |
|---|---|---|
| `the_light_state_roles_are_darkened_rather_than_exempted` | reverting light `live` to the bright dark value | fails |
| `every_text_pair_meets_wcag_aa_for_body_text` | same | fails |
| `both_appearances_and_every_state_role_are_actually_covered` | dropping the light appearance from the measured set | fails |
| `container_slots_with_different_ink_do_not_share_a_value` | restoring `errorContainer` to `PANEL` | fails |

## Not verified

- **The instrumentation suite has not been run**, `AccessibilityOrderTest`
  included. It needs a device, and the identical-bands diff is strong evidence
  for the traversal order but is not the same as executing it. This should run
  before the branch merges.
- **TalkBack** has still never been exercised on this surface. `5-A-2`'s
  environment limitation is untouched by this work.
- **Font scaling** at large accessibility sizes is unmeasured.
- **The light adaptation was seen once**, before the container fix. The
  post-fix capture is dark only. Light mode is believed correct — its slots
  never collided — but that is inference, not observation.

## Remaining sequence

Steps 4–7 of the approved design pass, none started:

4. `Scaffold` structure — header, rail, action surface — and action hierarchy
   via tonal and outlined button variants.
5. State hierarchy: no-Profile, disconnected, unavailable, ready.
6. Composer, history, and voice controls, including reduced-motion behaviour.
7. Re-verify TalkBack order and focus restoration on hardware, closing the
   `5-A-2` environment limitation in the same pass.

## What the hardware pass showed about the design

The palette is working. The structural problems the design pass diagnosed are
untouched, and the screenshots make them plain:

- The relay configuration form consumes the entire first screen. The
  conversation doorway — the point of the app — is below the fold.
- Profile and authorization are two unstyled text lines between boundary copy
  and a form.
- `Save relay profile` is the most prominent control on screen.

Step 4 is what addresses all three.

## Open finding, outside this slice

The header reads `Android Client bootstrap` / "The native Android surface is
alive, but Hermes session transport is not connected yet" while the same screen
shows `Authorization: Verified` and a configured, selected Profile. The copy
appears to predate working transport. It is the first thing a person reads and
it contradicts a line three rows below it.

This is a content defect rather than a visual one, so it was not fixed here.
Logged as `ANDROID-BUG-F4`.
