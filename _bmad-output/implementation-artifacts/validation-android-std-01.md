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
| Physical microphone/hands-free/barge-in | Not exercised; no waiver inferred |
| TalkBack | Partial emulator observation only (see TalkBack section); findings need re-verification on current main; no physical-device accessibility claim |
| Merge/release | PR #150 merged as `834530dee2b6ad015ee2447b5cd3123e3ab2b06c`; local main fast-forwarded. Release and remaining acceptance are not implied |

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

## Saved Standard authentication rejection — 2026-10-13

- Baseline `7c379ed86fd1979595ff5eaa0215da948cd454b6`: MockWebServer regression failed because a rejected initial WebSocket upgrade emitted Disconnected although no transport had been established. A proposed snapshot-state assertion also failed; that assertion and redundant client failure cache were subsequently removed because existing UI already derives Unavailable from typed Unauthorized. The earlier live failure observation was not rerun merely to confirm it.
- Fix: failed upgrades detach and return their typed outcome instead of invoking live-transport loss recovery. Established transport failures keep their existing observer path. A fixed, content-free `standard authorization result=rejected` event identifies refusal. No new presentation cache, public API, credential namespace or replay policy.
- Native API 36 smoke used a single explicit owned alias of the original Standard identity. Its invalid Standard credential had separate Home/rollback slots seeded. At `2026-10-14T00:16:59.584Z`, exactly one connection attempt failed and recorded authorization rejection. Native header displayed Authorization Unavailable, the failure retained its authentication reason, Retry was absent, and Edit relay opened configuration. No live-transport-loss event, accepted prompt, replay or credential fallback occurred during the bounded check.
- A private in-process fixture repaired only the disposable credential; relaunch connected at `00:18:09.258Z` and displayed Verified/Ready. This is a credential-store repair plus native relaunch check, not a claim that secret text was manually entered through the UI. The existing deterministic recovery test separately covers clearing the failure on successful explicit recovery in the same client/controller.
- Selecting the original connection independently restored Verified/Ready. The probe was deleted through actual Configure relay; cleanup instrumentation verified original authentication and removal of the owned Standard/Home/rollback slots. Final Profile JSON and all original history-file checksums exactly matched their starting values. Accessibility/services/radios were unchanged and zero held wakelocks were observed. The main app remains installed; temporary helper source, test APK and captures were removed.
- Full JVM suite, debug/test APK assembly and lint passed. Direct MainActivity, StandardConfigurationRecovery and StandardCaptureRecovery instrumentation passed 25 tests. Thirty runs of AndroidRecoveryControllerTest, ReconnectLadderTest, OkHttpStandardSessionClientTest and StandardRuntimeTest passed 60 tests/run (1,800 executions), zero failures. APK metadata passed; signer comparison skipped without EXPECTED_SIGNER_SHA256. Initial metadata invocation could not locate build tools; explicit SDK-local PATH passed without installation or machine configuration changes. Final fixture-free test APK assembly also passed (46 s).
- Independent transport/concurrency and native-safety/presentation reviews found no supported patch defect. The saved-credential status finding is resolved; default-profile response audio, cross-endpoint/Home live isolation and remaining physical/voice acceptance are not claimed resolved.

## Controlled native recovery and endpoint/mode isolation — 2026-10-29

Baseline `2565f3c`; isolated worktree and dedicated API 36 Google APIs ARM64
emulator `hermes-standard-acceptance-20261029`, serial `emulator-5580`. The
configured personal emulator was not started, installed over or reset. Existing
uncommitted main-checkout specification, validation and Gradle-settings changes
were left untouched.

These checks use actual Compose UI actions, `HomeRuntime`, Standard/Home
WebSocket transports and file-backed history/restart state against disposable
TLS peers. Isolation also uses real Android Keystore-backed credential records
in unique fixture preferences. No fake client port replaces production
networking. Certificate trust is scoped to the injected fixture HTTP client;
the setup controller's new optional internal client preserves production TLS
defaults when omitted. No system CA, radio setting, production endpoint/token,
upstream source or user phone was changed.

Four native scenarios passed:

1. An accepted turn receives partial response text, then a remote WebSocket
   error close (`1011`) before any terminal. Reconnection uses its durable
   reference and preserves uncertainty, retained text and sending/capture guards.
