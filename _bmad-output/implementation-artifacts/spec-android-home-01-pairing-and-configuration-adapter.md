---
title: 'Add the Android Home pairing and configuration adapter'
type: 'feature'
created: '2026-09-19'
status: 'done'
route: 'dispatch'
review_loop_iteration: 1
baseline_commit: 'b7cce51f26dfd7d689b42dc3c70fbf29f06803cf'
validation: '_bmad-output/implementation-artifacts/validation-android-home-01-pairing-and-configuration-adapter.md'
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-android-home-pairing-adapter.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Android can store an approved Home bridge binding and device credential, but it has no typed Device-administration flow for enrollment, Home-owned Room/Wake Mapping configuration, or the distinction between configured, ready, stale, revoked, and expired states.

**Approach:** Add a full Home-backed lifecycle adapter and Compose state flow: side-effect-free discovery, explicit offer/request/approval/consume, revocation and renewal, re-enrollment, and revisioned configuration. Home remains authoritative: admin Bearer access is only for household administration, device-scoped access is only for the bridge and device configuration, and the existing Profile and local history identity are preserved.

## Boundaries & Constraints

**Always:** Discovery is side-effect free; explicit approval is the only trust transition; admin and device credentials use separate secure slots; secrets never enter Profile JSON, UI state, logs, or bridge requests; Ready requires valid Home authorization, ordered configuration, and verification.

**Never:** Treat discovery as authorization, create a second Android configuration authority, send an admin credential to the bridge, auto-merge stale revisions, silently re-enroll after revocation, or replace the Profile/history identity during migration.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Discovery | Device observed or manually identified | Identity only; no credential, approval, or write | Invalid/unavailable discovery is typed and leaves storage unchanged |
| Approval | Approved enrollment returns one-time device material | Store the opaque device credential in Keystore and update the existing Profile binding; retain history; remain not-ready until setup completes | Malformed material or secure-write failure leaves the prior binding intact |
| Fetch | Approved Home route and admin credential | Decode `/api/v1/configuration` into a revisioned Rooms, Profiles, Wake Mappings, and Devices snapshot | 401/invalid response/transport failure leaves the active configuration unchanged |
| Publish | Valid snapshot plus `expected_revision` | PUT the complete Home-owned snapshot; adopt the returned revision only after a successful response | 409 surfaces a stale-revision state and requires reload; no local merge |
| Revocation or expiry | Home rejects device authorization | Mark the device unavailable and require explicit re-enrollment plus ordered setup before Ready | No automatic credential replacement or turn submission |
| Renewal or re-enrollment | Explicit lifecycle action with the current request/generation | Store only the fresh device material, preserve Profile/history identity, and return to setup-pending | Invalid generation, expired offer, or failed secure write leaves the old state unavailable |

</frozen-after-approval>

## Code Map

- `app/src/main/java/com/achappell/hermesrelay/RelayProfile.kt` -- non-secret Profile and Home binding metadata.
- `app/src/main/java/com/achappell/hermesrelay/RelayProfileStore.kt` -- identity-preserving migration and configuration controller.
- `app/src/main/java/com/achappell/hermesrelay/RelayCredentialStore.kt` -- Keystore-backed credential slots; currently separates Home device and rollback secrets.
- `app/src/main/java/com/achappell/hermesrelay/OkHttpRelaySessionClient.kt` -- Device-authenticated Home bridge and readiness gate.
- `app/src/main/java/com/achappell/hermesrelay/MainActivity.kt` and `RelayConfigurationScreen.kt` -- Compose entry points for the new ordered flow.
- `src/hermes_home/api/application.py` and `src/hermes_home/domain/configuration.py` -- existing HTTP and snapshot contract; reference only.

## Tasks & Acceptance

**Execution:**
- [x] `app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt` -- add typed Home transport, payloads, route normalization, auth separation, and error mapping.
- [x] `RelayCredentialStore.kt`, `RelayProfile.kt`, `RelayProfileStore.kt` -- model admin/device lifecycle, revision, and Ready prerequisites without leaking secrets or changing identity.
- [x] `MainActivity.kt`, `RelayConfigurationScreen.kt` -- expose discovery, explicit approval, ordered Room/Wake Mapping setup, conflict handling, and unavailable states.
- [x] `app/src/test/java/com/achappell/hermesrelay` -- cover wire shapes, secure transitions, stale revisions, revocation/expiry, and identity preservation.

