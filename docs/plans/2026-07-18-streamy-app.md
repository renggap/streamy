# Streamy App Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ship an Android app that captures live speech from the mic and broadcasts it to a SHOUTcast v2 (UVOX) server, running reliably in the background, with user-selectable codec and bitrate.

**Architecture:** Foreground `StreamService` owns an audio pipeline (`AudioRecord` → `Encoder` → `StreamTransport` → TCP socket). Encoders are pluggable: AAC via `MediaCodec` (native) and MP3 via `libmp3lame` (NDK/JNI). Transport speaks UVOX2 (primary) with a legacy ICY fallback. UI is Jetpack Compose + MVVM, designed with the `hallmark` skill.

**Tech Stack:** Kotlin, Jetpack Compose + Material3, Room, Coroutines, Android `MediaCodec` + `AudioRecord`, NDK + CMake, `libmp3lame` (LGPL), JUnit4 + truth, Docker (Icecast2 + DNAS2) for integration tests.

**Reference design doc:** `docs/plans/2026-07-18-streamy-app-design.md`

---

## How to use this plan

- Tasks are bite-sized and TDD where a unit boundary exists. Each task ends with a commit.
- File paths are exact. Package root: `com.streamy.app`.
- Min SDK 24, target SDK 35.
- The single hardest, most uncertain component is `UvoxClient`. Task 8 captures golden bytes from a real DNAS2 server before writing framing code. Do not skip the capture step.
- For protocol byte layouts you're unsure of: **never guess.** Capture, then encode the captured bytes.

---

## Task 0: Smoke the dev test servers (do this first)

You need working SHOUTcast v2 + Icecast2 servers locally to test against. Stand them up now so every later task can verify against the real thing.

**Files:**
- Create: `docker/docker-compose.yml`

**Step 1: Write the compose file**

```yaml
# docker/docker-compose.yml
services:
  icecast:
    image: infiniteproject/icecast:latest
    ports: ["8000:8000"]
    environment:
      ICECAST_SOURCE_PASSWORD: hackme
      ICECAST_ADMIN_PASSWORD: hackme
      ICECAST_RELAY_PASSWORD: hackme
      ICECAST_ADMIN_USERNAME: admin
      ICECAST_LOCATION: "Streamy Dev"
    volumes:
      - ./icecast/icecast.xml:/etc/icecast.xml

  dnas2:
    # SHOUTcast v2 / DNAS2. Use the official nullsoft image if you have access;
    # otherwise the `savetheinternetradio/sc_serv` community image works for dev.
    image: savetheinternetradio/sc_serv:latest
    ports: ["8001:8000"]
    volumes:
      - ./dnas2/sc_serv.conf:/tmp/sc_serv.conf
```

**Step 2: Provide minimal configs**

Create `docker/icecast/icecast.xml` (standard Icecast2 config with `<sources>`, one `<mount>` named `/stream`) and `docker/dnas2/sc_serv.conf` (DNAS2 config with `password=hackme`, `adminpassword=hackme`, `streamid_1=1`, `streampath_1=/stream`). Minimal configs only — enough to accept a source connection on `/stream`.

**Step 3: Bring it up**

Run: `docker compose -f docker/docker-compose.yml up -d`
Expected: both containers healthy, Icecast admin at `http://localhost:8000/admin.cgi`, DNAS2 admin at `http://localhost:8001/admin.cgi`.

**Step 4: Verify a known-good client can publish**

Use `ffmpeg` to push 10s of silence as a sanity check:

```bash
ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 10 -c:a libmp3lame -b:a 128k \
  -content_type audio/mpeg -ice_name "Smoke" -ice_genre "Speech" \
  "http://source:hackme@localhost:8000/stream"
```

Expected: Icecast accepts the connection; `http://localhost:8000/stream` is a live mount. Repeat against DNAS2 port 8001. **Capture this `ffmpeg` invocation's TCP bytes with a packet capture** (Task 8 uses them):

```bash
sudo tcpdump -i lo -w docker/captures/icy_source.pcap port 8000 &
sudo tcpdump -i lo -w docker/captures/dnas2_source.pcap port 8001 &
```

**Step 5: Commit**

```bash
git add docker/
git commit -m "chore: add Icecast2 + DNAS2 dev servers and smoke capture"
```

---

## Task 1: Project scaffold (Gradle, manifest, Compose shell)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts` (root), `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/streamy/app/MainActivity.kt`, `app/src/main/java/com/streamy/app/StreamyApp.kt`, `app/src/main/res/values/themes.xml`, `app/src/main/res/values/strings.xml`
- Use the latest stable AGP + Kotlin + Compose BOM at time of writing.

**Step 1: Gradle skeleton**

`gradle/libs.versions.toml` — pins: AGP 8.7+, Kotlin 2.0+, Compose BOM 2024.10+, Room 2.6+, Coroutines 1.9+, JUnit 4.13.2, `com.google.truth:truth:1.4.4`, `androidx.test:core` + `runner` + `ext-junit`. Enable Compose compiler via the Kotlin 2.0 compose plugin.

`app/build.gradle.kts` highlights:

```kotlin
android {
    namespace = "com.streamy.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.streamy.app"
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { cppFlags += "" } }
        ndkVersion = "27.0.12077973"          // or installed NDK
    }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
```

**Step 2: Manifest with permissions + service declaration**

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
    <uses-permission android:name="android.permission.WAKE_LOCK"/>
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>

    <application
        android:name=".StreamyApp"
        android:label="@string/app_name"
        android:theme="@style/Theme.Streamy"
        android:usesCleartextTraffic="true">   <!-- dev: allow http to local servers -->

        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>

        <service
            android:name=".service.StreamService"
            android:exported="false"
            android:foregroundServiceType="microphone"/>
    </application>
</manifest>
```

`usesCleartextTraffic="true"` is dev-only (local `http://`). Replace with `networkSecurityConfig` before any release build.

**Step 3: Minimal Compose shell so the app boots**

