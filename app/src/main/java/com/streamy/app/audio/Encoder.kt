package com.streamy.app.audio

interface Encoder : AutoCloseable {
    /** Encode one PCM frame; return raw encoded bytes (ADTS already stripped for AAC). */
    fun encode(pcm: ShortArray): List<ByteArray>
    /** Final trailing frames (e.g. LAME flush). */
    fun flush(): List<ByteArray>
    val mime: String           // "audio/aac" or "audio/mpeg"
}
