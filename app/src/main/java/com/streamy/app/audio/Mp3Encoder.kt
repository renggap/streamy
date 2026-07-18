package com.streamy.app.audio

/**
 * MP3 encoder backed by LAME 3.100 (NDK/JNI). LGPL-2.0+ — see [lame_notice.txt] (res/raw).
 *
 * Channel-aware: mono and interleaved-stereo take distinct JNI paths in lame_jni.cpp to avoid
 * the duplicated-channel bug of feeding the same buffer to lame_encode_buffer for stereo.
 *
 * Not thread-safe; callers confine encode/flush/close to a single thread (AudioPipeline).
 */
class Mp3Encoder(
    sampleRateHz: Int,
    bitrateKbps: Int,
    mono: Boolean,
) : Encoder {
    override val mime = "audio/mpeg"
    private val channels = if (mono) 1 else 2
    private val ptr = nativeInit(sampleRateHz, channels, bitrateKbps)

    init { check(ptr != 0L) { "lame_init_params failed" } }

    private var closed = false
    override fun close() {
        // Idempotent: AutoCloseable contract + guards double lame_close (UB on freed handle).
        if (closed) return
        closed = true
        nativeClose(ptr)
    }

    override fun encode(pcm: ShortArray): List<ByteArray> {
        // LAME worst-case: 1.25x + 7200 bytes per frame (per LAME docs).
        val maxOut = pcm.size * 5 / 4 + 7200
        val out = ByteArray(maxOut)
        val n = nativeEncode(ptr, pcm, pcm.size, channels, out)
        return if (n > 0) listOf(out.copyOf(n)) else emptyList()
    }

    override fun flush(): List<ByteArray> {
        val out = ByteArray(7200)
        val n = nativeFlush(ptr, out)
        return if (n > 0) listOf(out.copyOf(n)) else emptyList()
    }

    private external fun nativeInit(sampleRate: Int, channels: Int, bitrateKbps: Int): Long
    private external fun nativeEncode(ptr: Long, pcm: ShortArray, n: Int, channels: Int, out: ByteArray): Int
    private external fun nativeFlush(ptr: Long, out: ByteArray): Int
    private external fun nativeClose(ptr: Long)

    companion object {
        init { System.loadLibrary("streamy_lame") }
    }
}
