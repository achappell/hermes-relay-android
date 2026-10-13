---
story: ANDROID-STD-01
spec: spec-android-std-01.md
status: in-review
updated: 2026-10-12
baseline_commit: afb7c37aa703b8a5bfbb6bbd093f7075f2a3291e
standard_version: 0.21.5
standard_commit: f97608f178d1ffeca59860195ab7da295f7c8e5f
---

# ANDROID-STD-01 validation

All three approved slices are implemented locally. Implementation, merge and live/physical acceptance are distinct: this record does not claim a merged release or completed physical voice acceptance.

## Gate summary

| Gate | Observed result |
| --- | --- |
| Supported upstream baseline | Passed against the household's unmodified Standard 0.21.5 |
| Full local unit/build/lint | Passed; 556 debug tests, zero failures/errors/skips; all-variant `test` also passed |
| Repetition | 30 consecutive runs, zero failures; 16 transport/runtime tests per run |
| Focused API 36 instrumentation | 18 setup/pairing tests and 2 main-screen behavior tests passed |
| Live Android typed turn | Passed from both the initial emulator installation and Android Studio's Run action |
| Live deliberate New conversation | Passed; previous Local History retained with a divider |
| Live response audio | Upstream PCM and emulator AudioTrack drain passed; Amanda subsequently confirmed audible Standard replies after the media-volume repair |
| Live remote interrupt | Probe acknowledged interrupt and observed an interrupted terminal |
| Physical microphone/hands-free/barge-in/accessibility | Not exercised; no waiver inferred |
| Merge/release | Submitted for PR review; merge and release remain separate, incomplete gates |

## Actual upstream baseline

The retained `scripts/probe-standard-baseline.py` ran on the endpoint host, reading the existing token file locally. Its output contains facts/counts, not tokens, session references, transcripts or audio. It requires Python 3.11+ and `websockets`; invoke with `--endpoint`, `--token-file` and optional `--profile` on a host authorized to read that credential. Use a dedicated test session: the probe submits two benign turns.

Observed against the specified 0.21.5 commit:

- `session.create` returned both runtime `session_id` and durable `stored_session_id`.
- Query-token WebSocket authentication succeeded. An exploratory header-only bearer request returned HTTP 401; the app therefore uses the supported query-token handshake, not a fabricated header contract. Authenticated URLs are not logged.
- Typed streaming completed. Events included `gateway.ready`, `session.info`, `message.start`, `session.title`, `thinking.delta`, `message.delta`, `reasoning.available`, `sessions.changed`, and `message.complete`.
- Resuming with the runtime ID failed with session-not-found. Resuming with the durable reference succeeded and retained history continuity without submitting another prompt.
- Resume omits `stored_session_id`; the app retains its existing durable reference rather than overwriting it with an absent value. The final probe recorded `durable_reference_preserved=true`, `history_continuity=true`, and `reconnect_prompt_count=0`.
- A mid-response interrupt was acknowledged; the subsequent terminal reported `interrupted`.
- `/api/audio/speak-stream` returned chunked PCM16, mono, 24,000 Hz. The final probe received 31 binary frames / 134,912 bytes and an end event.
- A structured prompt was not reproduced. Unsupported-prompt handling has deterministic coverage only.

## Delivered behavior

- Profiles have explicit HomeBridge, Standard or Legacy modes. Old unlinked Profiles remain Legacy; existing Home links retain HomeBridge. No automatic migration or outage fallback.
- Standard setup accepts secure endpoints and validates them before a temporary `session.create` check. Failed checks save nothing. Standard credentials occupy their own Keystore slot; endpoint changes require fresh input, and stored tokens never repopulate the editor.
- `OkHttpStandardSessionClient` implements only the base client port. `ModeAwareClientPort` selects the configured transport without fallback. Standard does not expose Home conversation, approval or administration interfaces.
- Local and prompt history use mode + canonical endpoint + normalized Hermes profile identity. Switching identities does not import another identity's history or references.
- Transport reconnect can resume the in-process durable session; it never resends a turn or clears uncertainty. Successful deliberate New conversation clears uncertainty and inserts a divider without deleting earlier Local History. Failure retains the guard and reports disconnected transport truthfully.
- Standard response speech starts after text completion. Audio failure retains text. Local stop always stops playback; the supported remote interrupt is also sent. Busy/uncertain state prevents the next typed or spoken turn and blocks hands-free rearming.
- Reconfiguration is blocked during pending typed input, initiation/resend, recovery, active/uncertain turns, or armed/capturing voice. Existing Home pairing feedback remains visible. This change does not add an attachment-staging feature.
- Session references are not persisted across process restarts. Standard has no cross-launch session list/resume UI.

