package com.streamy.app.audio

import com.streamy.app.net.StreamTransport

/**
 * Glues [AudioCapture] -> [Encoder] -> [StreamTransport]. Runs entirely on the audio
 * capture thread (the `streamy-audio` HandlerThread inside AudioCapture); no offloading.
 *
 * Mic-busy detection: [AudioCapture] surfaces mic contention only as persistent short/zero
 * reads (another app grabbed the mic). We count consecutive short reads and fire [onMicBusy]
 * once the run exceeds [SHORT_READ_LIMIT], then stop capture.
 *
 * Optional [StreamSink] (local MP3 recording): [armRecord] / [disarmRecord] only flip volatiles
 * here; the audio thread swaps the sink in and flushes+closes it at frame boundaries, because the
 * transcode sink owns a LAME [Mp3Encoder] that is not thread-safe and may only be touched from the
 * audio thread. The sink itself is built off-thread by the caller (file + encoder init = slow).
 *
 * Not thread-safe beyond the single audio thread. The caller (StreamService) owns lifecycle
 * ordering: start pipeline, then start transport drain; on stop, flush pipeline (enqueues
 * trailing frames) BEFORE stopping the transport so the drain loop emits them.
 */
class AudioPipeline(
    private val capture: AudioCapture,
    private val encoder: Encoder,
    private val transport: StreamTransport,
    private val onMicBusy: () -> Unit,
    private val levelFeed: LevelFeed? = null,
) {
    private var consecutiveShortReads = 0

    // Record sink state. Read on the audio thread; written (under lock) from the binder thread.
    @Volatile private var activeSink: StreamSink? = null
    @Volatile private var pendingSink: StreamSink? = null
    @Volatile private var stopRequested = false

    fun start() {
        capture.start { pcm ->
            // Swap-in / flush+close the record sink at a frame boundary (audio thread only).
            pendingSink?.let { activeSink = it; pendingSink = null }
            if (stopRequested) {
                stopRequested = false
                activeSink?.let { runCatching { it.flush(); it.close() } }
                activeSink = null
            }

            if (pcm.size < MIN_USEFUL_SAMPLES) {
                if (++consecutiveShortReads > SHORT_READ_LIMIT) {
                    onMicBusy()
                    return@start false   // stop capture
                }
            } else {
                consecutiveShortReads = 0
            }
            val frames = encoder.encode(pcm)
            for (frame in frames) transport.enqueue(frame)
            // Local recording: offer BOTH sides; each sink consumes what it needs (no-op the rest).
            activeSink?.let { sink ->
                sink.onPcm(pcm)
                sink.onEncoded(frames)
            }
            // Pre-encoder level tap. Additive only — null feed (default) means zero work,
            // so the MP3/AAC streaming path is unchanged when no meter is attached.
            levelFeed?.let { feed ->
                feed.push(LevelSnapshot(LevelTap.decimate(pcm, 96), LevelTap.rmsDb(pcm)), System.nanoTime())
            }
            true
        }
    }

    /** Arm local recording. [sink] must already be built (encoder init done off the audio thread). */
    @Synchronized fun armRecord(sink: StreamSink) {
        // If a previous arm is still pending (audio thread hasn't swapped it in), close it so a
        // rapid double-arm can't leak a FileOutputStream + native LAME handle.
        pendingSink?.let { runCatching { it.flush(); it.close() } }
        pendingSink = sink
        stopRequested = false
    }

    /** Stop local recording while continuing to stream. Flush + close run on the audio thread. */
    @Synchronized fun disarmRecord() {
        stopRequested = true
    }

    /** Mic gain passthrough (software gain lives on [AudioCapture]). */
    fun setGain(g: Float) { capture.gain = g }

    /** Stop+join capture FIRST so the audio thread can't call encode() while we flush, then
     *  drain the encoder's trailing frames into the transport. (encoder.flush happens-after
     *  the capture thread has exited.) */
    fun stop() {
        capture.stop()
        for (frame in encoder.flush()) transport.enqueue(frame)
        // Close BOTH sinks — the audio thread has exited (capture.stop joined it), so this is the
        // sole accessor. pendingSink may hold an armed-but-not-yet-promoted sink if stop arrived
        // within one audio frame of arm; dropping it without closing would leak the fd + LAME.
        val toClose = listOfNotNull(activeSink, pendingSink)
        activeSink = null
        pendingSink = null
        stopRequested = false
        toClose.forEach { runCatching { it.flush(); it.close() } }
    }

    companion object {
        private const val MIN_USEFUL_SAMPLES = 160
        private const val SHORT_READ_LIMIT = 50   // ~1s of persistent short reads
    }
}
