---
spec: spec-android-rel-03-r8-baseline-16kb.md
status: done-with-environment-limitation
updated: 2026-10-09
---

# ANDROID-REL-03 validation

Host-verifiable release configuration and checks are implemented. Device-only startup measurement and minified APK runtime smoke remain pending; this is not a full device acceptance.

## Host evidence

- `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease lintRelease --no-daemon --console=plain` — **BUILD SUCCESSFUL**. JUnit XML reports 434 JVM cases across 34 suites, 0 failures/errors. Debug and release lint each report 0 errors, 14 warnings and 6 hints; a base-branch warning-count comparison was not run.
- `scripts/check-apk-metadata.sh app/build/outputs/apk/debug/app-debug.apk` — passed: min SDK 26, version 0.3.1 (301), backup disabled and extraction rules referenced, native alignment passed; signing digest check skipped because `EXPECTED_SIGNER_SHA256` was unset.
- `scripts/check-apk-metadata.sh app/build/outputs/apk/release/app-release.apk` — same metadata/alignment result. The local release APK uses the configured debug-key fallback because no release keystore secrets were supplied; it is not installed or published.
- `scripts/check_elf_alignment.py app/build/outputs/apk/release/app-release.apk` — **6/6 64-bit ELF libraries pass** (arm64-v8a and x86_64); every `PT_LOAD` segment has at least 0x4000 alignment and stored library entries are at 16 KB APK offsets. `zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk` also reports successful verification.
- `python3 -m unittest discover -s tests -p 'test_check_elf_alignment.py'` — **7 passed**, including a deliberately misaligned ELF, a misaligned stored ZIP entry, a compressed library and 32-bit ABI handling.
- `python -m unittest discover -s tests -p 'test_bmad_issue_tracking*.py'` — **18 passed**.
- Static release inspection: R8 reduced the APK from 13,225,201 bytes before shrinking to 2,810,553 bytes after shrinking. `apkanalyzer dex packages --defined-only` shows `MainActivity`, `HermesRelayApplication`, and CameraX `ImageProcessingUtil`; `mapping.txt` maps ZXing `MultiFormatReader` to its minified name. The CameraX JNI native names remain present, and the merged R8 configuration contains native-member keep rules from the library. No `missing_rules.txt` was emitted. This is static evidence only, not proof of runtime scanner, pairing, WebSocket, Keystore, Compose or audio behavior.
- `scripts/check-apk-metadata.sh` asserts both `android:allowBackup="false"` and `android:dataExtractionRules`; `DataExtractionRulesTest` checks all app data domains are excluded from cloud backup and device transfer.
- The minified APK packages `assets/dexopt/baseline.prof` and `.profm`. No app-specific Macrobenchmark/profile generator or startup measurement was added: the spec permits keeping a profile only when it measurably improves startup, and the required physical-device before/after measurement is unavailable under the no-install rule.

## Pending device / open decisions

- **PENDING DEVICE:** Do not install the release APK. On an owner-approved device/build, verify QR scan, Home pairing, a typed turn, a spoken turn, lock-screen playback, and the minified app's Keystore/Compose startup/runtime behavior. Record device model, Android version, build identity, and outcomes here before claiming runtime acceptance.
- **PENDING DEVICE:** Measure cold-start performance before/after with the chosen startup tool and a recorded device model. Decide whether an app-specific baseline profile measurably helps; add one only if that evidence supports retaining it.
- The local APK used the debug signing fallback; production release-keystore signature verification was not performed.
- No-device host validation cannot establish absence of runtime-only R8 regressions or 16 KB loading failures on a physical 16 KB-page device.
