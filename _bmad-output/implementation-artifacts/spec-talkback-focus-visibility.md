---
title: 'Reveal clipped TalkBack focus without moving keyboard focus'
type: bugfix
created: '2026-10-13'
status: done
baseline_revision: fa96838a0e924a1b95f52fe80cb39880e998c247
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
deferred: []
---

<intent-contract>

## Intent

**Problem:** Native emulator TalkBack can focus a completed-turn label partly hidden beneath the composer. Compose's accessibility-focus handler does not reveal the target.

**Approach:** After successful native accessibility focus, ask the original Compose provider to perform its existing Show on screen action for the same target. Preserve both accessibility focus and keyboard focus.

## Boundaries & Constraints

**Always:** Preserve Profile → state → response → action traversal, original provider callbacks, minimum platform scrolling, and lifecycle ownership. Work from the isolated focus-only branch; retain main's existing edits and the separate blocked speech worktree. Emulator-only acceptance is authorized while the Pixel is unavailable.

**Never:** Request keyboard focus, replace runtime/session ownership, change spoken announcements or media notifications, add a global accessibility service, wipe credentials/history, merge, deploy, or claim physical-phone acceptance. This fixes the focus component only; the separately recorded queued-speech defect remains open.

## I/O & Edge-Case Matrix

| Scenario | Input/state | Expected behavior | Error handling |
| --- | --- | --- | --- |
| Clipped short target | Successful accessibility focus on a label that fits the viewport | Full label revealed through the original provider; both focus identities unchanged | Return the original focus result |
| Visible/oversized target | Already visible target or response larger than viewport | Original platform minimum-scroll behavior, same target retained | Do not force another target |
| Unhandled focus | Original provider declines focus or reveal | Do not turn failed focus into success, or successful focus into failure | Reveal result does not replace focus result |
| Lifecycle | Composition leaves or Activity recreates | Restore original delegate only if this wrapper still owns the host | Preserve a delegate installed by another owner |