## Local verification

Executed with JDK 21 and the SDK in the machine-local Android SDK directory:

```text
./gradlew testDebugUnitTest assembleDebug lintDebug assembleDebugAndroidTest --no-daemon
BUILD SUCCESSFUL in 38s
JUnit XML: 556 tests, 0 failures, 0 errors, 0 skipped

./gradlew test --no-daemon
BUILD SUCCESSFUL in 5s

scripts/run-flake-gate.sh OkHttpStandardSessionClientTest,StandardRuntimeTest 30
30 consecutive runs, 0 failures

scripts/check-apk-metadata.sh
min SDK 26, version 0.3.1 (301), backup off with extraction rules,
16 KB aligned, signing skipped (EXPECTED_SIGNER_SHA256 unset)
```

Focused instrumentation ran on the isolated `hermes-std-20261012` API 36 ARM64 emulator:

- `RelayConfigurationTest` and `PairedHomesFeedbackTest`: **18 passed**, including Keystore isolation, masked Standard editing, invalid setup saving nothing, editor restoration, and existing Home pairing/refresh feedback.
- `MainActivityTest#no_profile_exposes_configuration_as_the_primary_path` and `#a_lost_turn_is_marked_unconfirmed_and_resent_only_by_an_explicit_action`: **2 passed** after the final status-label adjustment. These exercise fresh setup and preserve deliberate Home resend behavior; Standard never exposes that resend action.
- `RelayConfigurationTest` clears credential preferences. It was run only on the isolated emulator, whose real Standard credential was then re-entered through setup. Do not run it on an already paired personal phone.
- This is local verification, not a new CI run. Full device instrumentation, release-signature verification and API 37 instrumentation were not run.

## Review findings and informative failures

Two scoped reviews identified four issues, all corrected before final verification:

1. Standard editor identity was lost on Activity restoration: preserve the edited Profile ID with `rememberSaveable`; a restoration regression now exercises it.
2. Failed New conversation could leave recovery showing Connected after detaching the transport: reflect transport loss while preserving uncertainty; the runtime regression covers failure and success.
3. Standard audio dropped an accepted endpoint path prefix: derive the audio route from the canonical chat URL.
4. Standard transport rejected safe query parameters accepted by setup: retain safe queries and append authentication/profile parameters through the URL builder. The prefixed-endpoint/audio test checks both routes and query preservation.

Live UI checking also found a shared `Home bridge:` connection label on a Standard credential error. It is now mode-neutral `Connection:`. A fresh unconfigured screen says Choose a connection rather than classifying the absence of a Profile as Legacy. Removed the obsolete wording-only assertion while retaining its no-automatic-resend behavior test.

Initial failures were not ignored:

- Cleartext MockWebServer fixtures were correctly rejected by secure Standard validation; converted fixtures to the repository's TLS certificate convention.
- A mock WebSocket never answered the close handshake, causing audio-test teardown timeout; added the server close reply rather than suppressing teardown failure.
- Using Homebrew's formula prefix as `JAVA_HOME` launched Java but left Kotlin without class roots. The actual JDK home is `libexec/openjdk.jdk/Contents/Home`.
- The first SDK download was truncated; retried the incomplete packages, not the successful installations.
- Homebrew-root `apkanalyzer` could not locate build-tools in the separate SDK. The same metadata gate passed with the SDK-local command-line tools first on PATH.
- The initial baseline probe mistook an omitted resume reference for lost continuity. The wire result was investigated; the probe and app preserve the known durable reference.
- An initial UI-driver text-replacement error produced a nonexistent Hermes profile. Setup refused it without saving; correcting the test input succeeded.

New Standard APIs did not exist at the baseline, so new transport/runtime/profile tests are compile-red rather than a claimed executed pre-fix failure. No pre-fix execution is claimed for the four review regressions.

## Live Android and Studio observations

The API 36 app completed explicit Standard setup against the household endpoint/profile, showed Authorization: Verified, streamed a benign typed response, and reached Complete. Diagnostics observed prompt acceptance, audio focus granted, `standard audio result=drained`, foreground-service completion and wakelock release. Notification permission denial did not block the initial turn.

New conversation succeeded. Local History still displayed the prior turn and reply followed by its divider. The Standard overflow menu contained configuration, Local History and Disconnect, not Home approvals or household conversations.

