---
title: 'Android local tickets — design, brand, export, prompt history'
type: 'tickets'
created: '2026-09-12'
status: 'done'
baseline_commit: '091ae50'
context:
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
  - '_bmad-output/implementation-artifacts/validation-5-a-2-accessibility.md'
---

The parity audit proposed four items as **local tickets** rather than upstream
story identities, matching how iOS tracks `IOS-DESIGN-F1` and `IOS-BRAND-F1`.
All four are delivered here.

`ANDROID-DESIGN-F1` was delivered at a narrower scope than its name implies.
The visual design work that remains is the upstream story `5-A-3`, not this
ticket.

## `ANDROID-DESIGN-F1` — deliberate visual design pass

**Status:** done, and narrower than the parity obligation. Superseded for the
remaining work by the upstream story `5-A-3`.

This ticket delivered a palette, a contrast test, and dark/light wiring. It did
not touch layout, component structure, or the shared Night Console state
language, so closing it did not achieve design parity with iOS — a fact the
ticket's own title oversells. See the note at the end of this section.

The app shipped on the stock Material baseline scheme. It now has a deliberate
palette defined as ARGB values in `ui/theme/Palette.kt`, wired through
`HermesRelayTheme` for light and dark.

The colours live as plain numbers rather than Compose `Color` values for a
reason: it makes the contrast obligation **measurable by an ordinary unit
test**. `PaletteContrastTest` computes WCAG 2.2 relative luminance and asserts
every text pair the surface renders — sixteen of them, light and dark — meets
the 4.5:1 AA threshold for body text.

**This closes the contrast gap `5-A-2` explicitly left open.** That validation
record said "nothing here measured a contrast ratio". Now something does, and a
palette change that breaks readability fails the build.

Dynamic colour is deliberately not used: a wallpaper-derived scheme would
replace verified values with unverified ones at runtime.

The test suite includes a guard that a known-bad pair is actually caught, so
the check cannot pass vacuously.

### What this ticket did not do — now `5-A-3`

The palette is a light-first Material scheme. iOS ships **Night Console**: a
dark-first system whose four semantic roles — live, attention, identity,
unavailable — carry Hermes' state language across every surface. Android has
none of those roles, so Android state is expressed through generic Material
roles that cannot distinguish a healthy live signal from pending work from a
failed identity.

The whole Android UI is also a single 893-line `AndroidClientScreen` composable
with no component structure, so there is no spacing scale, type ramp, or
reusable state surface for good colour tokens to land in.

Both are cross-surface parity obligations rather than local polish, which is
why the remainder was promoted to the upstream identity `5-A-3` on 2026-09-12
rather than reopening this ticket.

## `ANDROID-BRAND-F1` — app icon

**Status:** done.

The app had no icon at all — not a placeholder, but nothing, so the launcher
fell back to the system default.

An adaptive icon now ships: three chevrons of decreasing weight reading as
transmission, drawn inside the 66dp safe zone so no launcher mask clips them,
on the palette's primary. A `monochrome` layer is included for themed icons.

The mark is deliberately simple. A detailed emblem turns to mud at launcher
size, and this one stays legible small.

## `ANDROID-UX-F1` — transcript export

**Status:** done.

`TranscriptExporter` renders Local History as plain text or Markdown, shared
through the standard Android share sheet.

Export carries only what Local History holds — the text of an exchange. A test
asserts no `token`, `wss://`, `session`, `bearer`, or `pcm` string can appear in
either format, because a shared transcript travels further than the device it
came from.

## `ANDROID-UX-F2` — prompt history

**Status:** done.

`AndroidPromptHistory` gives the composer shell-style recall: bounded to 50
entries, stepping back through submitted prompts, and stepping forward to
restore the half-typed draft the user was writing before they started
navigating. Blank and repeated prompts are not recorded.

### A bug this surfaced

Wiring recall exposed that **the composer was never cleared after sending**. A
sent prompt lingered in the box, reading as unsent, and made recall useless
because the field was never empty to recall into. The composer now empties when
a turn is accepted.

That changed a `2-A-2` test's premise: it asserted a cached-draft label after
submitting, but there is no longer an unsent draft at that point. The test now
types something new during the outage, which is the behaviour the label
actually describes.

## Verification

- `./gradlew testDebugUnitTest assembleDebug lintDebug` — 150 unit tests, 0
  failures; APK assembled; lint clean.
- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest --rerun
  --no-daemon --no-configuration-cache` — 34 non-live instrumentation tests, 0
  failures on the API 36 emulator started with host-audio forwarding. The
  emulator result does not close the TalkBack or real-session microphone
  limitations.
- The same 34 non-live instrumentation tests also pass at `font_scale=1.3`
  with Android animation scales disabled; the AVD was restored to its normal
  settings afterward. Hardware rendering and TalkBack remain open.
- A lint error was fixed rather than suppressed: `context.getString` inside the
  share action is not configuration-aware, so the string is now resolved in
  composable scope.

## Still open from `UX-DR21`

Contrast is now measured, but two obligations from the `5-A-2` record remain:

- **No screen reader has been run.** TalkBack is still unexercised.
- **Reduced motion** is implemented in `5-A-3` Step 6: the active voice
  indicator freezes and looping motion is omitted when Android's animation
  scales are disabled. Hardware observation remains part of Step 7.
