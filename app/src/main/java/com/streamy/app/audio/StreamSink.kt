package com.streamy.app.audio

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
 * [Mp3TranscodeSink] runs its LAME encode on a **dedicated thread** with a bounded queue: the
 * audio thread only enqueues a PCM frame (O(1) handoff) and returns, so a slow MP3 encode can
 * never stall the live AAC stream or trip the mic-busy short-read detector. If the transcode
 * thread falls behind (queue full), the newest PCM frame is dropped and counted — recording a
 * gap beats corrupting the live stream.
 *
 * Output is wrapped in a [BufferedOutputStream] so per-frame writes don't hit the kernel on
 * the transcode thread.
 *
 * Lifecycle is owned by [AudioPipeline]: once a sink is detached from the audio thread it is
 * flushed + closed exactly once, on a background "retire" thread or after the audio thread exited.
 *
 * [failed] latches on the first write/encode failure (disk full, revoked storage, LAME error) so
 * the pipeline can drop the sink and tell the user instead of silently truncating the recording.
 */
interface StreamSink : AutoCloseable {
    /** PCM pre-encode (transcode path). Default no-op. */
    fun onPcm(pcm: ShortArray) {}
    /** Already-encoded frames (pass-through path). Default no-op. */
    fun onEncoded(frames: List<ByteArray>) {}
    /** True once a write/encode failed; the recording is broken from that point on. */
    val failed: Boolean get() = false
    fun flush()
    override fun close()
}

/** Pass-through: the live stream is MP3, so write encoded MP3 frames as-is. */
class Mp3DirectSink(out: OutputStream) : StreamSink {
    private val out = BufferedOutputStream(out, BUF)
    @Volatile private var broken = false
    override val failed: Boolean get() = broken
    override fun onEncoded(frames: List<ByteArray>) {
        if (broken) return
        try {
            for (f in frames) out.write(f)
        } catch (e: java.io.IOException) {
            broken = true
        }
    }
    override fun flush() { runCatching { out.flush() } }
    override fun close() {
        runCatching { out.flush() }
        runCatching { out.close() }
    }
}

/**
 * Transcode: the live stream is AAC, so re-encode PCM → MP3 for a guaranteed-MP3 file.
 *
 * Off-thread design: [onPcm] (audio thread) hands each frame to a bounded [ArrayBlockingQueue];
 * a dedicated transcode thread pops frames, LAME-encodes, and writes to disk. [flush] sends an
 * end marker and joins the thread, then drains the final LAME frames.
 */
class Mp3TranscodeSink(
    out: OutputStream,
    sampleRateHz: Int,
    mono: Boolean,
    /** Encoder factory: production passes LAME; tests inject a fake (no JNI load). */
    private val encoderFactory: () -> Encoder = {
        Mp3Encoder(sampleRateHz, FILE_BITRATE_KBPS, mono)
    },
) : StreamSink {
    private val out = BufferedOutputStream(out, BUF)

    // Bounded pipe so a burst of PCM can't balloon memory; drop-newest on overflow (recording
    // a gap is acceptable — the live stream is the product). The end marker is matched by
    // IDENTITY: an empty PCM frame from elsewhere must never be mistaken for end-of-stream.
    private val queue = ArrayBlockingQueue<ShortArray>(QUEUE_CAPACITY)

    // Written on the audio thread (enqueue path), read by the transcode thread.
    private val droppedFrames = java.util.concurrent.atomic.AtomicLong(0)
    private val closed = AtomicBoolean(false)
    @Volatile private var broken = false
    override val failed: Boolean get() = broken

    private val encoder = encoderFactory()
    private val transcodeThread = Thread(::runLoop, "streamy-transcode").apply { isDaemon = true; start() }

    /** PCM frames dropped because the transcode thread fell behind (drop-newest policy). */
    val droppedFrameCount: Long get() = droppedFrames.get()

    override fun onPcm(pcm: ShortArray) {
        if (closed.get()) return
        if (!queue.offer(pcm)) droppedFrames.incrementAndGet()   // drop-newest (bounded latency)
    }

    /**
     * Hands the transcode thread the end marker and joins it, so encoder.flush()'s tail hits disk.
     * Called off the audio thread (retire / post-capture stop), so it may block briefly.
     */
    override fun flush() {
        if (!closed.compareAndSet(false, true)) return
        // The queue is full exactly when the transcode thread is behind; a plain offer() would
        // then lose the marker and leave the thread parked forever. Wait for a slot, and only if
        // the thread is wedged give up the backlog so the marker still gets through.
        if (transcodeThread.isAlive) {
            val queued = runCatching { queue.offer(END_MARKER, FLUSH_JOIN_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (!queued) {
                queue.clear()
                queue.offer(END_MARKER)
            }
            runCatching { transcodeThread.join(FLUSH_JOIN_MS) }
        }
        runCatching { out.flush() }
    }

    override fun close() {
        runCatching { flush() }
        // If the thread is still alive it is wedged inside encode(); Mp3Encoder's methods are
        // synchronized and close-checked, so this close waits for that encode to return and the
        // thread's next call fails cleanly (caught in runLoop) instead of touching a freed handle.
        runCatching { encoder.close() }
        runCatching { out.close() }
    }

    private fun runLoop() {
        try {
            while (true) {
                val frame = queue.take()
                if (frame === END_MARKER) break          // flush end-marker (identity, not size)
                for (f in encoder.encode(frame)) out.write(f)
            }
            for (f in encoder.flush()) out.write(f)
        } catch (e: Throwable) {
            // I/O error or encoder death: stop writing, drain the queue so flush()'s join
            // terminates promptly. The recording is already broken; don't wedge the audio path.
            broken = true
            queue.clear()
        }
    }

    companion object {
        // ~1 s of 44.1 kHz mono frames (1024 samples each). The live stream is the priority;
        // if the transcode thread can't keep up, recording drops frames rather than stalling.
        private const val QUEUE_CAPACITY = 32
        private const val FLUSH_JOIN_MS = 1_000L
        // File is always a clean 128 kbps MP3 — independent of the (possibly low) live bitrate.
        private const val FILE_BITRATE_KBPS = 128
        /** End-of-stream sentinel (ArrayBlockingQueue forbids null); compared by identity. */
        private val END_MARKER = ShortArray(0)
    }
}

private const val BUF = 128 * 1024
