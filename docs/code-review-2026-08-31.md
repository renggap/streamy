# Streamy — Comprehensive Code Review (2026-08-31)

Scope: full `app/` source (Kotlin + JNI/CMake), tests, gradle, manifest, res/xml.
Prior review: `code-review-2026-08-03.md` (16 findings, all fixed).

## Validation performed this session

| Check | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest` (clean `--rerun-tasks`) | **58 tests, 0 failures, 0 errors**, 5 env-gated skipped |
| `./gradlew :app:assembleDebug` | BUILD SUCCESSFUL (LAME NDK build included) |
| `./gradlew :app:assembleRelease` + `lintVitalRelease` | BUILD SUCCESSFUL (`app-release-unsigned.apk`, 7.7 MB) |
| E2E `IcecastSourceClientLiveTest` vs az1.radioislam.my.id:8035 | **PASSED** — SOURCE handshake 2xx, 40 KB audio accepted |
| E2E `UvoxClientLiveTest` vs az1.radioislam.my.id:8030 | Blocked: server NAK `Stream In Use` (mount on-air, 183 listener hits; `STREAMSTATUS=1` re-verified 3× over 15 min). AAC fixture generated at `/tmp/uvox.aac` for a future run. Not retried against the live station. |
| androidTest tier (instrumented) | Not runnable: no `/dev/kvm`, no adb device on this host |
| Repo hygiene | `docs/lame-4.0.tar.gz` + `graphify-out/` gitignored (commit `383e66a`); tree clean |

## Findings fixed (commit `d25dbd6`)

1. **`WireSafety.truncate` split surrogate pairs** (P2) — `take(max)` could emit a lone
   surrogate into UTF-8 wire payloads (Ultravox 0x902 XML, ICY StreamTitle). Now backs off
   one char when the cap lands between a high/low surrogate. +2 regression tests.
2. **`UvoxClient.close()` could hang teardown** (P2) — the best-effort Terminate (0x005)
   write to a dead peer blocks in kernel TCP retransmit for minutes; `stopAndShutdown`
   runs on the main thread → ANR vector. Close now runs the write on a daemon thread
   bounded by a 500 ms join (`CLOSE_JOIN_MS`), then force-closes the socket.
3. **`StreamyViewModel.setBitrate` comment vs behavior** (P3) — the KDoc claimed callers
   surface `require()` failures as user messages; in reality no caller catches. Chooser
   feeds only ladder values, so the comment now states the real invariant.

## Reviewed, deliberately not changed (existing `ponytail:` markers track them)

- Passwords stored plaintext in Room (no export/backup guard).
- Dual Room connections (`StreamService` + `StreamyViewModel` open `streamy.db`).
- `START_STICKY` restart relies on intent extras surviving process death.
- HE-AAC v2 (Parametric Stereo) for ≤24 kbps mono — pending on-device verification.
- No DI (Hilt), type-safe nav — flagged Task 14 in prior review.

## Overall assessment

High quality for its size. Wire safety (`WireSafety` sanitizers, numeric status parsing,
ADTS layout, XTEA golden vectors), lifecycle discipline (single idempotent teardown,
single-writer encoder confinement, `shuttingDown` latch), and the test story (58 unit
tests + env-gated live wire tests against a real DNAS2/harbor) are stronger than most
production apps of this size. The three findings above were the only new defects since
the 2026-08-03 review; all fixed and covered.
