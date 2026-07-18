package com.streamy.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * Reads 16-bit PCM from the mic in fixed-size frames, off the main thread.
 *
 * Uses [MediaRecorder.AudioSource.VOICE_COMMUNICATION] for echo-cancelled, AGC'd
 * speech. Capture dropouts (e.g. another app grabs the mic) surface as repeated
 * short reads; callers map persistent short/zero reads to a stream error.
 */
class AudioCapture(
    private val sampleRateHz: Int,
    private val mono: Boolean,
    private val frameSamples: Int = 1024,   // ~23ms @44.1k
) {
    private val channelConfig =
        if (mono) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private var record: AudioRecord? = null
    private var running = false

    /** Called repeatedly with a freshly-filled buffer; return false to stop. */
    fun start(onFrame: (ShortArray) -> Boolean) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuf > 0) { "min buffer $minBuf (bad params?)" }
        val bufBytes = Math.max(minBuf, frameSamples * 2 * (if (mono) 1 else 2))
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufBytes
        )
        check(record?.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
        record!!.startRecording()
        running = true
        thread(name = "streamy-audio", isDaemon = true) {
          val buf = ShortArray(frameSamples * (if (mono) 1 else 2))
          while (running) {
              val n = record!!.read(buf, 0, buf.size)
              if (n > 0 && !onFrame(buf.copyOf(n))) running = false
          }
        }
    }

    fun stop() {
        running = false
        record?.run { try { stop() } finally { release() } }
        record = null
    }
}
