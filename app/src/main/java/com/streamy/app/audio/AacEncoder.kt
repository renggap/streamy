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
        // AAC is HE-AAC v1 (AAC-LC + SBR) across the whole bitrate ladder — better quality per
        // bit than AAC-LC, which is what speech-grade low-bitrate broadcast needs.
        setInteger(MediaFormat.KEY_AAC_PROFILE, aacObjectType(bitrateBps / 1000))
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
    }
    private val info = MediaCodec.BufferInfo()
    @Volatile private var eos = false
    @Volatile private var closed = false

    init { codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start() }

    override fun encode(pcm: ShortArray): List<ByteArray> {
        if (eos) return emptyList()
        val inIdx = codec.dequeueInputBuffer(0)
        if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)!!
            buf.clear()
            val bytes = ByteArray(pcm.size * 2)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
            buf.put(bytes)
            codec.queueInputBuffer(inIdx, 0, bytes.size, 0, 0)
        }
        // inIdx < 0: no input buffer available now — drop this PCM frame under backpressure (live speech; see Task 9).
        return drain()
    }

    override fun flush(): List<ByteArray> {
        val inIdx = codec.dequeueInputBuffer(10_000)
        if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        eos = true
        return drain(awaitEos = true)
    }

    private fun drain(awaitEos: Boolean = false): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val deadlineMs = if (awaitEos) 200 else 0
        val start = if (awaitEos) System.nanoTime() else 0L
        while (true) {
            val timeoutUs = if (awaitEos) 10_000L else 0L
            val o = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                o >= 0 -> {
                    val raw = stripAdts(codec.getOutputBuffer(o)!!, info.size)
                    out.add(raw)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return out
                }
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!awaitEos) return out
                    // draining after EOS: keep waiting until the EOS buffer arrives or hard cap
                    if ((System.nanoTime() - start) / 1_000_000 >= deadlineMs) return out
                }
            }
        }
    }

    // MediaCodec's AAC encoder emits RAW access units (AOSP AACEncoder hardcodes adtsUsed=0; the
    // ADTS header is added by the file/sink writer). KEY_IS_ADTS is decoder-only — there is no
    // encoder key to emit ADTS directly. So there is nothing to strip here; [UvoxClient.send]
    // re-wraps each AU in a 7-byte ADTS header for the 0x8003 data message.
    private fun stripAdts(buf: java.nio.ByteBuffer, len: Int): ByteArray {
        val raw = ByteArray(len)
        buf.get(raw)
        return raw
    }

    override fun close() {
        if (closed) return
        closed = true
        try { codec.stop() } catch (_: IllegalStateException) {} finally { codec.release() }
    }

    companion object {
        /** AAC is HE-AAC v1 (AAC-LC + SBR) across the whole ladder — AAC-LC retired. Pure → unit tested.
         *  ponytail: switch ≤24 kbps mono to HE-AAC v2 (AACObjectHEV2, +Parametric Stereo) for even
         *  better speech quality once verified on-device against DNAS2. */
        fun aacObjectType(bitrateKbps: Int) =
            MediaCodecInfo.CodecProfileLevel.AACObjectHE
    }
}
