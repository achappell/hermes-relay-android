---
id: ANDROID-REL-03
title: R8 shrinking, baseline profile, 16 KB page-size check and data-extraction rules
type: 'feature'
status: in-review # draft | ready-for-dev | in-progress | in-review | done (workflow state; ticket delivery status lives in sprint-status.yaml)
route: 'dispatch'
review_loop_iteration: 0
baseline_commit: 601800ec084d57ff78b62bfb210b6b4ade3b7506
product_epic: 4
release_scope: migration
parity_epic: ANDROID-PARITY-02
parity_stream: S6
created: 2026-10-06
depends_on: []
parity_source: 'PX-35 (audit section 4: release signing and Play, app backup); re-scoped 2026-10-06 for sideloaded builds'
github_issue: https://github.com/achappell/hermes-relay-android/issues/94
validation: '_bmad-output/implementation-artifacts/validation-android-rel-03.md'
context:
  - '{project-root}/AGENTS.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The signed APK that GitHub Releases ships for sideloading is unshrunk, has never been checked for 16 KB page-size native-library loading (Android 15+; CameraX ships `.so` files), and lacks Android 12+ `dataExtractionRules`, so device-to-device transfer is not explicitly closed even with `allowBackup=false`.

**Approach:** Enable R8 and resource shrinking for release with minimal commented keep rules, retain the R8 mapping as a workflow artifact, enforce 16 KB ELF/ZIP alignment of 64-bit native libraries in CI and the metadata script, and add `dataExtractionRules` excluding all app data. Play Store scope stays declined (`ANDROID-REL-02`): no AAB, Play signing, or Play policy checks.

## Boundaries & Constraints

**Always:** The release workflow keeps the same sideloadable signed APK asset name and signature; debug builds stay unminified; keep rules stay minimal and commented (R8 full mode is the AGP 9 default); acceptance for shrinking is minified-build correctness, not size; host evidence is reported as host-only and device checks as pending.

