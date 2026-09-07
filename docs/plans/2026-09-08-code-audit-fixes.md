# Code Audit Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix all 5 P1 and 9 P2 findings from the 2026-09-07 code audit without architectural changes.

**Architecture:** Pure-function-first fixes where possible (extract testable helpers for teardown-state, retry-classification, and status parsing), then wire them into the services. Transport, socket, and capture fixes stay confined to their owning class. UI fixes hoist state collection and share one validation path.

**Tech Stack:** Kotlin, Android Services, Coroutines (Channel/StateFlow), Room, JNI/LAME (untouched), JUnit4 + Truth, Gradle `:app:testDebugUnitTest`.

---

## Phase 0 — Baseline (do first, proves the harness)

### Task 0: Baseline test run

**Files:**
- Modify: none (verification only)

**Step 1: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: BUILD SUCCESSFUL, ~58 tests, 0 failures (5 env-gated skipped — live tests need `local.properties` creds)

**Step 2: Confirm release still assembles**

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

No commit (verification only — proceed only if green; if red, stop and report).

---

## Phase 1 — P1 fixes (user-visible correctness, security)

### Task 1: Preserve terminal errors through teardown (Finding 1) — failing test

**Files:**
- Test: `app/src/test/java/com/streamy/app/service/TeardownStateTest.kt` (create)

**Step 1: Write the failing test**

```kotlin
package com.streamy.app.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TeardownStateTest {
    @Test fun error_is_preserved_when_preserveTerminal_is_true() {
        assertThat(
            StreamService.resolveTeardownState(StreamState.Error("mic_busy"), preserveTerminal = true)
        ).isEqualTo(StreamState.Error("mic_busy"))
    }

    @Test fun user_stop_clears_to_idle() {
        assertThat(
            StreamService.resolveTeardownState(StreamState.Error("mic_busy"), preserveTerminal = false)
        ).isEqualTo(StreamState.Idle)
    }

    @Test fun idle_stays_idle() {
        assertThat(
            StreamService.resolveTeardownState(StreamState.Idle, preserveTerminal = true)
        ).isEqualTo(StreamState.Idle)
    }
}
```

**Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.service.TeardownStateTest"`
Expected: FAIL with "unresolved reference: resolveTeardownState"

**Step 3: Commit test only (red)**

```bash
git add app/src/test/java/com/streamy/app/service/TeardownStateTest.kt
git commit -m "test: teardown-state preservation cases (red)"
```

### Task 2: Preserve terminal errors — implement in both services

**Files:**
- Modify: `app/src/main/java/com/streamy/app/service/StreamService.kt:401-418` (`stopAndShutdown`)
- Modify: `app/src/main/java/com/streamy/app/service/RecordService.kt:189-203` (`stopRecording`)

**Step 1: Add pure helper + wire it in (StreamService)**

Add to `StreamService` companion (or top-level in `StreamState.kt` if you prefer — keep it in `StreamService` so the test import above resolves):

```kotlin
companion object {
    fun resolveTeardownState(current: StreamState, preserveTerminal: Boolean): StreamState =
        if (preserveTerminal && current is StreamState.Error) current else StreamState.Idle
}
```

Change signature and final state assignment:

```kotlin
@Synchronized
fun stopAndShutdown(preserveTerminal: Boolean = false) {
    if (shuttingDown) return
    shuttingDown = true
    _isRecording.value = false
    closeOrphans()
    levelFeed.reset()
    releaseLocks()
    _state.value = resolveTeardownState(_state.value, preserveTerminal)
    // ... rest unchanged (stopForeground, stopSelf)
}
```

Update the two error call sites to preserve:
- `onMicBusy` lambda (`_state.value = StreamState.Error("mic_busy"); stopAndShutdown(preserveTerminal = true)`)
- `catch` in `startWithProfile` already sets `Error` before teardown → pass `preserveTerminal = true` there too.
- `ACTION_STOP` path keeps default (`stopAndShutdown()` → Idle).

Mirror in `RecordService`: same helper (import from `StreamService` or duplicate 3-line function — prefer `StreamService.resolveTeardownState` to avoid duplication), `stopRecording(preserveTerminal: Boolean = false)`, mic-busy/error paths pass `true`.

**Step 2: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.service.TeardownStateTest"`
Expected: PASS (3/3)

**Step 3: Run full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no regressions

