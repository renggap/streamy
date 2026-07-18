# Streamy App — Design

**Date:** 2026-07-18
**Status:** Approved (brainstorm phase)
**Target:** Android app that captures live speech from the microphone and broadcasts it to a SHOUTcast v2 (UVOX) server.

## 1. Goal

Capture microphone audio on an Android device, encode it, and stream it live to a SHOUTcast v2 (DNAS2 / Ultravox) server over TCP, while the app runs in the background. Let the user choose codec and bitrate. Make the UI distinctive (Hallmark design).

## 2. Non-goals (YAGNI — deferred)

- EQ / AGC / limiter DSP, multi-bitrate, OAuth/stream-key manager, themes, QR share, Bluetooth mic selection. (v1 = "Standard" scope.)
- Video capture.
- Receiving / playing streams back.
- A `:stream` separate process (only if UI jank appears).

## 3. Target & defaults

| Item | Value |
|---|---|
| Server protocol | SHOUTcast v2 (UVOX/Ultravox), legacy ICY as fallback |
| Audio codecs | AAC (MediaCodec, native) **and** MP3 (`libmp3lame` via NDK), user-selectable |
| Language | Kotlin |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 35 |
| UI | Jetpack Compose + Material3, single-activity, MVVM |
| Persistence | Room (server profiles) |
| Async | Coroutines + `HandlerThread` for audio path |
| DI | None — manual constructor wiring |
| Native | One NDK module wrapping `libmp3lame` (LGPL); AAC path has zero native code |
| Visual design | `hallmark` skill (anti-AI-slop, distinctive) — loaded during UI implementation |

## 4. Architecture (chosen approach: native lean pipeline)

```
mic ──AudioRecord──> PCM (ShortArray, ~26ms frames)
      │
      AudioPipeline ──> Encoder (AAC | MP3) ──> encoded frame (ByteArray)
      │                                              │
      │                       StreamTransport bounded queue (drop-oldest on overflow)
      │                                              │
      │                                       UvoxClient.sendFrame()
      │                                              │
      └── metadata tick (1/s) ──────────────> UvoxClient.sendMetadata(now-playing)
                                                     │
                                              TCP Socket ─> DNAS v2
```

Rationale: smallest APK (~+300KB for LAME), full control of UVOX2 wire protocol and reconnect logic, AAC path needs no native code. The only cost is implementing the UVOX2 client — the core value of the app and a few hundred well-documented lines.

## 5. Components / package layout

```
app/src/main/java/com/streamy/app/
  ui/                      Compose: Home, ServerProfiles, Settings, EditProfile
  data/
    db/                    Room: ProfileDao, ProfileEntity, AppDatabase
    ProfileRepository.kt
  audio/
    AudioCapture.kt        AudioRecord wrapper: PCM 16-bit, configurable rate/channels
    Encoder.kt             interface encode(pcm: ShortArray) -> frames
    AacEncoder.kt          MediaCodec AAC-LC / HE-AAC v1; strips ADTS (raw AAC for UVOX)
    Mp3Encoder.kt          JNI bridge to libmp3lame
    EncoderFactory.kt      picks AAC vs MP3 from profile
    AudioPipeline.kt       glues capture -> encoder, pushes frames to transport
  net/
    UvoxClient.kt          SHOUTcast v2 Ultravox TCP client (handshake/auth/metadata/data)
    IcyClient.kt           legacy ICY fallback (v2 DNAS accepts it)
    StreamTransport.kt     owns Socket, send loop, backpressure, reconnect
    ProtocolException.kt
  service/
    StreamService.kt       foreground Service; lifecycle owner for pipeline + transport
    StreamState.kt         sealed: Idle|Connecting|Live|Reconnecting|Error(reason)
  StreamyApp.kt            Application
cpp/
  lame_jni.cpp             JNI: Lame_init, encode_buffer, flush, close
  CMakeLists.txt           links prebuilt libmp3lame (.a / .so)
```

## 6. Threading & lifecycle

- `AudioRecord.read` + encode run on a dedicated audio `HandlerThread` — never the main thread.
- Socket send runs on an IO coroutine.
- A bounded queue (Channel) decouples them: a slow socket drops the oldest PCM rather than blocking capture or OOMing. Drop count is logged → stats.
- `StreamService` (foreground, type `microphone`) owns pipeline + transport. UI binds to the service and observes `StreamState` as a `StateFlow`.

## 7. Permissions

```
RECORD_AUDIO
INTERNET
ACCESS_NETWORK_STATE
FOREGROUND_SERVICE
FOREGROUND_SERVICE_MICROPHONE   (API 34+)
WAKE_LOCK
ACCESS_WIFI_STATE
POST_NOTIFICATIONS              (runtime, Android 13+)
```

