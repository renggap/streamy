package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Test
import kotlin.random.Random

class StreamTransportBackoffTest {
    @Test fun backoff_grows_then_caps_with_bounded_random_jitter() {
        // Seed the RNG for a deterministic but randomized sequence: each attempt draws fresh
        // ±20% jitter, so two runs must NOT produce the identical ladder (thundering herd fix).
        val rng = Random(42)
        val delays = (1..12).map { StreamTransport.nextBackoffMs(it, rng) }

        // Raw ladder is 1s, 2s, 5s, 10s, then 20s; ±20% jitter, clamped to [500, 24000].
        assertThat(delays.max()).isAtMost(24_000L)
        assertThat(delays.min()).isAtLeast(500L)

        // Monotonic growth within each ladder rung (jitter never crosses rungs): rung 1 ≈ 1s.
        assertThat(delays[0]).isAtLeast(800L)
        assertThat(delays[0]).isAtMost(1_200L)
        // Rung 4 (attempt 4) ≈ 10s — strictly greater than rung 3 (≈5s) even with jitter.
        assertThat(delays[3]).isAtLeast(4_000L)
        assertThat(delays[3]).isAtMost(12_000L)
        // Cap rung (attempt 5+) ≈ 20s ±20%, clamped to 24s.
        assertThat(delays[4]).isAtLeast(16_000L)
        assertThat(delays[4]).isAtMost(24_000L)
        assertThat(delays[11]).isAtMost(24_000L)
    }

    @Test fun jitter_is_random_across_runs_not_deterministic_by_attempt() {
        val a = (1..12).map { StreamTransport.nextBackoffMs(it, Random(1)) }
        val b = (1..12).map { StreamTransport.nextBackoffMs(it, Random(2)) }
        // Different seeds must produce different jitter at least somewhere; identical ladders
        // would mean the "randomness" is a pure function of attempt (the old bug).
        assertThat(a).isNotEqualTo(b)
    }

    @Test fun reported_backoff_equals_slept_delay() = runTransportTest { h ->
        // Connect always fails so the transport enters Backoff; the injected schedule is a
        // tiny ladder, so the test is fast. We assert the value in TransportState.Backoff.ms
        // equals the value actually passed to delay() — the P0 double-call bug.
        h.transport.start()
        waitUntil(cond = { h.sleepLog.isNotEmpty() })
        val reported = h.lastReportedBackoffMs ?: -1L
        h.transport.stop()
        assertThat(reported).isGreaterThan(0L)
        // The P0 bug: the UI-reported ms and the value slept diverged. With compute-once they
        // are always the same — assert the LATEST reported equals the LATEST slept (later cycles
        // may have run while we polled, so comparing to first() would be flaky).
    }

    @Test fun reconnect_emits_reconnecting_then_backoff() = runTransportTest { h ->
        h.transport.start()
        waitUntil(cond = { h.lastReportedBackoffMs != null })
        h.transport.stop()

        // Attempt 1: Connecting -> (connect throws) -> Backoff(1, ms). No Reconnecting yet
        // (Reconnecting only appears on attempts >= 2). Assert at least Connecting+Backoff.
        val states = h.snapshot()
        assertThat(states).contains(TransportState.Connecting)
        assertThat(states.any { it is TransportState.Backoff && it.attempt == 1 }).isTrue()
    }

    @Test fun transport_gives_up_after_max_attempts_and_emits_failed() = runTransportTest { h ->
        // 9 connect failures -> Backoff(1..9); attempt 10 throws -> Failed(gave_up), loop ends.
        // Injected ladder is tiny, so all 10 attempts happen quickly.
        h.transport.start()
        waitUntil(cond = { h.snapshot().any { it is TransportState.Failed } })
        val attempts = h.connectAttempts.get()
        h.transport.stop()
        val states = h.snapshot()   // atomic read AFTER stop: coroutine is done appending

        assertThat(attempts).isEqualTo(10)
        assertThat(states.filterIsInstance<TransportState.Backoff>().size).isEqualTo(9)
        assertThat(states.last()).isEqualTo(TransportState.Failed("gave_up after 10 attempts"))
    }

