package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** v1-then-Ultravox selection logic, with fake clients (no sockets). */
class FallbackSourceClientTest {

    private class Fake(var failWith: Throwable? = null) : SourceClient {
        var connects = 0
        var closed = false
        val sent = mutableListOf<ByteArray>()
        var titleResult = false
        val titles = mutableListOf<String>()
        override fun connect(bitrateKbps: Int, name: String, genre: String) {
            connects++
            failWith?.let { throw it }
        }
        override fun send(frame: ByteArray) { sent += frame }
        override fun close() { closed = true }
        override fun updateNowPlaying(title: String): Boolean {
            titles += title
            return titleResult
        }
    }

    @Test fun title_push_reports_true_when_either_client_confirmed() {
        // The toast's honesty flows through here: false only when NO client got a real
        // server confirmation; both clients still receive the title (whichever is live keeps it,
        // and the inactive one replays it after its next connect) — even when the first confirms.
        val v1 = Fake(); val uvox = Fake()
        val c = FallbackSourceClient(v1, uvox)
        assertThat(c.updateNowPlaying("T")).isFalse()
        assertThat(v1.titles).containsExactly("T")
        assertThat(uvox.titles).containsExactly("T")

        uvox.titleResult = true
        assertThat(c.updateNowPlaying("T2")).isTrue()
        assertThat(v1.titles).containsExactly("T", "T2")   // no short-circuit

        uvox.titleResult = false
        v1.titleResult = true
        assertThat(c.updateNowPlaying("T3")).isTrue()
        assertThat(uvox.titles).containsExactly("T", "T2", "T3")
    }

    @Test fun primary_success_never_touches_fallback() {
        val v1 = Fake(); val uvox = Fake()
        val c = FallbackSourceClient(v1, uvox)
        c.connect(24, "n", "g")
        c.send(byteArrayOf(1))
        assertThat(uvox.connects).isEqualTo(0)
        assertThat(v1.sent).hasSize(1)
    }

    @Test fun primary_failure_falls_back_and_reconnects_stay_on_fallback() {
        val v1 = Fake(ProtocolException("v1 NAK: HTTP/1.0 400 Bad Request")); val uvox = Fake()
        val c = FallbackSourceClient(v1, uvox)
        c.connect(24, "n", "g")
        c.send(byteArrayOf(1))
        assertThat(uvox.sent).hasSize(1)

        // A reconnect after a drop must not try v1 again (no protocol flapping on air).
        c.connect(24, "n", "g")
        assertThat(v1.connects).isEqualTo(1)
        assertThat(uvox.connects).isEqualTo(2)
    }

    @Test fun v1_password_rejection_wins_over_the_fallbacks_noise() {
        val auth = ProtocolException("auth NAK: invalid password")
        val c = FallbackSourceClient(Fake(auth), Fake(SocketTimeoutException(UvoxClient.HANDSHAKE_TIMEOUT_TEXT)))
        val err = runCatching { c.connect(24, "n", "g") }.exceptionOrNull()
        assertThat(err).isSameInstanceAs(auth)
    }

    @Test fun otherwise_the_fallback_error_is_reported() {
        val uvoxAuth = ProtocolException("auth NAK: NAK:auth")
        val c = FallbackSourceClient(Fake(ProtocolException("v1 NAK: HTTP/1.1 200 OK")), Fake(uvoxAuth))
        val err = runCatching { c.connect(24, "n", "g") }.exceptionOrNull()
        assertThat(err).isSameInstanceAs(uvoxAuth)
        assertThat(err!!.suppressed.toList()).hasSize(1)
    }

    @Test fun close_closes_both_and_send_after_close_fails() {
        val v1 = Fake(); val uvox = Fake()
        val c = FallbackSourceClient(v1, uvox)
        c.close()
        assertThat(v1.closed).isTrue()
        assertThat(uvox.closed).isTrue()
        assertThat(runCatching { c.send(byteArrayOf(1)) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test fun stop_during_v1_attempt_does_not_start_ultravox() {
        lateinit var c: FallbackSourceClient
        val uvox = Fake()
        // The primary fails *because* Stop closed it mid-handshake.
        val v1 = object : SourceClient {
            override fun connect(bitrateKbps: Int, name: String, genre: String) {
                c.close(); throw IOException("client closed")
            }
            override fun send(frame: ByteArray) = Unit
            override fun close() = Unit
        }
        c = FallbackSourceClient(v1, uvox)
        assertThat(runCatching { c.connect(24, "n", "g") }.isFailure).isTrue()
        assertThat(uvox.connects).isEqualTo(0)
    }
}
