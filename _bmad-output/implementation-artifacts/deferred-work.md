## Deferred from: code review of spec-5-a-3-night-console-validation.md (2026-09-14)

- Hardware acceptance is partially verified on the Pixel 6a: the 37-test
  non-live instrumentation run, default/130% dark configuration rendering,
  rail scrolling, and force-stop/relaunch check passed. Spoken TalkBack linear
  order and selected/live-turn focus restoration are deliberately deferred by
  product decision; the host suite and APK checks cannot settle those
  device-only claims.
- The Android navigation follow-up is implemented: configuration and Local
  History now open from the conversation header menu instead of rendering in
  the home rail. The unlocked post-change connected run passed all 37 non-live
  tests, and the installed build showed the menu and configuration sheet.

### Manual TalkBack traversal and focus restoration

- **Decision:** Defer the manual TalkBack spoken-navigation and
  selected/live-turn focus-restoration pass for the current family-only
  audience. This is a priority decision, not a claim that the behavior passed.
- **Evidence retained:** TalkBack bound on the Pixel 6a, produced TTS synthesis
  and accessibility focus for the overflow menu and configuration sheet, but
  injected traversal did not establish spoken order and no selected/live turn
  was available for post-turn focus restoration.
- **Reopen when:** the audience expands, an accessibility requirement is added,
  or a TalkBack user becomes a target.

## Deferred from: code review of spec-android-home-01-pairing-and-configuration-adapter.md (2026-09-20)

- Confirm the Home-issued credential shape and generation invariant against the Home issuer contract before changing Android validation.
- Decide whether an administrative credential must be bound to the approved Home route; the Android artifact does not define route-change semantics.
- Define an idempotent remote-consume or recovery contract for a one-time credential consumed before local secure storage succeeds; this crosses the Home API boundary.
