---
id: ANDROID-UX-08
title: Copy conversation, per-message copy and selectable transcript text
status: backlog
product_epic: 1
release_scope: migration
parity_epic: ANDROID-PARITY-03
parity_stream: S8
created: 2026-10-06
depends_on:
  - android:ANDROID-UX-04
parity_source: 'PX-25 (matrix C7)'
github_issue: https://github.com/achappell/hermes-relay-android/issues/104
---

# ANDROID-UX-08 — Copy, select and share polish

Source: PX-25 (matrix C7) (`android-ios-parity-audit.md`, 2026-10-06; PX numbers are cross-references only). Priority P2, size S.

## Background

iOS offers Export (Copy, Share text, Share Markdown) and per-message Copy/Share. Android has plain/Markdown export through `ACTION_SEND` (`TranscriptExporter`) but no copy anywhere.

## Android today (checked against `main` unless marked [INFERENCE])

- No `ClipboardManager`/`SelectionContainer` use (grep); `TranscriptExporter` shares only through the share sheet.

## Required behavior

- Copy conversation, long-press per-message menu (Copy, Share), and `SelectionContainer` for transcript text, using `ClipboardManager` with the Android 13+ system confirmation (no extra toast there).
- Copied text is exactly what export would share; transcripts keep the existing forbidden-substring guarantees.

## Acceptance criteria

- `TranscriptExportTest` extended: copy output equals export output and contains none of `token`, `wss://`, `session`, `bearer`, `pcm`.
- Long-press menu reachable by TalkBack custom actions.

## Android design notes

- Mark clip data as sensitive (`ClipDescription.EXTRA_IS_SENSITIVE`, API 33).

## Dependencies

`ANDROID-UX-04`.

## Test notes

JVM and one instrumented test.

## Device verification

Pixel: copy a message, paste elsewhere, TalkBack action.