2. A rejected explicit New leaves the draft/history and unresolved-turn marker
   intact. A later successful deliberate New clears the guard without replay.
   Ordered peer requests contain exactly six control RPCs across three upgrades:
   initial create/submit, resume/rejected create, and deliberate create/submit.
   Exactly two submitted payloads belong to their intended runtime sessions;
   write-ahead markers exist before both submissions.
3. Editing an endpoint with no fresh token is rejected without contacting the
   new peer or changing the saved credential/history. Supplying a fresh token
   checks and saves through the actual setup UI. The new endpoint has no prior
   session/transcript/history; deliberately returning to the original endpoint
   restores its retained history and remains usable. Rejected setup/runtime
   authentication cannot fall back to separately seeded Home/Legacy credentials.
4. Explicit Standard → HomeBridge → Standard selections preserve each mode's
   credential, prompt and assistant-response history. Actual history sheets and
   live response text are checked, and peer-observed upgrades/control/audio
   requests contain no foreign credential, session reference, prompt or reply.

Standard fixture responses deliberately exercise HTTP 404 from
`/api/audio/speak-stream`; the app truthfully reports unavailable audio while
retaining completed assistant text. Audio requests are counted separately from
control upgrades. Home advertises audio unavailable. Neither this fixture nor
the synthetic speech-recognition input establishes voice or physical microphone
acceptance, and no production voice capability was disabled.

Verification: full `testDebugUnitTest`, debug/test APK assembly and `lintDebug`
passed with JDK 21.0.12.1 / Gradle 9.8.2: **792 tests, 59 suites, zero
failures/errors/skips**. Direct instrumentation of
`StandardRecoveryAcceptanceTest,StandardIsolationAcceptanceTest` passed **4/4**.
APK metadata passed: min SDK 26, version 0.3.1 (301), backup/extraction policy and
16 KB native alignment. Expected release-signer comparison was skipped because
`EXPECTED_SIGNER_SHA256` was unset; no distribution-signing claim.

The Standard JVM repetition gate
`StandardRuntimeTest,OkHttpStandardSessionClientTest,StandardProfileTest` passed
30 consecutive runs of 43 tests (**1,290 executions**). The native four-case
matrix also passed three additional consecutive runs (**12 executions**).

Informative failures were fixture defects, not concealed production failures:
the initial 4-case run passed endpoint editing/auth rejection but failed because
server-side OkHttp `cancel()` has no associated client Call and the disposable
Home binding used an invalid `/api/ws` approved route. The corrected fixture
uses a supported remote error close and Home root route, without extending the
connection timeout. Stronger reply-history checks then found both the live
response and history entry; the selector was scoped to the history-entry tag.
An invalid import of the rule's member `onNode` was removed after the compiler
rejected it. No runtime guard, assertion or scenario was excluded to obtain a
pass. Teardown disposes Compose/runtime before closing transports/files, and
native screenshots wait for UI/event idle rather than a fixed sleep.

This is bounded native client/protocol-peer evidence, **not a new deployed
Home or unmodified-agent compatibility run**. The recorded upstream 0.21.5
baseline and prior live evidence remain separate. Native screenshot inspection
did reproduce one misleading UI hint: the shared composer advised resend/discard
in Standard mode despite its deliberate-New recovery policy. The hint is now
mode-neutral; existing mode-specific recovery actions and admission guards are
unchanged. No wording-only test was added; the native failed-New/Ready surfaces
provide the before/after smoke and behavioral guard coverage. Physical phone,
real provider/household
failure injection, default-profile audio, automatic hands-free/barge-in and
other remaining acceptance gates are not silently closed; story status remains
review. No dependency on Puck access or the pending upstream audio contribution
was introduced.

After the final composer-hint-only correction, the complete JVM/build/lint/test
APK command passed again (**792 tests, zero failures/errors/skips**) and all
four native scenarios passed again. The failed-New screenshot now shows the
mode-neutral hint, retained draft, uncertainty warning and disabled send;
the deliberate-New screenshot shows Ready and enabled deliberate submission.
Endpoint-rejection and explicit mode-selection screenshots were also inspected
from the dedicated emulator's app cache. Local captures are retained under the
temporary `hermes-standard-acceptance-*` evidence directory, not committed.
The 30-run JVM and three-matrix native repetition receipts above precede only
that final hint text correction; no transport or state logic changed afterward.
Owning story/index/tracker YAML and evidence links passed a system-parser check.
## Live emulator connection-loss recovery — 2026-10-12, 20:05–20:09 CDT

