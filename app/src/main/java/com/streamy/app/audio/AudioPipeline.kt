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

    fun start() {
        capture.start { pcm ->
            if (pcm.size < MIN_USEFUL_SAMPLES) {
                if (++consecutiveShortReads > SHORT_READ_LIMIT) {
                    onMicBusy()
                    return@start false   // stop capture
                }
            } else {
                consecutiveShortReads = 0
            }
            for (frame in encoder.encode(pcm)) transport.enqueue(frame)
            // Pre-encoder level tap. Additive only — null feed (default) means zero work,
            // so the MP3/AAC streaming path is unchanged when no meter is attached.
            levelFeed?.let { feed ->
                feed.push(LevelSnapshot(LevelTap.decimate(pcm, 96), LevelTap.rmsDb(pcm)), System.nanoTime())
            }
            true
        }
    }

    /** Stop+join capture FIRST so the audio thread can't call encode() while we flush, then
     *  drain the encoder's trailing frames into the transport. (encoder.flush happens-after
     *  the capture thread has exited.) */
    fun stop() {
        capture.stop()
        for (frame in encoder.flush()) transport.enqueue(frame)
    }

    companion object {
        private const val MIN_USEFUL_SAMPLES = 160
        private const val SHORT_READ_LIMIT = 50   // ~1s of persistent short reads
    }
}
