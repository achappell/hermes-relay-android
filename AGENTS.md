# AGENTS.md

Native Android client for the Hermes voice-session channel. This repository
owns the `A-*` surface stories; it does not own product planning.

## Planning lives in the TUI repository

`hermes-relay-tui` holds the cross-surface planning record. Android work is not
finished until that record matches it. In the same pass as the code:

- **Story identity** — `_bmad-output/planning-artifacts/epics.md`. A story is
  not real until it is written there. Delivering ahead of that gate leaves the
  record self-contradictory; on 2026-09-12, `A-4`–`A-9` and `5-A-2` had shipped
  and validated here while appearing nowhere in `epics.md`.
- **Board status** —
  `_bmad-output/implementation-artifacts/sprint-status.yaml`.
- **Cross-surface claims** —
  `_bmad-output/implementation-artifacts/surface-coverage-matrix.md`, which goes
  stale the same way.
- **Deferred work** —
  `_bmad-output/implementation-artifacts/deferred-work.md`. Every finding that
  is real but outside the current slice goes here, under a `## Deferred from:`
  heading in the existing `source_spec` / `summary` / `evidence` shape.
  `source_spec` may name a specification in this repository. This is the single
  cross-surface record; a finding logged only here is invisible to planning.

`_bmad-output/implementation-artifacts/android-open-defects.md` in this
repository is a convenience index, never a substitute for the entry above.

## Delivery record in this repository

Story specifications and validation artifacts live in
`_bmad-output/implementation-artifacts/` as `spec-a-*` and `validation-a-*`.
Record what was actually verified and what was not. A verdict of
`done-with-environment-limitation` is honest and preferred over a clean `done`
that overstates the evidence.

Keep wrong diagnoses in the record when they were informative. A fix that
changed nothing is evidence, and deleting it invites the next reader to repeat
the theory.

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
