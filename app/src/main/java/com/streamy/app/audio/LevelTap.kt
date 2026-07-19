package com.streamy.app.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Pure DSP helpers for the VU meter: pre-encoder level tap. Codec-agnostic — operates on
 * the raw PCM ShortArray before it reaches [Encoder]; does not touch the MP3/AAC path.
 *
 *  - [rmsDb]: RMS level in dBFS, floored at -90 (silence).
 *  - [decimate]: point-count waveform decimation to [-1,1] floats for the scope view.
 */
object LevelTap {
    private const val FLOOR_DB = -90.0f

    fun rmsDb(pcm: ShortArray): Float {
        var sum = 0.0
        for (s in pcm) { val n = s.toDouble() / 32768.0; sum += n * n }
        val rms = sqrt(sum / pcm.size)
        val db = (20.0 * log10(max(rms, 1e-7))).toFloat()
        return if (db < FLOOR_DB) FLOOR_DB else db
    }

    fun decimate(pcm: ShortArray, points: Int): FloatArray {
        val out = FloatArray(points)
        if (pcm.isEmpty()) return out
        for (i in 0 until points) {
            val src = (i * (pcm.size - 1)) / (points - 1).coerceAtLeast(1)
            out[i] = pcm[src].toFloat() / 32768.0f
        }
        return out
    }
}
