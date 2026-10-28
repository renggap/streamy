package com.streamy.app.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit-test [Mp3TranscodeSink]'s off-thread lifecycle WITHOUT real LAME (no JNI load): the
 * audio thread hands PCM to a bounded queue and returns immediately; a dedicated thread encodes;
 * flush() joins with a cap. Proves the P2 fix — re-encode moved OFF the audio thread — at the
 * queue/thread level, with a fake [Encoder] that blocks exactly like a real LAME encode would.
 */
class Mp3TranscodeSinkTest {

    /** Fake encoder that BLOCKS on every encode, simulating a slow LAME encode on the transcode thread. */
    private class BlockingEncoder : Encoder {
        override val mime = "audio/mpeg"
        val encodes = AtomicInteger(0)
        val closed = AtomicBoolean(false)
        val flushCalls = AtomicInteger(0)
        private val gate = CountDownLatch(1)
        private val started = CountDownLatch(1)

        override fun encode(pcm: ShortArray): List<ByteArray> {
            encodes.incrementAndGet()
            started.countDown()
            gate.await()   // block the transcode thread until the test releases it
            return listOf(pcm.map { it.toByte() }.toByteArray())  // deterministic "MP3"
        }
        override fun flush(): List<ByteArray> {
            flushCalls.incrementAndGet()
            return listOf(byteArrayOf(0x11, 0x22))
        }
        override fun close() { closed.set(true) }
        fun release() { gate.countDown() }
        fun awaitStarted() { check(started.await(2, TimeUnit.SECONDS)) { "transcode thread never started" } }
    }

    @Test fun onPcm_is_nonblocking_and_drops_newest_when_full() {
        val enc = BlockingEncoder()
        val out = ByteArrayOutputStream()
        val sink = Mp3TranscodeSink(out, 44_100, mono = true, encoderFactory = { enc })
        try {
            // The transcode thread blocks on queue.take() until the first frame arrives; enqueue
            // it, then wait until the encoder actually entered encode() (proves the thread runs).
            sink.onPcm(ShortArray(1024))
            enc.awaitStarted()

            // The transcode thread is now blocked on frame 1, so the queue (cap 32) fills with
            // frames 2..33. Frame 34+ must be DROPPED (drop-newest), and onPcm must never block.
            repeat(99) { sink.onPcm(ShortArray(1024)) }

            // Assert the audio-thread contract: onPcm returned immediately (no block), and the
            // drop counter reflects only the overflow beyond the 32-slot queue.
            assertThat(sink.droppedFrameCount).isGreaterThan(0L)
            assertThat(sink.droppedFrameCount).isEqualTo(99 - 32)
        } finally {
            enc.release()
            sink.close()
        }
    }

    @Test fun flush_joins_transcode_thread_and_drains_encoder_flush() {
        val enc = BlockingEncoder()
        val out = ByteArrayOutputStream()
        val sink = Mp3TranscodeSink(out, 44_100, mono = true, encoderFactory = { enc })
        try {
            sink.onPcm(ShortArray(1024))   // wakes the transcode thread
            enc.awaitStarted()             // it's inside encode(), blocked
            enc.release()                  // let it finish frame 1
            // flush() offers the end marker and joins; it must complete even though the fake
            // encoder is slow, and the encoder.flush() bytes must reach the output stream.
            sink.flush()
            // One encoded frame (1024 bytes) + encoder.flush() tail (2 bytes) reached disk.
            val bytes = out.toByteArray()
            assertThat(bytes.size).isEqualTo(1024 + 2)
            assertThat(bytes.copyOfRange(1024, 1026)).isEqualTo(byteArrayOf(0x11.toByte(), 0x22.toByte()))
            assertThat(enc.flushCalls.get()).isEqualTo(1)
            assertThat(enc.encodes.get()).isEqualTo(1)
        } finally {
            sink.close()
        }
    }

