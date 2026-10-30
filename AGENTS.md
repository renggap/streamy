# AGENTS.md — Streamy

Android app (Kotlin/Compose) that streams mic audio to SHOUTcast v2 (DNAS2) / Icecast-style servers over Ultravox 2.1 or ICE/1.0, plus a standalone MP3 recorder. Static marketing site in `web/`.

## Commands

```bash
./gradlew :app:assembleDebug       # debug APK (native NDK MP3 lib builds via CMake)
./gradlew :app:assembleRelease     # signed only if keystore set in local.properties
./gradlew :app:testDebugUnitTest   # JVM unit tests, no network (baseline: 104 tests, 7 env-gated skips)
./gradlew :app:connectedDebugAndroidTest  # instrumented; needs device/emulator + real server creds
```

Live network tests (`*LiveTest`) and instrumented tests are **env-gated**: they read `streamy.test.*` from gitignored `local.properties` and skip when absent. Never fake or commit those creds.

## Layout

```
app/src/main/java/com/streamy/app/
  audio/   AudioCapture, AudioPipeline, Encoder, Mp3Encoder (JNI), AacEncoder, StreamSink, LevelTap/LevelFeed
  net/     UvoxClient (Ultravox 2.1 + XTEA), ShoutcastV1Client (v1 DJ login), FallbackSourceClient (DJ: v1 → Ultravox), IcecastSourceClient (ICE/1.0), PlainHttp (raw-socket title GETs), StreamTransport, WireSafety
  service/ StreamService (live stream), RecordService (standalone recorder), ServiceController, StreamState/RecordState
  data/    Room (ProfileEntity/Dao/AppDatabase), Models (AudioConfig, BroadcastMode, DjProtocol), ProfileRepository
  ui/      Compose: HomeScreen, VuMeter, BitrateChooser, EditProfileScreen, ServerProfilesScreen, SettingsScreen, StreamyViewModel
app/src/main/cpp/   vendored libmp3lame (encoder-only, config.h preconfigured; decoder/mpglib intentionally not vendored) + lame_jni.cpp
app/scripts/        dev reference tools, not app code: xtea_ref.py (XTEA golden vectors), lame_readback.c (host-side LAME probe)
app/src/test/       JVM tests; silence.mp3 is a test fixture
docs/               dated plans, protocol findings, code reviews — history, do not delete
web/                static marketing/panel site (Cloudflare Pages), Bahasa Indonesia
```

## Hard rules

- **LAME is LGPL.** Vendored under `app/src/main/cpp/lame/`. Don't strip `app/src/main/res/raw/lame_notice.txt`; keep the shared-.so build model. When touching `cpp/`, verify with a full `:app:assembleDebug` (CMake glob only picks `libmp3lame/*.c`).
- **Protocol behavior is live-verified.** Ultravox handshake, XTEA auth, ADTS-wrapped AAC, in-stream `0x902` metadata are pinned by golden vectors (`UvoxXteaTest`) and live tests. Read `docs/plans/2026-07-18-uvox-finding.md` before changing `net/`. If a protocol change is unavoidable, keep golden vectors in sync deliberately.
- **Teardown/idempotency discipline.** Services use single idempotent teardown paths and race guards; `TeardownStateTest`, `WireSafetyTest` pin this. Preserve those patterns in `StreamService`/`RecordService`/`AudioPipeline`.
- **Secrets stay in `local.properties`** (gitignored): test-server creds and release keystore (`streamy.keystore.file/.alias/.password`). `streamy-release.jks` is gitignored too.
- **AAC via MediaCodec exists but is UI-disabled** (`BitrateChooser(allowAac = false)` at every call site; DNAS quirks, see protocol notes in README). Don't re-enable without re-verifying against a real DNAS2.
- **CI is GitHub Actions** (`.github/workflows/android.yml`): tests + lint + debug APK + 16 KB alignment check on every push/PR; signed release only from repo secrets. Keep `NDK_VERSION`/`CMAKE_VERSION` there in sync with `app/build.gradle.kts`.
- **Room schema is exported** to `app/schemas/` — commit the new JSON whenever `AppDatabase.version` bumps.
- Cleartext traffic is intentional (plain-TCP source protocol); see `network_security_config.xml`.

## Conventions

- Kotlin, JDK 17 target, Compose + Room (KSP), coroutines. State flows from services → ViewModel → UI; UI never touches sockets directly.
- Comments on protocol/concurrency code explain *why* (server quirks, race windows) — keep that style.
- After changes, run `:app:testDebugUnitTest` minimum; full `:app:clean :app:assembleDebug` when native code moved.
