---
title: '5-A-2 — Android accessibility order and focus restoration'
type: 'story'
story_id: '5-A-2'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: '3014d04'
context:
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
  - '../hermes-relay-tui/_bmad-output/planning-artifacts/epics.md'
---

## Intent

**Problem:** `UX-DR21` names the Android obligation explicitly — accessibility
order `Profile → state → response/Transcription → action` with focus
restoration. It was the clearest defect the parity audit found: a written
requirement naming this surface, with no story owning it and **zero**
`semantics` or `contentDescription` calls in the app. A screen-reader user
could not use the doorway at all.

**Approach:** Give every element an explicit traversal band, announce changing
state through live regions, mark section labels as headings, and restore focus
to the composer when a turn settles.

## Boundaries & Constraints

**Always:** Expose the required reading order regardless of visual order;
announce state changes without interrupting; announce failures assertively;
mark section labels as headings; return focus to the composer when a turn
settles.

**Never:** Reorder the visual layout to achieve the reading order; announce
every text change assertively; claim conformance that was not measured.

## Acceptance Criteria

- Given the doorway is rendered, then assistive technology reads Profile, then
  state, then response and Transcription, then actions.
- Given connection, phase, or capture state changes, then it is announced
  politely.
- Given an audio-unavailable, disconnected, or capture failure notice appears,
  then it is announced assertively.
- Given section labels, then they are exposed as headings.
- Given a turn reaches a terminal state, then focus returns to the composer.

## Code Map

- `MainActivity.kt` — the `A11yOrder` bands, the `a11yOrder`/`a11yHeading`
  modifiers, live regions on state and response elements, and the focus
  restoration effect.
- `AccessibilityOrderTest.kt` — reads the real semantics tree.

## Implementation Notes

- **Traversal index rather than layout change.** Every element is a direct
  child of one traversal group, so an explicit `traversalIndex` per element
  produces the required reading order without moving any UI. The visual order
  and the reading order are allowed to differ, and here they must.
- **Polite versus assertive is a deliberate split.** State changes are polite
  so they do not interrupt a response being read; failures are assertive
  because acting on them is the user's next step.
- **Focus restoration is narrow.** Focus returns to the composer only when an
  accepted turn reaches a terminal state, so the user is not stranded at the
  end of the reading order after an answer arrives.

## Verification

See `validation-5-a-2-accessibility.md`, including what was *not* measured.

## Closure

`UX-DR21`'s Android obligation is implemented and tested against the real
semantics tree. Contrast and reduced-motion conformance, and a real
TalkBack pass, remain explicitly unverified.
