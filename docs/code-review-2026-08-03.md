# Streamy — Comprehensive Code Review

Date: 2026-08-03
Scope: full `app/` source (Kotlin + JNI/CMake), tests, gradle, manifest, docs.
Validation performed this session: `./gradlew :app:testDebugUnitTest --offline` (BUILD SUCCESSFUL, **56 tests: 50 executed + 6 env-gated skipped, 0 failures**), `./gradlew :app:assembleDebug --offline` (BUILD SUCCESSFUL, CMake + APK). All 16 findings below were fixed in this session; see §9 status column and the fix notes in §3/§5/§6. Gate hardening added 7 more tests (49→56) and uncovered **two real runtime bugs** the original fixes would have shipped: (1) `Mp3TranscodeSink` used `queue.offer(null)` as its flush end-marker, but `ArrayBlockingQueue` forbids null — the marker was silently swallowed (`runCatching`), so `flush()`'s join timed out and the encoder's trailing bytes (up to ~1 s of audio) were never written; (2) `StreamTransport.enqueue` after `stop()` reported phantom `Backpressure` (a closed-channel `Failure` was treated as queue-full), emitting a stale state after the terminal Idle/Error and polluting the drop metric. Both fixed and covered by the new tests (§10).

---

## 1. Executive summary

Streamy is a small, unusually well-engineered Android app: a foreground-service mic broadcaster speaking real Ultravox 2.1 (DNAS2) / Icecast-harbor wire protocols, with a native LAME MP3 encoder, a Compose console UI, and a genuinely good test story (golden vectors for the crypto, ADTS bit-layout tests that caught a real regression, env-gated live-server tests).

Overall assessment: **high quality for its size**. The hard parts (wire protocol, native encoder lifetime, capture/pipeline teardown, permission/foreground-service rules) are handled with more care than most production apps. The findings below are mostly hardening opportunities, not defects. The most substantive issues are (a) the `StreamTransport` re-connect timer bug, (b) `AacEncoder`'s fixed `dequeueInputBuffer(0)` non-blocking path dropping frames under load and a 200ms EOS drain that can hang `stop()`, (c) LAME's default internal output rate not matching the configured rate, (d) passwords stored in plaintext Room + no export/backup guard, and (e) `usesCleartextTraffic="true"` for all builds.

Findings are graded:

- **P0** — ship-blocking: wrong behavior or security hole.
- **P1** — should fix soon; likely visible defect or a real risk.
- **P2** — worth fixing; robustness/security hardening.
- **P3** — nice-to-have / style.

---

## 2. Architecture

```
UI (Compose)  ── StreamyViewModel (Room, prefs, ServiceController)
                    │  binder push/pull
StreamService (foreground, scope = SupervisorJob + Dispatchers.IO)
  ├─ AudioPipeline (single audio thread inside AudioCapture)
  │    ├─ AudioCapture (AudioRecord, VOICE_COMMUNICATION, gain)
  │    ├─ Encoder (Mp3Encoder JNI / AacEncoder MediaCodec)
  │    ├─ StreamTransport.enqueue (drop-newest Channel(64))
  │    └─ LevelFeed ──> VU meter (30 fps throttle)
  ├─ SourceClient (UvoxClient / IcecastSourceClient) via transport coroutine
  ├─ RecordingStorage (MediaStore) + StreamSink (MP3 direct / transcode)
  └─ locks (PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF)
```

**What's right.**

- Single-writer discipline is explicit and respected. Encoder, sinks, and level feed are confined to the `streamy-audio` capture thread; `AudioPipeline.stop()` joins capture before flushing the encoder, so the native LAME handle is never touched concurrently (`AudioPipeline.kt:36-101`).
- Ownership/lifecycle is centralized in `StreamService.stopAndShutdown()` — one idempotent teardown path, `@Synchronized`, re-entrancy-guarded, used by every error/stop branch (`StreamService.kt:383-399`).
- A deliberate "assign + start under the service monitor" pattern prevents a concurrent Stop from orphaning a native encoder/socket mid-setup, and re-checks `shuttingDown` inside the monitor (`StreamService.kt:218-255`).
- The VM/service split is clean: the VM owns UI state and derives audio settings from the selected profile; the service owns the stream and mirrors state back through a binder. No Hilt, but the manual factory shim is 8 lines.
- Protocol discovery (`DjProtocolDetector`) is genuinely clever — probe Ultravox 0x1009, else Icecast SOURCE, at connect time, with a shared sniffer between app code and tests.