**Step 4: Commit**

```bash
git add app/src/main/java/com/streamy/app/service/StreamService.kt app/src/main/java/com/streamy/app/service/RecordService.kt app/src/test/java/com/streamy/app/service/TeardownStateTest.kt
git commit -m "fix: preserve terminal Error through service teardown"
```

### Task 3: Fail fast on auth errors (Finding 2) — failing test

**Files:**
- Test: `app/src/test/java/com/streamy/app/net/StreamTransportBackoffTest.kt` (append)

**Step 1: Write the failing test**

```kotlin
@Test fun protocol_exception_fails_fast_without_retries() {
    val parent = Job()
    val ownScope = CoroutineScope(parent + Dispatchers.Default)
    val observed = java.util.Collections.synchronizedList(mutableListOf<TransportState>())
    val attempts = java.util.concurrent.atomic.AtomicInteger(0)
    val t = StreamTransport(
        scope = ownScope,
        connect = { attempts.incrementAndGet(); throw ProtocolException("auth NAK: Bad password") },
        send = { },
        onState = { s -> observed.add(s) },
        backoffMs = { 5L },
    )
    t.start()
    val deadline = System.currentTimeMillis() + 2_000
    while (observed.none { it is TransportState.Failed } && System.currentTimeMillis() < deadline) Thread.sleep(5)
    t.stop()
    assertThat(attempts.get()).isEqualTo(1)
    assertThat(observed.last()).isInstanceOf(TransportState.Failed::class.java)
    ownScope.cancel()
}
```

**Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.StreamTransportBackoffTest"`
Expected: FAIL (attempts == 10, last state Failed("gave_up...") after retries, not fast-fail)

**Step 3: Commit test only (red)**

```bash
git add app/src/test/java/com/streamy/app/net/StreamTransportBackoffTest.kt
git commit -m "test: transport fails fast on ProtocolException (red)"
```

### Task 4: Fail fast on auth errors — implement classifier

**Files:**
- Modify: `app/src/main/java/com/streamy/app/net/StreamTransport.kt:79-93` (catch block)

**Step 1: Write minimal implementation**

Add to `StreamTransport` companion:

```kotlin
/** Auth/handshake rejections are permanent — retrying hammers the server and delays feedback. */
fun isNonRetryable(e: Throwable): Boolean = e is ProtocolException
```

Change the catch block:

```kotlin
} catch (e: Throwable) {
    if (stopped) break
    if (isNonRetryable(e)) {
        onState(TransportState.Failed(e.message ?: "auth_failed"))
        break
    }
    attempt++
    if (shouldGiveUp(attempt)) { /* unchanged */ }
    // ... backoff unchanged
}
```

Note: `ProtocolException` is in the same package (`com.streamy.app.net`) — no import needed. Keep `IOException` and all other throwables on the retry path.

**Step 2: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.StreamTransportBackoffTest"`
Expected: PASS

**Step 3: Full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/java/com/streamy/app/net/StreamTransport.kt app/src/test/java/com/streamy/app/net/StreamTransportBackoffTest.kt
git commit -m "fix: fail fast on auth/protocol errors instead of 10x retry"
```

### Task 5: AudioCapture read-error path + record NPE race (Finding 3)

**Files:**
- Modify: `app/src/main/java/com/streamy/app/audio/AudioCapture.kt:63-75` (`start` loop)

**Step 1: Implement (no JVM test — AudioRecord needs a device; verify by build + inspection)**

Replace the loop body with a local reference + error handling:

```kotlin
record!!.startRecording()
running = true
thread = kotlin.concurrent.thread(name = "streamy-audio", isDaemon = true) {
    val rec = record ?: return@thread
    val buf = ShortArray(frameSamples * (if (mono) 1 else 2))
    var consecutiveErrors = 0
    while (running) {
        val n = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
        if (n > 0) {
            consecutiveErrors = 0
            applyGain(buf, n)
            if (!onFrame(buf.copyOf(n))) running = false
        } else {
            // Zero/negative = dead mic or transient underrun. Count toward the
            // pipeline's short-read detector instead of spin-looping.
            consecutiveErrors++
            try { Thread.sleep(10) } catch (_: InterruptedException) {}
            if (consecutiveErrors > 100) {
                // ~1s of dead reads: surface as a short frame so AudioPipeline
                // trips onMicBusy via its SHORT_READ_LIMIT path.
                if (!onFrame(ShortArray(0))) running = false
                consecutiveErrors = 0
            }
        }
    }
}
```

Also null-guard `stop()`: wrap `record?.run` in try/catch (already present) and keep the 500 ms join.

**Step 2: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/java/com/streamy/app/audio/AudioCapture.kt
git commit -m "fix: handle AudioRecord read errors, remove record!! race"
```