Exercised the existing installed app on API 36 emulator-5554 with **Amanda Standard**, preserving credentials, profiles and app data. Used synthetic moon-fact prompts and short response markers through the actual UI. No production source changes or new permanent tests.

- **Active-turn loss:** one `standard prompt result=accepted` at 20:05:31, then Wi-Fi and mobile data disabled while the visible phase was Thinking. At 20:05:42, transport loss recorded `uncertain=true`; the UI said reconnect does not resend. Restore led to `session.resume result=connected uncertain=true` at 20:05:54. No additional prompt acceptance appeared before the deliberate next submission. This is client-side no-replay evidence, not a server-side request count.
- **Deliberate recovery:** New conversation recorded `session.create result=connected uncertain=false` at 20:06:04. The explicitly submitted next question returned `RECOVERY_OK`, visible phase Complete, with audio drained at 20:06:18.
- **Audio-side loss:** a second long response entered audio playback at 20:08:01. Cutting networking caused `standard audio result=failed` at 20:08:44 and released foreground-service/wakelock retention. A screenshot showed retained moon-fact text. UI automation could not observe the long text node reliably while scrolled, so this does not establish a precisely timed text-delta interruption.
- **Stale socket after short outage:** after restoring both radios, the next explicit submission at 20:09:12 encountered transport loss and became uncertain rather than replaying. Resume kept uncertainty. New conversation cleared it at 20:09:34; an explicit subsequent submission returned `AUDIO_RECOVERY_OK`, Complete, with audio drained at 20:09:47.
- **UI defect found:** after successful New conversation and completed replies, the screen retained an **Unavailable / “The Home bridge is unavailable”** banner despite **Authorization: Verified**. Standard also displayed Home-specific accepted-turn/uncertain-turn copy. Recovery functioned, but the displayed connection status is contradictory; this is not a clean UI acceptance pass. Subsequently addressed by PR #151 (see `spec-standard-recovery-status.md`); not re-observed on the current `origin/main`.

Both Wi-Fi and mobile data restored to their original enabled values (`1`). Selected profile remains Amanda Standard; final draft empty and final response complete. Synthetic conversations remain in history. Physical-phone, automatic hands-free/barge-in, TalkBack, failed-New-conversation and cross-mode isolation gates remain open. Story status stays review pending acceptance; implementation is merged.

## Live emulator rotation and background playback — 2026-10-12, 20:52–20:54 CDT

The recovery-status fix is merged in PR #151 (`be3a6c7b0967dc2c0cbf82055641c8240d8a507a`); its implementation, regression evidence and earlier test-cleanup incident are recorded in [spec-standard-recovery-status.md](spec-standard-recovery-status.md). This lifecycle check used the already-installed fixed app on emulator-5554 with Amanda Standard. No Gradle connected tests, installs, profile edits or credential resets were performed.

- Submitted one synthetic moon-fact question at 20:52:55; rotated portrait to landscape during the active turn. The Activity was recreated with `config_change=true`, while the process runtime was retained.
- Sent the app Home at 20:52:57. Diagnostics recorded `voice background enter retention=reply`; Android reported HomeVoiceService `isForeground=true`. Audio focus was granted while backgrounded at 20:53:01.
- Returned at 20:53:18 during response playback and rotated back to portrait. The same turn remained Speaking; a second configuration recreation did not replace the runtime or reconnect the transport.
- Natural audio drain at 20:54:19 released the wakelock and stopped the foreground service. UI showed Ready; a screenshot verified the retained LIFECYCLE_OK response and Complete phase, with Tap to speak enabled.
- Across this bounded window: one accepted prompt, two configuration recreations, zero runtime recreation events, zero transport-loss events and one audio drain. This is client diagnostic evidence of no duplicate submission, not a server-side request audit.

Restored original orientation settings (`accelerometer_rotation=1`, `user_rotation=0`) with final readback. An intermediate check found auto-rotate back at 0, so the restore was reapplied; cause of that reset was not established. No remaining app service record after completion. Removed temporary UI/screenshot captures. Profile and credentials remained intact in this scenario; one synthetic conversation turn was added.

