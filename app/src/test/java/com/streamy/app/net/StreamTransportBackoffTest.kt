package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamTransportBackoffTest {
    @Test fun backoff_grows_then_caps_and_jitters() {
        val delays = (1..12).map { StreamTransport.nextBackoffMs(it) }
        assertThat(delays.max()).isAtMost(20_000L * 12 / 10)   // never exceeds +20% of the 20s cap
        assertThat(delays[0]).isAtLeast(900L)                  // ~1s ±10%
        assertThat(delays[0]).isAtMost(1_100L)
        assertThat(delays[5]).isAtMost(20_000L)                // capped well before attempt 6
    }
    @Test fun gives_up_after_max_attempts() {
        assertThat(StreamTransport.shouldGiveUp(attempt = 10)).isTrue()
        assertThat(StreamTransport.shouldGiveUp(attempt = 9)).isFalse()
    }
}
