package com.streamy.app.audio

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.annotation.SuppressLint
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
    @Volatile private var record: AudioRecord? = null
    @Volatile private var running = false
    @Volatile private var thread: Thread? = null

    /** Linear mic gain (1.0 = unity). Written from UI thread via binder, read on streamy-audio;
     *  @Volatile gives the happens-before for a 32-bit float. */
    @Volatile var gain: Float = initialGain

    /**
     * Called repeatedly with a freshly-filled buffer; return false to stop. A zero-length frame
     * is the dead-mic signal: ~[DEAD_READ_LIMIT] x 10 ms (~1 s) of consecutive failed reads
     * (ERROR_DEAD_OBJECT after a USB unplug / audioserver restart, or another app holding the
     * mic). Consumers must not treat it as audio.
     *
     * An exception thrown by [onFrame] ends capture (logged) instead of escaping the daemon
     * thread — an uncaught throw on `streamy-audio` would kill the whole process mid-broadcast.
     */
    /** RECORD_AUDIO is checked by the callers' permission flow before the service starts. */
    @SuppressLint("MissingPermission")
    fun start(onFrame: (ShortArray) -> Boolean) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuf > 0) { "min buffer $minBuf (bad params?)" }
        val frameBytes = frameSamples * 2 * (if (mono) 1 else 2)
        // Several frames of headroom: a single-frame record buffer overruns whenever the audio
        // thread is descheduled for one frame-time (~23 ms).
        val bufBytes = Math.max(minBuf * 2, frameBytes * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT, bufBytes
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            // Release now: stop() never sees an uninitialised record (its stop() would throw).
            rec.release()
            throw IllegalStateException("AudioRecord not initialized")
        }
        // setPreferredDevice must precede startRecording; post-start routing is OEM-flaky.
        preferredDevice?.let { dev ->
            if (!rec.setPreferredDevice(dev)) {
                Log.w(TAG, "setPreferredDevice rejected for ${dev.productName}")
            }
        }
        record = rec
        rec.startRecording()
        running = true
        thread = kotlin.concurrent.thread(name = "streamy-audio", isDaemon = true) {
          val buf = ShortArray(frameSamples * (if (mono) 1 else 2))
          var consecutiveErrors = 0
          try {
              while (running) {
                  val n = try { rec.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                  if (n > 0) {
                      consecutiveErrors = 0
                      applyGain(buf, n)
                      if (!onFrame(buf.copyOf(n))) running = false
                  } else {
                      // Zero/negative = dead mic or transient underrun. Back off instead of
                      // spin-looping; only a sustained run is reported (as an empty frame).
                      consecutiveErrors++
                      try { Thread.sleep(10) } catch (_: InterruptedException) {}
                      if (consecutiveErrors > DEAD_READ_LIMIT) {
                          if (!onFrame(DEAD_FRAME)) running = false
                          consecutiveErrors = 0
                      }
                  }
              }
          } catch (t: Throwable) {
              Log.e(TAG, "capture loop aborted", t)
              running = false
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

    /**
     * Stops capture and joins the audio thread (bounded). Returns true if the thread has exited,
     * so callers know whether the encoder is safe to flush. Safe from any thread — including the
     * audio thread itself (mic-busy teardown runs inside onFrame), where joining would just burn
     * the full timeout waiting on ourselves.
     */
    fun stop(): Boolean {
        running = false
        record?.run {
            // stop() throws IllegalStateException if recording never started; release regardless.
            try { stop() } catch (_: IllegalStateException) {} finally { release() }
        }
        record = null
        val t = thread ?: return true
        if (t === Thread.currentThread()) {
            thread = null
            return true   // we ARE the audio thread: no further onFrame after this returns false
        }
        // Bounded so a stuck thread can't wedge stop(). If it outlives the join, the encoders'
        // synchronized + closed-check turn a late frame into a caught failure, not UB.
        try { t.join(JOIN_MS) } catch (_: InterruptedException) {}
        thread = null
        return !t.isAlive
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val JOIN_MS = 500L
        /** Consecutive failed reads (10 ms apart) before reporting a dead mic: ~1 s. */
        private const val DEAD_READ_LIMIT = 100
        /** The dead-mic signal handed to onFrame. Shared, never written. */
        private val DEAD_FRAME = ShortArray(0)
    }
}
