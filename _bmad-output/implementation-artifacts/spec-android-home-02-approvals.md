---
title: 'ANDROID-HOME-02 (slice 3) — Approve owner requests and see who holds a Profile'
type: 'feature'
created: '2026-09-25'
status: 'in-progress'
baseline_commit: 'e948135'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/spec-android-home-02-conversations.md'
  - 'hermes-relay-home docs/contracts/v1/README.md §Profile grants (HOME-NW-17)'
---

## Intent

A grant to an owned Profile stays `pending_owner` until a device that already holds that Profile approves it. This slice lets the Android phone see and decide those requests, and see every device holding its Profiles.

## Decisions (Amanda, 2026-09-25)

- **Where:** a "Devices & approvals" item in the header ⋮ menu opens a sheet. When a request is waiting, a banner on the conversation screen says so and opens the sheet.
- **When to check:** on connect, whenever the app returns to the foreground, and when the sheet opens. No background polling; push notifications are a later epic.
- **Confirmation:** Approve and Decline are one tap on a card showing the device name, type, Profile, and request time. Removing a device that holds a Profile asks first, because it ends that device's conversations.

## Contract as implemented by Home (differs from the NW-17 prose)

- `GET /api/v1/profile-grants/pending` → `{"pending": [...]}` and `GET /api/v1/profile-grants/holders` → `{"holders": [...]}`. Items carry `grant_id`, `device_label`, `device_type`, `profile_label`, `status`, `bootstrap`, `this_device`, `created_at`.
- `POST /api/v1/profile-grants/{grant_id}/approve|reject|revoke` with `{"schema": 1}`. The spec's `pending_id` is `grant_id` in the running code.
- `unauthorized` or `forbidden` on these actions means "not this Profile's owner", not a bad credential, so the phone shows "not allowed" and never "pair again". `not_found` or `expired_or_consumed` means the request already expired or was decided.

## Boundaries

- Only other devices get a Remove action; this phone does not remove itself here.
- The phone never sees device IDs or Profile IDs.
