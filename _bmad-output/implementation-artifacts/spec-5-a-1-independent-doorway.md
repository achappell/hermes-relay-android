---
title: '5-A-1 — Independent Android conversation doorway with Local History'
type: 'story'
story_id: '5-A-1'
created: '2026-09-12'
status: 'done'
route: 'dispatch'
baseline_commit: '091ae50'
context:
  - '_bmad-output/planning-artifacts/android-ios-parity-audit.md'
  - '../hermes-relay-ios/_bmad-output/implementation-artifacts/spec-5-1-ios-independent-conversation-doorway.md'
---

## Intent

**Problem:** `5-A-1` requires Android to be a complete independent doorway:
typed and tap-to-speak turns, response audio, phase state, recovery, secure
profile storage, and **per-profile Local History**. Everything but the last had
shipped across `A-4` through `A-9`. Local History did not exist at all — no
transcript was persisted, so every conversation vanished with the process.

**Approach:** Audit the delivered capability against the story, then build the
one missing piece. Deliberate Local History: text the household chose to keep,
scoped to one Profile, bounded, and clearable.

## Boundaries & Constraints

**Always:** Keep one Profile's conversation in its own store; record a turn
when submitted and an answer when the turn settles; keep a partial answer from
an interrupted turn; bound retention; delete a Profile's history with the
Profile; allow clearing.

**Never:** Persist audio, PCM, or a credential; record speculatively; let one
Profile's history reach another; record without a selected Profile; grow
without limit; share history with another Client.

## Delivery audit

| Story requirement | Where it lives | Status |
|---|---|---|
| Typed doorway | `A-7` `beginTurn`, Compose composer | Delivered |
| Tap-to-speak doorway | `A-9` capture and on-device transcription | Delivered |
| Response audio | `A-8` `AudioTrackAudioSink` | Delivered |
| Phase state | `A-2` reducer, projected from live events by `A-7` | Delivered |
| Recovery | `A-3` bounded ladder, no replay | Delivered |
| Secure profile storage | `A-4` Keystore credential store | Delivered |
| Interrupt | `A-5` capability-gated | Delivered |
| **Per-profile Local History** | — | **Built here** |

## Acceptance Criteria

- Given a submitted turn and a settled answer, then both are kept for the
  selected Profile and survive a restart.
- Given two Profiles, then each keeps its own conversation and neither can see
  the other's.
- Given no selected Profile, then nothing is recorded.
- Given a blank or repeated entry, then it is not recorded.
- Given more entries than the retention limit, then the oldest fall away and
  the newest are kept.
- Given a Profile is deleted, then its conversation is deleted with it.
- Given the user clears Local History, then the conversation is removed.
- Given an unreadable history file, then it degrades to an empty conversation.
- Given any recorded conversation, then no audio or credential is serialized.

## Code Map

- `AndroidLocalHistory.kt` — entry model, bounded history, per-Profile store,
  file and in-memory implementations, and the recorder.
- `RelayProfileStore.kt` — profile deletion now deletes history too.
- `MainActivity.kt` — opens history for the selected Profile, records both
  sides of an exchange, renders it, and offers clearing.

## Implementation Notes

- **Retention is bounded on purpose.** An unbounded transcript is a privacy
  liability that grows on its own, so the history keeps a fixed number of
  recent entries. The limit is a constant rather than a hidden behavior.
- **History follows the Profile, not the app.** Switching Profiles opens that
  Profile's conversation; deleting a Profile deletes its history alongside its
  credential, so nothing outlives the identity that produced it.
- **An interrupted turn keeps its partial answer**, because that is what was
  actually said.
- **A repeated identical entry is treated as a re-render**, not a second
  utterance, so recomposition cannot duplicate a conversation.
- A test asserts the serialized form contains no `token`, `pcm`, `audio`, or
  `credential` text, so the boundary is enforced rather than described.

## Verification

See `validation-5-a-1-independent-doorway.md`.

## Closure

Android is an independent doorway with a conversation that persists. Epic 5's
Android surface is complete apart from the local design and brand tickets the
parity audit records separately.