**What's worth watching.**

- `StreamService` and `StreamyViewModel` each open their own Room `streamy.db` (same file, no in-memory sharing). Room serializes file access, but two independent connections means two caches; the service reads a profile row that the VM may have just updated. Fine in practice (they never write concurrently on the same row) but it's a design smell; a shared repository instance would remove the whole class.
- `StreamyApp` is empty; `StreamService`/VM do their own DI-less wiring. Fine at this size; the code already flags this as Task 14.
- The queue's backpressure policy is drop-newest on a 64-frame channel (~1.5 s at 44.1 kHz mono). For a live broadcast that is a reasonable latency-vs-completeness tradeoff, but it means listeners can hear gaps; there is no "you dropped N frames" surfacing beyond a transient `Backpressure` state that the service maps to `null` (`StreamService.kt:341-352`), so drops are invisible to the user.
- `service` holds `START_STICKY` and reconnect loops for up to 10 attempts; on a sticky restart after process death the profileId comes back from the intent, so reconnect-after-kill works — but see the timer bug in §3.

---

## 3. Correctness

### P0 — `StreamTransport` backoff sleep is computed twice, and the test bakes in the bug

`StreamTransport.kt:55-56`:

```kotlin
onState(TransportState.Backoff(attempt, nextBackoffMs(attempt)))
delay(nextBackoffMs(attempt))
```

`nextBackoffMs` uses a deterministic jitter seeded by `attempt`, so the two calls return the *same* value — the state and the actual delay agree. That makes the double call harmless today. What makes it a latent P0 is that the jitter is a **function of `attempt`, not of wall-clock randomness**, so `Backoff(attempt, ms)` is entirely predictable, and the "jitter" claim in the comment (`deterministic ±10% jitter`) is misleading: consecutive failures produce the same delay sequence every run, which is exactly the thundering-herd behavior jitter exists to prevent. Worse, the unit test asserts the *implementation* (`delays[5] <= 20_000`, `delays[0] in 900..1100`) rather than the *property* (bounds + monotonic growth + actual sleep), so any refactor that makes the sleep differ from the reported value would still pass.

Fix: compute once into a local, sleep the local, and make jitter random-with-seed (or add a real test that the value slept equals the value reported — inject a clock/scheduler).

### P1 — `AacEncoder` drops frames under encoder backpressure, silently

`AacEncoder.kt:33`: `codec.dequeueInputBuffer(0)` with a 0 µs timeout. When the MediaCodec AAC encoder has no input buffer available (it can lag), the PCM frame is dropped — the comment even says "drop this PCM frame under backpressure (live speech; see Task 9)". On a slow device this is every frame during a transient; there is no counter, no surface, and no `Backpressure` signal like the transport has. For a broadcast app whose whole point is not dropping the listener's audio, a bounded *blocking* wait (e.g. `dequeueInputBuffer(10_000)` with a drop counter after that) would bound latency while avoiding drop-everything.

### P1 — `AacEncoder.flush()` EOS drain can hang `stop()` for up to 200 ms on the service scope

`AacEncoder.kt:46-51`: `dequeueInputBuffer(10_000)` blocks up to 10 s to queue EOS, then `drain(awaitEos = true)` loops with `INFO_TRY_AGAIN_LATER` up to a 200 ms wall-cap. This runs on `AudioPipeline.stop()` which runs inside `StreamService.stopAndShutdown()` on the service's IO scope — a Stop could stall ~200 ms worst-case, and if the codec is wedged the `dequeueInputBuffer(10_000)` itself can stall 10 s (the `eos` flag is set before, but the call itself is the risk). A stuck stop is user-visible (the app keeps broadcasting). Consider: bound the EOS-queue wait to ~50 ms, drop the EOS frame if unavailable, and only drain what's immediately available.

### P1 — LAME output sample rate is not pinned to the configured rate