`MainActivity.kt`:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Streamy") }
    }
}
```

`StreamyApp.kt`:

```kotlin
class StreamyApp : Application()
```

**Step 4: Build + install on a device/emulator, confirm it launches**

Run: `./gradlew :app:assembleDebug` then install on a connected device.
Expected: builds clean, launches showing "Streamy".

**Step 5: Commit**

```bash
git add .
git commit -m "chore: project scaffold (Gradle, manifest, Compose shell)"
```

---

## Task 2: Room — server profile storage

**Files:**
- Create: `app/src/main/java/com/streamy/app/data/db/ProfileEntity.kt`
- Create: `app/src/main/java/com/streamy/app/data/db/ProfileDao.kt`
- Create: `app/src/main/java/com/streamy/app/data/db/AppDatabase.kt`
- Create: `app/src/main/java/com/streamy/app/data/ProfileRepository.kt`
- Create: `app/src/main/java/com/streamy/app/data/Models.kt`
- Test: `app/src/test/java/com/streamy/app/data/ProfileRepositoryTest.kt`

**Step 1: Domain models first (no Android deps, easy to test)**

`Models.kt`:

```kotlin
package com.streamy.app.data

enum class Codec { AAC, MP3 }

/** Bitrate + sample-rate + channels resolved for an encode session. */
data class AudioConfig(
    val codec: Codec,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val mono: Boolean,
)

/** Validates a chosen AudioConfig against codec rules. Throws on impossible combos. */
object AudioConfigRules {
    val AAC_BITRATES = listOf(16, 24, 32, 48, 64, 96, 128)
    val MP3_BITRATES = listOf(16, 24, 48, 64, 96, 128, 192)
    val SAMPLE_RATES = listOf(16000, 22050, 44100, 48000)

    /** At 16/24 kbps we want HE-AAC v1 (AAC+) and mono + low rate. */
    fun needsHeAac(codec: Codec, bitrateKbps: Int) =
        codec == Codec.AAC && bitrateKbps <= 24

    fun validate(c: AudioConfig): AudioConfig {
        val allowed = if (c.codec == Codec.AAC) AAC_BITRATES else MP3_BITRATES
        require(c.bitrateKbps in allowed) { "bitrate ${c.bitrateKbps} not in $allowed" }
        require(c.sampleRateHz in SAMPLE_RATES) { "sample rate ${c.sampleRateHz}" }
        if (needsHeAac(c.codec, c.bitrateKbps)) require(c.mono) { "16/24k must be mono" }
        return c
    }
}
```

**Step 2: Write the failing test for `AudioConfigRules`**

```kotlin
package com.streamy.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioConfigRulesTest {
    @Test fun aac_low_rate_needs_heaac_and_mono() {
        assertThat(AudioConfigRules.needsHeAac(Codec.AAC, 16)).isTrue()
        assertThat(AudioConfigRules.needsHeAac(Codec.AAC, 32)).isFalse()
        assertThat(AudioConfigRules.needsHeAac(Codec.MP3, 16)).isFalse()
    }

    @Test fun validate_accepts_aac_64_stereo() {
        AudioConfigRules.validate(AudioConfig(Codec.AAC, 64, 44100, mono = false))
    }

    @Test fun validate_rejects_16k_stereo() {
        assertThrows<IllegalArgumentException> {
            AudioConfigRules.validate(AudioConfig(Codec.AAC, 16, 22050, mono = false))
        }
    }

    @Test fun validate_rejects_unknown_bitrate() {
        assertThrows<IllegalArgumentException> {
            AudioConfigRules.validate(AudioConfig(Codec.MP3, 77, 44100, mono = true))
        }
    }
}

inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
    try { block(); error("expected ${T::class}") }
    catch (e: Throwable) { if (e !is T) throw e }
}
```

**Step 3: Run, confirm fail→pass for the rules portion**

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.data.AudioConfigRulesTest"`
Expected: passes once `Models.kt` is present.

**Step 4: Room entity, DAO, database, repository**

`ProfileEntity.kt`:

```kotlin
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val sid: Int,                 // SHOUTcast v2 stream id; 1 default
    val password: String,
    val mount: String,            // used by ICY fallback, e.g. "/stream"
    val codec: String,            // "AAC" | "MP3"
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val mono: Boolean,
)
```

`ProfileDao.kt`:

```kotlin
@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY id")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun byId(id: Long): ProfileEntity?

    @Insert suspend fun insert(p: ProfileEntity): Long
    @Update suspend fun update(p: ProfileEntity)
    @Delete suspend fun delete(p: ProfileEntity)
}
```

`AppDatabase.kt`:

```kotlin
@Database(entities = [ProfileEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() { abstract fun profileDao(): ProfileDao }
```

`ProfileRepository.kt` — thin wrapper, exposes `Flow<List<ProfileEntity>>`, `save`, `delete`, and a pure `toAudioConfig(ProfileEntity): AudioConfig` mapper that calls `AudioConfigRules.validate`.

**Step 5: Commit**

```bash
git add app/src/main/java/com/streamy/app/data app/src/test/java/com/streamy/app/data
git commit -m "feat(data): Room profile storage + AudioConfig rules (TDD)"
```

---

## Task 3: Audio capture (`AudioRecord` wrapper)

**Files:**
- Create: `app/src/main/java/com/streamy/app/audio/AudioCapture.kt`

**Step 1: Implement**

```kotlin
package com.streamy.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/** Reads 16-bit PCM from the mic in fixed-size frames, off the main thread. */
class AudioCapture(
    private val sampleRateHz: Int,
    private val mono: Boolean,
    private val frameSamples: Int = 1024,   // ~23ms @44.1k
) {
    private val channelConfig =
        if (mono) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private var record: AudioRecord? = null
    private var running = false

    /** Called repeatedly with a freshly-filled buffer; return false to stop. */
    fun start(onFrame: (ShortArray) -> Boolean) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuf > 0) { "min buffer $minBuf (bad params?)" }
        val bufBytes = Math.max(minBuf, frameSamples * 2 * (if (mono) 1 else 2))
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufBytes
        )
        check(record?.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        record!!.startRecording()
        running = true
        thread(name = "streamy-audio", isDaemon = true) {
          val buf = ShortArray(frameSamples * (if (mono) 1 else 2))
          while (running) {
              val n = record!!.read(buf, 0, buf.size)
              if (n > 0 && !onFrame(buf.copyOf(n))) running = false
          }
        }
    }

    fun stop() {
        running = false
        record?.run { try { stop() } finally { release() } }
        record = null
    }
}
```

