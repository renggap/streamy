package com.streamy.app.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A VU-meter snapshot: [samples] is the decimated waveform ([-1,1]), [rmsDb] is the
 * RMS level in dBFS (floored at -90).
 */
class LevelSnapshot(val samples: FloatArray, val rmsDb: Float)

/**
 * Throttled sink for [LevelSnapshot]s tapped from [AudioPipeline]. Pushed from the audio
 * capture thread; [flow] is observed from the UI. Throttles to ~30 fps (33 ms) so the
 * StateFlow doesn't emit faster than a meter can render. [reset] clears on stop.
 *
 * Thread-safe: [push] is called from the audio thread, [flow] collected from main.
 */
class LevelFeed {
    private val _flow = MutableStateFlow<LevelSnapshot?>(null)
    val flow: StateFlow<LevelSnapshot?> = _flow.asStateFlow()

    @Volatile private var lastPushNs = 0L

    fun push(snapshot: LevelSnapshot, nowNs: Long) {
        if (nowNs - lastPushNs < THROTTLE_NS) return
        lastPushNs = nowNs
        _flow.value = snapshot
    }

    fun reset() { _flow.value = null }

    companion object { private const val THROTTLE_NS = 33_000_000L }
}
