---
title: 'Validation — A-4 relay configuration, Keystore credentials, and live transport'
type: 'validation'
story_id: 'A-4'
created: '2026-09-12'
status: 'done'
---

## Scope

Validate relay profile validation and persistence, Keystore-sealed credential
storage, the `hello`/`hello_ack` handshake, the failure-to-outcome mapping that
`A-3`'s bounded ladder consumes, the configuration surface, and a live
authenticated Session against the household Hermes relay. Turn submission,
normalized events, microphone, PCM, speaker, and Device operations are out of
scope and remain unimplemented.

## Checks

- `./gradlew testDebugUnitTest assembleDebug lintDebug --no-daemon` — passed
  under explicit JDK 21. 47 unit tests, 0 failures (23 new across
  `OkHttpRelaySessionClientTest` and `RelayProfileTest`); debug APK assembled;
  lint reported no blocking findings.
- `./gradlew connectedDebugAndroidTest --no-daemon` — passed on
  `hermes-relay-api36` (Android 16 / API 36). 11 instrumentation tests, 0
  failures, 0 skipped.
- Live gate — passed. `LiveRelayHandshakeTest` connected from the emulator to
  `wss://media-server.<magicdns>/voice-session`, sent `hello` as
  `client_id: amanda-laptop` / `device_id: android`, and received a `hello_ack`
  carrying a Session identity: 1 test, 0 failures. The token was supplied as an
  instrumentation argument read directly from the server and was never written
  to source, to a profile file, or to any log.
- `scripts/check-apk-metadata.sh` — passed; APK declares minimum SDK 26.
- `git diff --check` — passed before commit.

## Evidence notes

- **The live gate caught a real defect on its first run.** The app declared no
  `INTERNET` permission — the bootstrap manifest never needed one — so the
  first live attempt failed with `SecurityException: Permission denied (missing
  INTERNET permission?)`. Deterministic `MockWebServer` tests could not have
  found this: the loopback server is reachable without the permission that a
  real network requires. The permission was added and the gate then passed.
- **The `allowed_users` question is resolved.** The relay checks
  `client_id not in self._allowed_users` and derives `chat_id` as
  `f"{client_id}:{device_id}"`. An Android client using the existing
  `amanda-laptop` client id with its own device id is admitted with no server
  change, and receives a distinct chat identity. Confirmed by source reading
  and then by the live handshake.
- **Excluding the live test needed care.** Marking it skipped via `Assume`
  recorded an assumption failure as a `<failure>` in the result XML. It is now
  excluded by a `@LiveRelay` annotation and a `notAnnotation` default. Passing
  an *empty* `notAnnotation` override does not clear that default — the run
  silently executes zero tests and still reports success. The documented
  command overrides it with `org.junit.Ignore` instead, which was confirmed to
  run exactly one test.
- Unit coverage spans the successful handshake and its frame contents, 401 and
  403 rejection, unknown host reporting Tailscale guidance, protocol mismatch,
  a relay `error` frame, refused connection, acknowledgement timeout,
  mid-handshake disconnect, both no-socket short circuits, snapshot
  authorization states, endpoint validation, collection JSON round-trip without
  a token, and credential deletion alongside profile deletion.
- Instrumentation coverage spans the Keystore round trip, confirmation that the
  persisted envelope is not the plaintext token, deletion, per-profile
  isolation, the configuration screen never rendering a saved token back, and
  field-level refusal of a cleartext endpoint.

## Server-side changes made for this story

Recorded here because they are outside this repository and were required for
the live gate:

- `voice_session.extra.host` in the `amanda` profile on `media-server` changed
  from `100.90.186.57` to `0.0.0.0`, so `tailscale serve` can proxy the relay
  through loopback. The prior file was backed up, the YAML validated, and the
  gateway restarted with `launchctl kickstart`.
- `tailscale serve --bg --https=443 http://127.0.0.1:8792` added, tailnet-only,
  alongside the pre-existing `:8443` mapping which was left untouched.

The binding change widens exposure from the tailnet to the household LAN as
well. Unauthenticated requests are still rejected with 401. Tightening back to
loopback would require moving the iOS client to the `wss://` endpoint too.
