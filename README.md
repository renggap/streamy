# Streamy App

Android app that broadcasts **live speech** from the microphone to a **SHOUTcast v2 (DNAS2)** server, running reliably in the **background**, with a Hallmark-designed broadcast-console UI.

[![status](https://img.shields.io/badge/status-v1.12-FF3B30)]() [![platform](https://img.shields.io/badge/platform-Android-3DDC84)]() [![minSdk](https://img.shields.io/badge/minSdk-24-00B0FF)]()

## Features (v1.12)

- **Live speech → SHOUTcast v2 (DNAS2)** — captures the mic, encodes, and streams over the real **Ultravox 2.1** source protocol (live-verified against DNAS2 2.6.1).
- **MP3 encoding** via `libmp3lame` (NDK/JNI) — bitrates **16 / 24 / 48 / 64 / 96 / 128 / 192 kbps**, sample rates 16 / 22.05 / 44.1 / 48 kHz, mono/stereo. (AAC encoding is implemented via `MediaCodec` but disabled in the UI for v1.0.0 — being revisited.)
- **Background streaming** — foreground service (`microphone` type) holding a `PARTIAL_WAKE_LOCK` + `WifiLock`; survives screen-off and backgrounding. `START_STICKY` reconnects after a process kill.
- **Two broadcast modes** — **SOURCE** (Streamy is the source: push encoded audio straight to DNAS2 `sc_serv`) or **DJ** (push to the Transcoder `sc_trans` on its `djport2`; sc_trans re-encodes). DJ mode speaks the HTTP ICE/1.0 source protocol (Icecast-style `SOURCE <mount>` + Basic auth) for AzuraCast / Liquidsoap, or — the default — SHOUTcast: it tries a v1 login on the entered port + 1 (what Centova sc_trans2 panels advertise) and falls back to Ultravox on the entered port. Selected per profile.
- **Standalone MP3 recorder** — `RecordService` records mic → local MP3 without broadcasting (direct MP3 pass-through or AAC→MP3 transcode); can run concurrently with a live stream.
- **Auto-reconnect** with exponential backoff (1→2→5→10→20s, 10 attempts) and an immediate **Stop-reconnect** control to abort during Connecting/Reconnecting/Error.
- **Admin-free now-playing metadata** — in-stream title updates via Ultravox `0x902` XML metadata. **No DNAS admin user/password required**; just type and tap UPDATE while live.
- **Real oscilloscope VU meter** — the actual mic PCM waveform (pre-encoder, RMS + decimated at ~30 fps) drawn as a glowing on-air-red trace with phosphor afterglow.
- **Bitrate / sample-rate / channels** as compact dropdowns; codec toggle (MP3 active).
- **Multiple server profiles** (Room) — host, port, stream id, password, mount, per-profile audio settings.
- **Hallmark UI** — "late-night tally-light" theme (deep ink, on-air red, signal green, monospace readout), custom app icon + monochrome notification icon.
- **Robust pipeline** — bounded drop-newest queue, idempotent teardown, setup/shutdown race guards, leak-free sockets/locks.

## Architecture

```
mic ──AudioRecord──> PCM ──> Encoder (MP3 libmp3lame | AAC MediaCodec)
      └──> StreamTransport (bounded queue + reconnect) ──> UvoxClient (Ultravox 2.1) ──> DNAS2
                                                    │        or IcecastSourceClient (ICE/1.0)
                                                    └──> in-stream 0x902 metadata
      (pre-encoder tap ──> LevelFeed ──> VU meter)
      (RecordService: mic ──> Encoder ──> local MP3 file, independent of streaming)
```

`StreamService` (foreground) owns the streaming pipeline; `RecordService` (foreground) owns standalone recording; `StreamyViewModel` mirrors state + levels for the Compose UI.

## Protocol notes (important)

Reverse-engineered / live-verified against DNAS2 2.6.1 — see [`docs/plans/2026-07-18-uvox-finding.md`](docs/plans/2026-07-18-uvox-finding.md):

- DNAS2's v1 source protocol is **MP3-only** (it mislabels AAC as `audio/mpeg` and resets the source). AAC requires **Ultravox 2.1**.
- The app uses the **Ultravox 2.1** source protocol for both codecs: cipher (`foobar`) → XTEA auth → mime → config → standby → data frames.
- **AAC must be ADTS-wrapped** — DNAS rejects raw AAC after ~2s (`BrokenPipe`) and shows `BITRATE=0`; ADTS framing fixes both the disconnect and the bitrate display.
- **In-stream `0x902` metadata** updates `SONGTITLE` with no admin creds.
- `usesCleartextTraffic` is intentional — SHOUTcast source is plain TCP/http to user-defined servers.

## Build

```bash
./gradlew :app:assembleDebug          # debug APK (native MP3 lib included)
./gradlew :app:assembleRelease       # signed release (keystore via local.properties, see below)
./gradlew :app:testDebugUnitTest      # JVM unit tests (no network)
```

Requires Android SDK + NDK 27.0.12077973 + CMake 3.22.1 (JDK 17/21, AGP 8.7.3, Kotlin 2.0.21). APK: `app/build/outputs/apk/debug/app-debug.apk`.

Release signing reads from gitignored `local.properties` (`streamy.keystore.file` / `.alias` / `.password`, optional `.keyPassword` when it differs from the store password); unsigned release APKs can't install.

### CI (GitHub Actions)

`.github/workflows/android.yml` runs on every push/PR: unit tests → lint → debug APK → 16 KB page-alignment check, uploading the APK and reports as artifacts. On `master` and `v*` tags a signed release APK is also built (and attached to a GitHub Release for tags) — only when these repository secrets exist, otherwise the job skips:

| Secret | Value |
|---|---|
| `STREAMY_KEYSTORE_BASE64` | `base64 -w0 streamy-release.jks` |
| `STREAMY_KEYSTORE_PASSWORD` | store password |
| `STREAMY_KEY_ALIAS` | key alias (default `streamy`) |
| `STREAMY_KEY_PASSWORD` | key password, only if different from the store password |

Live/instrumented tests don't run in CI (they need real server creds).

## Test against a real server

Put your server in `local.properties` (gitignored):
```
streamy.test.host=...
streamy.test.port=...
streamy.test.sid=1
streamy.test.password=...
```

```bash
# real Kotlin client → your DNAS2 (JVM, live)
./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.UvoxClientLiveTest" --rerun-tasks

# full pipeline on a device (mic → encoder → service → server)
./gradlew :app:connectedDebugAndroidTest --tests "com.streamy.app.IntegrationTest"
```

## Project structure

```
app/src/main/java/com/streamy/app/
  audio/    AudioCapture, Encoder, AacEncoder, Mp3Encoder (NDK), AudioPipeline, LevelTap/LevelFeed
  net/      UvoxClient (Ultravox 2.1), UvoxXtea, ShoutcastV1Client (v1 DJ), FallbackSourceClient, IcecastSourceClient (ICE/1.0), SourceClient, StreamTransport, ProtocolException
  service/  StreamService (stream), RecordService (standalone recorder), StreamState/RecordState, ServiceController
  data/     Room: ProfileEntity/Dao/AppDatabase, AudioConfig, BroadcastMode/DjProtocol, ProfileRepository
  ui/       Theme, StreamyViewModel, Home, VuMeter, BitrateChooser, profiles, settings
app/src/main/cpp/   libmp3lame (vendored, LGPL) + lame_jni.cpp + CMake
app/scripts/        dev-time reference tools (xtea_ref.py golden vectors, lame_readback.c host probe)
docs/               design + implementation plans + protocol findings + code reviews
web/                marketing/panel site (Cloudflare Pages, static HTML, Bahasa Indonesia)
```

## License

`libmp3lame` (LAME) is **LGPL-2.0+**, vendored under `app/src/main/cpp/lame/` and built as a shared `.so`. Notice + source-offer in `app/src/main/res/raw/lame_notice.txt` (surfaced in-app → Settings). The rest of the app is the author's to license.