Notes for the implementing engineer:
- `VOICE_COMMUNICATION` gives echo-cancelled, AGC'd speech — good default. Make the audio source a later config knob if users want raw `MIC`.
- A capture dropout (another app takes the mic) surfaces as repeated short reads; `AudioPipeline` (Task 7) turns persistent short/zero reads into `StreamState.Error("mic_busy")`.

**Step 2: No unit test (requires hardware)** — verified by the integration test in Task 10.

**Step 3: Commit**

```bash
git add app/src/main/java/com/streamy/app/audio/AudioCapture.kt
git commit -m "feat(audio): AudioRecord capture wrapper"
```

---

## Task 4: Encoder interface + AAC encoder (`MediaCodec`)

**Files:**
- Create: `app/src/main/java/com/streamy/app/audio/Encoder.kt`
- Create: `app/src/main/java/com/streamy/app/audio/AacEncoder.kt`
- Test: `app/src/test/java/com/streamy/app/audio/AacEncoderConfigTest.kt`

**Step 1: Encoder contract**

```kotlin
package com.streamy.app.audio

interface Encoder : AutoCloseable {
    /** Encode one PCM frame; return raw encoded bytes (ADTS already stripped for AAC). */
    fun encode(pcm: ShortArray): List<ByteArray>
    /** Final trailing frames (e.g. LAME flush). */
    fun flush(): List<ByteArray>
    val mime: String           // "audio/aac" or "audio/mpeg"
}
```

**Step 2: Failing config test (pure: bitrate→object-type mapping, no codec needed)**

```kotlin
class AacEncoderConfigTest {
    @Test fun low_rate_maps_to_heaac_object_type() {
        assertThat(AacEncoder.aacObjectType(16)).isEqualTo(MediaCodecInfo.CodecProfileLevel.AACObjectHE)
        assertThat(AacEncoder.aacObjectType(24)).isEqualTo(MediaCodecInfo.CodecProfileLevel.AACObjectHE)
        assertThat(AacEncoder.aacObjectType(64)).isEqualTo(MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    }
}
```

**Step 3: Run, confirm fail, then implement the pure function + the full encoder**

`AacEncoder.kt`:

```kotlin
package com.streamy.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

class AacEncoder(
    sampleRateHz: Int,
    bitrateBps: Int,
    mono: Boolean,
) : Encoder {
    override val mime = "audio/aac"

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val format = MediaFormat().apply {
        setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC)
        setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRateHz)
        setInteger(MediaFormat.KEY_CHANNEL_COUNT, if (mono) 1 else 2)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        setInteger(MediaFormat.KEY_AAC_PROFILE, aacObjectType(bitrateBps / 1000))
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
    }
    private val info = MediaCodec.BufferInfo()
    private var eos = false

    init { codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start() }

    override fun encode(pcm: ShortArray): List<ByteArray> {
        if (eos) return emptyList()
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)!!
            buf.clear()
            val bytes = ByteArray(pcm.size * 2)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
            buf.put(bytes)
            codec.queueInputBuffer(inIdx, 0, bytes.size, 0, 0)
        }
        return drain()
    }

    override fun flush(): List<ByteArray> {
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        eos = true
        return drain()
    }

    private fun drain(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        while (true) {
            val o = codec.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> {
                    val raw = stripAdts(codec.getOutputBuffer(o)!!, info.size)
                    out.add(raw)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return out
                }
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return out
            }
        }
    }

    /** AAC config broadcast wants raw AAC-LC/HE frames, no ADTS header (7/9 bytes). */
    private fun stripAdts(buf: java.nio.ByteBuffer, len: Int): ByteArray {
        val pos = buf.position()
        val total = buf.limit() - pos
        if (len in 7..9 && total > len) buf.position(pos + len)   // crude: drop ADTS-sized prefix
        val raw = ByteArray(total - (buf.position() - pos))
        buf.get(raw)
        return raw
    }

    override fun close() { try { codec.stop() } finally { codec.release() } }

    companion object {
        /** 16/24k → HE-AAC v1, else AAC-LC. Pure → unit tested. */
        fun aacObjectType(bitrateKbps: Int) =
            if (bitrateKbps <= 24) MediaCodecInfo.CodecProfileLevel.AACObjectHE
            else MediaCodecInfo.CodecProfileLevel.AACObjectLC
    }
}
```

Caveat (mark with a `// ponytail:` comment): the ADTS-strip heuristic above is a placeholder — `MediaCodec` AAC encoders on Android may emit raw packets already (no ADTS) depending on KEY_AAC_ENCODED_TARGET_FORMAT. During Task 10 integration, confirm with a real DNAS2 whether raw frames are accepted; if the server expects ADTS, set `KEY_AAC_ENCODED_TARGET_FORMAT = ADTS` and **stop** stripping. The upgrade path is in the comment.

**Step 4: Run test, confirm pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.streamy.app.audio.AacEncoderConfigTest"`
Expected: PASS.

**Step 5: Commit**

```bash
git add app/src/main/java/com/streamy/app/audio/Encoder.kt app/src/main/java/com/streamy/app/audio/AacEncoder.kt app/src/test
git commit -m "feat(audio): Encoder contract + AAC MediaCodec encoder (HE-AAC at low rate)"
```

---

## Task 5: MP3 encoder via NDK + `libmp3lame`

**Files:**
- Create: `app/src/main/cpp/CMakeLists.txt`
- Create: `app/src/main/cpp/lame_jni.cpp`
- Create: `app/src/main/java/com/streamy/app/audio/Mp3Encoder.kt`
- Vendored: `app/src/main/cpp/lame/` (LAME sources, LGPL)

**Step 1: Vendor LAME**

Drop LAME source (or a prebuilt `libmp3lame.a`) under `app/src/main/cpp/lame/`. Record the version and LGPL notice in `app/src/main/res/raw/lame_notice.txt` and surface it in the About screen (Task 9).

