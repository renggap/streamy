package com.streamy.app.audio

import java.io.BufferedOutputStream
import java.io.OutputStream

/**
 * Local MP3 recording sink, fed from [AudioPipeline] on the audio thread. Each captured PCM
 * frame AND its already-encoded form are offered; a sink consumes whichever side it needs and
 * no-ops the other, so the pipeline doesn't care which codec is live:
 *
 *  - [Mp3DirectSink]   — stream codec is MP3: writes the encoded frames straight to disk (zero
 *    re-encode; the cheapest path).
 *  - [Mp3TranscodeSink] — stream codec is AAC: re-encodes PCM → MP3 via a LAME [Mp3Encoder]
 *    so the file is always MP3 regardless of the live codec.
 *
 * Output is wrapped in a [BufferedOutputStream] so per-frame writes don't hit the kernel on the
 * audio thread (a raw FileOutputStream.write per frame can stall and trip mic_busy).
 *
 * Lifecycle is owned by [AudioPipeline]: flush()/close() are called either on the audio thread
 * (disarmRecord path) or after the audio thread has exited (stop path). Either way there is a
 * single accessor — never concurrent.
 */
interface StreamSink : AutoCloseable {
    /** PCM pre-encode (transcode path). Default no-op. */
    fun onPcm(pcm: ShortArray) {}
    /** Already-encoded frames (pass-through path). Default no-op. */
    fun onEncoded(frames: List<ByteArray>) {}
    fun flush()
    override fun close()
}

/** Pass-through: the live stream is MP3, so write encoded MP3 frames as-is. */
class Mp3DirectSink(out: OutputStream) : StreamSink {
    private val out = BufferedOutputStream(out, BUF)
    override fun onEncoded(frames: List<ByteArray>) {
        for (f in frames) runCatching { out.write(f) }
    }
    override fun flush() { runCatching { out.flush() } }
    override fun close() {
        runCatching { out.flush() }
        runCatching { out.close() }
    }
}

/** Transcode: the live stream is AAC, so re-encode PCM → MP3 for a guaranteed-MP3 file. */
class Mp3TranscodeSink(
    out: OutputStream,
    sampleRateHz: Int,
    mono: Boolean,
) : StreamSink {
    private val out = BufferedOutputStream(out, BUF)
    private val encoder = Mp3Encoder(sampleRateHz, FILE_BITRATE_KBPS, mono)
    override fun onPcm(pcm: ShortArray) {
        for (f in encoder.encode(pcm)) runCatching { out.write(f) }
    }
    override fun flush() {
        for (f in encoder.flush()) runCatching { out.write(f) }
        runCatching { out.flush() }
    }
    override fun close() {
        runCatching { flush() }
        runCatching { encoder.close() }
        runCatching { out.close() }
    }
    companion object {
        // File is always a clean 128 kbps MP3 — independent of the (possibly low) live bitrate.
        private const val FILE_BITRATE_KBPS = 128
    }
}

private const val BUF = 128 * 1024