All runtime permissions requested at first launch. Denied → UI shows rationale + blocks Start.

## 8. Background streaming (hard requirement: must keep running)

- `StreamService` is a **foreground service**, type `microphone`. `startForeground` posts a **persistent notification with a Stop action**. Foreground services are exempt from Doze network/CPU restrictions → stream survives screen-off and backgrounding.
- **`PARTIAL_WAKE_LOCK`** held while `Live` → CPU keeps encoding with screen off. Released on Stop/error.
- **`WifiLock` MODE_FULL_HIGH_PERF** held while `Live` → Wi-Fi radio stays active. Released on Stop.
- `onStartCommand` returns **`START_STICKY`** → if the OS kills the process, Android restarts the service and it reconnects with the last profile. An idempotent connect guard prevents duplicate streams.
- **FGS timing:** `startForeground` called within 5s of `startForegroundService()` (Android 12+). Permission check happens first; the notification is posted immediately, then connection proceeds. Connect failures surface as `StreamState.Error`, not by delaying FGS.

## 9. Bitrate chooser (must-have, first-class UI control)

Prominent on Home, always visible while Idle.

- **AAC:** 16 / 24 / 32 / 48 / 64 / 96 / 128 kbps
  - At **16/24**, prefer **HE-AAC v1 (AAC+)** where the device encoder supports it; fall back to AAC-LC. Force **mono**, sample rate **16000 / 22050 Hz**.
- **MP3:** 16 / 24 / 48 / 64 / 96 / 128 / 192 kbps
  - 16/24 mono is clean (LAME supports down to 8 kbps). Same low sample-rate recommendation.
- Sample rate: 16000 / 22050 / 44100 / 48000 Hz. Channels: mono (default for speech) / stereo.
- Changing codec/bitrate while **Live** → soft stop + reconnect (these are connection-time params in UVOX). UI warns before applying.

## 10. Error handling

- **Encoder errors** (MediaCodec init fail, LAME init fail) → `StreamState.Error("encoder")`; stop pipeline; release locks.
- **Network errors** (broken socket, DNS, auth NAK) → `Reconnecting` with exponential backoff 1→2→5→10→20s (cap 20s, jitter), max 10 attempts, then `Error("network")`. Encoder is re-initialized on each reconnect (flush + recreate) to avoid drift.
- **Mic busy** (`AudioRecord` unroutable / state loss) → `Error("mic_busy")`; notify user.
- **Auth failure** (bad password/sid) → `Error("auth")` with a clear message.
- **Backpressure:** bounded queue drops oldest PCM when the socket is slow. Drop count surfaced in stats.
- **Graceful stop:** LAME flush returns final frames; remaining queued frames are sent; socket closed cleanly; locks released; foreground stopped. No buffered audio is lost on a normal Stop.

## 11. Testing

- **Unit (JVM, JUnit):**
  - `UvoxClient` framing — encode/decode handshake + auth frames against golden-byte fixtures captured from a real DNAS2 handshake.
  - `EncoderFactory` selection; bitrate → config mapping (incl. 16/24 HE-AAC path).
  - `AudioPipeline` backpressure — feed faster than drain, assert drops, no block, no OOM.
  - Reconnect backoff schedule, cap, jitter bounds.
- **Instrumented (device, `androidTest`):**
  - `AacEncoder` round-trip via real MediaCodec (PCM in → AAC frames, length sanity).
  - `StreamService` lifecycle — Start → Live → Stop; foreground notification present; no leaked wakelock/wifilock after teardown.
- **Integration / live:** Docker Icecast2 + SHOUTcast v2 (DNAS2) on localhost; stream 10s of tone, assert server received bytes + metadata, then stop. Manual smoke checklist against a real server in the appendix.
- **Runnable self-check:** `UvoxClientFramingTest` — assert-based golden-byte checks, no framework beyond JUnit.

## 12. Legal / licensing note

`libmp3lame` is LGPL. Ship as a shared `.so`; provide source/offering in an About screen. MP3 itself has no remaining patent encumbrance (as of this date) but document the LGPL notice for LAME.

## 13. Open items / assumptions

- Dev test target: local Icecast2 + DNAS2 Docker containers (assumed; confirm before integration tests).
- UVOX2 protocol details to be locked from the Ultravox 2.1 spec during `UvoxClient` implementation; golden bytes captured from a real server handshake.
- If device lacks HE-AAC v1 encoder at 16/24 kbps, fall back to AAC-LC and log it.