**Step 2: CMakeLists**

```cmake
cmake_minimum_required(VERSION 3.22)
project(streamy_lame)

add_library(mp3lame STATIC
    lame/src/lame.c
    lame/src/util.c
    lame/src/quantize.c
    lame/src/vbrquantize.c
    lame/src/takehiro.c
    lame/src/id3tag.c
    lame/src/version.c
    lame/src/presets.c
    lame/src/VbrTag.c
    lame/src/common.c
    lame/src/mpeghuffman.c
    lame/src/encoder.c
    lame/src/fft.c
    lame/src/gain_analysis.c
    lame/src/mpglib_interface.c
    lame/src/newmdct.c
    lame/src/noise.c
    lame/src/reservoir.c
    lame/src/pcm.c
)
target_include_directories(mp3lame PUBLIC lame/include)

add_library(streamy_lame SHARED lame_jni.cpp)
target_link_libraries(streamy_lame mp3lame log)
```

(Adjust the file list to match your vendored LAME tree.)

**Step 3: JNI bridge**

`lame_jni.cpp`:

```cpp
#include <jni.h>
#include <lame/lame.h>
#include <android/log.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_streamy_app_audio_Mp3Encoder_nativeInit(
        JNIEnv* env, jobject, jint sampleRate, jint channels, jint bitrateKbps) {
    lame_t g = lame_init();
    lame_set_in_samplerate(g, sampleRate);
    lame_set_num_channels(g, channels);
    lame_set_out_samplerate(g, sampleRate);
    lame_set_brate(g, bitrateKbps);
    lame_set_quality(g, 5);                 // 0=best,9=fastest; 5 is a good default
    if (lame_init_params(g) < 0) { lame_close(g); return 0; }
    return reinterpret_cast<jlong>(g);
}

// returns number of MP3 bytes written into outBuffer
extern "C" JNIEXPORT jint JNICALL
Java_com_streamy_app_audio_Mp3Encoder_nativeEncode(
        JNIEnv* env, jobject, jlong ptr, jshortArray pcm, jint n, jbyteArray out) {
    lame_t g = reinterpret_cast<lame_t>(ptr);
    jshort* in = env->GetShortArrayElements(pcm, nullptr);
    jbyte* o = env->GetByteArrayElements(out, nullptr);
    int written = lame_encode_buffer(g, in, in, n, reinterpret_cast<unsigned char*>(o), n);
    env->ReleaseShortArrayElements(pcm, in, JNI_ABORT);
    env->ReleaseByteArrayElements(out, o, 0);
    return written;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_streamy_app_audio_Mp3Encoder_nativeFlush(
        JNIEnv* env, jobject, jlong ptr, jbyteArray out) {
    lame_t g = reinterpret_cast<lame_t>(ptr);
    jbyte* o = env->GetByteArrayElements(out, nullptr);
    int w = lame_encode_flush(g, reinterpret_cast<unsigned char*>(o), 7200);
    env->ReleaseByteArrayElements(out, o, 0);
    return w;
}

extern "C" JNIEXPORT void JNICALL
Java_com_streamy_app_audio_Mp3Encoder_nativeClose(JNIEnv*, jobject, jlong ptr) {
    if (ptr) lame_close(reinterpret_cast<lame_t>(ptr));
}
```

**Step 4: Kotlin side**

```kotlin
package com.streamy.app.audio

class Mp3Encoder(
    sampleRateHz: Int,
    bitrateKbps: Int,
    mono: Boolean,
) : Encoder {
    override val mime = "audio/mpeg"
    private val channels = if (mono) 1 else 2
    private val ptr = nativeInit(sampleRateHz, channels, bitrateKbps)
    init { check(ptr != 0L) { "lame_init_params failed" } }

    override fun encode(pcm: ShortArray): List<ByteArray> {
        val maxOut = pcm.size * 5 / 4 + 7200
        val out = ByteArray(maxOut)
        val n = nativeEncode(ptr, pcm, pcm.size, out)
        return if (n > 0) listOf(out.copyOf(n)) else emptyList()
    }
    override fun flush(): List<ByteArray> {
        val out = ByteArray(7200)
        val n = nativeFlush(ptr, out)
        return if (n > 0) listOf(out.copyOf(n)) else emptyList()
    }
    override fun close() = nativeClose(ptr)

    private external fun nativeInit(sampleRate: Int, channels: Int, bitrateKbps: Int): Long
    private external fun nativeEncode(ptr: Long, pcm: ShortArray, n: Int, out: ByteArray): Int
    private external fun nativeFlush(ptr: Long, out: ByteArray): Int
    private external fun nativeClose(ptr: Long)
    companion object { init { System.loadLibrary("streamy_lame") } }
}
```

**Step 5: Build the native module, confirm `.so` is produced**

Run: `./gradlew :app:externalNativeBuildDebug`
Expected: builds `libstreamy_lame.so` for each ABI.

**Step 6: Commit**

```bash
git add app/src/main/cpp app/src/main/java/com/streamy/app/audio/Mp3Encoder.kt app/src/main/res/raw/lame_notice.txt
git commit -m "feat(audio): MP3 encoder via libmp3lame NDK/JNI (LGPL)"
```

---

## Task 6: `EncoderFactory`

**Files:**
- Create: `app/src/main/java/com/streamy/app/audio/EncoderFactory.kt`
- Test: `app/src/test/java/com/streamy/app/audio/EncoderFactoryTest.kt`

**Step 1: Failing test**

```kotlin
class EncoderFactoryTest {
    @Test fun selects_aac_and_sets_mime() {
        val e = EncoderFactory.create(AudioConfig(Codec.AAC, 64, 44100, mono = false))
        assertThat(e.mime).isEqualTo("audio/aac")
        e.close()
    }
    @Test fun selects_mp3_and_sets_mime() {
        val e = EncoderFactory.create(AudioConfig(Codec.MP3, 128, 44100, mono = true))
        assertThat(e.mime).isEqualTo("audio/mpeg")
        e.close()
    }
}
```