This accepts the exercised foreground/background and rotation lifecycle path, not physical audibility, process death, reboot, long suspension, landscape-layout accessibility or TalkBack. No source changes or permanent tests were added.

## Failed New conversation while offline — 2026-10-12, 21:09–21:11 CDT

Exercised the installed fixed app through the actual UI, retaining Amanda Standard and its credentials. One synthetic Saturn prompt was accepted at 21:09:04. Disabling Wi-Fi and mobile data interrupted that turn at 21:09:06 with `uncertain=true`. After the bounded reconnect ladder failed, explicitly tapped New conversation while still offline.

- At 21:09:41, `standard new_conversation result=failed`. The UI reported that the previous turn remained unresolved and nothing was resent; the uncertainty warning remained.
- Entered a nonempty synthetic follow-up draft. Attempts to activate Send and Tap to speak did not produce another prompt or capture event. A screenshot verified the saved draft, uncertainty warning and disabled send state.
- Restoring both radios yielded `session.resume result=connected uncertain=true` at 21:10:22. Authorization became Verified, but uncertainty and the admission guards persisted; attempts to Send or speak still did not submit or open capture.
- Explicit New conversation succeeded at 21:10:38 with `uncertain=false`. The failure notice cleared, Ready returned, and the draft remained available. Deliberate Send at 21:10:42 returned OFFLINE_RECOVERY_OK; Complete and enabled Tap to speak were visually verified, with audio drained at 21:10:47.

Exactly two prompt-accepted events occurred: the original interrupted question and the explicitly sent post-recovery question. No capture-start event or intermediate submission was observed; no server-side request-count audit is claimed. Final radio readback matched the original Wi-Fi/data values (`1`/`1`); profiles, credentials and unrelated data were not reset. Removed temporary captures. This closes the exercised live failed-New-conversation/uncertainty gate; it does not close other failure modes, profile isolation or physical-device acceptance. No source changes, new permanent tests, commit or push.

## Live Standard profile isolation — 2026-10-12, 21:23–21:30 CDT

Used the installed emulator app and actual setup/history controls; no install, connected-test runner, source changes, commit or push. Created a disposable Standard connection for the existing `default` Hermes identity, using the same endpoint and an explicitly entered protected token. Amanda Standard retained its `amanda` identity. An Android input-selection mistake affected only the disposable display name; the checked endpoint and default identity were verified through setup and successful session creation.

- The new identity initially had empty Local History and no Amanda response or recent-prompt controls. An unsent synthetic draft blocked configuration with the finish/leave/clear-pending-input guard; no profile selector was exposed.
- After clearing that draft, a synthetic default turn was accepted at 21:24:24. Local History contained its exact DEFAULT_ISOLATION_OK response. Response audio failed at 21:24:48, while the response text remained available; this was not an audio-acceptance pass. At that time its cause was not established; later investigation recorded in `spec-standard-configuration-connection.md` attributes it to upstream provider eligibility, not an Android defect.
- Switched back to Amanda with an empty draft. Scanned its history through Clear Local History: existing STATUS_RECOVERY_OK and OFFLINE_RECOVERY_OK records remained, and no default marker appeared. A screenshot verified the retained Amanda history.
- An Amanda prompt accepted at 21:26:47 was interrupted by disabling both radios; diagnostics recorded `uncertain=true`. Configuration remained guarded both offline and after `session.resume ... uncertain=true` at 21:27:01. Amanda remained selected. Deliberate New conversation succeeded at 21:27:39, after which profile selection became available.
- Switched to the default identity again: only its synthetic prompt/reply appeared, with none of Amanda's turns. Cleared this disposable history, restored Amanda, and deleted only the disposable connection.
- Configuration mutation exposed another live issue: saving a nonselected profile and deleting it closed Amanda's transport without automatically reconnecting (the configuration-recovery fix merged as PR #152 targets this; **needs re-verification on current origin/main**, since later Standard history/restart/auth changes landed). After deletion, selecting the already-selected Amanda profile still left Disconnected/Verifying and a draft could not send. Explicit New conversation restored the connection at 21:29:33; the retained Amanda credential required no re-entry. The fresh AMANDA_ISOLATION_RESTORED reply completed, with audio drained at 21:29:42 and Ready/Verified visually confirmed.

