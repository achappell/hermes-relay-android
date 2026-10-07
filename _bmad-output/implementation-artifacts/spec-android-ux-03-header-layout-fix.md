---
title: 'ANDROID-UX-03 header font-scale layout fix'
type: 'bugfix'
created: '2026-10-07'
status: 'in-progress'
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

## Review Triage Log

- Blind-hunter review (2026-10-07) found the UX-03 spec and story index referenced a missing validation record. Accepted; added `validation-android-ux-03.md` and recorded the verified local build plus outstanding device/CI gates.
