---
id: ANDROID-WATCH-F1
title: Verify recovery and interruption while hands-free is armed with no turn binding
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S3
created: 2026-10-06
depends_on: []
source_defect: 'android-open-defects.md ANDROID-WATCH-F1 (2026-09-12 Pixel 6a session)'
---

# ANDROID-WATCH-F1 — `Listening` with no binding

Ticket for the open watch item recorded in `android-open-defects.md` as `ANDROID-WATCH-F1`. Nothing in this ticket is new product scope.

## Background

Since PR #25, a hands-free reopen sets the turn phase to `Listening` before any turn exists. The phase can therefore be non-terminal while `binding` is null. Nothing reads `binding` in that window today, and the `A-3` recovery ladder keys off connection state, but the defect file says recovery and interruption while hands-free is armed should be checked here first.

## Required behavior

1. Enumerate every reader of the turn phase and `binding` and every path that can run while the phase is `Listening` with a null `binding`: connection loss and the `A-3` ladder, `A-5` interrupt, user stop, microphone capture ending or failing, Activity stop and destroy, and (once they exist) the `ANDROID-HOME-06` backgrounded-transport close and the `ANDROID-HOME-08` background teardown.
2. Pin the intended behavior of each path with a JVM test: no null dereference, no stale non-terminal phase left over after the path completes, the microphone is released, and no turn is replayed.
3. Fix any path the tests show to be wrong. If a path is correct but fragile, make it explicit rather than leaving it to the current absence of a reader.

## Acceptance criteria

- JVM tests cover each enumerated path while hands-free is armed with no turn.
- On a physical device: arm hands-free, then lose and regain the connection, press interrupt, stop, background and foreground the app, and deny or revoke the microphone; the phase ends in a truthful terminal or ready state each time and the microphone indicator clears.
- Re-run the device check after `ANDROID-HOME-07`, `ANDROID-HOME-08` and `ANDROID-HOME-14` land, because they move where the phase and `binding` live; record the result in the validation record of whichever ticket lands last, or in this ticket's own record.

## Dependencies

None hard. Re-verify after `ANDROID-HOME-07`, `ANDROID-HOME-08` and `ANDROID-HOME-14`.

## Release scope decision (2026-10-06)

`migration`. Hands-free (`A-6`) is part of the migration voice conversation, and an armed microphone with an inconsistent phase is a correctness and privacy risk rather than polish.
