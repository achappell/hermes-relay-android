# AGENTS.md

Native Android client for the Hermes voice-session channel. This repository
owns the `A-*` surface stories; it does not own product planning.

## Planning and delivery ownership

This repository owns Android story identity and delivery status. In the same
pass as the code, update the local records:

- **Story identity and scope** —
  `_bmad-output/implementation-artifacts/story-index.yaml`.
- **Delivery status** —
  `_bmad-output/implementation-artifacts/sprint-status.yaml`.
- **Specifications and validation** — the local `spec-*` and `validation-*`
  artifacts under `_bmad-output/implementation-artifacts/`.

The sibling TUI `epics.md` and coverage matrix are imported context for
applicability, evidence, and dependencies. An Android status transition does
not require an edit to those files, another repository's tracker, or the
product hub. Update the private product hub only when implementation changes
durable shared intent or a cross-surface decision; update the coverage matrix
only when applicability, ownership, evidence, or a shared dependency changes.

`_bmad-output/implementation-artifacts/android-open-defects.md` is a local
convenience index. Keep Android-only findings here and in the local validation
record; do not copy them into a sibling repository's status tracker.

## Delivery record in this repository

Story specifications and validation artifacts live in
`_bmad-output/implementation-artifacts/` as `spec-a-*` and `validation-a-*`.
Record what was actually verified and what was not. A verdict of
`done-with-environment-limitation` is honest and preferred over a clean `done`
that overstates the evidence.

Keep wrong diagnoses in the record when they were informative. A fix that
changed nothing is evidence, and deleting it invites the next reader to repeat
the theory.

## Issue tracking

The configured GitHub issue tracker is active for Android-owned story work.
Keep issue changes scoped to this repository and preserve the stable Android
story identity. The local story index, specification, validation record, and
`sprint-status.yaml` remain authoritative for delivery scope and status.
GitHub Project #3 is a mechanical mirror maintained through the coordinator
procedure; mirror only accepted Android-owned records from this repository.

## Worktrees

All linked feature and agent worktrees for this repository belong under
`.worktrees/<name>` inside the repository's main checkout. Keep `.worktrees/`
ignored and do not create sibling `*-worktrees` directories or use a global
tool-specific worktree location. BMAD loop-managed run worktrees under
`.bmad-loop/runs/<run>/worktrees/` are engine-owned and remain there.

## Verification

```
./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon
scripts/check-apk-metadata.sh
```

Instrumentation tests need a device or emulator. Live-relay tests are excluded
by the `@LiveRelay` annotation; overriding `notAnnotation` with an empty value
does not clear the default and silently runs zero tests.

## What only a real device finds

The emulator has no microphone and runs `-no-audio`. Deterministic fakes cannot
exercise the microphone, the speaker, real network permissions, or real
recogniser timing. Every defect found on 2026-09-12 — a missing `INTERNET`
permission, a silent debug-key signing fallback, an underrun-stranded audio
drain, and a terminal phase displayed over a live microphone — passed both the
unit suite and the live gate first. Treat a green suite as necessary, not
sufficient, and do not dismiss a device-only surprise as a flake.

## Release

Releases are signed with a developer keystore; see `README.md` for the four
repository secrets and `scripts/setup-release-signing.sh`. `versionCode` is
derived from the version name, so both advance together — never pin it.