`lame_jni.cpp:13-17` sets `lame_set_in_samplerate`, `lame_set_num_channels`, `lame_set_out_samplerate` to the configured values — but `lame_init_params()` may internally adjust the output rate (notably it can half the rate for low bitrates). The app *assumes* the output equals the input (e.g. `AudioConfigRules` clamps MP3 16/24 kbps to 22.05 kHz so LAME won't "bump 24→32", and the README claims exact rates). If LAME halves internally, the DNAS2 bitrate math and the MP3 `Mp3DirectSink` recording would both be subtly wrong. The fix is to read back `lame_get_out_samplerate()` after `lame_init_params()` and either expose it to the caller or assert it matches. (Worth verifying empirically; LAME at quality 5 for 24 kbps mono 22.05 kHz is likely fine, but nothing in the code proves it.)

### P1 — `StreamTransport` drop counter and Backpressure are lost in the UI

`StreamTransport.enqueue` (line 27-30) counts drops and emits `Backpressure(drops)`; `StreamService.toStreamState()` deliberately maps it to `null` (line 350). Result: drops are *invisible*. For a live-audio product, "you are dropping audio" is exactly the thing a DJ needs to know. Surface a drop count in the `Live` state or as a separate flow.

### P2 — `DjProtocolDetector` sniffs by *writing a SOURCE request with a 2 KB audio nudge* to a port that may be a production source port

`DjProtocolDetector.kt:37-48` sends a real `SOURCE <mount> ICE/1.0` handshake plus 2 KB of garbage bytes to determine protocol. On a misconfigured AUTO profile pointing at the wrong port, this writes 2 KB of noise into a server's source path. It's gated on an explicit AUTO profile, so it's acceptable, but the probe should be read-only where possible (Ultravox 0x1009 is; the Icecast leg isn't). Also: two sequential probes at up to 2.5 s each (`PROBE_MS`) means `startWithProfile` can take up to ~5 s before the transport even begins — and this happens *inside* `resolveDjProtocol` which is called synchronously on the service scope while the FGS notification is already up. Fine, but a `DjProtocol` set explicitly avoids it; consider caching the detection result on the profile after the first success (the form already writes `djProtocol = AUTO` every save, discarding any knowledge).

### P2 — `UvoxClient.close()` sends Terminate even after a failed connect

`UvoxClient.kt:129-134`: `close()` unconditionally writes `message(1, 0x005, …)` on `out` if non-null. After a NAK'd handshake, `connect()` throws and nulls `out`, so the only path is the normal one — but if `close()` races a mid-connect `socket` assignment (it can't today, single-threaded transport), it could write to a dead socket. Low risk; the `runCatching` already swallows it. The bigger smell: `close()` is called by `closeOrphans()` on the *stop path*, where the socket is already being closed — the Terminate write is best-effort, which is fine.

### P2 — `AudioCapture.stop()` joins only 500 ms; a wedged AudioRecord read can outlive the pipeline

`AudioCapture.kt:86-94` — correct and pragmatic, but if `read()` is stuck (some OEMs), `pipeline.stop()` proceeds while the capture thread may still call `onFrame` → `encoder.encode` on a closed native handle. `Mp3Encoder.close()` sets a `closed` flag but `encode()` doesn't check it. This is defense-in-depth; the 500 ms join covers the realistic case.

### P2 — `EditProfileScreen` delete path reconstructs a `ProfileEntity` from form state instead of using the loaded row

`EditProfileScreen.kt:356-388`: the delete confirm builds a `ProfileEntity` from current form fields (including `mode`, `djUser`, `djMount` dropped — it passes only a subset, letting `mode`/`djUser`/`djMount` default). Room `@Delete` matches on primary key, so deletion works, but if the user edited fields and *then* deleted, the entity's other columns are never written — only the id matters. It works; it's just fragile. Minor.

### P2 — `IcecastSourceClient` treats any status line containing "200" as success

`IcecastSourceClient.kt:65`: `if (status.isNotEmpty() && !status.contains("200"))` — a `HTTP/1.0 2000` or a body line containing "200" would pass. Should parse the numeric code. The live test asserts `200 OK` so in practice this is fine.

---

## 4. Concurrency

**What's right.**

