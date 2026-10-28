package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/**
 * close() during connect() must abort the handshake. Previously the socket was published only
 * after the handshake, so a Stop mid-handshake closed nothing and the server kept an
 * authenticated source holding the mount/sid "in use".
 */
class SourceClientCloseTest {

    @Test fun uvox_connect_after_close_refuses() {
        val c = UvoxClient("127.0.0.1", 1, sid = 1, password = "x", mime = "audio/mpeg")
        c.close()
        val err = runCatching { c.connect(64, "n", "g") }.exceptionOrNull()
        assertThat(err).isInstanceOf(java.io.IOException::class.java)
        assertThat(err!!.message).isEqualTo("client closed")
    }

    @Test fun uvox_close_aborts_an_in_flight_handshake() {
        // Server accepts the TCP connection but never answers the cipher request: connect()
        // blocks in the handshake read (6 s soTimeout) until close() tears the socket down.
        ServerSocket(0).use { server ->
            val acceptor = Thread { runCatching { server.accept() } }.apply { isDaemon = true; start() }
            val c = UvoxClient("127.0.0.1", server.localPort, sid = 1, password = "x", mime = "audio/mpeg")
            val err = AtomicReference<Throwable?>()
            val connector = Thread { err.set(runCatching { c.connect(64, "n", "g") }.exceptionOrNull()) }
            connector.start()
            Thread.sleep(200)          // let it reach the blocking read
            val t0 = System.nanoTime()
            c.close()
            connector.join(2_000)
            assertThat(connector.isAlive).isFalse()
            assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(2_000L)
            assertThat(err.get()).isNotNull()
            acceptor.join(100)
        }
    }

    @Test fun icy_title_block_is_utf8_and_defuses_field_terminator() {
        val block = String(IcecastSourceClient.icyTitleBlock("Café — it's on'; air"), Charsets.UTF_8)
        // Non-ASCII survives (was '?' under US_ASCII); a lone apostrophe is left alone (no
        // backslash escape, which players render literally); only the "';" terminator is defused.
        assertThat(block).isEqualTo("StreamTitle='Café — it's on\u2019; air';")
    }
}
