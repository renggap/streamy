# Streamy App

Android app that broadcasts **live speech** from the microphone to a **SHOUTcast v2 (DNAS2)** server, running reliably in the **background**, with a Hallmark-designed broadcast-console UI.

[![status](https://img.shields.io/badge/status-v1.0.0-FF3B30)]() [![platform](https://img.shields.io/badge/platform-Android-3DDC84)]() [![minSdk](https://img.shields.io/badge/minSdk-24-00B0FF)]()

## Features (v1.0.0)

- **Live speech → SHOUTcast v2 (DNAS2)** — captures the mic, encodes, and streams over the real **Ultravox 2.1** source protocol (live-verified against DNAS2 2.6.1).
- **MP3 encoding** via `libmp3lame` (NDK/JNI) — bitrates **16 / 24 / 48 / 64 / 96 / 128 / 192 kbps**, sample rates 16 / 22.05 / 44.1 / 48 kHz, mono/stereo. (AAC encoding is implemented via `MediaCodec` but disabled in the UI for v1.0.0 — being revisited.)
- **Background streaming** — foreground service (`microphone` type) holding a `PARTIAL_WAKE_LOCK` + `WifiLock`; survives screen-off and backgrounding. `START_STICKY` reconnects after a process kill.
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
                                                    └──> in-stream 0x902 metadata
      (pre-encoder tap ──> LevelFeed ──> VU meter)
```

`StreamService` (foreground) owns the pipeline; `StreamyViewModel` mirrors state + levels for the Compose UI.

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
./gradlew :app:testDebugUnitTest      # JVM unit tests (no network)
```

Requires Android SDK + NDK 27.0.12077973 + CMake 3.22.1 (JDK 17/21, AGP 8.7.3, Kotlin 2.0.21). APK: `app/build/outputs/apk/debug/app-debug.apk`.

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
  net/      UvoxClient (Ultravox 2.1), UvoxXtea, SourceClient, StreamTransport, ProtocolException
  service/  StreamService (foreground), StreamState, ServiceController
  data/     Room: ProfileEntity/Dao/AppDatabase, AudioConfig, ProfileRepository
  ui/       Theme, StreamyViewModel, Home, VuMeter, BitrateChooser, profiles, settings
app/src/main/cpp/   libmp3lame (vendored, LGPL) + lame_jni.cpp + CMake
docs/plans/         design + implementation plans + protocol findings
```

## License

`libmp3lame` (LAME) is **LGPL-2.0+**, vendored under `app/src/main/cpp/lame/` and built as a shared `.so`. Notice + source-offer in `app/src/main/res/raw/lame_notice.txt` (surfaced in-app → Settings). The rest of the app is the author's to license.