**Step 2: Run (fails — no factory), then implement**

```kotlin
package com.streamy.app.audio

import com.streamy.app.data.AudioConfig
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec

object EncoderFactory {
    fun create(cfg: AudioConfig): Encoder {
        AudioConfigRules.validate(cfg)
        return when (cfg.codec) {
            Codec.AAC -> AacEncoder(cfg.sampleRateHz, cfg.bitrateKbps * 1000, cfg.mono)
            Codec.MP3 -> Mp3Encoder(cfg.sampleRateHz, cfg.bitrateKbps, cfg.mono)
        }
    }
}
```

This test instantiates `MediaCodec`; run it as **instrumented** if your JVM test runner lacks `MediaCodec`. Move it to `androidTest` if the JVM run fails with a stub exception.

**Step 3: Commit**

```bash
git add app/src/main/java/com/streamy/app/audio/EncoderFactory.kt app/src/test/java/com/streamy/app/audio/EncoderFactoryTest.kt
git commit -m "feat(audio): EncoderFactory (TDD)"
```

---

## Task 7: ICY client (legacy fallback that DNAS2 accepts — complete, correct)

The legacy HTTP-ICY source protocol is the safe baseline. DNAS2 accepts it; Icecast2 accepts it. Ship this working first, then layer UVOX2 on top (Task 8).

**Files:**
- Create: `app/src/main/java/com/streamy/app/net/IcyClient.kt`
- Create: `app/src/main/java/com/streamy/app/net/ProtocolException.kt`
- Test: `app/src/test/java/com/streamy/app/net/IcyClientHandshakeTest.kt`

**Step 1: Failing test — handshake request bytes are exactly right**

```kotlin
class IcyClientHandshakeTest {
    @Test fun put_request_for_mp3_is_well_formed() {
        val req = IcyClient.buildHandshake(
            mount = "/stream", host = "srv.example:8000",
            contentType = "audio/mpeg", bitrateKbps = 128,
            name = "Streamy", genre = "Speech", password = "hackme")
        val s = String(req, Charsets.US_ASCII)
        assertThat(s).startsWith("PUT /stream HTTP/1.1\r\n")
        assertThat(s).contains("Host: srv.example:8000\r\n")
        assertThat(s).contains("Authorization: Basic " +
            java.util.Base64.getEncoder().encodeToString("source:hackme".toByteArray()))
        assertThat(s).contains("Content-Type: audio/mpeg\r\n")
        assertThat(s).contains("ice-bitrate: 128\r\n")
        assertThat(s).contains("ice-name: Streamy\r\n")
        assertThat(s).endsWith("\r\n\r\n")
    }
}
```

**Step 2: Run (fails), then implement**

```kotlin
package com.streamy.app.net

import java.io.OutputStream
import java.net.Socket

/** Legacy HTTP/ICY source client. DNAS2 (v2) accepts it in legacy mode; Icecast2 accepts it. */
class IcyClient(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val mount: String,
) {
    private var socket: Socket? = null
    private var out: OutputStream? = null

    /** @param contentType "audio/mpeg" or "audio/aacp" */
    fun connect(contentType: String, bitrateKbps: Int, name: String, genre: String) {
        val s = Socket(host, port); socket = s; s.tcpNoDelay = true
        val req = buildHandshake(mount, "$host:$port", contentType, bitrateKbps, name, genre, password)
        val o = s.getOutputStream(); o.write(req); o.flush(); out = o
        val line = s.getInputStream().bufferedReader().readLine()
            ?: throw ProtocolException("empty response")
        if (!line.contains("200")) throw ProtocolException("server said: $line")
    }

    fun send(frame: ByteArray) { out!!.write(frame); out!!.flush() }

    fun close() { try { socket?.close() } finally { socket = null; out = null } }

    companion object {
        fun buildHandshake(
            mount: String, host: String, contentType: String,
            bitrateKbps: Int, name: String, genre: String, password: String,
        ): ByteArray {
            val auth = java.util.Base64.getEncoder().encodeToString("source:$password".toByteArray())
            val sb = StringBuilder()
            sb.append("PUT ").append(mount).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(host).append("\r\n")
            sb.append("Authorization: Basic ").append(auth).append("\r\n")
            sb.append("Content-Type: ").append(contentType).append("\r\n")
            sb.append("ice-bitrate: ").append(bitrateKbps).append("\r\n")
            sb.append("ice-name: ").append(name).append("\r\n")
            sb.append("ice-genre: ").append(genre).append("\r\n")
            sb.append("ice-public: 1\r\n")
            sb.append("ice-url: https://streamy.app\r\n")
            sb.append("\r\n")
            return sb.toString().toByteArray(Charsets.US_ASCII)
        }
    }
}
```

`ProtocolException.kt`:

```kotlin
package com.streamy.app.net
class ProtocolException(message: String) : RuntimeException(message)
```

**Step 3: Run test → PASS. Then verify against the dev Icecast:** wire `IcyClient` into a throwaway `main()` / instrumented snippet, push 10s of MP3 frames from `Mp3Encoder` fed by `AudioCapture` to `localhost:8000/stream`, confirm the mount appears in the Icecast admin.

**Step 4: Commit**

```bash
git add app/src/main/java/com/streamy/app/net app/src/test/java/com/streamy/app/net
git commit -m "feat(net): legacy ICY source client (DNAS2-compatible, TDD handshake)"
```

---

## Task 8: UVOX2 client (TDD against captured golden bytes — the risky task)

**Goal:** Speak the real SHOUTcast v2 Ultravox source protocol so we get proper `sid` routing and in-stream metadata.

**⚠ Do not invent bytes.** The Ultravox 2.1 wire format is fiddly; the only reliable source is a real DNAS2 handshake. This task captures it first.

**Files:**
- Create: `docker/captures/dnas2_source.pcap` (already from Task 0; re-capture if needed)
- Create: `app/src/main/java/com/streamy/app/net/UvoxClient.kt`
- Create: `app/src/main/java/com/streamy/app/net/UvoxFrames.kt`
- Create: `app/src/test/resources/uvox/golden_handshake.bin`
- Test: `app/src/test/java/com/streamy/app/net/UvoxClientHandshakeTest.kt`