### Task 6: Stop-during-record-arm race (Finding 4)

**Files:**
- Modify: `app/src/main/java/com/streamy/app/service/StreamService.kt:293-326` (`startRecording`)

**Step 1: Implement generation guard**

Inside the `scope.launch(Dispatchers.IO)` completion block, extend the bail condition:

```kotlin
synchronized(this@StreamService) {
    if (shuttingDown || pipeline == null || !_isRecording.value) {
        runCatching { built.flush(); built.close() }
        _isRecording.value = false
    } else {
        pipeline?.armRecord(built)
    }
}
```

One-line change: add `|| !_isRecording.value`. Rationale in commit message: user tapped OFF (sets `_isRecording=false`) while the IO build was in flight; without this the sink still arms and the file grows while UI says off.

**Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/java/com/streamy/app/service/StreamService.kt
git commit -m "fix: don't arm recording if user stopped during file setup"
```

### Task 7: Scope test secrets to debug builds (Finding 5)

**Files:**
- Modify: `app/build.gradle.kts:15-41` (`defaultConfig` + `buildTypes`)

**Step 1: Implement**

Remove the four `buildConfigField` lines from `defaultConfig` and add a debug-only block:

```kotlin
buildTypes {
    debug {
        val lp = Properties()
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { lp.load(it) }
        fun lp(k: String, default: String): String = lp.getProperty(k) ?: default
        buildConfigField("String", "TEST_HOST", "\"${lp("streamy.test.host", "")}\"")
        buildConfigField("int", "TEST_PORT", lp("streamy.test.port", "0"))
        buildConfigField("String", "TEST_PASSWORD", "\"${lp("streamy.test.password", "")}\"")
        buildConfigField("int", "TEST_SID", lp("streamy.test.sid", "1"))
    }
    release {
        isMinifyEnabled = false
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

Check: `BuildConfig.TEST_HOST` references in `app/src/test/` and `androidTest/` resolve against the debug BuildConfig — unit tests run against debug by default (`testDebugUnitTest`). If any release-only reference breaks, add stub empty fields to release instead.

**Step 2: Verify both variants**

Run: `./gradlew :app:testDebugUnitTest :app:assembleRelease`
Expected: BUILD SUCCESSFUL for both

**Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "fix: scope test server creds to debug builds only"
```

---

## Phase 2 — P2 fixes (reliability, correctness)

### Task 8: Drop stale queue on reconnect (Finding 6)

**Files:**
- Modify: `app/src/main/java/com/streamy/app/net/StreamTransport.kt:64-77` (post-connect drain)

**Step 1: Implement queue flush on (re)connect**

Add helper:

```kotlin
/** Drop pre-reconnect backlog so live speech resumes live, not ~1.5s stale. */
private fun clearBacklog() {
    while (queue.tryReceive().isSuccess) { /* drop */ }
}
```

Call it immediately after `connect()` and before `onState(TransportState.Live)`:

```kotlin
connect()
clearBacklog()
onState(TransportState.Live)
```

`tryReceive` is available on `Channel<ByteArray>` (kotlinx-coroutines 1.9.0). No test change needed; existing backoff tests assert states, not queue contents.

**Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.*"`
Expected: PASS

**Step 3: Commit**

```bash
git add app/src/main/java/com/streamy/app/net/StreamTransport.kt
git commit -m "fix: drop stale audio backlog on reconnect"
```

### Task 9: Socket connect timeout (Finding 7)

**Files:**
- Modify: `app/src/main/java/com/streamy/app/net/UvoxClient.kt:56-60` (socket creation)
- Modify: `app/src/main/java/com/streamy/app/net/IcecastSourceClient.kt:42-45` (socket creation)

**Step 1: Implement bounded connect**

UvoxClient:

```kotlin
import java.net.InetSocketAddress

val s = Socket().apply {
    tcpNoDelay = true
    keepAlive = true
    connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
    soTimeout = HANDSHAKE_TIMEOUT_MS
}
```

IcecastSourceClient (same, minus keepAlive):

```kotlin
val s = Socket().apply {
    tcpNoDelay = true
    connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)
    soTimeout = HANDSHAKE_TIMEOUT_MS
}
```

`HANDSHAKE_TIMEOUT_MS` is already 6_000 in both companions — reuse it.

**Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.*"`
Expected: PASS

**Step 3: Commit**

```bash
git add app/src/main/java/com/streamy/app/net/UvoxClient.kt app/src/main/java/com/streamy/app/net/IcecastSourceClient.kt
git commit -m "fix: bound socket connect with 6s timeout"
```

### Task 10: Block AAC-over-Icecast (Finding 8) — shared rule + test

**Files:**
- Modify: `app/src/main/java/com/streamy/app/data/Models.kt` (`AudioConfigRules`)
- Test: `app/src/test/java/com/streamy/app/data/AudioConfigRulesTest.kt` (append)

**Step 1: Write the failing test**

```kotlin
@Test fun icecast_rejects_aac() {
    assertThat(AudioConfigRules.isSupported(Codec.AAC, DjProtocol.ICECAST)).isFalse()
    assertThat(AudioConfigRules.isSupported(Codec.MP3, DjProtocol.ICECAST)).isTrue()
    assertThat(AudioConfigRules.isSupported(Codec.AAC, DjProtocol.ULTRAVOX)).isTrue()
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.data.AudioConfigRulesTest"`
Expected: FAIL with "unresolved reference: isSupported"

**Step 2: Implement the rule**

```kotlin
fun isSupported(codec: Codec, protocol: DjProtocol): Boolean =
    !(codec == Codec.AAC && protocol == DjProtocol.ICECAST)
```

**Step 3: Re-run, expect PASS, commit red+green**

```bash
git add app/src/main/java/com/streamy/app/data/Models.kt app/src/test/java/com/streamy/app/data/AudioConfigRulesTest.kt
git commit -m "feat: shared AAC-over-Icecast support rule"
```

### Task 11: Wire the AAC/Icecast guard into UI + service

**Files:**
- Modify: `app/src/main/java/com/streamy/app/ui/StreamyViewModel.kt:254-263` (`setCodec`)
- Modify: `app/src/main/java/com/streamy/app/ui/StreamyViewModel.kt:278-281` (`setSampleRate`)
- Modify: `app/src/main/java/com/streamy/app/ui/EditProfileScreen.kt:131-133` (`formValid`)
- Modify: `app/src/main/java/com/streamy/app/service/StreamService.kt:206-213` (Icecast client construction)

**Step 1: VM — force MP3 when profile is DJ+ICECAST**

In `setCodec`, after loading `p`, resolve protocol and clamp:

```kotlin
val protocol = DjProtocol.parse(p.djProtocol)
val effectiveCodec = if (c == Codec.AAC && protocol == DjProtocol.ICECAST) Codec.MP3 else c
// ... rest of setCodec uses effectiveCodec instead of c
```

In `setSampleRate`, clamp before persisting (service currently clamps silently):

```kotlin
fun setSampleRate(hz: Int) {
    val p = selectedProfile.value ?: return
    val clamped = AudioConfigRules.clamp(AudioConfig(p.codecEnum(), p.bitrateKbps, hz, p.mono))
    updateProfileAudio(p.copy(sampleRateHz = clamped.sampleRateHz))
}
```

**Step 2: Form — surface the constraint**

In `formValid`, add `&& (codec != Codec.AAC || !isDj || !isAzuracast)` and show a helper text under the codec toggle when violated: "AAC is not supported on Icecast mounts — MP3 will be used."

**Step 3: Service — defense in depth**

At Icecast construction, coerce config: `val iceCfg = if (cfg.codec == Codec.AAC) cfg.copy(codec = Codec.MP3) else cfg` and use `iceCfg` for the mime. Log a warning.

**Step 4: Verify + commit**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL

```bash
git add app/src/main/java/com/streamy/app/ui/StreamyViewModel.kt app/src/main/java/com/streamy/app/ui/EditProfileScreen.kt app/src/main/java/com/streamy/app/service/StreamService.kt
git commit -m "fix: block AAC-over-Icecast in chooser, form, and service"
```

### Task 12: Scope VU levels to the meter (Finding 9)

**Files:**
- Modify: `app/src/main/java/com/streamy/app/ui/HomeScreen.kt:81` (`val levels by ...`)
- Modify: `app/src/main/java/com/streamy/app/ui/VuMeter.kt` (collect inside)

**Step 1: Implement**

Delete the top-level `val levels by vm.levels.collectAsStateWithLifecycle()` in `HomeScreen` and pass the flow down: `VuMeter(levelsFlow = vm.levels)`. Inside `VuMeter`, collect: `val levels by levelsFlow.collectAsStateWithLifecycle()`. No behavior change; recomposition is now scoped to the meter.

**Step 2: Verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (Compose — no unit test; verify visually that the meter still animates while Live)

**Step 3: Commit**

```bash
git add app/src/main/java/com/streamy/app/ui/HomeScreen.kt app/src/main/java/com/streamy/app/ui/VuMeter.kt
git commit -m "perf: scope VU level collection to VuMeter composable"
```

### Task 13: Null-intent START_STICKY restart (Finding 10)

**Files:**
- Modify: `app/src/main/java/com/streamy/app/service/StreamService.kt:135-161` (`onStartCommand`)
- Modify: `app/src/main/java/com/streamy/app/service/RecordService.kt:95-110` (same)

**Step 1: Implement**

At the top of `onStartCommand`:

```kotlin
if (intent?.action == null) {
    // System restart after process kill: no profile id to resume from.
    // Re-promote briefly so we don't miss the FGS timeout, then stop.
    startForegroundTyped(buildNotification("Idle"))
    stopAndShutdown()
    return START_STICKY
}
```

Apply identically in `RecordService` (`stopRecording()` instead of `stopAndShutdown()`).

**Step 2: Verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add app/src/main/java/com/streamy/app/service/StreamService.kt app/src/main/java/com/streamy/app/service/RecordService.kt
git commit -m "fix: handle null-intent sticky restart without FGS timeout"
```

### Task 14: Verify foreground notification IDs (Finding 11 — investigate)

**Files:** none (read-only task)

**Step 1: Check**

Search for `NOTIF_ID` / `CHANNEL_ID` in both services. Confirm `StreamService` and `RecordService` use distinct notification IDs (and ideally distinct channel IDs).

**Step 2: Outcome**
- Distinct → no change; record the finding as verified in the commit message of the next task.
- Shared → file a follow-up fix task (new channel + ID for `RecordService`) before closing this plan.

### Task 15: AacEncoder close-guard + volatile counter (Finding 12)

**Files:**
- Modify: `app/src/main/java/com/streamy/app/audio/AacEncoder.kt:36-42`, `46-48`

**Step 1: Implement**

```kotlin
@Volatile private var closed = false   // was plain var

override fun encode(pcm: ShortArray): List<ByteArray> {
    check(!closed) { "encode after close" }  // mirrors Mp3Encoder
    if (eos) return emptyList()
    // ... unchanged
}

@Volatile var droppedFrames: Long = 0  // was plain var; single-writer but read off-thread
    private set
```

**Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.audio.*"`
Expected: PASS

**Step 3: Commit**

```bash
git add app/src/main/java/com/streamy/app/audio/AacEncoder.kt
git commit -m "fix: AacEncoder after-close guard and volatile drop counter"
```

### Task 16: Single Room instance (Finding 13)

**Files:**
- Create: `app/src/main/java/com/streamy/app/data/db/DatabaseHolder.kt`
- Modify: `app/src/main/java/com/streamy/app/service/StreamService.kt:89-93` (`db by lazy`)
- Modify: `app/src/main/java/com/streamy/app/service/RecordService.kt:67-74`
- Modify: `app/src/main/java/com/streamy/app/ui/StreamyViewModel.kt:52-54`

**Step 1: Create the holder**

```kotlin
package com.streamy.app.data.db

import android.content.Context
import androidx.room.Room

/** Single Room instance per process — replaces the three independent `streamy.db` opens. */
object DatabaseHolder {
    @Volatile private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "streamy.db",
            ).addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
            ).build().also { instance = it }
        }
}
```

**Step 2: Replace the three `Room.databaseBuilder` call sites with `DatabaseHolder.get(this)`**

Services: keep `db.close()` only via `onDestroy` guarded by `if (instance != null)` — better, remove `db.close()` from services entirely and let the process own the singleton (closing a shared singleton from one component breaks the others). Remove `runCatching { db.close() }` from both `onDestroy` methods and the VM's `onCleared`.

**Step 3: Verify**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add app/src/main/java/com/streamy/app/data/db/DatabaseHolder.kt app/src/main/java/com/streamy/app/service/StreamService.kt app/src/main/java/com/streamy/app/service/RecordService.kt app/src/main/java/com/streamy/app/ui/StreamyViewModel.kt
git commit -m "fix: single shared Room instance via DatabaseHolder"
```

### Task 17: Whitespace-tolerant status parsing (Finding 14) — TDD

**Files:**
- Modify: `app/src/main/java/com/streamy/app/net/WireSafety.kt:41-47`
- Test: `app/src/test/java/com/streamy/app/net/WireSafetyTest.kt` (append)

**Step 1: Write the failing test**

```kotlin
@Test fun parseHttpStatus_tolerates_extra_whitespace() {
    assertThat(WireSafety.parseHttpStatus("HTTP/1.0  200  OK")).isEqualTo(200)
    assertThat(WireSafety.parseHttpStatus("  HTTP/1.0 200 OK  ")).isEqualTo(200)
    assertThat(WireSafety.parseHttpStatus("HTTP/1.0 2000 Broken")).isEqualTo(-1)
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.WireSafetyTest"`
Expected: FAIL (first assertion returns -1)

**Step 2: Implement**

```kotlin
fun parseHttpStatus(statusLine: String): Int {
    val parts = statusLine.trim().split(Regex("\\s+"))
    if (parts.size < 2) return -1
    val code = parts[1].toIntOrNull() ?: return -1
    return if (code in 100..999) code else -1
}
```

**Step 3: Re-run, expect PASS, commit**

```bash
git add app/src/main/java/com/streamy/app/net/WireSafety.kt app/src/test/java/com/streamy/app/net/WireSafetyTest.kt
git commit -m "fix: whitespace-tolerant HTTP status parsing"
```

---

## Phase 3 — Hygiene + final verification

### Task 18: P3 cleanup batch

**Files:**
- Modify: `app/src/main/java/com/streamy/app/audio/AacEncoder.kt` (remove unused `bitrateKbps` param from `aacObjectType` or document)
- Modify: `app/src/main/java/com/streamy/app/audio/AacEncoder.kt` (rename `stripAdts` → `copyRaw` with comment)
- Modify: `app/src/main/java/com/streamy/app/service/StreamService.kt` (unique recording filenames: append millis)

**Step 1: Implement (three small, safe renames/fixes)**

1. `aacObjectType(bitrateKbps: Int)` → `aacObjectType()` + update the one call site and the KDoc (or keep the param with `@Suppress("UNUSED_PARAMETER")` + comment — prefer removal).
2. `stripAdts` → `copyRawAu`, keeping the explanatory comment that MediaCodec emits raw AUs.
3. Recording filename: `"streamy-${timestamp}.mp3"` → `"streamy-${timestamp}-${System.currentTimeMillis() % 1000}.mp3"` in both `StreamService.startRecording` and `RecordService.startWithProfile`.

**Step 2: Verify**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add -A
git commit -m "chore: P3 cleanup (HE-AAC signature, stripAdts rename, unique filenames)"
```

### Task 19: Full verification + review doc

**Step 1: Run everything**

Run: `./gradlew :app:testDebugUnitTest --rerun-tasks`
Expected: all green

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL

Run: `./gradlew :app:lintDebug`
Expected: no new errors (pre-existing 39 Compose/TOML warnings acceptable)

**Step 2: Update the audit trail**

Append a section to `docs/code-review-2026-08-31.md` (or create `docs/code-review-2026-09-08.md`) listing each finding → commit hash → test coverage.

**Step 3: Commit**

```bash
git add -A
git commit -m "docs: audit fix verification notes"
```

---

## Execution order summary

1. Tasks 0–2 (teardown error preservation — highest user impact)
2. Tasks 3–4 (auth fail-fast)
3. Tasks 5–7 (capture race, record-arm race, secrets scoping)
4. Tasks 8–9 (stale replay, connect timeout)
5. Tasks 10–11 (AAC/Icecast guard)
6. Tasks 12–17 (perf, sticky restart, notif IDs, AAC guard, Room, status parsing)
7. Tasks 18–19 (hygiene + verification)
