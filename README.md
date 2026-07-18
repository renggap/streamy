# Streamy App

Android app that broadcasts **live speech** from the microphone to a **SHOUTcast v2 (DNAS2)** server, running reliably in the **background**, with user-selectable codec and bitrate. Hallmark-designed UI.

## Status

End-to-end **live-verified** against a real DNAS2 v2.6.1 server: the app's `IcyClient` (real Kotlin client) publishes MP3 and the server reports `STREAMSTATUS=1`.

## Pipeline

```
mic ──AudioRecord──> PCM ──> Encoder (AAC via MediaCodec | MP3 via libmp3lame NDK)
      └──> StreamTransport (bounded queue + reconnect) ──> IcyClient (SHOUTcast v1 source) ──> DNAS2
```

`StreamService` is a **foreground service** (type `microphone`) holding a `PARTIAL_WAKE_LOCK` + `WifiLock` so streaming survives screen-off / backgrounding. `START_STICKY` reconnects after a process kill.

## Protocol note (important)

DNAS2 v2 here accepts the **SHOUTcast v1 source protocol** (password-line → `OK2` → icy headers → raw bytes), **not** Icecast HTTP-PUT and **not** Ultravox framing for MP3. Full details: [`docs/plans/2026-07-18-uvox-finding.md`](docs/plans/2026-07-18-uvox-finding.md).

## Bitrates

- **MP3:** 16 / 24 / 48 / 64 / 96 / 128 / 192 kbps (LAME, verified on DNAS2)
- **AAC:** 16 / 24 / 32 / 48 / 64 / 96 / 128 kbps (HE-AAC v1 at 16/24, mono) — **AAC-over-v1 on DNAS2 is unverified**; MP3 is the proven path.

## Build

```bash
./gradlew :app:assembleDebug          # debug APK (native MP3 lib included)
./gradlew :app:testDebugUnitTest      # JVM unit tests (no network) — 17 tests
```

Requires Android SDK + NDK 27.0.12077973 + CMake 3.22.1 (JDK 17/21, AGP 8.7.3, Kotlin 2.0.21).

## Test against a real server

Put your server in `local.properties` (gitignored):
```
streamy.test.host=...
streamy.test.port=...
streamy.test.sid=1
streamy.test.password=...
```

Live JVM test (real `IcyClient` → your server):
```bash
./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.IcyClientLiveTest" --rerun-tasks \
  -Dstreamy.host=$HOST   # creds read from local.properties via env; see test
```

Full-pipeline on-device (mic → encoder → service → server):
```bash
./gradlew :app:connectedDebugAndroidTest --tests "com.streamy.app.IntegrationTest"
```

## Design & plan

- Design: [`docs/plans/2026-07-18-streamy-app-design.md`](docs/plans/2026-07-18-streamy-app-design.md)
- Implementation plan: [`docs/plans/2026-07-18-streamy-app.md`](docs/plans/2026-07-18-streamy-app.md)
- Protocol finding: [`docs/plans/2026-07-18-uvox-finding.md`](docs/plans/2026-07-18-uvox-finding.md)

## Licensing

`libmp3lame` (LAME) is **LGPL-2.0+**, vendored under `app/src/main/cpp/lame/` and built as a shared `.so`. Notice + source-offer in `app/src/main/res/raw/lame_notice.txt` (surfaced in-app → Settings). The rest of the app is the user's to license.

## Known limitations / next steps

- AAC-to-DNAS2-over-v1 unverified (likely needs Ultravox). MP3 is the verified default.
- Now-playing metadata updates not wired (DNAS2 `admin.cgi?mode=updinfo` — needs admin creds).
- No launcher icon (uses system default); branding pending.
- Selected profile + codec/bitrate not persisted across process death (DataStore TODO).
- Release `isMinifyEnabled=false`; enable after a minified release smoke test (JNI keep rules are staged in `proguard-rules.pro`).