**Step 1: Capture a reference handshake**

Start the DNAS2 dev server. Publish to it from a known-good v2 source client (e.g., Edcast/oddcast with Ultravox enabled, or `sc_trans` if available). Capture the TCP exchange to the source port:

```bash
sudo tcpdump -i lo -w docker/captures/dnas2_source.pcap port 8001
# publish ~10s of audio, then stop
```

Open the pcap in Wireshark, follow the TCP stream, and export the **first N bytes the client sent** (the Ultravox handshake + first data frame) as `app/src/test/resources/uvox/golden_handshake.bin`. Also record the server's response bytes.

**Step 2: Document the discovered layout**

Write `app/src/main/java/com/streamy/app/net/UvoxProtocol.md` describing, **from the bytes you captured**: the frame header layout, the message-type constants (negotiate / auth / config-broadcast / stream-data / metadata), the auth sequence, and how `sid` is encoded. Cite the Ultravox 2.1 spec by section for each.

**Step 3: Failing test — our handshake matches the golden bytes**

```kotlin
class UvoxClientHandshakeTest {
    @Test fun client_handshake_matches_captured_golden() {
        val golden = readResource("/uvox/golden_handshake.bin")
        val ours = UvoxFrames.negotiateRequest(pass = "hackme", sid = 1, codec = "MP3", bitrate = 128)
        // Compare up to the length of the negotiate frame (exclude the live audio that follows).
        assertThat(ours).isEqualTo(golden.copyOfRange(0, ours.size))
    }
}
```

**Step 4: Implement `UvoxFrames`** to reproduce the captured bytes exactly. If the test won't match, the fix is *your frame builder*, never the golden file — go back to the pcap.

**Step 5: Implement `UvoxClient`**

Same shape as `IcyClient` — `connect()`, `send(frame)`, `sendMetadata(title)`, `close()` — but speaking Ultravox: send negotiate → read negotiate-ack → send auth → read auth-ack → send config-broadcast → read ack → then stream data frames; send a metadata frame every ~1s. Reconnect re-runs the whole handshake (encoder is re-init upstream).

**Step 6: Verify against dev DNAS2** — publish 10s, confirm the stream appears on the DNAS2 admin with the correct `sid` and that title updates appear.

**Step 7: Commit**

```bash
git add app/src/main/java/com/streamy/app/net/UvoxClient.kt app/src/main/java/com/streamy/app/net/UvoxFrames.kt app/src/main/java/com/streamy/app/net/UvoxProtocol.md app/src/test
git commit -m "feat(net): UVOX2 source client (golden-byte TDD against real DNAS2)"
```

---

## Task 9: `StreamTransport` — backpressure + reconnect

**Files:**
- Create: `app/src/main/java/com/streamy/app/net/StreamTransport.kt`
- Test: `app/src/test/java/com/streamy/app/net/StreamTransportBackoffTest.kt`

**Step 1: Pure test — backoff schedule, cap, jitter bounds**

```kotlin
class StreamTransportBackoffTest {
    @Test fun backoff_grows_then_caps_and_jitters() {
        val delays = (1..12).map { StreamTransport.nextBackoffMs(it) }
        // raw caps at 20_000; jitter keeps it within +-20%
        assertThat(delays.max()).isAtMost(20_000L * 12 / 10)
        assertThat(delays[0]).isAtLeast(900L); assertThat(delays[0]).isAtMost(1_100L)
        assertThat(delays[5]).isAtMost(20_000L)   // capped well before attempt 5
    }
    @Test fun gives_up_after_max_attempts() {
        assertThat(StreamTransport.shouldGiveUp(attempt = 10)).isTrue()
        assertThat(StreamTransport.shouldGiveUp(attempt = 9)).isFalse()
    }
}
```

**Step 2: Run (fails), then implement the pure functions + the transport**

```kotlin
package com.streamy.app.net

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

class StreamTransport(
    private val scope: CoroutineScope,
    private val connect: suspend () -> Unit,        // runs full handshake
    private val send: (ByteArray) -> Unit,
    private val onState: (TransportState) -> Unit,
) {
    private val queue = Channel<ByteArray>(CAPACITY)
    private var drops = 0L

    fun enqueue(frame: ByteArray) {
        val result = queue.trySend(frame)
        if (result.isFailure) { drops++; onState(TransportState.Backpressure(drops)) }
    }

    fun start() {
        scope.launch {
            var attempt = 0
            while (!shouldGiveUp(attempt)) {
                try {
                    onState(if (attempt == 0) TransportState.Connecting else TransportState.Reconnecting(attempt))
                    connect()
                    onState(TransportState.Live)
                    attempt = 0
                    for (f in queue) send(f)
                } catch (e: Throwable) {
                    attempt++
                    onState(TransportState.Backoff(attempt, nextBackoffMs(attempt)))
                    delay(nextBackoffMs(attempt))
                }
            }
            onState(TransportState.Failed("max reconnect attempts"))
        }
    }

    fun stop() { queue.close() }

    companion object {
        const val CAPACITY = 64
        fun shouldGiveUp(attempt: Int) = attempt >= 10
        fun nextBackoffMs(attempt: Int): Long {
            val raw = listOf(1_000L, 2_000L, 5_000L, 10_000L).getOrElse(attempt - 1) { 20_000L }
            val jit = (raw * ((attempt * 37) % 40 - 20) / 100)   // deterministic +-20% (no Math.random)
            return (raw + jit).coerceIn(500L, 20_000L)
        }
    }
}

sealed interface TransportState {
    data object Connecting : TransportState
    data class Reconnecting(val attempt: Int) : TransportState
    data class Backoff(val attempt: Int, val ms: Long) : TransportState
    data object Live : TransportState
    data class Backpressure(val drops: Long) : TransportState
    data class Failed(val reason: String) : TransportState
}
```

Note: jitter above is deterministic (no `Math.random`) so it's testable. In production you may swap in real randomness; document that as a `// ponytail:` upgrade.

**Step 3: Run test → PASS. Commit.**

