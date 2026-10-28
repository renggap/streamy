package com.streamy.app.audio

import com.streamy.app.net.StreamTransport

/**
 * Glues [AudioCapture] -> [Encoder] -> [StreamTransport] and/or a [StreamSink].
 * Runs entirely on the audio capture thread (`streamy-audio` inside AudioCapture); no offloading
 * of the encode path.
 *
 * Two modes:
 *  - Broadcast ([transport] non-null): encoded frames go to the transport; an optional
 *    sink taps the same audio for a local recording (armed later via [armRecord]).
 *  - Record-only ([transport] null, [sink] pre-armed): standalone recorder — mic → encode
 *    → file, no network. Frames still flow through the encoder so the file is a real
 *    MP3 (transcode) or the encoded stream itself (direct).
 *
 * Mic-busy detection: [AudioCapture] reports ~1 s of dead reads as one empty frame, which trips
 * [onMicBusy] immediately. Persistent short (non-empty) reads are counted separately and trip it
 * past [SHORT_READ_LIMIT]. Empty frames never reach the encoder, sinks or level tap.
 *
 * Record sink swaps happen at frame boundaries on the audio thread, under the pipeline lock so a
 * fast disarm→arm can't lose track of the outgoing sink. Outgoing sinks are *retired* (flushed +
 * closed) on a short-lived background thread: a transcode sink's flush joins its LAME thread for
 * up to a second, which must never stall live capture or the main thread.
 *
 * A throw from the encoder (or any per-frame work) is caught and reported once via [onFatal];
 * capture then stops. Caller (StreamService / RecordService) owns lifecycle ordering.
 */
class AudioPipeline(
    private val capture: AudioCapture,
    private val encoder: Encoder,
    /** Null = record-only mode (standalone recorder). */
    private val transport: StreamTransport? = null,
    /** Pre-armed sink for record-only mode; null in broadcast mode (use [armRecord]). */
    private val sink: StreamSink? = null,
    private val onMicBusy: () -> Unit,
    private val levelFeed: LevelFeed? = null,
    /** Unexpected per-frame failure (encoder died, etc.). Capture has stopped when this fires. */
    private val onFatal: (Throwable) -> Unit = {},
    /** The active record sink reported a write/encode failure (disk full, LAME error). Fired once
     *  per sink; the sink is retired, streaming continues. */
    private val onSinkError: () -> Unit = {},
) {
    private var consecutiveShortReads = 0

    // Record sink state. activeSink is only touched by the audio thread (and by stop() after the
    // audio thread exited); pending/stop requests are written under the lock from binder threads
    // and consumed under the same lock at a frame boundary.
    @Volatile private var activeSink: StreamSink? = sink
    @Volatile private var pendingSink: StreamSink? = null
    @Volatile private var stopRequested = false

    fun start() {
        capture.start { pcm ->
            try {
                onFrame(pcm)
            } catch (t: Throwable) {
                onFatal(t)
                false
            }
        }
    }

    private fun onFrame(pcm: ShortArray): Boolean {
        // Cheap volatile check first; only take the lock when a swap is actually requested.
        if (pendingSink != null || stopRequested) applySinkRequests()

        if (pcm.isEmpty()) {
            // AudioCapture already waited ~1 s of dead reads before sending this.
            onMicBusy()
            return false
        }
        if (pcm.size < MIN_USEFUL_SAMPLES) {
            if (++consecutiveShortReads > SHORT_READ_LIMIT) {
                onMicBusy()
                return false   // stop capture
            }
        } else {
            consecutiveShortReads = 0
        }
        val frames = encoder.encode(pcm)
        transport?.enqueueAll(frames)
        // Local recording: offer BOTH sides; each sink consumes what it needs (no-op the rest).
        activeSink?.let { sink ->
            sink.onPcm(pcm)
            sink.onEncoded(frames)
            if (sink.failed) {
                activeSink = null
                retire(sink)
                onSinkError()
            }
        }
        // Pre-encoder level tap. Additive only — null feed (default) means zero work,
        // so the MP3/AAC streaming path is unchanged when no meter is attached.
        levelFeed?.let { feed ->
            feed.push(LevelSnapshot(LevelTap.decimate(pcm, 96), LevelTap.rmsDb(pcm)), System.nanoTime())
        }
        return true
    }

    /** Audio thread, frame boundary: promote a pending sink and/or retire the active one. */
    @Synchronized private fun applySinkRequests() {
        val next = pendingSink
        val current = activeSink
        if (next != null) {
            if (current != null && current !== next) retire(current)
            activeSink = next
        } else if (stopRequested) {
            if (current != null) retire(current)
            activeSink = null
        }
        pendingSink = null
        stopRequested = false
    }

    /** Arm local recording. [newSink] must already be built (encoder init done off the audio thread). */
    @Synchronized fun armRecord(newSink: StreamSink) {
        // A previous arm the audio thread hasn't promoted yet would otherwise leak its fd + LAME.
        pendingSink?.let { if (it !== newSink) retire(it) }
        pendingSink = newSink
        // Any queued disarm is superseded: the swap retires the current active sink anyway.
        stopRequested = false
    }

    /** Stop local recording while continuing to stream. Flush + close happen off the audio thread. */
    @Synchronized fun disarmRecord() {
        pendingSink?.let { retire(it) }
        pendingSink = null
        stopRequested = true
    }

    /** Mic gain passthrough (software gain lives on [AudioCapture]). */
    fun setGain(g: Float) { capture.gain = g }

    /**
     * Stop+join capture FIRST so the audio thread can't call encode() while we flush. The
     * encoder's trailing frames go to the record sink (a direct-MP3 file needs them); they are
     * NOT sent to the transport — it is being stopped and would discard them anyway.
     */
    fun stop() {
        val joined = capture.stop()
        val tail = if (joined) runCatching { encoder.flush() }.getOrDefault(emptyList()) else emptyList()
        // The audio thread has exited (or was abandoned after the join cap), so this is now the
        // sole accessor of the sink slots. pendingSink may hold an armed-but-not-yet-promoted sink
        // if stop arrived within one frame of arm; dropping it without closing would leak.
        val active: StreamSink?
        val toClose = synchronized(this) {
            active = activeSink
            val list = listOfNotNull(activeSink, pendingSink)
            activeSink = null
            pendingSink = null
            stopRequested = false
            list
        }
        active?.let { s -> runCatching { s.onEncoded(tail) } }
        // Record-only mode: the file IS the product and the service is about to stop, so close
        // synchronously (publishing the MediaStore entry before stopSelf). Broadcast mode retires
        // in the background to keep the main thread clear of LAME/disk work.
        if (transport == null) {
            toClose.forEach { runCatching { it.flush(); it.close() } }
        } else {
            toClose.forEach { retire(it) }
        }
    }

    private fun StreamTransport.enqueueAll(frames: List<ByteArray>) {
        for (f in frames) enqueue(f)
    }

    companion object {
        private const val MIN_USEFUL_SAMPLES = 160
        private const val SHORT_READ_LIMIT = 50   // persistent short (non-empty) reads

        /** Flush + close a sink off the calling thread (see class doc). */
        private fun retire(s: StreamSink) {
            kotlin.concurrent.thread(name = "streamy-sink-close", isDaemon = true) {
                runCatching { s.flush() }
                runCatching { s.close() }
            }
        }
    }
}
