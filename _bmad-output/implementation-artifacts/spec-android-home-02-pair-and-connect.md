---
title: 'ANDROID-HOME-02 (slice 1) — Pair Android with a Home and connect through a client claim'
type: 'feature'
created: '2026-09-24'
status: 'in-progress'
baseline_commit: '003b94a'
route: 'dispatch'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/course-correction-2026-09-23.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-android-home-02.md'
  - 'hermes-relay-home docs/contracts/v1/README.md §Personal clients (HOME-NW-17, Home 0d345be)'
  - 'hermes-relay-ios spec-ios-home-02-pair-and-connect.md (approved sibling slice)'
---

## Approval state

Drafted and implemented autonomously overnight on 2026-09-24 at Amanda's request ("go as far as you can in the android app") and merged in PR #56. Amanda reviewed the decisions on 2026-09-25: she confirmed one saved Profile per grant and reversed the no-scanner decision.

## Intent

**Problem:** Android Home mode needs an operator-supplied Device credential and a disposable conversation handle. The handle expires about 90 seconds after issue if unopened, so a paired Android client cannot hold a conversation on its own. HOME-NW-17 now provides a pairing link, `client_claim` enrollment, `client_grants`, renewal and `POST /api/v1/client-claims`.

**Approach:** Pair from a `hermes-home://pair?home=…&code=…` link or a typed short code plus Home address. Submit a `client_claim` enrollment, show the confirmation code, and poll consume until approval. Store one Keystore credential per pairing, renew it automatically, expose one saved Profile per active grant, and make a fresh client claim (`session: new`) on every `conversation.open`. Session listing/resume and owner approvals are deferred ANDROID-HOME-02 work.

## Decisions (reviewed by Amanda, 2026-09-25)

- **One saved Profile per grant (confirmed).** One pairing per Home holds a single Keystore credential, keyed by pairing, not by Profile. Every active grant appears as its own saved Profile named `<grant label> · <Home host>`, with its own Local History. The pairing credential is deleted when the Home's last Profile is deleted.
- **In-app QR scanner (reversed 2026-09-25).** Slice 1 shipped without one, relying on the system camera opening `hermes-home://` links through the intent filter. Amanda chose an in-app scanner to match iOS. Built on 2026-09-25 with CameraX and ZXing (see validation): it needs a camera permission and a scanning dependency, it must accept only `hermes-home://pair` payloads, and it must fall back to link or typed entry when the camera is denied or unavailable. The intent filter stays.
- **Local transcript continuity.** A fresh Home session on an existing Profile keeps local history visible; earlier messages are never sent to Hermes. The iOS "New conversation" divider is deferred for Android (see Deferred).

## Boundaries & Constraints

**Always:**
- Enrollment: `type` `android`; `requested_rooms` `[]`; `requested_capabilities` `["client_claim"]`; `secure_storage` `platform_secure_store`; `endpoint_id` a stable per-install, per-Home UUID so pairing again replaces the previous generation.
- The Home address is `https` with a host name, no credentials, query or fragment. The bridge route is `wss://<same authority>/api/v1/bridge/ws`.
- Credentials, handles, and codes stay out of logs, Profile JSON, Local History and diagnostics. `grant_id` and `device_id` are non-secret and live in the pairing record.
- Renew inside the 14-day window before claiming.
- Send `conversation.close` on explicit close, Profile switch, and Profile deletion (best-effort).
- An uncertain turn is never replayed. A reconnect within the grace period uses the existing `conversation.reconnect` on the held handle.
- Operator-handle Home Profiles and ANDROID-HOME-01 Device administration behave exactly as before.

**Never:** show, store or send a Profile ID or Standard Session ID; send a Room, wake mapping or acoustic evidence on a claim; switch mode or fall back after a Home outage; build session listing/resume, owner approvals/holders, remote unpair, or Standard-only mode (ANDROID-STD-01); change a sibling repository; claim physical or live acceptance that was not run.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected behavior | Error handling |
|---|---|---|---|
| Link opened | Valid `hermes-home://pair` URL | Configuration sheet opens prefilled; request submitted; confirmation code shown | Malformed or non-https link: specific message, nothing submitted |
| Typed code | Code (any case, dash optional) plus https Home address | Same as link | — |
| Waiting | consume `409 approval_pending` | "Waiting for approval on the Home page"; poll every 2 s until `expires_at`; cancellable | — |
| Rejected / expired | `403 rejected`, `410 expired_or_consumed` | Terminal message; start again | No credential stored |
| Approved | `200` with credential and `client_grants` | One Keystore credential; pairing saved; one Profile per active grant; `pending_owner` grants reported as waiting for the owner | Keystore failure: nothing saved |
| Connect | Paired Profile, active grant | Renew if due → device configuration revision → claim `session: new` → `conversation.open` | `stale_configuration`: refresh once and retry |
| Claim denied | `grant_pending`, `profile_unavailable`, `client_claim_unavailable`, `claim_limit`, `session_*` | Specific unavailable state; no retry loop | — |
| Credential invalid | `401` or expired | Unauthorized state ("pair again") | No retry loop |
| Route pin | First `ready` after pairing | Route ID recorded; a later different route fails closed | ConversationMismatch |

## Code Map

- `HomeClientPairing.kt` (new): link/code parsing, wire client over the existing `HomeHttpTransport`, pairing record store, pairing coordinator, and claim provider.
- `RelayProfile.kt`: optional `homeClientGrant` reference `(pairingId, grantId)` in Profile JSON.
- `OkHttpRelaySessionClient.kt`: a paired Profile's binding is claimed per open and held in memory; close sends `conversation.close`.
- `RelayConfigurationScreen.kt`, `MainActivity.kt`, `AndroidManifest.xml`: pairing UI, `hermes-home` intent filter, wiring.

## Deferred (still ANDROID-HOME-02 scope)

Session list/resume/rename, owner approvals and holder lists, remote unpair, the "New conversation" divider, and physical/live acceptance against a deployed HOME-NW-17.

## Verification

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon`
- `scripts/check-apk-metadata.sh`
- Physical: pair a Pixel against deployed Home from a QR link and complete a typed turn. Not yet run; see `validation-android-home-02.md`.