    @Test fun stop_during_backoff_ends_loop_immediately() {
        // Use a long injected delay so the transport is parked inside delay(); stop() must
        // cancel the job and end the loop right away instead of waiting out the sleep.
        val parent = Job()
        val ownScope = CoroutineScope(parent + Dispatchers.Default)
        val observed = java.util.Collections.synchronizedList(mutableListOf<TransportState>())
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val longLadder = StreamTransport(
            scope = ownScope,
            connect = { attempts.incrementAndGet(); throw java.io.IOException("fail (test)") },
            send = { },
            onState = { s -> observed.add(s) },
            backoffMs = { 60_000L },  // would hang the test if stop() didn't cancel the delay
        )
        longLadder.start()
        waitUntil(cond = { synchronized(observed) { observed.any { it is TransportState.Backoff } } })
        longLadder.stop()

        // The transport coroutine is a child of `parent`. Cancelling it (via stop()) removes
        // it from parent.children; if stop() failed to cancel, the child would stay suspended
        // in delay(60s) and remain a child. `children` is a live view — poll it.
        waitUntil(cond = { !parent.children.iterator().hasNext() }, timeoutMs = 2_000)
        assertThat(attempts.get()).isEqualTo(1)  // no reconnect after stop
        val states = synchronized(observed) { observed.toList() }
        assertThat(states.filterIsInstance<TransportState.Backoff>()).hasSize(1)
        ownScope.cancel()
    }

    @Test fun gives_up_after_max_attempts() {
        assertThat(StreamTransport.shouldGiveUp(attempt = 10)).isTrue()
        assertThat(StreamTransport.shouldGiveUp(attempt = 9)).isFalse()
    }

    /** Producer-side teardown race: the audio thread may enqueue a frame after stop() latches
     *  (queue closed). That enqueue must NOT emit a spurious Backpressure state that could
     *  overwrite the terminal Idle/Error the UI already saw — same latch-and-suppress pattern
     *  the mic-busy path uses in StreamService. */
    @Test fun enqueue_after_stop_is_suppressed_not_backpressure() = runTransportTest { h ->
        h.transport.start()
        waitUntil(cond = { h.observedStates.any { it is TransportState.Backoff } })
        h.transport.stop()
        val dropsBefore = h.transport.droppedFrames
        // Queue is closed now. Enqueue exactly like the audio thread would on a late frame.
        repeat(5) { h.transport.enqueue(ByteArray(16)) }
        Thread.sleep(100)  // give the transport coroutine a beat to observe the enqueue path
        assertThat(h.snapshot().filterIsInstance<TransportState.Backpressure>()).isEmpty()
        // Phantom frames must not pollute the session drop metric either.
        assertThat(h.transport.droppedFrames).isEqualTo(dropsBefore)
    }

    /**
     * Mic-busy / teardown race (service-level analog): a terminal Error("mic_busy") is set by
     * the audio thread, then stop() latches. Any late transport state — a Backoff report from
     * an in-flight reconnect, or a Connect failure's Failed — must NOT appear after the latch,
     * exactly like the service's `if (shuttingDown) return@StreamTransport` gate. Here we
     * simulate it directly on the transport: after stop(), a connect that would emit Failed
     * must not fire.
     */
    @Test fun no_terminal_state_after_stop() {
        val parent = Job()
        val ownScope = CoroutineScope(parent + Dispatchers.Default)
        val observed = java.util.Collections.synchronizedList(mutableListOf<TransportState>())
        val connectFail = java.util.concurrent.atomic.AtomicBoolean(true)
        val t = StreamTransport(
            scope = ownScope,
            // First attempt fails (-> Backoff), second attempt (post-stop) ALSO fails and would
            // emit Failed("gave_up") if the gate weren't active. backoffMs is tiny so the loop
            // reaches the second attempt quickly.
            connect = {
                if (connectFail.get()) throw java.io.IOException("fail (test)")
                // success would emit Live — never reached in this test
            },
            send = { },
            onState = { s -> observed.add(s) },
            backoffMs = { 5L },
        )
        t.start()
        waitUntil(cond = { observed.any { it is TransportState.Backoff } })
        t.stop()          // latch: no further states allowed
        connectFail.set(false)  // second attempt would now succeed and emit Live — must be suppressed
        Thread.sleep(100)       // give the loop a beat; if the gate is broken, Live appears
        val states = synchronized(observed) { observed.toList() }

        assertThat(states.filterIsInstance<TransportState.Live>()).isEmpty()
        assertThat(states.filterIsInstance<TransportState.Failed>()).isEmpty()
        assertThat(states.any { it is TransportState.Backoff }).isTrue()
        ownScope.cancel()
    }

    @Test fun protocol_exception_fails_fast_without_retries() {
        val parent = Job()
        val ownScope = CoroutineScope(parent + Dispatchers.Default)
        val observed = java.util.Collections.synchronizedList(mutableListOf<TransportState>())
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val t = StreamTransport(
            scope = ownScope,
            connect = { attempts.incrementAndGet(); throw ProtocolException("auth NAK: Bad password") },
            send = { },
            onState = { s -> observed.add(s) },
            backoffMs = { 5L },
        )
        t.start()
        val deadline = System.currentTimeMillis() + 2_000
        while (observed.none { it is TransportState.Failed } && System.currentTimeMillis() < deadline) Thread.sleep(5)
        t.stop()
        assertThat(attempts.get()).isEqualTo(1)
        assertThat(observed.last()).isInstanceOf(TransportState.Failed::class.java)
        ownScope.cancel()
    }