Android Studio Rabbit 1 / 2026.2.1 was already installed and open. Its first sync could not find Java 21 despite bundled JBR 25. Registered the installed JDK under the user JavaVirtualMachines directory and set ignored project-local Gradle Java home configuration to the actual JDK 21 home. Sync succeeded. Studio's **app** Run action built, installed and launched the application on the visible embedded emulator. A second live turn returned the requested benign marker, showed Complete, and drained AudioTrack. The visible IDE build-success and emulator surface were inspected.

At initial setup, microphone permission was ungranted; the app offered Allow microphone while typed turns worked. The later environment repair below supersedes that permission/model state, not the physical voice acceptance limits.

## Remaining acceptance limits

- Physical tap-to-speak, on-device recognizer behavior, audible speaker output, echo-safe hands-free/barge-in, and TalkBack.
- Actual unsupported structured prompt delivery.
- Live Android mid-turn transport loss, reconnect with uncertainty, and failed New conversation; deterministic tests cover these branches but the live probe alone is not Android UI acceptance.
- Live setup alongside existing Home Profiles and live endpoint-change token re-entry; deterministic storage/isolation coverage is not a claim of those live gates.
- No new merge, release, reboot/sleep-wake persistence check, or physical-device acceptance. Full voice scope remains open for acceptance, not silently reduced to text-only.

## Emulator speech provisioning repair — 2026-10-12, 19:28 CDT

Amanda reported Tap to speak did not work while using her HomeBridge Profile. Android microphone permission was already granted. A temporary capability probe found on-device recognition available but **zero installed languages**. Its supported-language list included `en-US`; `triggerModelDownload` succeeded, and a separate subsequent capability query returned `installed=en-US`.

Enabled host microphone forwarding with `adb -e emu avd hostmicon`. A temporary device smoke using the production `PlatformSpeechInput` reached its real Started/Listening callback with `networkRecognitionAllowed=false`, then cancelled capture without submitting a Hermes turn. No production code, credential or selected Profile changed. The temporary probe and installed test package were removed.

This verifies the repaired offline recognizer startup boundary, not a completed spoken transcript/reply, physical microphone quality, barge-in, or Standard-specific voice acceptance. Full spoken interaction and microphone-routing persistence still require verification.

Amanda subsequently retried Tap to speak and confirmed “it works.” This is user acceptance of the reported emulator voice-input repair. It does not independently establish physical-phone, barge-in, restart-persistence, or Standard-specific voice acceptance.

Amanda also confirmed the requested follow-on check worked: stop a reply while it is speaking, then ask another question. Record this as user acceptance of manual interruption and next-turn recovery on the current HomeBridge emulator setup. Automatic hands-free barge-in, physical-phone behavior and Standard-specific voice acceptance remain separate.

## Audible emulator playback — 2026-10-12, 19:40 CDT

Amanda confirmed the requested Standard voice/manual-stop/next-question check, then clarified she was not hearing emulator output. Therefore those confirmations and earlier AudioTrack drain must not be interpreted as audible-playback acceptance.

Android media volume was 5/15; its music stream was unmuted and routed to the emulator speaker. The Mac used its built-in speakers, unmuted at 44%, and the running emulator had no `-no-audio` flag. Shell `media_session volume --set 11` requests logged but left the readback at 5. Changing Android's visible **Sound & vibration → Media volume** slider produced a verified 11/15 readback. Amanda then confirmed hearing the next Hermes reply.

This supplies user-confirmed emulator audibility after the adjustment. The last observed selected transport before this repair was HomeBridge; it does not establish a separate audible Standard turn, physical-phone speaker acceptance, or hands-free barge-in. No production code changed.

## Final Standard emulator acceptance

After the volume repair, Amanda was explicitly asked to select **Amanda Standard** and repeat a spoken question, confirm an audible reply, stop playback, and ask another question. She confirmed “yep worked,” then authorized creating the PR. This accepts those Standard emulator flows; physical-phone behavior, automatic hands-free/barge-in, TalkBack and the separately listed failure/isolation scenarios are not silently waived.

Two scoped implementation reviews were completed and their four findings fixed before the recorded verification. No new feature-source changes were made during the later keyboard, offline-model or volume setup repairs. Additional local toolchain-resolver/daemon changes were present at publication; those are excluded from this feature PR and left untouched.

## Standard process restart — 2026-10-13

