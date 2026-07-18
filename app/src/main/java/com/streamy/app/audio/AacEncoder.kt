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

    // ponytail: ADTS-strip heuristic is a placeholder. MediaCodec AAC encoders may already emit raw
    // packets (no ADTS) depending on KEY_AAC_ENCODED_TARGET_FORMAT. Task 13 integration confirms what the
    // real DNAS2 expects; if it needs ADTS, set KEY_AAC_ENCODED_TARGET_FORMAT = ADTS and STOP stripping.
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
