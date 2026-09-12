---
title: 'Validation — 5-A-1 independent Android doorway with Local History'
type: 'validation'
story_id: '5-A-1'
created: '2026-09-12'
status: 'done'
---

## Scope

Validate per-Profile Local History: recording both sides of an exchange,
persistence across a restart, Profile isolation, bounded retention, clearing,
deletion with the Profile, and the audio/credential exclusion. The rest of the
story was audited as already delivered.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21. 102 unit tests, 0 failures (10 new); debug APK
  assembled; lint clean.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed on
  `hermes-relay-api36` (Android 16 / API 36). 24 instrumentation tests, 0
  failures, 0 skipped (3 new).
- Live gate — 3 tests, 0 failures, unchanged by this slice.
- `scripts/check-apk-metadata.sh`, `git diff --check` — passed.

## Evidence notes

- Persistence is proven by writing through one `FileAndroidHistoryStore` and
  reading through a **new** one over the same directory, which stands in for the
  next app launch rather than asserting an in-memory value.
- Profile isolation is proven twice: separate files on disk, and a recorder
  switching Profiles seeing only that Profile's entries.
- Bounded retention asserts both ends — the oldest entry is gone and the newest
  is present — so the limit cannot pass by truncating the wrong end.
- `no_audio_or_credential_is_ever_serialized` scans the serialized form for
  `token`, `pcm`, `audio`, and `credential`. The boundary is enforced by a test
  rather than only described in prose.
- Profile deletion is asserted to remove both the credential and the history,
  so neither outlives the identity that produced it.
- The Compose test drives a real exchange to completion, asserts both sides are
  kept and written to the store, then clears and asserts both the UI and the
  store are empty.

## Deferred

- Search, export, and editing of Local History are not implemented. Transcript
  export remains a local ticket in the parity audit for both surfaces.
- The draft is persisted in the history record but is not yet restored into the
  composer on launch; only the conversation is displayed.
- No physical-device pass; the same standing limitation as the other stories.
