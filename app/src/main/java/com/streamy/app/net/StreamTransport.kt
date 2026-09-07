package com.streamy.app.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class StreamTransport(
    private val scope: CoroutineScope,
    private val connect: suspend () -> Unit,        // runs full handshake
    private val send: (ByteArray) -> Unit,
    /**
     * State-change callback. For [TransportState.Backpressure] this fires INLINE on the
     * audio capture thread (via [enqueue]) — it MUST be non-blocking; offload any heavy
     * work. Other states fire on this transport's own coroutine.
     */
    private val onState: (TransportState) -> Unit,
    /**
     * Reconnect backoff schedule. Injected so tests can use tiny delays and assert the
     * reported [TransportState.Backoff.ms] equals the value actually slept. Defaults to
     * [nextBackoffMs] — randomized ±20% jitter so concurrent clients don't reconnect in
     * lockstep (thundering herd).
     */
    private val backoffMs: (attempt: Int) -> Long = { nextBackoffMs(it) },
) {
    private val queue = Channel<ByteArray>(CAPACITY)

    // Atomic: [enqueue] is called from the audio capture thread AND from
    // AudioPipeline.stop()'s flush path (service scope) after capture exits.
    private val drops = AtomicLong(0)

    @Volatile private var stopped = false
    private var job: Job? = null

    /** Total frames dropped since [start] (drop-newest policy). Monotonic; safe to poll. */
    val droppedFrames: Long get() = drops.get()

    /** Non-blocking enqueue. If the queue is full, DROP THE NEW frame (drop-newest) — keeps
     *  broadcast latency low under downstream backpressure. Counts the drop for metrics.
     *
     *  After [stop] the queue is closed: a late producer (audio thread still draining a frame
     *  while teardown runs) gets `Closed` from [Channel.trySend]. That must be SILENT — no
     *  phantom Backpressure state and no drop-count bump — or the UI could see a stale
     *  Backpressure after the terminal Idle/Error, and the session's drop metric would count
     *  frames the transport never accepted. Same latch-and-suppress pattern as the service's
     *  mic-busy `shuttingDown` gate. */
    fun enqueue(frame: ByteArray) {
        // trySend() returns: Success (accepted), Failure/Closed (not accepted — either full or
        // stopped). The two rejection reasons MUST behave differently:
        //   - queue full   → drop-newest: count + surface Backpressure (the live product matters,
        //                     so a late frame is dropped rather than stalling the audio thread)
        //   - channel closed (stop() latched) → silent: a producer frame arriving during teardown
        //                     must not emit phantom Backpressure after the terminal Idle/Error, nor
        //                     pollute the session drop metric.
        // isClosed is the only reliable discriminator (ChannelResult.Closed is internal).
        val result = queue.trySend(frame)
        if (result.isClosed) return
        if (result.isFailure) onState(TransportState.Backpressure(drops.incrementAndGet()))
    }

    /** Drop pre-reconnect backlog so live speech resumes live, not ~1.5s stale. */
    private fun clearBacklog() {
        while (queue.tryReceive().isSuccess) { /* drop */ }
    }

    fun start() {
        job = scope.launch {
            var attempt = 0
            while (!stopped) {
                try {
                    onState(if (attempt == 0) TransportState.Connecting else TransportState.Reconnecting(attempt))
                    connect()
                    clearBacklog()
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
                    if (isNonRetryable(e)) {
                        onState(TransportState.Failed(e.message ?: "auth_failed"))
                        break
                    }
                    attempt++
                    if (shouldGiveUp(attempt)) {
                        // Wrong password / unreachable host: terminate instead of looping forever.
                        onState(TransportState.Failed("gave_up after $attempt attempts"))
                        break
                    }
                    // Compute ONCE and sleep the same value we report, so the state the UI
                    // shows is exactly the delay being waited (single source of truth).
                    val backoff = backoffMs(attempt)
                    onState(TransportState.Backoff(attempt, backoff))
                    delay(backoff)
                }
            }
            // Loop only exits on stop() or give-up — both already emitted their terminal
            // state. No spurious Failed("stopped"): Stop is signaled by StreamService -> Idle.
        }
    }

    /**
     * Stop the transport. Cancelling [job] throws CancellationException out of any in-flight
     * [delay] (the backoff sleep), so the reconnect loop ends at once instead of waiting up
     * to 20s. The [stopped] flag + [queue.close] cover the producer side.
     */
    fun stop() {
        stopped = true
        queue.close()
        job?.cancel()
    }

    companion object {
        const val CAPACITY = 64
        fun shouldGiveUp(attempt: Int) = attempt >= 10

        /** Auth/handshake rejections are permanent — retrying hammers the server and delays feedback. */
        fun isNonRetryable(e: Throwable): Boolean = e is ProtocolException

        /**
         * Backoff ladder: 1s, 2s, 5s, 10s, then 20s cap — with ±20% uniform random jitter,
         * clamped to [500, 24_000] ms. Randomized (not attempt-seeded) so many clients that
         * fail together spread their retries instead of stampeding in lockstep.
         */
        fun nextBackoffMs(attempt: Int, random: Random = Random): Long {
            val raw = listOf(1_000L, 2_000L, 5_000L, 10_000L).getOrElse(attempt - 1) { 20_000L }
            val jitter = random.nextLong(-20L, 21L) * raw / 100
            return (raw + jitter).coerceIn(500L, 24_000L)
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