    @Test fun encoder_death_clears_queue_so_flush_join_terminates() {
        val out = ByteArrayOutputStream()
        // Encoder whose encode() throws immediately: simulates LAME dying mid-recording.
        val dying = object : Encoder {
            override val mime = "audio/mpeg"
            override fun encode(pcm: ShortArray): List<ByteArray> { throw IllegalStateException("encoder died") }
            override fun flush(): List<ByteArray> = listOf(byteArrayOf(0x7F))
            override fun close() {}
        }
        val sink = Mp3TranscodeSink(out, 44_100, mono = true, encoderFactory = { dying })
        try {
            // Fill the queue past capacity; the run-loop clears it on the first throw so the
            // join in flush() can't wait forever on unconsumed frames.
            repeat(40) { sink.onPcm(ShortArray(1024)) }
            val flushCompleted = AtomicBoolean(false)
            val t = Thread { sink.flush(); flushCompleted.set(true) }
            t.start()
            t.join(2_000)
            assertThat(flushCompleted.get()).isTrue()  // join terminated promptly
        } finally {
            sink.close()
        }
    }

    @Test fun flush_with_full_queue_still_delivers_end_marker_and_drains() {
        // Regression: flush() used offer(END_MARKER), which fails exactly when the queue is full
        // (transcode thread behind) — the thread then never saw the marker and the encoder tail
        // was lost. The marker must get through once the thread catches up.
        val enc = BlockingEncoder()
        val out = ByteArrayOutputStream()
        val sink = Mp3TranscodeSink(out, 44_100, mono = true, encoderFactory = { enc })
        try {
            sink.onPcm(ShortArray(1024))
            enc.awaitStarted()
            repeat(32) { sink.onPcm(ShortArray(1024)) }   // queue now full
            val flushed = AtomicBoolean(false)
            val t = Thread { sink.flush(); flushed.set(true) }
            t.start()
            Thread.sleep(50)
            enc.release()                                 // thread catches up, takes the marker
            t.join(3_000)
            assertThat(flushed.get()).isTrue()
            assertThat(enc.flushCalls.get()).isEqualTo(1) // ran encoder.flush() → saw the marker
            assertThat(enc.encodes.get()).isEqualTo(33)   // no backlog was thrown away
        } finally {
            enc.release()
            sink.close()
        }
    }

    @Test fun empty_pcm_frame_is_not_mistaken_for_end_marker() {
        // Regression: the run-loop ended on ANY empty frame, so a dead-mic empty frame silently
        // stopped the recording. Only the END_MARKER instance ends the loop.
        val enc = BlockingEncoder().also { it.release() }
        val out = ByteArrayOutputStream()
        val sink = Mp3TranscodeSink(out, 44_100, mono = true, encoderFactory = { enc })
        try {
            sink.onPcm(ShortArray(0))
            sink.onPcm(ShortArray(4))
            sink.flush()
            assertThat(enc.encodes.get()).isEqualTo(2)
            assertThat(sink.failed).isFalse()
        } finally {
            sink.close()
        }
    }

    @Test fun encoder_death_marks_sink_failed() {
        val dying = object : Encoder {
            override val mime = "audio/mpeg"
            override fun encode(pcm: ShortArray): List<ByteArray> { throw IllegalStateException("encoder died") }
            override fun flush(): List<ByteArray> = emptyList()
            override fun close() {}
        }
        val sink = Mp3TranscodeSink(ByteArrayOutputStream(), 44_100, mono = true, encoderFactory = { dying })
        try {
            sink.onPcm(ShortArray(1024))
            sink.flush()
            assertThat(sink.failed).isTrue()
        } finally {
            sink.close()
        }
    }

    @Test fun direct_sink_latches_failed_on_io_error() {
        val broken = object : java.io.OutputStream() {
            override fun write(b: Int) { throw java.io.IOException("ENOSPC") }
            override fun write(b: ByteArray, off: Int, len: Int) { throw java.io.IOException("ENOSPC") }
        }
        val sink = Mp3DirectSink(broken)
        // Larger than the 128 KB buffer so the write reaches the failing stream immediately.
        sink.onEncoded(listOf(ByteArray(200 * 1024)))
        assertThat(sink.failed).isTrue()
        sink.close()
    }
}