**Acceptance Criteria:**
- Given a discovered Device, when it is displayed, then no authorization or credential write occurs.
- Given explicit approval, when enrollment completes, then the opaque device credential is protected, the existing Profile/history remain, and Ready is still false until setup verifies.
- Given a Home snapshot, when a valid edit publishes with its revision, then Home returns the new revision and Android reflects it; a stale revision never overwrites Home.
- Given revoked, expired, or re-enrolled credentials, when the bridge responds, then Android shows unavailable and requires the explicit lifecycle path before Ready.
- Given a user turn, when the bridge is active, then Android consumes Home arbitration results and never contacts the Standard service directly.

## Implementation Notes

- Added a typed Home enrollment, configuration, revocation, renewal, and re-enrollment controller. Discovery is local-only; Profile metadata is written only after lifecycle transitions.
- Kept Home admin, Home Device, and rollback credentials in separate encrypted slots. Non-secret lifecycle metadata includes revision and credential expiry; Profile and history IDs remain unchanged.
- Required an approved Home bridge binding for administration, gated bridge readiness on valid Home lifecycle state, and close the active bridge when Device credentials change.
- Ready now requires a successful revisioned publish plus matching Device wake-mapping verification. Stale writes, malformed material, expiry, revocation, and storage failures remain typed and visible.
- Added transport contract tests, controller lifecycle tests, Profile deletion coverage, bridge readiness coverage, and a mounted Compose-surface test. Hardware Compose execution was attempted; the connected device reported no Compose hierarchy for the existing suite, so that result is recorded as an environment limitation rather than green UI evidence.

## Spec Change Log

## Review Triage Log

