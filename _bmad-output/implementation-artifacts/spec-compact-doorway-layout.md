---
title: 'Keep the conversation reachable when header and composer exceed the viewport'
type: bugfix
created: '2026-10-13'
status: done
route: oneshot
baseline_revision: fa96838a0e924a1b95f52fe80cb39880e998c247
review_loop_iteration: 1
context: []
---

<frozen-after-approval reason="unattended implementation authorized by Amanda">

## Intent

The actual API 36 emulator in landscape at normal font size loses the entire conversation area: Scaffold independently measures the header and 280 dp composer, whose combined height exceeds the window. The composer overlaps the profile block and the Ready/status/response rail has zero visible height. This is a concrete reachability defect, not acceptance of the entire HOME-11 matrix.

Make the doorway fill the safe viewport when the three regions fit and scroll as a whole when they do not, while retaining a bounded, scrollable conversation rail and the existing bounded composer. Keep the same header, controls, callbacks, text, state ownership and navigation order; do not move the composer between separate composition branches as the keyboard changes. Respect system/IME insets once. Preserve the existing portrait arrangement whenever it fits.

Given a short landscape window or large fonts, when the screen is displayed and scrolled, then the profile/menu, state, response and composer controls are reachable without overlapping each other. Given an overflowing transcript, when its rail is scrolled, then its final text remains reachable. Given a focused draft, when the IME opens or closes, then editing remains usable without discarding the draft or losing focus through reparenting. Given an ordinary portrait viewport, when the regions fit, then the rail fills the remaining space and the outer layout does not create an unnecessary overflow.

Add behavior/geometry regressions using the real screen's existing test fixtures. Reproduce before changing production layout, verify the changed path on the actual emulator with portrait/landscape, large text and software keyboard, and restore original settings. Do not reset credentials/history, open unattended microphone capture, change runtime/transport/voice policy, merge, deploy, or claim physical Pixel, tablet/Fold or full HOME-11 acceptance.

</frozen-after-approval>

## Implementation Notes

- No unresolved intent gap or irreversible operation. The separate focus and notification PRs remain independent; this branch starts at the same main baseline.
- Reproduced on the installed baseline-layout APK: settled 1920×1080 screenshot showed the composer obscuring the lower header, with no Ready node in a fresh UI hierarchy. Original font scale 1.0, rotation settings 1/0 and hard-keyboard IME setting 0 were captured; rotation was restored after reproduction.
- Reuse `DoorwayHeaderZone`, all existing rail items and the existing action surface. Replace only Scaffold's overlapping measurement policy with a small one-pass layout: measure header/actions, allocate the remaining height to the rail with a readable minimum, and let an outer scroll container expose overflow. Apply safe drawing/IME insets outside that layout, keeping child composition identity stable.
- The new real-screen regression failed against the unchanged layout in 2.968 s:
  `the composer must not obscure the conversation`. It checks actual unclipped
  region geometry, not a source-level Scaffold assertion.
- `DoorwayLayout.kt` measures header and actions once, then gives the rail the
  remaining safe viewport height with a 160 dp reading floor. An outer scroller
  exposes overflow; the lazy rail keeps finite constraints. No resize-driven
  state writes or orientation/IME-dependent reparenting are introduced.
- `MainActivity.kt` retains all three existing region contents and callbacks.
  System/IME padding moves to the layout boundary; old bottom-bar inset calls
  and the Scaffold import/inner padding are removed.
- Actual full-keyboard smoke found a second reachable cause: Android selected
  implicit `adjustPan`, which panned the whole window as Compose also handled
  IME insets. At 2× landscape this put the focused draft under the status bar.
  `dumpsys window` confirmed `sim={adjust=pan}`. MainActivity now explicitly uses
  `adjustResize`; no focus-clearing or delayed-scroll workaround is added.
- The first post-layout suite passed 34 existing tests but exposed two test
  assumptions in the new cases: labeled TextField semantics include the label
  as well as editable text, and `performScrollTo` targets the nearest scroller.
  The regressions now check `EditableText` directly and navigate the outer
  action region before its inner composer, matching the actual nested gesture
  path. Real full-IME scrolling independently reached Send while preserving
  the focused draft at 1×; the 2× resize-policy verification remains in progress.
- The final native check confirmed `sim={adjust=resize}`. With the full keyboard
  open at 2× in landscape, the focused draft occupied `[42,112][1878,344]`,
  wholly above the keyboard and below the status bar. After one scroll, the
  complete Send button was visible and enabled. The same draft survived
  rotation and remained focused/visible above the full portrait keyboard.
- One existing offline-draft test also needed outer action-region navigation
  before scrolling its explanatory message. Its wording, enabled-state and
  retained-draft assertions were preserved; no failing assertion was removed.

## Verification

| Gate | Observed result |
| --- | --- |
| Red-first | The real-screen short-window test failed on the original overlapping geometry in 2.968 s. |
| Build | Full JVM tests, debug app/test APK assembly and lint passed after the final production resize change. The test APK was rebuilt after the last navigation-only test adjustment. |
| Emulator suite | All 36 focused tests passed in 59.175 s: MainActivity, accessibility order, microphone capture/permission and runtime recreation. |
| Metadata | min SDK 26, version 0.3.1 (301), backup/extraction rules and 16 KB alignment passed; expected-signer check explicitly skipped. |
| Actual viewport/IME | Original landscape overlap reproduced; fixed normal portrait/landscape inspected. At 2×, full-IME landscape editing and Send were reachable; portrait editing and draft retention after rotation were inspected. No unattended microphone capture was opened. |
| Actual transcript | Synthetic 30-line Standard reply accepted at 03:57:07.876 and audio drained at 03:58:31.196. The final line was visibly above the composer in portrait, then again after the 03:59:40 Activity recreation into landscape; process 3907 retained the response. |
| Restoration | Ready, original selected profile and empty draft. Font scale 1.0, accelerometer rotation 1, user rotation 0, hard-keyboard IME setting 0, TalkBack off, zero active wake locks. Test APK and 15 local/device captures removed; no main-app wipe or credential/history reset. |

These are targeted emulator checks, not the whole HOME-11 matrix. Physical
Pixel, tablet/Fold, split-screen, unsampled font/viewport combinations and
human audibility remain unverified. The independent focus and notification
changes were not included in this branch's APK.

## Review Triage Log

- Blind production review: no supported issues in measurement, finite rail
  constraints, inset handling, RTL placement or preserved controls.
- Verified medium / deferred automation improvement: the synthetic viewport
  focus regression does not exercise Android's real IME/window policy.
  The required actual-IME acceptance was completed separately on the emulator,
  including reproducing pan and verifying resize at 2×. A deterministic,
  isolated real-window IME fixture remains recorded in `deferred-work.md`;
  no claim is made that the permanent test protects the manifest setting.
