---
title: 'Android open defects and unverified behavior'
type: 'tickets'
created: '2026-09-12'
status: 'open'
baseline_commit: 'bb14eb1'
context:
  - '_bmad-output/implementation-artifacts/validation-a-4-relay-configuration.md'
  - '_bmad-output/implementation-artifacts/validation-a-6-hands-free.md'
  - '_bmad-output/implementation-artifacts/validation-a-8-response-audio-playback.md'
---

Found during the first real-device session on a Pixel 6a against the live
relay, 2026-09-12. Everything here is outside the four fixes in PR #25.

## `ANDROID-BUG-F1` — off-tailnet state is unreachable for the live endpoint

`A-4` requires an honest off-tailnet unavailable state. Detection depends on
`UnknownHostException`, which cannot fire for
`wss://voice-amanda.chappell-home.dev/voice-session`: the name resolves
**publicly** to a Tailscale address (`100.106.8.34`), verified against
`8.8.8.8`. Off the tailnet, DNS succeeds, the socket waits out the 10 s connect
timeout, that classifies as `Retryable`, the bounded ladder exhausts, and the
user sees a bare `Disconnected`.

The story's acceptance criterion is therefore unmet in practice, though it was
met against the original `media-server.<magicdns>` endpoint, which only
resolved through MagicDNS. The Caddy arrangement changed the hostname and
silently invalidated the detection strategy.

A connect timeout to `100.64.0.0/10` is a far stronger off-tailnet signal than
DNS failure. This probably deserves an upstream story identity rather than a
local ticket, since it is `A-4` acceptance scope.

## `ANDROID-BUG-F2` — the reconnect ladder discards its reason

`AndroidRecovery.recover()` tracks `lastReason` across every retry and drops it
when the ladder exhausts, so `Disconnected` — the state a user actually reaches
— is the least informative one available. Surfacing the retained reason would
have identified `ANDROID-BUG-F1` immediately instead of requiring a code read.

## `ANDROID-BUG-F3` — response audio arrives slower than real time

`AudioFlinger` reported `BUFFER TIMEOUT ... due to underrun` four times during a
single response, each followed by a track restart. Playback was **inaudible**ly
affected — it sounded correct — but the underruns are what made the drain guard
unsatisfiable (fixed in PR #25 by other means). The underlying streaming rate is
untouched. Same territory as Puck `P-5`.

## `ANDROID-WATCH-F1` — `Listening` with no binding

Since PR #25, hands-free reopen sets the phase to `Listening` before any turn
exists, so the phase can be non-terminal with a null `binding`. Nothing reads
`binding` in that window today and `A-3`'s ladder keys off connection state, but
recovery and interruption while hands-free is armed should be checked here
first.

## Unverified on hardware

- **Echo and barge-in** (`A-6`). Playback was audible through the device
  speaker, but no deliberate barge-in was attempted. Whether the speaker leaks
  into the next capture window is still open — the last piece of `A-6`'s
  environment limitation.
- **TalkBack navigation** (`5-A-2`). No screen reader pass has ever been run.
  Announcement wording, verbosity, and gesture navigation are unproven.
- **Contrast measurement** (`5-A-2`, `UX-DR21`). No contrast ratio has been
  measured against the WCAG 2.2 AA target.

## Release engineering

- **`versionCode` caps minor and patch at 99.** `major * 10000 + minor * 100 +
  patch` fails the build beyond that, deliberately: a silent rollover would
  produce a *lower* code than the previous release and break upgrades. Widen the
  formula before any `0.100.x`.
- **`v0.2.0` still publishes `hermes-relay-android-0.2.0-unsigned.apk`**, which
  cannot be installed. Cosmetic; the tag predates signing so it cannot be
  rebuilt from its own tree.

## Diagnosability

The app contains **zero** `android.util.Log` call sites. That keeps a
privacy-sensitive client quiet by default and is likely deliberate, but it meant
this session's diagnosis rested entirely on platform `AudioFlinger` logs. A
debug-only, opt-in log of phase transitions and transport outcomes would have
made every defect here faster to find. Worth a deliberate decision rather than
remaining an accident.