- B01 — verdict `medium`; route `patch` — The remembered controller could retain one Profile's state while the selected Profile changed. The controller is now keyed by selected Profile and rejects in-flight cross-Profile use.
- B02 — verdict `high`; route `patch` — Falling back to the editable legacy endpoint could send Home admin credentials to an unapproved service. Administration now requires `homeBinding.approvedRoute`.
- B03 — verdict `medium`; route `patch` — Admin credentials and enrollment codes used saved-instance state. They now use transient Compose state, the admin field clears after secure save, and the code is masked.
- B04 — verdict `low`; route `patch` — The one-time enrollment code was visible as plain text. The field is now masked.
- B05 — verdict `medium`; route `patch` — HTTP request headers and bodies had generated `toString()` output. Request and response representations now redact secrets and bodies.
- B06 — verdict `medium`; route `patch` — Approval trusted any parsed response. The controller now requires matching request identity, approved status, and an approved scope.
- B07 — verdict `high`; route `patch` — Credential expiry was parsed but neither enforced nor retained. Material is rejected when expired and the non-secret expiry is persisted and checked by the bridge gate.
- B08 — verdict `high`; route `patch` — Ready previously checked only the revision. Device wake mappings are now checked against Home's published mappings before Ready.
- B09 — verdict `medium`; route `patch` — A non-advancing publish response could be accepted. The production client now requires a strictly newer revision.
- B10 — verdict `medium`; route `patch` — Duplicate wake-mapping and Device identifiers were not rejected. Snapshot validation now rejects both.
- B11 — verdict `medium`; route `patch` — A successful 204 revoke response was parsed as JSON and could skip local cleanup. Empty successful responses are now accepted.
- B12 — verdict `high`; route `patch` — Local revocation could restore a credential Home had already revoked when Profile persistence failed. The Device credential now remains deleted.
- B13 — verdict `high`; route `patch` — The bridge ignored the new lifecycle phase. Profiles with administration state are unavailable until Ready, including expired credentials.
- B14 — verdict `medium`; route `patch` — Home's generic credential conflict code was classified as stale configuration. It now has a distinct `Conflict` reason; only `revision_conflict` maps to stale revision.
- B15 — verdict `false` — The context path names the pre-approval intent source, not the new spec itself; it is intentionally retained as provenance.
- V01 — verdict `medium`; route `patch` — Profile deletion cleanup lacked admin-slot verification. The existing deletion test now asserts the Home admin slot is removed.
- V02 — verdict `medium`; route `patch` — Production POST/PUT lifecycle routes were not exercised. A transport-seam contract test now covers offer, submit, approve, consume, revoke, renew, rotate, fetch, publish, and Device configuration authorities.
- V03 — verdict `medium`; route `patch` — No test drove a raw HTTP 409 through error mapping. The controller test now supplies a real transport 409 and asserts StaleRevision.
- V04 — verdict `medium`; route `patch` — Only publish failures were tested. A successful publish test now asserts the returned revision, Ready phase, persisted revision, and matching Device verification.
- V05 — verdict `medium`; route `patch` — The Home Compose surface had no boundary test. An instrumentation test mounts it and drives discovery; hardware execution was blocked by the device's no-hierarchy condition shared by existing Compose tests.
- V06 — verdict `medium`; route `patch` — The saved-state secret concern is the same confirmed issue as B03; it is fixed by the transient-state and masking changes above.
- V07 — verdict `medium`; route `patch` — Discovery did persist metadata before approval; it no longer calls persistence and resets stale request/configuration state when a new Device is identified.
- V08 — verdict `medium`; route `patch` — The controller-selection concern is the same confirmed issue as B01; keying, synchronization, and bound-profile guards cover it.
- V09 — verdict `high`; route `patch` — Publish could mutate Home before checking for an enrolled Device binding. Binding, lifecycle, and readable Device credential checks now precede the remote PUT.
- E01 — verdict `medium`; route `patch` — Discovery wrote a lifecycle record; fixed as V07.
- E02 — verdict `medium`; route `patch` — A second discovery could retain the prior request and Device state; discovery now clears it.
- E03 — verdict `high`; route `patch` — A selected-Profile change during an operation could redirect storage; the bound-Profile guard rejects the operation before a second client or local write.
- E04 — verdict `medium`; route `patch` — Saved-instance state could retain secrets; fixed as B03.
- E05 — verdict `medium`; route `patch` — `observe` accepted an unvalidated constructed Device; it now re-runs the same field validation as manual discovery.
- E06 — verdict `medium`; route `patch` — Empty explicit request IDs could form malformed routes; approve and consume now reject them before transport.
- E07 — verdict `medium`; route `patch` — Non-approved responses could advance the state; fixed as B06.
- E08 — verdict `medium`; route `patch` — Repeated consume was possible while the one-time code remained in the form; the consume action is disabled after enrollment and clears the code.
- E09 — verdict `medium`; route `patch` — 204 revoke responses lacked a body; fixed as B11.
- E10 — verdict `low`; route `patch` — Common transient HTTP statuses 408, 429, and 500 were not typed as service unavailable; they now are.
- E11 — verdict `medium`; route `patch` — The OkHttp transport had no call timeout; it now caps calls at 30 seconds.
- E12 — verdict `medium`; route `patch` — Duplicate mapping IDs could make edits ambiguous; fixed as B10.
- E13 — verdict `medium`; route `patch` — Equal or older returned revisions could be accepted; fixed as B09.
- E14 — verdict `high`; route `patch` — Matching revision alone did not prove Device mapping correctness; fixed as B08.
- E15 — verdict `high`; route `patch` — Persisted Ready metadata could outlive a missing Device secret; initialization now reports unavailable and the bridge gate checks both state and credential.
- E16 — verdict `high`; route `patch` — Revoked or expired Profiles could publish again; publish now rejects those phases and missing Device credentials before contacting Home.
- E17 — verdict `medium`; route `patch` — Metadata-save failures were ignored; persistence now returns a typed storage error on failure.
- E18 — verdict `medium`; route `patch` — Keystore deletion failure was not observable during revocation; deletion now returns a Boolean and revocation stops before state publication when it fails.
- E19 — verdict `high`; route `patch` — Expired material could be stored; fixed as B07.
- E20 — verdict `medium`; route `patch` — Credential rollback results were ignored; the enrollment rollback now checks deletion and restoration outcomes before returning failure.
- E21 — verdict `medium`; route `patch` — Home lifecycle changes did not refresh the parent client snapshot; the screen now reports changes, and credential transitions close the active bridge.
- E22 — verdict `high`; route `patch` — Re-enrollment could leave an active socket using old material; successful consume, renewal, re-enrollment, and revocation now close the bridge.

## Design Notes

The two credential authorities are intentional: the Home admin Bearer can publish household configuration, while the Device credential is scoped to the enrolled endpoint. Treating them as one token would make the configuration editor a tiny authority leak wearing a neat little UI.

