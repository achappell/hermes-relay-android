---
title: 'ANDROID-HOME-02 (slice 2) — List, resume, rename, and continue Home conversations'
type: 'feature'
created: '2026-09-25'
status: 'in-progress'
baseline_commit: 'e8433af'
route: 'dispatch'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-android-home-02-pair-and-connect.md'
  - 'hermes-relay-home docs/contracts/v1/README.md §Personal clients (HOME-NW-17)'
  - 'hermes-relay-home PR #60: POST /api/v1/client-claims/session'
---

## Intent

Slice 1 starts a fresh Home conversation on every connect. This slice lets a paired Profile list its Home conversations (including ones started on other devices), resume one, start a new one deliberately, rename the current one, and continue this phone's last conversation after a restart.

## Decisions (Amanda, 2026-09-25)

- **Conversations sheet.** A "Conversations" item in the header ⋮ menu opens a bottom sheet. Paired Profiles only.
- **Resume keeps the local transcript.** Resuming adds a "Resumed: <title>" divider to this phone's Local History. Earlier messages are never re-sent, and messages from other devices are not shown (Home does not send them).
- **Continue last on launch or Profile switch.** Connect to the conversation this phone last used for that Profile, if Home still has it and no other claim holds it. Otherwise start a new one and say why.
- **Rename** the current conversation from the sheet, using Hermes's advertised `title` command through `command.dispatch`. Offered only when the bridge advertises `title`.
- **Owner approvals** are slice 3.
- **Home change.** A new claim carries no `session_ref`, and the Standard session is bound only after the first accepted turn. Home PR #60 adds `POST /api/v1/client-claims/session` so the phone can learn the reference behind its own claim after a turn completes.

## Boundaries

- Session references are opaque, grant-scoped and non-secret; the last one per grant is kept in the pairing record. No Standard Session ID or Profile ID reaches the phone.
- Switching conversations closes the current claim first (`conversation.close`), then claims the new choice.
- A continue-last fallback is reported, never silent. `session_busy` and `session_unavailable` fall back to a new conversation; other denials stay as they were.
- An uncertain turn is never replayed across a switch or resume.
- Operator-handle Profiles are unchanged.

## Verification

- `./gradlew testDebugUnitTest assembleDebug lintDebug compileDebugAndroidTestKotlin --no-daemon`, `scripts/check-apk-metadata.sh`.
- Emulator against Home: live continue-last needs Home PR #60 deployed; list, resume, new and rename do not.
