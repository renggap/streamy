package com.streamy.app.audio

import com.streamy.app.net.StreamTransport

/**
 * Glues [AudioCapture] -> [Encoder] -> [StreamTransport] and/or a [StreamSink].
 * Runs entirely on the audio capture thread (the `streamy-audio` HandlerThread inside
 * AudioCapture); no offloading.
 *
 * Two modes:
 *  - Broadcast ([transport] non-null): encoded frames go to the transport; an optional
 *    sink taps the same audio for a local recording (armed later via [armRecord]).
 *  - Record-only ([transport] null, [sink] pre-armed): standalone recorder — mic → encode
 *    → file, no network. Frames still flow through the encoder so the file is a real
 *    MP3 (transcode) or the encoded stream itself (direct).
 *
 * Mic-busy detection: [AudioCapture] surfaces mic contention only as persistent short/zero
 * reads (another app grabbed the mic). We count consecutive short reads and fire [onMicBusy]
 * once the run exceeds [SHORT_READ_LIMIT], then stop capture.
 *
 * The record sink is swapped in and flushed+closed at frame boundaries on the audio thread,
 * because the transcode sink owns a LAME [Mp3Encoder] that is not thread-safe and may only
 * be touched from the audio thread. A sink passed via [sink] is armed from the first frame.
 *
 * Not thread-safe beyond the single audio thread. The caller (StreamService / RecordService)
 * owns lifecycle ordering: on stop, flush pipeline (draining trailing frames) BEFORE
 * stopping any transport so the drain loop emits them.
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
) {
    private var consecutiveShortReads = 0

    // Record sink state. Read on the audio thread; written (under lock) from the binder thread.
    // In record-only mode activeSink is the constructor [sink] (immutable; no swap path used).
    @Volatile private var activeSink: StreamSink? = sink
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
            transport?.enqueueAll(frames)
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
    @Synchronized fun armRecord(newSink: StreamSink) {
        // If a previous arm is still pending (audio thread hasn't swapped it in), close it so a
        // rapid double-arm can't leak a FileOutputStream + native LAME handle.
        pendingSink?.let { runCatching { it.flush(); it.close() } }
        pendingSink = newSink
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
        transport?.let { t -> for (frame in encoder.flush()) t.enqueue(frame) }
        // In record-only mode the trailing encoder output belongs to the FILE: flush into the
        // sink's onEncoded before closing, or the last ~1s of audio is lost. Broadcast mode's
        // trailing frames go to the transport instead (above).
        if (transport == null) {
            activeSink?.let { s -> runCatching { s.onEncoded(encoder.flush()) } }
        }
        // Close BOTH sinks — the audio thread has exited (capture.stop joined it), so this is the
        // sole accessor. pendingSink may hold an armed-but-not-yet-promoted sink if stop arrived
        // within one audio frame of arm; dropping it without closing would leak the fd + LAME.
        val toClose = listOfNotNull(activeSink, pendingSink)
        activeSink = null
        pendingSink = null
        stopRequested = false
        toClose.forEach { runCatching { it.flush(); it.close() } }
    }

    private fun StreamTransport.enqueueAll(frames: List<ByteArray>) {
        for (f in frames) enqueue(f)
    }

    companion object {
        private const val MIN_USEFUL_SAMPLES = 160
        private const val SHORT_READ_LIMIT = 50   // ~1s of persistent short reads
    }
}