```bash
git add app/src/main/java/com/streamy/app/net/StreamTransport.kt app/src/test/java/com/streamy/app/net/StreamTransportBackoffTest.kt
git commit -m "feat(net): StreamTransport with bounded queue + reconnect backoff (TDD)"
```

---

## Task 10: `AudioPipeline` (glue + mic-busy detection)

**Files:**
- Create: `app/src/main/java/com/streamy/app/audio/AudioPipeline.kt`

**Step 1: Implement**

```kotlin
package com.streamy.app.audio

import com.streamy.app.net.StreamTransport

/** Glues AudioCapture -> Encoder -> StreamTransport. Detects mic loss. */
class AudioPipeline(
    private val capture: AudioCapture,
    private val encoder: Encoder,
    private val transport: StreamTransport,
    private val onMicBusy: () -> Unit,
) {
    private var consecutiveShortReads = 0

    fun start() {
        capture.start { pcm ->
            if (pcm.size < MIN_USEFUL_SAMPLES) {
                if (++consecutiveShortReads > SHORT_READ_LIMIT) { onMicBusy(); return@start false }
            } else consecutiveShortReads = 0
            for (frame in encoder.encode(pcm)) transport.enqueue(frame)
            true
        }
    }

    fun stop() {
        for (frame in encoder.flush()) transport.enqueue(frame)
        capture.stop()
    }

    companion object {
        private const val MIN_USEFUL_SAMPLES = 160
        private const val SHORT_READ_LIMIT = 50   // ~1s of persistent short reads
    }
}
```

**Step 2: Integration-check on a device** — wire pipeline to `IcyClient` + dev Icecast; confirm 10s of audio reaches the mount. Defer formal coverage to Task 13's integration test.

**Step 3: Commit**

```bash
git add app/src/main/java/com/streamy/app/audio/AudioPipeline.kt
git commit -m "feat(audio): AudioPipeline (capture->encode->transport, mic-busy detection)"
```

---

## Task 11: `StreamService` — foreground, wakelock, wifilock

**Files:**
- Create: `app/src/main/java/com/streamy/app/service/StreamService.kt`
- Create: `app/src/main/java/com/streamy/app/service/StreamState.kt`
- Create: `app/src/main/java/com/streamy/app/service/ServiceController.kt`
- Create: `app/src/main/res/values/strings.xml` (notification strings)
- Create: `app/src/main/res/xml/stream_notification_channel` (channel via code is fine)

**Step 1: State model**

```kotlin
sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data object Live : StreamState
    data class Reconnecting(val attempt: Int) : StreamState
    data class Error(val reason: String) : StreamState
}
```

**Step 2: The foreground service**

```kotlin
class StreamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var pipeline: AudioPipeline? = null
    private var transport: StreamTransport? = null
    private var encoder: Encoder? = null
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() { fun service(): StreamService = this@StreamService }
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.toProfile())
            ACTION_STOP -> stopAndShutdown()
        }
        return START_STICKY
    }

    private fun start(profile: ProfileEntity) {
        startForeground(NOTIF_ID, buildNotification("Connecting…"))
        acquireLocks()
        val cfg = ProfileRepository.toAudioConfig(profile)
        val enc = EncoderFactory.create(cfg); encoder = enc
        val client = UvoxClient(profile.host, profile.port, profile.password, sid = profile.sid, mount = profile.mount)
        val trans = StreamTransport(
            scope = scope,
            connect = {
                client.connect(enc.mime, cfg.bitrateKbps, name = profile.name, genre = "Speech")
            },
            send = { client.send(it) },
            onState = { ts -> _state.value = ts.toStreamState() },
        )
        transport = trans; trans.start()
        pipeline = AudioPipeline(
            capture = AudioCapture(cfg.sampleRateHz, cfg.mono),
            encoder = enc,
            transport = trans,
            onMicBusy = { _state.value = StreamState.Error("mic_busy") },
        ).also { it.start() }
        _state.value = StreamState.Live
        updateNotification("Live: ${profile.name}")
    }

    private fun stopAndShutdown() {
        runCatching { pipeline?.stop() }
        transport?.stop()
        runCatching { encoder?.close() }
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        _state.value = StreamState.Idle
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streamy::stream").apply { acquire(30 * 60 * 1000L) }
        val wm = getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "streamy::wifi").apply { acquire() }
    }
    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
    }

    companion object {
        const val ACTION_START = "com.streamy.START"
        const val ACTION_STOP = "com.streamy.STOP"
        const val NOTIF_ID = 1
    }
}
```

`ServiceController.kt` — static helpers `start(context, profile)` / `stop(context)` that build the intent, call `startForegroundService` (API 26+), and provide `bind(context)` returning the bound service via `ServiceConnection`.

**FGS timing correctness:** `startForeground` is called at the top of `start()` before any blocking network work, satisfying the ~5s budget.

**Step 3: Instrumented test — lifecycle + no leaked locks**

```kotlin
@RunWith(AndroidJUnit4::class)
class StreamServiceTest {
    @get:Rule val perm = GrantPermissionRule.grant(RECORD_AUDIO, POST_NOTIFICATIONS)

    @Test fun starts_live_then_stops_cleanly() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ServiceController.start(ctx, fakeProfile())
        // poll bound service until state == Live (10s timeout)
        // then ServiceController.stop(ctx)
        // assert wakelock/wifilock reported released via dumpsys:
        //   `adb shell dumpsys power | grep streamy::` is empty
    }
}
```

**Step 4: Run instrumented test on device → PASS. Commit.**

```bash
git add app/src/main/java/com/streamy/app/service app/src/androidTest
git commit -m "feat(service): foreground StreamService + wakelock/wifilock + lifecycle test"
```

---

## Task 12: Bitrate chooser + Home UI (Hallmark design)

> **REQUIRED SUB-SKILL for this task:** load and follow the `hallmark` skill for the visual design of every screen below. Do not ship default Material3 boilerplate — Hallmark governs type, color, spacing, motion. The bitrate chooser is the hero control on Home.

