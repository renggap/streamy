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

    /**
     * The sample rate LAME actually emits after `lame_init_params` (read back via
     * `lame_get_out_samplerate`). LAME may internally adjust the output rate — notably halving
     * it at low bitrates — and the app's bitrate math + direct-sink MP3 recording assume the
     * output matches the configured input. Pinned here so a mismatch is a loud failure, not a
     * silently wrong stream.
     */
    val outSampleRateHz: Int = if (ptr != 0L) nativeOutSampleRate(ptr) else 0

    init {
        check(ptr != 0L) { "lame_init_params failed" }
        // The AudioConfigRules ladder clamps MP3 16/24 kbps to 22.05 kHz (MPEG-2), which LAME
        // accepts verbatim. Any other adjustment is unexpected — fail loudly rather than ship a
        // stream whose real bitrate differs from what DNAS2 reports.
        check(outSampleRateHz == sampleRateHz) {
            "LAME output sample rate $outSampleRateHz != configured $sampleRateHz"
        }
    }

    private var closed = false
    override fun close() {
        // Idempotent: AutoCloseable contract + guards double lame_close (UB on freed handle).
        if (closed) return
        closed = true
        nativeClose(ptr)
    }

    override fun encode(pcm: ShortArray): List<ByteArray> {
        // Defense-in-depth: AudioPipeline.stop() joins capture before flushing the encoder, so
        // encode() should never see a closed handle. If a wedged capture thread outlives the
        // join (500ms cap), calling into freed native memory is UB — refuse instead.
        check(!closed) { "encode after close" }
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
    private external fun nativeOutSampleRate(ptr: Long): Int
    private external fun nativeEncode(ptr: Long, pcm: ShortArray, n: Int, channels: Int, out: ByteArray): Int
    private external fun nativeFlush(ptr: Long, out: ByteArray): Int
    private external fun nativeClose(ptr: Long)

    companion object {
        init { System.loadLibrary("streamy_lame") }
    }
}