## Verification

**Commands:**
- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` -- expected: all unit tests pass, debug APK assembles, and lint is clean.

**Manual checks (if no CLI):**
- Walk the ordered Compose flow and confirm discovery, approval, configuration, conflict, revocation, and re-enrollment states are visible without displaying secret values.

### Review Findings

#### Core lifecycle chunk — 2026-09-20

##### Decision needed

- [x] [Review][Decision] Decide whether a configuration fetch may take a Ready bridge offline — `HomeDeviceAdministration.kt:814-825` changes a Ready Profile to `SetupPending` on a read-only fetch. **Decision (2026-09-20):** preserve Ready for an unchanged read-only refresh; enter `SetupPending` only when the fetched candidate differs from the last verified configuration or the user begins an edit.
- [x] [Review][Decision] Define which administrative errors change the persisted lifecycle phase — `HomeDeviceAdministration.kt:1006-1019` persists `Unavailable` for local input errors, missing credentials, and transport failures as well as Home authorization failures. **Decision (2026-09-20):** preserve the last-known-good lifecycle phase for local validation, transport, and service failures; transition the phase only for evidence that the credential or lifecycle is invalid, such as expiry, revocation, authorization failure, or a missing local credential.
- [x] [Review][Decision] Define recovery for a consumed one-time credential when local secure storage or Profile persistence fails — `HomeDeviceAdministration.kt:782-811` consumes remotely before `enrollHomeDevice` can complete. **Decision (2026-09-20):** require an idempotent Home consume or reissue/recovery contract so Android can retry safely after local storage or persistence failure instead of permanently losing the enrollment.

##### Patch

- [x] [Review][Patch] Redact enrollment confirmation codes from generated representations [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:217-257] — `HomeEnrollmentRequest` and `HomeEnrollmentSubmission` now override `toString()` with the confirmation code redacted.
- [x] [Review][Patch] Bind approved and replacement credential material to the requested device, approved scope, and generation [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:757-810,924-948] — approval scope is bounded locally, material scope is persisted and checked, and replacements must retain the Home device ID and advance generation. Home's generated device ID remains distinct from the endpoint ID by contract.
- [x] [Review][Patch] Resolve default request IDs after profile synchronization [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:757-786] — approval and consume resolve nullable defaults inside `runAdmin` after synchronization.
- [x] [Review][Patch] Reject blank Room and Profile names in candidate snapshots [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:101-137] — `requireValid()` now rejects blank editable names.
- [x] [Review][Patch] Require an enrolled setup phase before publishing configuration [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:828-843] — publication is limited to `SetupPending` or `Ready` with a readable Device credential.
- [x] [Review][Patch] Re-check persisted Device-credential expiry immediately before publishing [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:828-843] — publication rejects missing, non-finite, or expired persisted expiry values at the mutation boundary.
- [x] [Review][Patch] Require complete Device wake-mapping equality before Ready [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:845-852] — verification now compares the complete authorized active mapping set, accounting for Home's scoped Device response.
- [x] [Review][Patch] Fail closed on malformed Home administration metadata and missing Ready expiry [app/src/main/java/com/achappell/hermesrelay/RelayProfile.kt:275-305; app/src/main/java/com/achappell/hermesrelay/OkHttpRelaySessionClient.kt:195-204] — malformed records become explicit `Unavailable` metadata, and Ready requires a finite persisted expiry at both snapshot and reconnect gates.
- [x] [Review][Patch] Make the default Home credential deletion fail closed [app/src/main/java/com/achappell/hermesrelay/RelayCredentialStore.kt:41-43] — an implementation must prove deletion instead of inheriting a successful no-op.
- [x] [Review][Patch] Map an unqualified HTTP 409 to `Conflict`, not `RevisionConflict` [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:585-607] — only the explicit `revision_conflict` code enters stale-revision recovery.
- [x] [Review][Patch] Add an expired persisted-Ready bridge regression test [app/src/test/java/com/achappell/hermesrelay/OkHttpRelaySessionClientTest.kt:105-175] — expired, missing, and non-finite persisted Ready expiry fixtures now open no socket.
- [x] [Review][Patch] Add equal and older publish-revision regression tests [app/src/test/java/com/achappell/hermesrelay/HomeDeviceAdministrationTest.kt:276-363] — production transport tests cover both non-advancing response cases.
- [x] [Review][Patch] Add a generic-409 transport-to-controller regression test [app/src/test/java/com/achappell/hermesrelay/HomeDeviceAdministrationTest.kt:170-214] — an unqualified HTTP 409 now maps to `Conflict` and preserves Ready.
- [x] [Review][Patch] Verify production Profile deletion removes the Home admin slot [app/src/test/java/com/achappell/hermesrelay/RelayProfileTest.kt:132-152] — an instrumentation test deletes a Profile through production Keystore-backed credentials and verifies the admin slot is gone.
- [x] [Review][Patch] Add malformed-snapshot and incomplete-device verification fixtures [app/src/test/java/com/achappell/hermesrelay/HomeDeviceAdministrationTest.kt:276-363,507-513] — tests cover blank labels, duplicate mappings, omitted authorized mappings, and scoped Home mappings.
- [x] [Review][Patch] Validate enrollment scope identifiers before transport [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:356-419] — blank room, capability, profile-mapping, and wake-mapping identifiers fail before a request is sent.

##### Deferred pending contract evidence

- [x] [Review][Defer] Confirm the Home-issued credential shape and generation invariant [app/src/main/java/com/achappell/hermesrelay/RelayCredentialStore.kt:74-85; app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:1085-1095] — deferred: the Android spec says only that the credential is opaque and does not establish whether 43 URL-safe characters or generation zero are valid; settle against the Home issuer contract before changing validation.
- [x] [Review][Defer] Decide whether an admin credential must be bound to the approved route [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:964-973] — deferred: route-change semantics and credential scope are not specified in the Android artifact, so the security requirement belongs in the shared Home contract first.
- [x] [Review][Defer] Add an idempotent remote-consume/recovery contract if Home cannot reissue consumed material [app/src/main/java/com/achappell/hermesrelay/HomeDeviceAdministration.kt:422-437] — deferred: the failure is real but the correct repair crosses the Home API boundary and cannot be chosen from Android code alone.

##### Rejected findings

- `false` — HTTPS downgrade: approved Home routes are validated as `wss://`; `HomeRoute` therefore maps the accepted scheme to HTTPS.
- `false` — Approved-route path loss: the validator deliberately accepts only the Home host/root or exact bridge path, and REST administration routes are rebuilt from the authority by contract.
- `false` — Missing discovery attestation: manual discovery is explicitly local and side-effect free; attestation is not an acceptance condition for this adapter.
- `false` — Saved-instance secret leakage: the admin credential and offer code use `remember`, while only non-secret fields use `rememberSaveable`.
- `false` — Plain enrollment-code display: the offer-code field is masked.
- `false` — Approval response ignored: the controller checks request ID, approved status, and non-null approved scope.
- `false` — Duplicate wake-mapping and Device IDs are unvalidated: `requireValid()` rejects both duplicate sets.
- `false` — 204 revoke body failure: successful 204 responses are converted to an empty schema response before parsing.
- `false` — Non-advancing response accepted: the production client rejects returned revisions less than or equal to the expected revision; the remaining issue is test coverage.
- `false` — New controller is unwired: `MainActivity` keys and passes `HomeDeviceAdministrationController` into the mounted screen.
- `false` — Blocking network call on the UI thread: the Compose actions invoke the controller through `Dispatchers.IO`.
- `false` — Re-enrollment must use a fresh offer/request flow: the approved lifecycle explicitly permits a current request/generation renewal or rotation path.
- `false` — Repeated consume remains enabled: the mounted consume button disables after a Device ID exists and clears the one-time code.
- `false` — Generic 409 is already typed as `Conflict`: the explicit `conflict` code maps correctly; only the unqualified status fallback remains a patch finding.
- `false` — The controller remains bound across Profile changes: `MainActivity` keys it by `selectedProfileId`, and the controller also synchronizes the selected ID.
- `low` — Blank device IDs at the raw revoke endpoint: normal controller state and parsed Home material require a nonblank device ID; adding a direct-client guard is not worth another public branch without evidence of that call path.
- `maybe-false` — Same-ID Profile replacement necessarily redirects an in-flight operation: the diff does not establish that the Profile collection can replace contents under an unchanged selected ID during an operation; the selected-ID race is covered separately above.

---
