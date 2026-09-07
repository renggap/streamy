package com.streamy.app.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Reads 16-bit PCM from the mic in fixed-size frames, off the main thread.
 *
 * Uses [MediaRecorder.AudioSource.VOICE_COMMUNICATION] for echo-cancelled, AGC'd
 * speech. Capture dropouts (e.g. another app grabs the mic) surface as repeated
 * short reads; callers map persistent short/zero reads to a stream error.
 *
 * [gain] is a linear software gain applied to captured PCM before it leaves [start] —
 * the only mic-"volume" knob that works uniformly across Android (hardware gain is not
 * exposed). It feeds both the live encode and the level tap, so the VU meter reflects
 * what the encoder sees. Hard-clips past 0 dBFS (gain > 1.0); callers that want a ceiling
 * should cap the slider.
 *
 * [preferredDevice] routes capture to a user-chosen input (USB / BT / wired). Must be set
 * BEFORE [AudioRecord.startRecording] — post-start re-routing is OEM-flaky. Caveat: the
 * voice-communication preprocessor is bound to the built-in voice path on some OEMs and
 * may ignore [AudioRecord.setPreferredDevice] for non-built-in sources; the return value is
 * checked and a failure is warn-logged.
 */
class AudioCapture(
    private val sampleRateHz: Int,
    private val mono: Boolean,
    private val frameSamples: Int = 1024,   // ~23ms @44.1k
    initialGain: Float = 1.0f,
    private val preferredDevice: AudioDeviceInfo? = null,
) {
    private val channelConfig =
        if (mono) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private var record: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Linear mic gain (1.0 = unity). Written from UI thread via binder, read on streamy-audio;
     *  @Volatile gives the happens-before for a 32-bit float. */
    @Volatile var gain: Float = initialGain

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
        // setPreferredDevice must precede startRecording; post-start routing is OEM-flaky.
        preferredDevice?.let { dev ->
            if (!record!!.setPreferredDevice(dev)) {
                Log.w(TAG, "setPreferredDevice rejected for ${dev.productName}")
            }
        }
        record!!.startRecording()
        running = true
        thread = kotlin.concurrent.thread(name = "streamy-audio", isDaemon = true) {
          val rec = record ?: return@thread
          val buf = ShortArray(frameSamples * (if (mono) 1 else 2))
          var consecutiveErrors = 0
          while (running) {
              val n = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
              if (n > 0) {
                  consecutiveErrors = 0
                  applyGain(buf, n)
                  if (!onFrame(buf.copyOf(n))) running = false
              } else {
                  // Zero/negative = dead mic or transient underrun. Count toward the
                  // pipeline's short-read detector instead of spin-looping.
                  consecutiveErrors++
                  try { Thread.sleep(10) } catch (_: InterruptedException) {}
                  if (consecutiveErrors > 100) {
                      // ~1s of dead reads: surface as a short frame so AudioPipeline
                      // trips onMicBusy via its SHORT_READ_LIMIT path.
                      if (!onFrame(ShortArray(0))) running = false
                      consecutiveErrors = 0
                  }
              }
          }
        }
    }

    /** In-place linear gain. Fast path at unity (no work, MP3/AAC path unchanged). */
    private fun applyGain(buf: ShortArray, n: Int) {
        val g = gain
        if (g == 1.0f) return
        for (i in 0 until n) {
            buf[i] = (buf[i].toFloat() * g).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    fun stop() {
        running = false
        record?.run { try { stop() } finally { release() } }
        record = null
        // Best-effort join so the audio thread can't call encode() after we tear down;
        // bounded by 500ms + try/catch so a stuck thread can't wedge stop(). If the thread is
        // still alive after the join, Mp3Encoder.encode's `check(!closed)` (defense-in-depth)
        // turns any late frame into a loud failure instead of UB on a freed native handle.
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
    }

    companion object {
        private const val TAG = "AudioCapture"
    }
}
