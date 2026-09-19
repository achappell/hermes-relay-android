---
title: 'Add the Android Home pairing and configuration adapter'
type: 'feature'
created: '2026-09-19'
status: 'done'
route: 'dispatch'
review_loop_iteration: 0
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

---
