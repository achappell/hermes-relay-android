---
title: 'ANDROID-UX-03 header font-scale layout fix'
type: 'bugfix'
created: '2026-10-07'
status: 'review'
route: 'oneshot'
review_loop_iteration: 1
context:
  - _bmad-output/implementation-artifacts/spec-android-ux-03-session-header.md
  - _bmad-output/implementation-artifacts/spec-android-home-11-hud-scroll-layout.md
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The existing conversation header truncates its title/profile at font scale 1.0 and loses title, subtitle, and overflow-menu access at 2.0 with a long profile name; the same defect is visible on the pre-change baseline.

**Approach:** Reflow the existing header responsively so title and profile text never overlap, and keep its labelled, clickable overflow menu visible and usable at font scales 1.0 and 2.0 in light and dark themes. Limit the change to this header reachability slice of ANDROID-UX-03; preserve its current actions and traversal order, do not edit #129 or implement unrelated UX-03 card features, and deliver a separate PR without merging it.

</frozen-after-approval>

## Implementation Notes
- Intent gaps: none; the required scales, themes, menu behavior, and PR boundary are specified by the user.
- Irreversibles: none; the change is presentational and must not mutate pairing, profile, history, or conversation data.
- Footprint: `DoorwayZones.kt`, a focused instrumented regression/screenshot test, the parent UX-03 spec and validation record, and `CHANGELOG.md`.
- Initial code map: `DoorwayHeaderZone` placed the profile block in `TopAppBar.actions` beside the title/description. The fix reuses its existing `A11yOrder` modifiers, menu semantics/tag, and `DropdownMenu`; non-header runtime, Home, and #129 stay out of scope.
- Implementation: a dynamically measured, status-bar-inset-aware `Surface` replaces the fixed-height app bar; title, description, and profile stay in a weighted vertical column with the existing accessible menu as a trailing action. The instrumented regression uses synthetic content across the 1×/2× and light/dark matrix and captures closed/open-menu screenshots off-repo.

## Review disposition — 2026-10-07

The header reachability slice in PR #134 is ready for review (`review`), not done or fully TalkBack verified. Existing local, Pixel 6a layout/semantics and automated-order evidence is preserved in `validation-android-ux-03.md`; final-head CI is recorded in the PR. Metadata-only closeout is explicitly exempt from bmad-build; no implementation or device scenarios are reopened.

Amanda accepted the waiver on 2026-10-07: manual physical TalkBack spoken-output/focus-gesture acceptance is waived. Actual spoken output and focus gestures remain unverified, with known residual spoken-label/focus-order/gesture risk. This supersedes the earlier unwaived blocking gate, not the evidence limitation.

The parent ANDROID-UX-03 story remains `in-progress`: status dot, timer, title tap, settings, Disconnect placement and the full ANDROID-HOME-11 scale/scroll matrix are outside this agreed slice and are not PR #134 blockers. No other acceptance gates are waived.


## Review Triage Log

- Blind-hunter review (2026-10-07) found the UX-03 spec and story index referenced a missing validation record. Accepted; added `validation-android-ux-03.md` and recorded the verified local build plus outstanding device/CI gates.