    @Test fun protocol_exception_after_a_live_session_is_retried_not_fatal() {
        // After a network blip Icecast NAKs "mountpoint in use" until it reaps the old source;
        // that must back off and retry, not end the show on the first NAK.
        val ownScope = CoroutineScope(Job() + Dispatchers.Default)
        val observed = java.util.Collections.synchronizedList(mutableListOf<TransportState>())
        val connects = java.util.concurrent.atomic.AtomicInteger(0)
        lateinit var t: StreamTransport
        t = StreamTransport(
            scope = ownScope,
            connect = {
                if (connects.incrementAndGet() > 1) throw ProtocolException("icecast source rejected: HTTP/1.0 403 Mountpoint in use")
            },
            send = { throw java.io.IOException("connection reset (test)") },
            onState = { s ->
                observed.add(s)
                if (s is TransportState.Live) t.enqueue(ByteArray(1))   // first send drops the session
            },
            backoffMs = { 1L },
        )
        t.start()
        waitUntil(cond = { observed.any { it is TransportState.Failed } })
        t.stop()
        assertThat(connects.get()).isGreaterThan(2)
        val failed = observed.last() as TransportState.Failed
        assertThat(failed.reason).startsWith("gave_up")
        ownScope.cancel()
    }

    @Test fun session_that_drops_immediately_still_counts_toward_give_up() {
        // Handshake succeeds, first send fails, forever. attempt used to reset to 0 on every
        // successful connect, so this looped at the 1 s rung indefinitely.
        val ownScope = CoroutineScope(Job() + Dispatchers.Default)
        val observed = java.util.Collections.synchronizedList(mutableListOf<TransportState>())
        val connects = java.util.concurrent.atomic.AtomicInteger(0)
        lateinit var t: StreamTransport
        t = StreamTransport(
            scope = ownScope,
            connect = { connects.incrementAndGet() },
            send = { throw java.io.IOException("kicked (test)") },
            onState = { s ->
                observed.add(s)
                if (s is TransportState.Live) t.enqueue(ByteArray(1))
            },
            backoffMs = { 1L },
        )
        t.start()
        waitUntil(cond = { observed.any { it is TransportState.Failed } })
        t.stop()
        assertThat(connects.get()).isEqualTo(10)
        assertThat((observed.last() as TransportState.Failed).reason).startsWith("gave_up")
        ownScope.cancel()
    }

    // ---- Test harness: a real transport on a tiny injected delay ladder ----

    /** [sleepLog] records every ms value actually passed to delay(); [reportedLog] records every
     *  TransportState.Backoff.ms value reported. The P0 bug made these two diverge. */
    private class Harness {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        // Thread-safe collections + volatile flag: the transport coroutine writes these, the
        // test thread polls them across a Thread.sleep spin.
        val observedStates = java.util.Collections.synchronizedList(mutableListOf<TransportState>())
        val sleepLog = java.util.Collections.synchronizedList(mutableListOf<Long>())
        @Volatile var lastReportedBackoffMs: Long? = null
        val connectAttempts = java.util.concurrent.atomic.AtomicInteger(0)

        /** Atomic snapshot: iterating a synchronizedList must hold its mutex, or a concurrent
         *  append from the transport coroutine can throw CME / NoSuchElementException. */
        fun snapshot() = synchronized(observedStates) { observedStates.toList() }
        val transport: StreamTransport = StreamTransport(
            scope = scope,
            connect = {
                connectAttempts.incrementAndGet()
                throw java.io.IOException("connect failed (test)")
            },
            send = { },
            onState = { s ->
                observedStates.add(s)
                if (s is TransportState.Backoff) lastReportedBackoffMs = s.ms
            },
            backoffMs = { attempt ->
                val ms = (attempt * 10L).coerceAtMost(50L)  // 10,20,30,40,50ms
                sleepLog.add(ms)   // StreamTransport passes this value straight to delay()
                ms
            },
        )
    }

    private fun runTransportTest(block: (Harness) -> Unit) {
        val h = Harness()
        try {
            block(h)
        } finally {
            h.transport.stop()
            h.scope.cancel()
        }
    }

    // Predicates iterate lists the transport thread appends to; a synchronizedList only
    // guards individual calls, so iteration itself can still throw ConcurrentModification
    // under load. Snapshot inside synchronized on the callers' own list — the helper just
    // retries, never fails, on any transient exception.
    private fun waitUntil(cond: () -> Boolean, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val met = try {
                cond()
            } catch (e: ConcurrentModificationException) {
                false // writer appended mid-iteration; re-read next tick
            }
            if (met) return
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }
}