**Files:**
- Create: `app/src/main/java/com/streamy/app/ui/HomeScreen.kt`
- Create: `app/src/main/java/com/streamy/app/ui/BitrateChooser.kt`
- Create: `app/src/main/java/com/streamy/app/ui/ServerProfilesScreen.kt`
- Create: `app/src/main/java/com/streamy/app/ui/EditProfileScreen.kt`
- Create: `app/src/main/java/com/streamy/app/ui/SettingsScreen.kt`
- Create: `app/src/main/java/com/streamy/app/ui/StreamyViewModel.kt`
- Create: `app/src/main/java/com/streamy/app/ui/Theme.kt`
- Modify: `app/src/main/java/com/streamy/app/MainActivity.kt`

**Step 1: ViewModel**

`StreamyViewModel` exposes `StateFlow<StreamState>`, `StateFlow<List<ProfileEntity>>`, the selected profile, and the chosen `AudioConfig`. `start()` checks `RECORD_AUDIO` + `POST_NOTIFICATIONS` (requests via the Activity result API if missing), then calls `ServiceController.start`. `stop()` calls `ServiceController.stop`. Changing codec/bitrate while Live → emit a confirmation dialog → soft stop + restart with new config.

**Step 2: Bitrate chooser component**

`BitrateChooser.kt` — a segmented/picker control bound to `AudioConfigRules.AAC_BITRATES` / `MP3_BITRATES`. Selecting 16/24 forces mono + shows a "HE-AAC, mono" hint for AAC. Sample-rate and mono/stereo pickers live alongside. Always visible while Idle; disabled-with-warning while Live (per Task 9 "soft stop + reconnect" rule).

**Step 3: Screens (Hallmark-styled)**

- `HomeScreen` — Start/Stop, live state pill (`Idle`/`Connecting`/`Live`/`Reconnecting`/`Error`), `BitrateChooser`, selected profile chip + link to profiles, a live audio-level (VU) meter fed from `AudioCapture` RMS (wire a callback through the pipeline), and an optional local-recording toggle (writes the same encoded frames to a file in `getExternalFilesDir`).
- `ServerProfilesScreen` — list from Room, add/edit/delete → `EditProfileScreen`.
- `EditProfileScreen` — host/port/sid/mount/password/name, codec/bitrate/samplerate/mono.
- `SettingsScreen` — about, LGPL/LAME notice, clear-data.

**Step 4: Theme + wire into MainActivity**

`Theme.kt` per Hallmark output. `MainActivity` hosts the nav host with the four screens, binds to `StreamService`, feeds `StreamState` into the ViewModel.

**Step 5: Manual visual QA** — screenshot every screen, confirm Hallmark criteria (distinctive, not generic AI-slop). Commit.

```bash
git add app/src/main/java/com/streamy/app/ui app/src/main/java/com/streamy/app/MainActivity.kt
git commit -m "feat(ui): Home + bitrate chooser + profiles + settings (Hallmark design)"
```

---

## Task 13: Integration test — end-to-end against dev servers

**Files:**
- Create: `docker/captures/integration-tone.sh`
- Test: `app/src/androidTest/java/com/streamy/app/IntegrationTest.kt`

**Step 1: Instrumented test**

Streams ~10s of mic/tone through the full stack to the dev Icecast + DNAS2, polls the server's admin/status endpoint (via OkHttp or `HttpURLConnection`), and asserts the mount is present and byte-count is increasing.

```kotlin
@Test fun end_to_end_icy_to_dev_icecast() {
    ServiceController.start(ctx, profile(host = "10.0.2.2", port = 8000, codec = MP3, bitrate = 128))
    awaitState(Live, timeout = 15_000)
    Thread.sleep(10_000)
    val status = get("http://admin:hackme@10.0.2.2:8000/admin.cgi?mode=viewxml")
    assertThat(status).contains("/stream")
    ServiceController.stop(ctx)
}
```

(`10.0.2.2` is the emulator's host-loopback; use the LAN IP for a real device.)

**Step 2: Repeat for DNAS2 via UVOX2 (Task 8 client)** and for AAC content-type.

**Step 3: Commit**

```bash
git add app/src/androidTest/java/com/streamy/app/IntegrationTest.kt docker/captures/integration-tone.sh
git commit -m "test: end-to-end integration against dev Icecast + DNAS2"
```

---

## Task 14: Polish, release-cleartext removal, about/legal

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (drop `usesCleartextTraffic`, add `networkSecurityConfig`)
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/java/com/streamy/app/ui/SettingsScreen.kt` (LAME/LGPL notice, version)

**Step 1:** Allow cleartext only to `10.0.2.2` / `localhost` for debug builds via `network_security_config.xml`; release builds forbid cleartext.

**Step 2:** Add the LAME LGPL notice + source-offer in Settings/About.

**Step 3: Final full build + all tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest`
Expected: all green.

**Step 4: Commit**

```bash
git add .
git commit -m "chore: network security config, LAME notice, release polish"
```

---

## Appendix A — Manual smoke checklist (real server)

1. Add a real SHOUTcast v2 profile (host/port/sid/password).
2. Pick MP3 128k mono, press Start → status Live within 5s.
3. Open the stream URL in a player (VLC) → hear your voice.
4. Edit now-playing title → metadata updates in player within 2s.
5. Screen off 60s → stream continues, no drop.
6. Switch app to background 60s → continues.
7. Airplane mode → `Reconnecting` → restore network → reconnects Live.
8. Press Stop (notification or app) → clean shutdown, notification dismissed, no leaked wakelock (`adb shell dumpsys power | grep streamy`).

## Appendix B — Deliberate simplifications (ponytail notes)

- **ADTS-strip heuristic** in `AacEncoder` — confirm against real DNAS2 in Task 13; upgrade to `KEY_AAC_ENCODED_TARGET_FORMAT` if server needs ADTS.
- **Deterministic jitter** in `StreamTransport` — swap to real `Random` once backoff is validated.
- **No DI** — fine at this scope; introduce Hilt if the service grows beyond ~1k LOC.
- **Single process** — move `StreamService` to a `:stream` process only if UI jank appears.
- **Audio source fixed to `VOICE_COMMUNICATION`** — expose as a setting if raw `MIC` is requested.
