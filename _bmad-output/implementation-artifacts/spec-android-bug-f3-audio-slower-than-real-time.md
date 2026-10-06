---
id: ANDROID-BUG-F3
title: Find why response audio arrives slower than real time
status: backlog
product_epic: 1
release_scope: later
parity_epic: ANDROID-PARITY-01
parity_stream: S2
created: 2026-10-06
depends_on: []
source_defect: 'android-open-defects.md ANDROID-BUG-F3 (2026-09-12 Pixel 6a session)'
---

# ANDROID-BUG-F3 — response audio arrives slower than real time

Ticket for the open defect recorded in `android-open-defects.md` as `ANDROID-BUG-F3`. Nothing in this ticket is new product scope.

## Background

During the first real-device session (Pixel 6a, live relay, 2026-09-12) `AudioFlinger` reported `BUFFER TIMEOUT ... due to underrun` four times in a single response, each followed by a track restart. Playback sounded correct, so the effect was not audible, but the underruns are what made the drain guard unsatisfiable. PR #25 fixed the drain guard by other means; **the underlying streaming rate was left untouched**. The defect file marks it as the same territory as Puck `P-5` (TUI repository).

## Boundary with `ANDROID-HOME-09`

`ANDROID-HOME-09` owns playback cushion, audio focus, route changes and track restart, and its spec already says to re-measure these underruns on a device after the cushion change. This ticket owns the question `ANDROID-HOME-09` cannot answer by tuning the cushion: **is the audio stream itself delivered slower than it plays?** If it is, a larger cushion hides the symptom and the cause belongs to the sender (Home speak stream, relay or network) or to client read/decode throughput.

## Required behavior

1. Measure, on a physical device, the arrival rate of reply audio against its playback rate for several typical replies, using content-free figures only (byte counts, chunk intervals, underrun counts; never audio, text or identifiers). Journal lines from `ANDROID-DIAG-01` are the intended record when it has landed.
2. Attribute the cause to one of: Home or relay delivery rate, network, client read or decode throughput, or the sink's write pattern.
3. If the cause is client-side, fix it. If it is Home-side, record the finding with its evidence and raise it with the Home owner as a Home story; do not edit the Home tracker from this repository.

## Acceptance criteria

- A short measurement record (device model, build revision, reply lengths, underrun counts before and after any change) is committed as a validation record.
- Either the underruns are gone across the measured replies, or the record names the owning component and a Home-owned story exists for it.
- The record separates JVM evidence from physical-device evidence; the emulator runs `-no-audio` and is not accepted.

## Dependencies

None hard. Do this together with or after `ANDROID-HOME-09` so the cushion change is not measured twice; `ANDROID-DIAG-01` makes the evidence cheaper but is not required.

## Release scope decision (2026-10-06)

`later`. The underruns are not audible and `ANDROID-HOME-09`, which is a migration ticket, already re-measures them after the cushion change; the open question here is the stream's own rate. Promote to `migration` if the measurement shows audible gaps.
