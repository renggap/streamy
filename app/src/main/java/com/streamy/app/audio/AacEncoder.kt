package com.streamy.app.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * AAC encoder backed by MediaCodec (HE-AAC v1 across the whole ladder).
 *
 * Backpressure policy (live speech): [encode] waits up to [INPUT_WAIT_MS] for an input buffer
 * instead of dropping the frame on the first `dequeueInputBuffer(0)` miss — a slow device that
 * lags the codec no longer silently drops every frame. If the codec is genuinely wedged, the
 * frame is dropped and counted in [droppedFrames] so the caller can surface it.
 *
 * Not thread-safe; callers confine encode/flush/close to a single thread (AudioPipeline).
 */
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

    // Encoder-side drop counter. Written on the audio thread only (single-writer discipline);
    // exposed for tests + UI surfacing.
    var droppedFrames: Long = 0
        private set

    init { codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start() }

    override fun encode(pcm: ShortArray): List<ByteArray> {
        if (eos) return emptyList()
        val inIdx = codec.dequeueInputBuffer(INPUT_WAIT_US)
        if (inIdx >= 0) {
            val buf = codec.getInputBuffer(inIdx)!!
            buf.clear()
            val bytes = ByteArray(pcm.size * 2)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)
            buf.put(bytes)
            codec.queueInputBuffer(inIdx, 0, bytes.size, 0, 0)
        } else {
            // No input buffer within the bound: drop ONE frame rather than blocking the audio
            // thread indefinitely (live speech). Count it so drops are visible, unlike the old
            // silent drop-everything behavior.
            droppedFrames++
        }
        return drain()
    }

    override fun flush(): List<ByteArray> {
        // Bounded EOS queue: wait up to EOS_QUEUE_WAIT_MS for an input slot so a wedged codec
        // can't hang stop() for seconds. If no slot frees up, skip the EOS frame entirely and
        // drain what's available — the stream just ends without the formal EOS marker.
        val inIdx = codec.dequeueInputBuffer(EOS_QUEUE_WAIT_US)
        if (inIdx >= 0) codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        eos = true
        return drain(awaitEos = true)
    }

    private fun drain(awaitEos: Boolean = false): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val deadlineNs = if (awaitEos) System.nanoTime() + DRAIN_CAP_MS * 1_000_000L else 0L
        while (true) {
            val timeoutUs = if (awaitEos) DRAIN_POLL_US else 0L
            val o = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                o >= 0 -> {
                    val raw = stripAdts(codec.getOutputBuffer(o)!!, info.size)
                    out.add(raw)
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return out
                }
                // Informational one-shot events (emitted right after start): skip and keep
                // draining. Without explicit branches, -2/-3 fall through the `when` and the
                // loop re-polls, which happens to work but is relying on undocumented behavior.
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                    o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* ignore; continue */ }
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!awaitEos) return out
                    // Draining after EOS: keep polling until the EOS buffer arrives or the hard
                    // wall-cap expires. Never block the stop path longer than DRAIN_CAP_MS.
                    if (System.nanoTime() >= deadlineNs) return out
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
        /**
         * How long [encode] waits for a codec input slot before dropping a frame. 10 ms is a
         * generous multiple of a frame-time (~23 ms at 44.1 kHz / 1024 samples); a healthy
         * codec returns immediately. The old `dequeueInputBuffer(0)` dropped on ANY lag.
         */
        const val INPUT_WAIT_US = 10_000L

        /** Bounded wait to queue EOS on [flush]: 50 ms. Prevents a wedged codec hanging stop(). */
        const val EOS_QUEUE_WAIT_US = 50_000L

        /** Hard wall-cap on the post-EOS drain in [drain]. */
        const val DRAIN_CAP_MS = 200L
        const val DRAIN_POLL_US = 10_000L

        /** AAC is HE-AAC v1 (AAC-LC + SBR) across the whole ladder — AAC-LC retired. Pure → unit tested.
         *  ponytail: switch ≤24 kbps mono to HE-AAC v2 (AACObjectHEV2, +Parametric Stereo) for even
         *  better speech quality once verified on-device against DNAS2. */
        fun aacObjectType(bitrateKbps: Int) =
            MediaCodecInfo.CodecProfileLevel.AACObjectHE
    }
}
