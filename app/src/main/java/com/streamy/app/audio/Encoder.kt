package com.streamy.app.audio

interface Encoder : AutoCloseable {
    /**
     * Encode one PCM frame; return raw encoded bytes (ADTS already stripped for AAC).
     *
     * Implementations accept PCM frames up to 16384 bytes (8192 samples). Callers (AudioPipeline)
     * must keep frames within this bound.
     *
     * Not thread-safe; callers must confine encode/flush/close to a single thread (the audio pipeline thread).
     */
    fun encode(pcm: ShortArray): List<ByteArray>
    /** Final trailing frames (e.g. LAME flush). */
    fun flush(): List<ByteArray>
    val mime: String           // "audio/aac" or "audio/mpeg"
}