- Baseline `11edf120147fe3a330458e6142a977dca06dee8f`: completed-turn force-stop/relaunch preserved selected connection, verified saved credentials, Local History and an unsent draft. Mid-reply force-stop after an accepted prompt exposed silent `session.create`/Ready without an interrupted-turn warning. No automatic prompt replay was observed.
- Fix: opaque no-backup write-ahead marker, scoped to Profile and Standard endpoint/Hermes identity. No prompt, credential or session reference is stored in it. New process admission/reconnect blocks until deliberate successful New conversation; corrupt/read/write/clear failures fail closed. Existing in-process uncertainty policy is unchanged.
- Fixed real app on API 36 emulator: short turn accepted at 19:57:05Z and audio drained at 19:57:11Z. Force-stop changed PID; saved authorization remained Verified, draft matched, native Local History showed the completed prompt/reply. Mid-reply prompt accepted at 19:58:40Z; relaunch at 19:58:43Z showed the unfinished-turn warning, disabled input and no automatic session creation or replay. A second force-stop/relaunch retained the block. Native screenshots were inspected.
- Deliberate New conversation at 19:59:15Z restored Ready. One explicit verification prompt completed and audio drained at 19:59:37Z. Subsequent restart remained Ready without an interrupted-turn warning. No Pixel, unattended microphone, audible-playback claim, app reset or server change was involved.
- Verification: final full `testDebugUnitTest` passed; `assembleDebug`, `assembleDebugAndroidTest`, lint and APK metadata passed. Direct instrumentation passed 48 tests without uninstalling the configured app. `StandardRestartStoreTest,OkHttpStandardSessionClientTest,StandardRuntimeTest,AndroidRecoveryControllerTest,ReconnectLadderTest` passed 30 consecutive runs, 69 tests/run (2,070 executions). Signer comparison remained skipped without EXPECTED_SIGNER_SHA256.
- The first full JVM run had one unchanged audio-pause regression failure; isolated baseline repetition and subsequent full runs passed. No root cause or audio fix is claimed; evidence is retained in `deferred-work.md`. Review dispositions and detailed implementation evidence are in `spec-standard-restart.md`.
- Standard remains in review: this closes the bounded process-restart slice, not physical-phone, automatic hands-free/barge-in or other still-open acceptance gates.

## Standard configuration and isolation — 2026-10-13

- Baseline `614ea7c382bb775d4f96e829d8b7d648fd95212b`: deterministic regression reproduced deletion of a surviving alias's shared transcript/draft (one failure among 11 Standard Profile tests). Fix retains identity-scoped history until successful deletion of its last saved owner; credential and restart cleanup remain Profile-ID scoped.
- Configured API 36 emulator: a throwaway in-process fixture verified two explicitly owned `default` connections against the real server without exporting credentials. Original `amanda` configuration remained selected until deliberate native selection. The different Hermes identity started with empty Local History; the original identity did not show the probe turn.
- Probe prompt accepted at 23:32:50.811Z; its completed text reply remained in native Local History. Deleting the duplicate alias through Configure relay retained the survivor's prompt/reply. Audio failed at 23:33:29.386Z; this is not an audible/drained success. Cause is unconfirmed and recorded in `deferred-work.md`.
- Pending draft and real active turn hid Select/Delete controls. The active prompt was accepted once at 23:35:38.118Z; force-stop/relaunch at 23:35:48Z retained the uncertain-turn guard. Deliberate New conversation at 23:37:01.237Z restored configuration controls and Ready, without another prompt acceptance or replay.
- Replacing only the disposable Standard credential with an invalid value failed connection despite seeded disposable Home/rollback slots. It did not inherit the original working credential. Native status remained incorrectly “Authorization: Verifying” with Disconnected/Unavailable; the negative-path wording is a separate recorded follow-up, not accepted as correct.
- Selecting the original connection restored Verified/Ready independently. Both disposable profiles were deleted through native configuration. The private cleanup fixture passed: original selection/authentication preserved, owned Standard/Home/rollback credentials and restart state absent, last-owner probe history/draft removed. No global preferences reset, main-app uninstall, server changes, Pixel or microphone capture.
- Verification: full JVM suite, debug APK/test APK assembly and lint passed. Direct non-destructive instrumentation passed 25 tests, including five Standard configuration tests. Thirty runs of `StandardProfileTest,StandardRestartStoreTest,StandardRuntimeTest,OkHttpStandardSessionClientTest,AndroidLocalHistoryTest` passed 58 tests/run (1,740 executions). APK metadata passed; signer comparison was skipped without EXPECTED_SIGNER_SHA256. Independent ownership and native-safety reviews found no supported patch defects.
- This closes the bounded shared-history deletion and emulator isolation/guard slice, not cross-endpoint/Home live isolation, the newly recorded failure-path issues, physical-phone or automatic hands-free acceptance.