</intent-contract>

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/MainActivity.kt`: install the effect at the start of `AndroidClientScreen(HomeRuntime, ...)`; do not touch runtime state.
- `app/src/main/java/com/achappell/hermesrelay/AccessibilityFocusReveal.kt`: isolate the provider/delegate wrapper, forwarding node metadata, extra-data and event callbacks.
- `app/src/androidTest/java/com/achappell/hermesrelay/AccessibilityOrderTest.kt`: existing isolated fake client and semantics tests; add the established native-provider focus regression with clipped viewport geometry and focused composer.
- `app/src/androidTest/java/com/achappell/hermesrelay/ConversationRailTestExtensions.kt`: reuse rail scrolling helpers.
- `_bmad-output/implementation-artifacts/validation-5-a-2-accessibility.md`, `story-index.yaml`, `sprint-status.yaml`: attach follow-up evidence without reopening or overstating historical acceptance.
- Read-only evidence: Compose UI 1.12.1's accessibility-focus handler does not scroll, while its Show on screen handler handles scroll ancestors. The separate `talkback-focus-announcements` worktree already demonstrated this regression failing before the bridge and passing after it; speech changes from that worktree are not imported.

## Tasks & Acceptance

**Execution:**
- `AccessibilityFocusReveal.kt` and `MainActivity.kt`: isolate the verified focus bridge, composition-scoped, forwarding all existing behavior and restoring only owned delegates.
- `AccessibilityOrderTest.kt`: isolate the regression and its touch-exploration setup/teardown; exercise real native focus, clipped/visible bounds and unchanged keyboard focus. Existing lifecycle tests and actual recreation cover Activity survival; inspect the provider's failed-action return and ownership guards directly rather than adding forwarding-only mock tests.
- `README.md`, `CHANGELOG.md`, existing accessibility validation and local tracker records: document exercised evidence and explicitly leave the speech and physical-device gates open.

**Acceptance Criteria:**
- Given the emulator's real app has a completed-turn label clipped by the composer, when native TalkBack traversal selects it, then the whole short label is visible above the composer without moving focus to a different control.
- Given the focused composer and a deliberately clipped label in the regression fixture, when the original native provider grants accessibility focus, then the label bounds fit the rail, the label stays accessibility-focused, and the composer stays keyboard-focused.
- Given verification is complete, when restoring the starting emulator setup, then the selected profile/history remain, TalkBack is disabled again, and only the test APK and test-owned temporary captures are removed.

## Spec Change Log

## Review Triage Log

### 2026-10-13 — Review pass

- verdicts: 3 descriptive alignment observations — high 0, medium 0, low 0, false 3, maybe-false 0. Blind, edge-case and verification-gap reviewers returned no actionable findings.
- findings:
  - `[false]` `[reject]` The wrapper applies beyond completed-turn labels — the intent explicitly calls for native Show on screen after successful accessibility focus; applying the existing provider policy to other targets is consistent, not a scope deviation.
  - `[false]` `[reject]` The new test does not exercise every native/lifecycle boundary — the fixture clips against the actual Scaffold rail/composer boundary; the isolated APK also received real TalkBack traversal and recreation checks, and the unchanged bridge's earlier oversized-response check is explicitly reused rather than represented as rerun. Failed-reveal return and replacement-owner guards were directly inspected. No missing mandatory observed result or reachable defect was identified.
  - `[false]` `[reject]` The new regression adds no traversal-order assertions — the existing traversal tests remained unchanged and passed within the 35-test focused run; duplication would add no independent protection.


## Design Notes

The bridge uses Compose's existing minimum-scroll policy rather than estimating screen coordinates or replacing focus handling. Its lifetime follows the Compose host, not the application runtime. This isolated fix does not claim to resolve TalkBack's separate polite-speech queue behavior.

## Verification

- Java 21 and the installed SDK: `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --no-daemon`.
- `scripts/check-apk-metadata.sh`; disclose skipped signing checks.
- Update the main/test APKs with `adb install -r`; run reviewed isolated `AccessibilityOrderTest`, `MainActivityTest`, `MicrophoneCaptureTest`, `MicrophonePermissionTest` and `HomeRuntimeRecreationTest`. Never use connected-test cleanup or credential-clearing tests.
- Exercise the actual app with native TalkBack traversal; inspect short-label focus bounds and Activity recreation. UIAutomator may inspect UI only while TalkBack is disabled. Restore original emulator settings and uninstall only the test package.

### Observed verification

On 2026-10-13 the isolated build passed JVM tests, app/test assembly, lint,
metadata checks and all 35 focused instrumentation tests (51.469 seconds).
The native-provider regression covers clipped and already-visible targets,
declined focus and retained keyboard/accessibility focus. The lifecycle suite
passed and two real Activity recreations retained the response and native
traversal. Actual TalkBack activated a real Standard turn, then revealed its
initially clipped Complete label entirely above the composer; text and audio
drain were observed. Emulator settings, notification flags and test artifacts
were restored without clearing app data. Detailed evidence and precise
limits are in [the accessibility validation follow-up](validation-5-a-2-accessibility.md#2026-10-13--clipped-talkback-focus-follow-up).

## Auto Run Result

**Done for the isolated focus component; not completion of the queued-speech
task or broader Android acceptance.**

- `AccessibilityFocusReveal.kt`: composition-scoped delegate/provider bridge,
  native minimum-scroll reveal, preserved original focus result and ownership.
- `MainActivity.kt`: install the effect once for the conversation Compose host.
- `AccessibilityOrderTest.kt`: native clipped/visible/declined-focus regression
  with both focus identities checked and service flags restored.
- `README.md`, `CHANGELOG.md`: describe the focus behavior and explicit limits.
- `validation-5-a-2-accessibility.md`: exact test and native runtime evidence.
- `story-index.yaml`, `sprint-status.yaml`: additive follow-up references; no
  broad story gate or physical-device acceptance closed.
- This spec: contract, verification and review record.

Review: four independent layers completed; zero patches or deferrals. Three
descriptive intent observations rejected for the reasons above. Patched entries
by verdict: high 0, medium 0, low 0. Follow-up review recommendation: false.

Verification: JVM tests, app/test assembly and lint passed; 35 focused emulator
tests passed; APK metadata passed with signing verification explicitly skipped.
Actual native TalkBack revealed the clipped label, activated a real turn, and
continued traversing after two Activity recreations. The emulator was restored
and test artifacts removed. No tests, builds or code edits were delegated to
the reviewers.

Residual risks: physical and gesture-only accessibility acceptance remains
unverified. Queued obsolete polite speech remains blocked in the separate
unpublished combined worktree; this branch deliberately contains no speech or
notification changes. Main's pre-existing edits were untouched.
