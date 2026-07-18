package com.streamy.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class StreamTransport(
    private val scope: CoroutineScope,
    private val connect: suspend () -> Unit,        // runs full handshake
    private val send: (ByteArray) -> Unit,
    private val onState: (TransportState) -> Unit,
) {
    private val queue = Channel<ByteArray>(CAPACITY)
    private var drops = 0L
    @Volatile private var stopped = false

    /** Non-blocking enqueue. If the queue is full, DROP THE NEW frame (drop-newest) — keeps
     *  broadcast latency low under downstream backpressure. Counts the drop for metrics. */
    fun enqueue(frame: ByteArray) {
        val result = queue.trySend(frame)
        if (result.isFailure) { drops++; onState(TransportState.Backpressure(drops)) }
    }

    fun start() {
        scope.launch {
            var attempt = 0
            while (!stopped) {
                try {
                    onState(if (attempt == 0) TransportState.Connecting else TransportState.Reconnecting(attempt))
                    connect()
                    onState(TransportState.Live)
                    attempt = 0
                    for (f in queue) {
                        if (stopped) break
                        send(f)
                    }
                    // for-loop ended: either stop() closed the channel, or producer side closed it.
                    if (stopped) break
                } catch (e: Throwable) {
                    if (stopped) break
                    attempt++
                    onState(TransportState.Backoff(attempt, nextBackoffMs(attempt)))
                    delay(nextBackoffMs(attempt))
                }
            }
            onState(TransportState.Failed("stopped")) // only reached when stopped==true
        }
    }

    fun stop() { stopped = true; queue.close() }

    companion object {
        const val CAPACITY = 64
        fun shouldGiveUp(attempt: Int) = attempt >= 10
        fun nextBackoffMs(attempt: Int): Long {
            val raw = listOf(1_000L, 2_000L, 5_000L, 10_000L).getOrElse(attempt - 1) { 20_000L }
            // deterministic ±10% jitter (no Math.random — keeps it unit-testable)
            val jit = raw * (((attempt * 37) % 20) - 10) / 100
            return (raw + jit).coerceIn(500L, 20_000L)
        }
    }
}

sealed interface TransportState {
    data object Connecting : TransportState
    data class Reconnecting(val attempt: Int) : TransportState
    data class Backoff(val attempt: Int, val ms: Long) : TransportState
    data object Live : TransportState
    data class Backpressure(val drops: Long) : TransportState
    data class Failed(val reason: String) : TransportState
}