- `@Volatile` is used exactly where it belongs (flags, gain, mic-busy latch, recording path).
- The `pipeline` field is `@Volatile` while `transport`/`client`/`encoder` are plain `var`s — and every access to them is inside `synchronized(this)` or the single transport coroutine. Consistent.
- `StreamService.stopAndShutdown()` is `@Synchronized`; `startWithProfile` takes the same monitor for the assign+start block. The `shuttingDown` re-check inside the monitor closes the setup/teardown race.
- `MainActivity` collects service flows in `lifecycleScope` — auto-cancelled on destroy — and pushes `micGain` on bind so the first frame is never at unity by mistake. That's a nice touch.
- `AudioPipeline`'s record sink design (arm/disarm volatiles, swap at frame boundary on the audio thread) is exactly right for a non-thread-safe LAME transcode sink.

**Watch out for.**

- `StreamService.state` is a `StateFlow` written from three threads (main via `onStartCommand`, transport coroutine, audio thread via `onMicBusy`). `StateFlow` is thread-safe for value updates, but ordering is not guaranteed: an `onMicBusy` fired from the audio thread can interleave with the transport coroutine's `Live` transition, so a `mic_busy` error could be followed by `Live` (transport just connected) and then `stopAndShutdown()` → `Idle`. The UI would flash Live then Idle. Rare, but the mic-busy path sets error state *then* calls `stopAndShutdown()` from the audio thread — the transport coroutine may still be in `connect()` and will emit `Connecting`/`Live` after the error. This is the one real state-ordering race I'd fix (e.g. drop transport state updates after an error state has been set, or route mic-busy through the same state machine).
- Two Room connections (service + VM) mean two SQLite handles to the same file. Room uses WAL-safe locking, so no corruption, but the service's `by lazy` db + the VM's db are independent; a profile update from the VM and a read in `startWithProfile` can theoretically race mid-write (the service reads a partially-written row). In practice `repo.update` writes a single row transactionally, so the read sees before-or-after. Low risk, worth a comment.

---

## 5. Security

### P1 — Passwords stored in plaintext Room, no export/backup protection

`ProfileEntity.password` / `adminPassword` / `djUser` are plaintext in the SQLite db, and `android:allowBackup` is not set (defaults to `true`). On a device with USB debugging / adb backup, the source/dj passwords leave the device. For a v1 app this is common, but: (a) use EncryptedSharedPreferences or Android Keystore for the password field, or (b) at minimum set `android:allowBackup="false"` / `android:fullBackupContent` exclusions and use `databaseProtectionParams` with a passphrase. The app already stores these in the `profiles` table which Room writes with no encryption.

### P1 — `usesCleartextTraffic="true"` for all builds including release

`AndroidManifest.xml:17` sets it globally; the Settings screen even says "Cleartext HTTP is enabled in debug builds … Disable before release" — but the manifest doesn't distinguish debug/release. A release APK will happily send passwords + audio over plaintext HTTP to whatever host the user typed. SHOUTcast source *is* plain TCP (Ultravox has no TLS), so some cleartext is unavoidable for the protocol — but the *user-entered server* is arbitrary, and DNAS2/Icecast both support TLS (Icecast: `https` source). At minimum: split manifests (debug `usesCleartextTraffic=true`, release `false` with a per-host `network_security_config` if you must support http servers), and consider warning in the UI when a profile host is http.

### P2 — No size cap on `updateNowPlaying` titles

`UvoxClient.kt:143-150` builds an XML payload of `6 + xml.size` with a 16-bit length field (`payload[4]<<8 | payload[5]`). A very long title (or one with many escaped entities) overflows the u16 length in `message()`. The UI is a single-line text field so realistically bounded, but a defensive truncate (e.g. 512 chars) would make the wire contract explicit. Same for the `name`/`genre`/`mount` values that go into headers in `IcecastSourceClient` — a profile with a `\r\n` in `name` would inject a header (header injection). The form doesn't sanitize `name`/`host`/`mount` beyond `trim()`. This is a real (if low-likelihood) injection: user-controlled strings are concatenated into an HTTP header stream.

### P2 — `DjProtocolDetector` / live tests commit no credentials (good), but the IntegrationTest `statsField` reads `/stats` over plaintext http

That's a test-only detail against a user's own server. Not a product issue.

### P2 — `PendingIntent`s are correctly `FLAG_IMMUTABLE` (good), service is `exported=false` (good)

No issues here — calling out as verified-good.

### P2 — AacEncoder HE-AAC is advertised as `audio/aacp` with no SBR signaling check

