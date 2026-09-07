# Streamy — Code Review Follow-up (2026-09-08)

Scope: verifies every P1/P2/P3 finding from the 2026-09-07 audit,
implemented via `docs/plans/2026-09-08-code-audit-fixes.md` (Tasks 0–19).

## Validation performed this session

| Check | Result |
|---|---|
| `./gradlew :app:testDebugUnitTest --rerun-tasks` | **BUILD SUCCESSFUL — 64 tests, 0 failures, 0 errors, 5 skipped** (env-gated live tests) |
| `./gradlew :app:assembleRelease` | BUILD SUCCESSFUL |
| `./gradlew :app:lintDebug` | BUILD SUCCESSFUL, no new errors |
| Null-intent / notification-ID paths | Verified by inspection (Task 14: IDs distinct) |

## Findings → fix → commit

| # | Finding | Fix commit |
|---|---|---|
| P1-1 | Terminal errors erased by teardown | `faae45e` preserve terminal Error (`resolveTeardownState`, both services) + `TeardownStateTest` 3/3 |
| P1-2 | Auth NAK retried 10x | `b0face0` fail-fast on `ProtocolException` (+ red test `fcd67ac`) |
| P1-3 | `AudioCapture` read-error spin + `record!!` race | `2ba3edd` local ref, error counting, 10 ms backoff |
| P1-4 | Stop-during-record-arm re-arms | `195a31f` `!_isRecording` bail guard |
| P1-5 | Test creds in all build types | `076f710` TEST_* fields debug-only |
| P2-6 | Stale queue replay on reconnect | `0973aa6` `clearBacklog()` after connect |
| P2-7 | Unbounded socket connect | `ff15445` 6 s `InetSocketAddress` connect, both clients |
| P2-8 | AAC-over-Icecast reachable | `cca4441` `isSupported` rule + test; `c5e5c42` VM/form guards; `4eeff25` service coerce |
| P2-9 | VU levels recompose Home @30fps | `bc636c9` flow collection scoped into `VuMeter` |
| P2-10 | Null-intent sticky restart misses FGS | `0b97852` re-promote + stop, both services |
| P2-11 | Shared notification ID? | Verified distinct (`0x5bee7`/`streamy_stream` vs `0x5bee8`/`streamy_record`) — no change |
| P2-12 | `AacEncoder` missing close-guard | `4031275` `check(!closed)` + volatile counter |
| P2-13 | Three Room instances | `bc636c9` `DatabaseHolder` singleton, close calls removed |
| P2-14 | Status parse rejects multi-space | `ac0b2d2` `Regex("\\s+")` split + test |
| P3 | HE-AAC param, stripAdts name, filenames | `b00736f` filenames; `e0ed9e8` renames |

## Notes

- Fix-up commit inside `bc636c9`: `VuMeter` delegated-property smart-cast
  (`snapshot?.let { prevSamples = it.samples }`) — broke `assembleDebug`
  after the Task 12 signature change; caught by full build, not by unit tests.
- Peacock's Task 11/18 commit messages reference sibling hunks owned by other
  workers; content verified correct per-file (service coerce `4eeff25`,
  filenames in `b00736f`).
- Deferred (conscious, unchanged): plaintext passwords in Room, `START_STICKY`
  profile-id loss on process death, HE-AAC v2, minification off, `file(GLOB)`
  in CMake, unused admin columns, hardcoded genre/icy-url.