Final radios matched original Wi-Fi/data values (`1`/`1`), Amanda was the only saved connection and selected, and the draft was empty. Removed temporary captures. This passes the exercised identity/history separation and draft/uncertainty switch guards, not all configuration lifecycle behavior. Credential survival after disposable-profile deletion passed; cross-mode credential isolation and endpoint-change token reset were not exercised. The default-profile audio failure is cross-referenced above; the configuration-mutation disconnection is addressed by PR #152 and needs re-verification on current origin/main.

## TalkBack emulator acceptance — 2026-10-12, 22:01–22:21 CDT

Used installed TalkBack 16.0.0.738667889 on the API 36 emulator and the installed configuration-recovery fix (subsequently merged in PR #152 as `fa96838a0e924a1b95f52fe80cb39880e998c247`). No build, instrumentation runner, APK install, source change, commit or push in this check. TalkBack was originally disabled with no enabled accessibility service; Wi-Fi/data were both enabled.

Observation method: enabled TalkBack's temporary Display speech output overlay and inspected actual focus rectangles and generated speech text. Initial `uiautomator dump` calls repeatedly interrupted/rebound the service and produced TTS-not-ready startup logs; those are not treated as an app accessibility failure. For the continuous-service scenarios, stopped using UIAutomator, used native Android Studio emulator keyboard input (TalkBack Alt+arrow/Alt+Enter), screenshots, and client diagnostics. ADB-injected shortcut attempts did not reliably drive TalkBack traversal and are not acceptance evidence.

- Actual TalkBack focus reached the composer, send-admission explanation, disabled Send, New conversation and completed-turn state. Speech output showed the admission explanation, Start typed turn, Button/disabled, New conversation's button role, and Turn phase: Complete. This is partial traversal/label evidence, not an exhaustive reading-order pass.
- Entered a synthetic prompt and activated the focused Send through native Alt+Enter. The turn was accepted at 22:15:57 and audio drained at 22:16:03; the retained reply was TALKBACK_OK. TalkBack displayed “Finish the current turn before sending” during the reply. A later overlay still announced the media notification “Hermes is replying” while the surface was already Ready.
- Focus visibility concern: navigating backward from the composer selected Turn phase: Complete, but its green focus rectangle was clipped to a narrow strip at the bottom of the conversation pane beneath the fixed composer. The completed reply was not brought into view in that traversal; a manual pane scroll subsequently exposed it. No claim that the reply is unreachable through every TalkBack navigation method.
- Recovery speech concern: idle transport loss at 22:18:16 and successful resume at 22:18:18 were exercised with TalkBack continuously active. The speech overlay displayed the send-verification explanation during reconnect, then “Unavailable” and subsequently “Connection: Reconnecting (attempt 1 of 6)” after the visible app had already returned to Ready/Verified. This establishes delayed/conflicting state output in the sampled sequence; complete utterance history and the underlying queue/live-region cause were not captured.
- During earlier settings excursions, resume twice failed with “Start a new conversation deliberately”; explicit New conversation restored it. Cause was not established; subsequent restart/interrupted-turn and recovery work on main (e.g. #158, #157, #160) may change this behavior, so it **needs re-verification on current origin/main**. The later network and final settings-return resumes succeeded, so this is not recorded as a universal background-resume failure.

Restored enabled accessibility services to absent (`null`) and accessibility_enabled to 0; disabled Display speech output and verified its original false switch state. The startup notification dialog had granted TalkBack notification permission during keyboard handling; revoked that grant and cleared the newly set user-set flag, with final granted=false readback. Other observed developer toggles matched their starting states. Final radios were 1/1, Amanda Standard was Verified/Ready, its TALKBACK_OK reply remained, and the draft was empty. Temporary captures were removed and the original host foreground application restored.

Verdict: TalkBack acceptance remains open. The clipped-focus and stale recovery/notification speech observations were made before later main commits aimed at them (#153 focus reveal, #154 neutral reply caption, #156 recovery status on focus) and **need re-verification on current origin/main**; no result on current main is claimed. Actual spoken-output text was observed through TalkBack's overlay, not a human listening assessment. Touch-gesture traversal, full header/menu order, long replies and physical-device accessibility are not claimed passed.