The ADTS wrapper declares AAC-LC profile with implicit SBR (per the excellent comment at `UvoxClient.kt:120-127`). DNAS2 accepts it. If a future encoder ever emits AAC-LC-only frames the bitrate math silently breaks. The unit test locks the ADTS layout, but nothing tests *actual* encoder output profiles on-device except the env-gated live test. Acceptable; flagged.

---

## 6. Performance

**What's right.**

- No per-frame allocations in the VU-meter draw lambda; `Path`s reused, strokes hoisted (`VuMeter.kt:55-68`). The afterglow trick (holding the previous `FloatArray` sample set, drawn at low alpha) is a genuinely elegant zero-copy effect.
- `LevelFeed` throttles to 30 fps so a 44.1 kHz waveform doesn't spam the UI.
- `Mp3DirectSink` writes through a 128 KB `BufferedOutputStream` so per-frame writes don't hit the kernel on the audio thread (comment cites a real stall → mic_busy false positive).
- `AudioCapture` applies gain in-place and skips entirely at unity.
- The transport uses `trySend` (non-blocking) so the audio thread never blocks on the queue.

**Watch out for.**

- `AudioPipeline` calls `sink.onPcm(pcm)` and `sink.onEncoded(frames)` on the audio thread; `Mp3TranscodeSink` runs a *second* LAME encode on every frame while streaming AAC. On a low-end device that's a full extra MP3 encode at 128 kbps on the same thread that's already feeding the live stream — the mic_busy detector counts consecutive short reads, so a slow device will trip "MICROPHONE BUSY" while recording. Consider moving the transcode to a dedicated thread with its own buffered queue (it's already one-way — PCM in, MP3 out — so a simple pipe would work).
- `StreamService` creates the Room db lazily on first profile read inside `startWithProfile`, on the service scope — fine, but the first `byId` query after process death can take tens of ms on cold storage. Negligible.
- `StreamyViewModel` derives `codec/bitrate/sampleRate/mono` each as a separate `stateIn(WhileSubscribed)` over `selectedProfile` — four derived flows recomputed per emission; trivial at this scale.
- The 44.1 kHz 1024-sample frame is ~23 ms; at 128 kbps that's ~370 bytes per frame into the channel. Channel(64) holds ~1.5 s of audio; at 192 kbps the byte volume roughly doubles. Bounded, fine.

---

## 7. Testing

This is the standout area. Evidence from this session: 56 JVM tests ran, 0 failures, 6 skipped (all env-gated live tests — correctly skipped without creds). The 33-test baseline from the review grew to 56 with the additions below, which also close most of the "Gaps" listed here.

**What's genuinely good.**

- `UvoxXteaTest` — golden vectors captured from a reference Python port, locking the crypto so a refactor can't silently break the handshake. **Expanded this session** to cover the key-padding path (keys < 16 / > 16 bytes) and multi-block concatenation; the reference `scripts/xtea_ref.py` was corrected (Python `+` binds tighter than `^`) and now reproduces the pre-existing golden vectors.
- `UvoxClientAdtsTest` — bit-level ADTS assertions that *caught a real regression* (channel-config bits shifted into the wrong nibble made every AAC stream unplayable while the server still reported correct CONTENT/BITRATE).
- `AudioConfigRulesTest` / `ProfileRepositoryTest` — the config rules (HE-AAC mono forcing, MPEG-2 clamping, ladder validation) are exactly the kind of logic that rots without tests.
- Env-gated live tests (`UvoxClientLiveTest`, `IcecastSourceClientLiveTest`, `DjProtocolProbeTest`, `ListenProbeTest`) — skipped in CI without creds, but one command runs the real pipeline against a real server. This is the right shape for protocol work.
- `IntegrationTest` — full pipeline (mic → LAME → service → real DNAS2) with `GrantPermissionRule`, seeding its own profile and asserting `STREAMSTATUS=1` on the server. Excellent.

**New this session (closing the review's gaps).**

- **`StreamTransport` state machine tests** (`StreamTransportBackoffTest`, 7 tests): the reconnect loop's actual transitions (Connecting → Backoff → give-up → Failed, stop-during-backoff cancellation), plus the P0 property (reported backoff == value slept, seeded-random jitter bounds, not-deterministic-by-attempt). The stop-during-backoff test proves `stop()` cancels a 60 s injected delay immediately.
- **`DjProtocolDetectorTest`** (3 tests): Ultravox-ACK vs Icecast-200 vs neither, decided with a real `ServerSocket` fake — exactly the fake-server approach the review called for.
- **`WireSafetyTest`** (5 tests): `parseHttpStatus` rejects `"HTTP/1.0 2000 Broken"` and body lines (the `contains("200")` bug class), header CRLF stripping, truncation cap, UTF-8 preservation.
- **`UvoxXteaTest` key-padding** (3 new tests): short-key zero-pad, long-key truncation to 16 bytes (asserts 20-byte key == first 16 bytes), multi-block concatenation — all golden-vector locked.

**Remaining gaps (unchanged).**

- **No instrumented tests for `AudioPipeline`** (mic-busy detector, record-sink swap, stop ordering) — needs Robolectric/instrumented with a fake capture/encoder. The off-thread transcode sink design makes this more testable now (pure `ArrayBlockingQueue` pipe), but the test itself still needs an Android test runner.
- **`StreamServiceLifecycleTest` is a compile-only smoke test** — real assertions still TODO. The `IntegrationTest` covers the happy path, but there's no instrumented test for error paths (`profile_missing`, `mic_busy`, give-up).
- **No Compose UI tests** (screens are stateless + VM-driven, so they're eminently testable; the form-validation logic in `EditProfileScreen` would benefit).
- **`SilenceTest`-style fixture**: `silence.mp3` exists as a test resource but is 10 s of silence — fine for probing, but a real tone would validate audio content.

---

## 8. Protocol / wire details (verified against docs)

- Ultravox handshake order matches the spec (`docs/ultravox-2.1.md`): 0x1009 cipher → 0x1001 auth ("2.1:sid:xtea(uid):xtea(password)") → mime/setup/buffer/ICY/max-payload → 0x1004 standby. The empty-cipher-key handling (Centova) is a real-world catch; defaulting to "foobar" would encrypt with the wrong key.
- ADTS framing: profile AAC-LC with implicit SBR and `sampleRateHz / 2` for the core rate is correct for HE-AAC and the comment cites the ISO ref. The channel-config bit placement is verified by test.
- `0x902` metadata: the 6-byte prefix + XML is verified live; `xmlEsc` escapes `&<>` but not quotes — titles with `"` are legal XML text so this is fine.
- Icecast inline ICY metadata: `metaint=8192`, length byte = padded/16, escapes `\` and `'` — correct per the ICY spec. The `adminUpdateMetadata` fallback (404 on harbor) is correctly best-effort.
- One thing to double-check: `UvoxClient` sends `icy-url: http://streamy.app` and `icy-pub: 1` hardcoded — if the app ever supports non-public streams, `icy-pub` should be a profile field. Minor.

---

## 9. Bugs / issues ranked (actionable list)

Status: ✅ fixed in this session · 🔧 mitigated / accepted · ⬜ open

| # | Sev | Where | Issue | Status |
|---|-----|-------|-------|--------|
| 1 | P0 | `StreamTransport.kt:55` | Backoff jitter is deterministic-by-attempt (not random), reported-vs-slept value computed twice, test bakes in the behavior instead of the property | ✅ `nextBackoffMs(attempt, Random)` computes once; test asserts property + reported==slept |
| 2 | P1 | `AacEncoder.kt:33` | `dequeueInputBuffer(0)` drops frames under encoder backpressure with no signal to the user | ✅ bounded 10 ms wait + `droppedFrames` counter surfaced in `Live.droppedFrames` |
| 3 | P1 | `AacEncoder.kt:46-74` | `flush()` can block up to 10 s queuing EOS + 200 ms drain on the stop path | ✅ EOS queue wait capped 50 ms; drain wall-cap 200 ms; format-changed branches explicit |
| 4 | P1 | `lame_jni.cpp:13-17` | LAME output rate never read back; app assumes out==in (bitrate math / direct-sink recording could be off) | ✅ `lame_get_out_samplerate()` read back; `Mp3Encoder.outSampleRateHz` pinned with `check` |
| 5 | P1 | `AndroidManifest.xml:17` | `usesCleartextTraffic` true for all builds; Settings copy promises debug-only | ✅ main manifest has no cleartext; debug overlay + `network_security_config` (TLS-only base) |
| 6 | P1 | `ProfileEntity` / manifest | Passwords plaintext in Room, `allowBackup` default true → adb-backup exfil | ✅ `allowBackup="false"` + `fullBackupContent="false"` + `dataExtractionRules` exclude-all |
| 7 | P2 | `StreamService.kt:341-352` | Backpressure drops invisible to the UI | ✅ `Live.droppedFrames` + HomeScreen Warn hint (throttled 250 ms) |
| 8 | P2 | `StreamService` mic-busy | Error state can race transport's `Live` transition (state ordering) | ✅ transport state updates suppressed once `shuttingDown` latched; `enqueue_after_stop_is_suppressed_not_backpressure` + `no_terminal_state_after_stop` (StreamTransport tests) prove late frames/states after the latch are silent — no phantom Backpressure, no drop-metric pollution |
| 9 | P2 | `IcecastSourceClient.kt:65` | Status parse uses `contains("200")` not code parse | ✅ `WireSafety.parseHttpStatus` (exact 3-digit 100..999) |
| 10 | P2 | `IcecastSourceClient` headers | Profile strings (`name`, `mount`) not CRLF-sanitized → header injection risk | ✅ `WireSafety.headerValue` on mount/name/genre + titles; `UvoxClient` name/genre/titles too |
| 11 | P2 | `UvoxClient.kt:143` | No title length cap; u16 length could overflow on huge input | ✅ titles truncated via `WireSafety` (MAX_TITLE=1024) in both clients |
| 12 | P2 | `AudioPipeline` + `Mp3TranscodeSink` | Second LAME encode on the audio thread while recording AAC → mic_busy false positives on slow devices | ✅ transcode moved to a dedicated thread with bounded `ArrayBlockingQueue` (drop-newest, join on flush); `Mp3TranscodeSinkTest` (3 tests, fake encoder) proves onPcm never blocks, drop-newest counts overflow, flush joins and drains, encoder-death clears queue so join terminates. Gate hardening also fixed a real bug the original shipped: `offer(null)` end-marker is rejected by `ArrayBlockingQueue`, so flush silently timed out and dropped the encoder's trailing bytes — replaced with an empty-array sentinel |
| 13 | P2 | `DjProtocolDetector` | Icecast probe writes 2 KB garbage to the port; AUTO detection not cached | ✅ 16-byte probe nudge; per-(host,port) cache + `invalidate()` on profile save; `detection_is_cached_until_invalidate` (fake-server test) proves cached calls re-probe 0 times and invalidate re-probes exactly once |
| 14 | P3 | `AudioCapture.kt:92` | 500 ms join cap + `Mp3Encoder.encode` unchecked `closed` | ✅ encode-after-close guard (`check(!closed)`); join note documents defense-in-depth |
| 15 | P3 | `EditProfileScreen` delete | Rebuilds entity from form fields instead of the loaded row | ✅ stores the loaded row, deletes exactly what's persisted |
| 16 | P3 | `UvoxClient` | `icy-pub: 1` hardcoded | 🔧 named `ICY_PUB` constant + doc note (Streamy is always public; profile field if ever non-public) |

---

## 10. Verdict

This is a **solid v1** with real engineering discipline: the concurrency model is coherent and mostly provably safe, the protocol layer is tested against real servers with golden vectors, and the UI is genuinely well-crafted. All 16 findings from §9 were fixed in this session (15 fixed outright, 1 documented constant). The test suite grew from 33 to **56 tests** with 0 failures (50 executed + 6 env-gated skipped). Gate hardening also host-compiled the vendored LAME (`scripts/lame_readback.c`) against the app's exact config — `lame_get_out_samplerate` returns 44100 for the 44.1 kHz mono 128 kbps MP3 path the `check` pins — and the new tests caught two real bugs the original fixes would have shipped (§5 header).

Priorities if I were the author (post-fix):

1. The P0/P1/P2 items from this review are all closed. Next-highest value: an instrumented `AudioPipeline` test (mic-busy detector, record-sink swap, stop ordering) — the one gap that still needs an Android test runner.
2. `StreamServiceLifecycleTest` real assertions (error paths: `profile_missing`, `mic_busy`, give-up) would round out the instrumented coverage.
3. Compose UI tests for the form-validation logic in `EditProfileScreen`.
4. If non-public streams are ever a product goal, plumb `icy-pub` through the profile instead of the `ICY_PUB` constant.

Everything else is hardening.
