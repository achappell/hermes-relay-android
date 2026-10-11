---
spec: spec-android-rel-03-r8-baseline-16kb.md
status: done-with-environment-limitation
updated: 2026-10-11
---

# ANDROID-REL-03 validation

Host-verifiable release configuration and checks are implemented. A partial device smoke was run on 2026-10-11 (below). Lock-screen/background playback is blocked-by `ANDROID-HOME-08`; a before/after startup comparison was not done.

## Host evidence

- `./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease lintRelease --no-daemon --console=plain` — **BUILD SUCCESSFUL**. JUnit XML reports 434 JVM cases across 34 suites, 0 failures/errors. Debug and release lint report 0 errors, 13 warnings and 6 hints; comparison with `origin/main` reports 14 warnings and 6 hints there, with no new lint diagnostics (the pre-existing `allowBackup` deprecation warning is removed).
- `scripts/check-apk-metadata.sh app/build/outputs/apk/debug/app-debug.apk` — passed: min SDK 26, version 0.3.1 (301), backup disabled, Android 12+ extraction rules referenced, pre-12 full backup explicitly disabled, native alignment passed; signing digest check skipped because `EXPECTED_SIGNER_SHA256` was unset.
- `scripts/check-apk-metadata.sh app/build/outputs/apk/release/app-release.apk` — same metadata/alignment result. The local release APK uses the configured debug-key fallback because no release keystore secrets were supplied; it is not installed or published.
- `python3 scripts/check_elf_alignment.py app/build/outputs/apk/release/app-release.apk` — **6/6 64-bit ELF libraries pass** (arm64-v8a and x86_64); every `PT_LOAD` segment has at least 0x4000 alignment and stored library entries are at 16 KB APK offsets. `zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk` also reports successful verification.
- `python3 -m unittest discover -s tests -p 'test_check_elf_alignment.py'` — **11 passed**, including a deliberately misaligned ELF, a misaligned stored ZIP entry, a compressed library, 32-bit ABI handling, a truncated ELF, an ELF with no `PT_LOAD`, a 32-bit ELF in a 64-bit folder and a big-endian ELF.
- `python -m unittest discover -s tests -p 'test_bmad_issue_tracking*.py'` — attempted but the host has no `python` executable; equivalent `python3 -m unittest discover -s tests -p 'test_bmad_issue_tracking*.py'` — **18 passed**.
- Static release inspection: R8 reduced the APK from 13,225,201 bytes before shrinking to 2,811,197 bytes after shrinking (rebuilt 2026-10-10 after review patches; first build 2,810,553). `apkanalyzer dex packages --defined-only` shows `MainActivity`, `HermesRelayApplication`, and CameraX `ImageProcessingUtil`; `mapping.txt` maps ZXing `MultiFormatReader` to its minified name while `okhttp3.internal.http2.StreamResetException` retains its name. The CameraX JNI native names remain present, and the merged R8 configuration contains native-member keep rules from the library. No `missing_rules.txt` was emitted. This is static evidence only, not proof of runtime scanner, pairing, WebSocket, Keystore, Compose or audio behavior.
- `scripts/check-apk-metadata.sh` asserts `android:allowBackup="false"`, `android:dataExtractionRules` and pre-12 `android:fullBackupContent="false"` in the APK manifest; `DataExtractionRulesTest` checks the exact manifest values and that all app data domains are excluded from cloud backup and device transfer.
- The minified APK packages `assets/dexopt/baseline.prof` and `.profm`. No app-specific Macrobenchmark/profile generator or before/after startup measurement was added; only the single 424 ms after figure was captured on device (below), so the baseline-profile decision stays open.

## Device smoke (2026-10-11, owner-authorized)

- Device: Pixel 6a (`bluejay`). The minified release APK (debug-key fallback signing, 0.3.1 (301), 2,811,197 bytes) was installed with `adb install -r` over the owner's debug-signed 0.3.1 (301); same signer and versionCode, data kept (`firstInstallTime` unchanged, app opened paired to its saved profile with "Authorization: Verified"). The prior APK is backed up in `/tmp/hermes-relay-backup/` on the host.
- Cold start (`am start -W`, force-stopped first): 424 ms. No FATAL/AndroidRuntime/ClassNotFound/NoSuchMethod/Keystore errors in logcat.
- Owner manually confirmed QR scan, typed turn and spoken turn: **PASS**.
- Lock-screen/background playback: **did not work; blocked-by `ANDROID-HOME-08`** (and `ANDROID-HOME-09`). Not an R8 regression: `main` has no foreground service, MediaSession, audio focus or wake lock (the manifest declares only INTERNET, RECORD_AUDIO and CAMERA), the app code is identical apart from the backup manifest attributes, and both tickets are backlog. No A/B run against the debug build was performed.
- The owner's phone now runs this minified build.

## Open decisions

- No before/after startup measurement: only the 424 ms after figure exists. Decide whether an app-specific baseline profile helps only with that evidence.
- The local APK used the debug signing fallback; production release-keystore signature verification was not performed.
- No-device host validation cannot establish absence of runtime-only R8 regressions or 16 KB loading failures on a physical 16 KB-page device.
- The release workflow keeps `mapping.txt` as a 90-day workflow artifact, not a published release asset; longer-term retention has not been selected.
- Owner attention: the release pipeline changes (`release.yml` mapping upload; stricter `check-apk-metadata.sh` in the release workflow; new CI gates on `missing_rules.txt` and `StreamResetException`) can newly block a release PR or tag. `scripts/install-release.sh` would install a minified APK over a real install; lock-screen playback remains blocked-by `ANDROID-HOME-08`.
- Approval: director review of the spec in place of the owner checkpoint (owner authorized); owner to correct.