**Never:** Install, uninstall, clear or run connected tests on the Pixel 6a (it runs the owner's real app under the same package id); install the minified APK anywhere; publish `mapping.txt` as a release asset; change release signing; add Play-only machinery; add an app-specific baseline profile without device before/after measurement.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| Minified build | `assembleRelease` + `lintRelease` | APK builds, passes `check-apk-metadata.sh`; `StreamResetException` name preserved in `mapping.txt` | Non-empty `missing_rules.txt` fails CI |
| Aligned native libs | 64-bit `.so` with 16 KB `PT_LOAD` alignment, stored at a 16 KB APK offset | `check_elf_alignment.py` exits 0 | N/A |
| Misaligned library | `PT_LOAD` < 0x4000 or stored off a 16 KB offset | Exit 1 naming the entry | Fails CI/metadata script |
| Non-64-bit or unreadable | 32-bit ABI entry / invalid ELF | 32-bit skipped with a report line / reported `invalid ELF` | Invalid ELF fails |
| Extraction rules | Manifest lacks `allowBackup="false"`, `dataExtractionRules` or `fullBackupContent="false"` | Metadata script exits 1 | Message names the missing attribute |

**Decisions (provisional — made under owner authorization with director review; Amanda corrects afterward):**
- Keep the 90-day mapping workflow-artifact retention; very old sideloaded builds then cannot be de-obfuscated. Longer retention or a release asset needs a separate storage and exposure decision.
- An app-specific baseline profile is decided only after device before/after startup measurement; none is added now (the APK carries only library-supplied `baseline.prof`).
- The signing digest is unverified locally (`EXPECTED_SIGNER_SHA256` unset, debug-key fallback); the real-keystore signature is verified only by the release workflow.

</frozen-after-approval>

## Code Map

- `app/build.gradle.kts` -- `release` build type: `isMinifyEnabled`/`isShrinkResources`, proguard files; signing falls back to debug key when release secrets are absent.
- `app/proguard-rules.pro` -- app-only R8 rules; library consumer rules (OkHttp/Okio, CameraX, Compose) merge automatically.
- `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/data_extraction_rules.xml` -- `dataExtractionRules`, `fullBackupContent="false"`, all domains excluded for cloud backup and device transfer.
- `scripts/check_elf_alignment.py`, `tests/test_check_elf_alignment.py` -- ELF64 `PT_LOAD` and stored-ZIP offset check for arm64-v8a/x86_64; 7 unit tests.
- `scripts/check-apk-metadata.sh` -- adds manifest backup/extraction assertions and calls the alignment check.
- `.github/workflows/ci.yml`, `release.yml` -- CI runs release build, lint, metadata, mapping assertions; release uploads mapping as a 90-day artifact only.
- `app/src/test/java/com/achappell/hermesrelay/DataExtractionRulesTest.kt` -- pins manifest reference and exclusions.
- Not to change: release signing config, asset names, `ANDROID-REL-02` decline, HOME runtime/diagnostics code.

## Tasks & Acceptance

**Execution:**
- [x] `app/build.gradle.kts`, `app/proguard-rules.pro` -- enable R8 and resource shrinking with minimal rules.
- [x] `app/src/main/AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`, `DataExtractionRulesTest.kt` -- exclude all app data; pin with a JVM test.
- [x] `scripts/check_elf_alignment.py`, `tests/test_check_elf_alignment.py`, `scripts/check-apk-metadata.sh` -- 16 KB alignment and backup checks with a misaligned-fixture failure test.
- [x] `.github/workflows/ci.yml`, `release.yml` -- release build/lint/mapping gates in CI; mapping artifact on release.
- [x] `CHANGELOG.md`, `validation-android-rel-03.md` -- record behavior and the host-only evidence boundary.
- [ ] Device before/after startup measurement and minified-APK smoke -- pending, owner-approved device only; not in this PR.

**Acceptance Criteria:**
- Given the release variant, when `assembleRelease lintRelease` and `check-apk-metadata.sh` run, then they pass with no new lint diagnostics versus `origin/main`.
- Given a deliberately misaligned `.so`, when the alignment check runs, then it fails; given the build output, then it passes.
- Given the release workflow, when it runs, then the published APK asset name and signature are unchanged and nothing requires a Play account.
- Given a physical device, when the minified APK is smoke-tested and startup measured, then results are recorded — PENDING DEVICE, so this ticket is not device-accepted.

## Implementation Notes

- Implemented in local commits `5cc1607` and `f05e9d5` before this spec was reconciled. An earlier unapproved flip of the ticket to `done` was reverted; the tracker now reads `review` (draft PR, host evidence only), the spec is `in-review` (draft PR awaiting review; not `done`), and the validation record's verdict stays `done-with-environment-limitation`: these are three different things and none claims device acceptance. R8 cut the APK from 13,225,201 to 2,810,553 bytes (static evidence only).
- Step-04 review (this pass) patched: truncated-ELF `IndexError` in the alignment checker, tests for truncated, no-`PT_LOAD`, 32-bit-in-64-bit-folder and big-endian ELFs, `overwrite: true` on the mapping upload so a re-run does not hit an artifact-name conflict, a README paragraph for the new gates, and the multi-APK verification command.
- `release.yml` changes are limited to one new step, the `r8-mapping-<tag>` artifact upload. The package, checksum and release-upload steps are untouched, so the APK asset name and signing are unchanged. The existing `check-apk-metadata.sh` call in that workflow now also enforces the new backup/extraction and 16 KB alignment checks, and `ci.yml` newly fails on a non-empty `missing_rules.txt` or a renamed `StreamResetException`; either can newly block a release PR or tag. Flagged for owner attention.
- Owner-decision risk, not patched: once merged, `scripts/install-release.sh` (`installRelease`) and the next `v*` tag produce a minified APK that has had no device smoke. Do not merge or tag before the PENDING DEVICE checks pass.

## Spec Change Log

## Review Triage Log

| Finding | Verdict | Evidence and route |
|---|---|---|
| Inconsistent status across spec, tracker and validation | false | The spec's `done`, sprint tracker `in-progress`, and validation's environment-limited status describe implementation workflow, ticket progress, and evidence; they are not competing values for one state. Route: reject. |
| Baseline-profile work is not recorded as open | false | The release APK contains baseline profile assets and the validation record explicitly leaves app-specific profile measurement pending device evidence; no acceptance is claimed complete. Route: reject. |
| R8 mapping expires after 90 days | low | The mapping is a temporary workflow artifact, not a published release asset; very late support would need a separate retention choice, beyond the current acceptance. Route: reject as a low-frequency concern requiring a storage-policy change; retain as an open risk. |
| CI does not fail on `missing_rules.txt` | medium | CI now fails if the release R8 output contains a non-empty `missing_rules.txt`. Route: patch. |
| ELF32 parser branch is unreachable | low | The checker deliberately skips 32-bit ABIs before parsing, so ELF32 parsing was unused; removed that branch while retaining the explicit skip test. Route: patch. |
| APK check accepts any `dataExtractionRules` value | false | `DataExtractionRulesTest` asserts the exact `@xml/data_extraction_rules` manifest reference and all exclusions; the APK metadata check confirms the built manifest carries the attribute. Route: reject. |
| Nested `.so` entries are skipped | false | Android-loadable JNI libraries use `lib/<ABI>/<name>.so`; the app has no custom `System.load`/`System.loadLibrary` caller for a nested path. Route: reject as unreachable for this app. |
| ELF with no `PT_LOAD` is called unreadable | low | The checker now reports that the ELF has no loadable segment, while other parse failures say `invalid ELF`. Route: patch. |
| CI does not lint the minified release variant | medium | The CI release step now runs `lintRelease` alongside `assembleRelease`. Route: patch. |
| Offset helper uses private `ZipFile.fp` | low | It now opens the APK path through a separate file handle and no longer mutates the ZIP reader cursor. Route: patch. |
| Local ZIP header signature is not checked | false | `ZipFile.read(info)` validates the local header before offset calculation; a corrupted signature raises `BadZipFile`, which the CLI catches and returns as failure. Route: reject. |
| CI does not assert readable OkHttp exception names | medium | CI now asserts the release mapping keeps `okhttp3.internal.http2.StreamResetException` unchanged, matching the diagnostic journal's `Throwable` name rule. Route: patch. |
| ELF32 parsing branch is not covered by a test | low | This duplicates the unreachable-branch finding; the unused parser path was removed and 32-bit ABI skipping remains tested. Route: patch. |
| No origin-main lint comparison was recorded | medium | Baseline `lintDebug`/`lintRelease` had 14 warnings and 6 hints; final feature reports 13 warnings and 6 hints with no new diagnostics after adding `fullBackupContent="false"`. Route: patch. |
| Step-04 (2026-10-10): `check-apk-metadata.sh` failure branches and the 16 KB wiring have no shell-level test (verification-gap) | medium | Repo never tested this script; the Python checker's failure paths are unit-tested. Route: defer (shell contract test with stubbed `apkanalyzer`). |
| Step-04: no automated run exercises the minified APK at runtime (verification-gap) | medium | Only static R8 evidence exists; spec forbids installing the APK. Route: defer to the PENDING DEVICE smoke recorded in the validation record. |
| Step-04: truncated 4-5 byte ELF raises `IndexError` and prints a traceback (edge-case; blind) | low | `data[4]` is read after only a 4-byte magic check. Route: patch (`truncated ELF header` guard plus test). |
| Step-04: riscv64 `.so` is skipped as "32-bit or unlisted ABI" (edge-case) | low | Android ships no riscv64 ABI for this app's dependencies; fix would add ABI branching. Route: reject. |
| Step-04: nothing keeps the unverified minified APK off `scripts/install-release.sh` or the next `v*` tag (blind) | medium | Confirmed: `isMinifyEnabled` applies to every release path. A guard is new public surface beyond the spec. Route: escalated to the PR body as an owner merge gate (no merge/tag before device smoke); not patched. |
| Step-04: the 16 KB check exits 0 when it finds no 64-bit library (blind) | low | A build with no native libraries has nothing to misalign; the unit test pins the message. Route: reject. |
| Step-04: missing tests for no-`PT_LOAD`, ELF32-in-64-bit-folder and big-endian ELF (blind) | low | Real gap in coverage. Route: patch (three tests added; checker unit tests now 11). |
| Step-04: earlier triage row says no-`PT_LOAD` is reported differently from other parse failures (blind) | low | It is still wrapped as `invalid ELF (...)`; the row overstates. Historical row kept; correction recorded here. Route: patch (this note). |
| Step-04: mapping upload precedes the metadata check and lacks `overwrite` (blind) | medium | A failed release can leave a mapping artifact for an unpublished APK (harmless), and a re-run can hit an artifact-name conflict. Route: patch (`overwrite: true`). |
| Step-04: spec verification command with brace expansion checks only the first APK (blind) | low | The script reads `$1`. Route: patch (two explicit commands). |
| Step-04: ticket status described inconsistently across notes (blind) | low | Notes said "reverted to backlog" while front matter and tracker differ. Route: patch (Implementation Notes now state each value). |
| Step-04: README does not describe the new CI gates or mapping retrieval (blind) | medium | Permanent feature needs docs. Route: patch (README paragraph). |

## Design Notes

R8 correctness cannot be shown without running the minified app: OkHttp/Okio, CameraX JNI, ZXing, Keystore and Compose paths need a device smoke. The 16 KB check parses ELF64 headers and ZIP local-header offsets directly because stored libraries are memory-mapped from the APK, so both ELF and APK alignment matter.

## Verification

**Commands:**
- `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease lintRelease --no-daemon` -- expected: success, no new lint diagnostics.
- `scripts/check-apk-metadata.sh app/build/outputs/apk/debug/app-debug.apk` and again with `.../release/app-release.apk` -- expected: each passes (the script reads one APK per run).
- `python3 -m unittest discover -s tests` -- expected: ELF (11) and issue-tracking tests pass.
- `git diff --check` -- expected: clean.

**Manual checks (PENDING DEVICE):**
- On an owner-approved device: pair, QR scan, typed turn, spoken turn, lock-screen playback, and cold-start before/after. Never the Pixel 6a real install.
