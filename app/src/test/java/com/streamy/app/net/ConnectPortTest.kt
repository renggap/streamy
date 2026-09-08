package com.streamy.app.net

import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Regression for the host:0 bug: inside `Socket().apply { connect(InetSocketAddress(host, port)) }`
 * the unqualified `port` resolved to Socket.getPort() — 0 on an unconnected socket — so the client
 * dialed host:0 and got instant refusal ("cannot connect"). Binding the address OUTSIDE the apply
 * scope (a named local) fixed it. These tests bind a local listener and assert the client actually
 * reaches it; with the old code both fail with ConnectException before any byte is read.
 */
class ConnectPortTest {

    @Test fun uvox_client_dials_configured_port_not_zero() {
        ServerSocket(0, 1).use { srv ->
            srv.soTimeout = 10_000
            var accepted = false
            val t = thread {
                srv.accept().use { sock ->
                    accepted = true
                    // Minimal uvox reply to the 0x1009 cipher request so connect() proceeds to
                    // auth (which we don't answer — client should fail there, NOT at TCP connect).
                    sock.getOutputStream().apply {
                        val payload = "ACK:foobar".toByteArray(Charsets.US_ASCII)
                        write(byteArrayOf(0x5A, 0x00, 0x10, 0x09,
                            ((payload.size + 1) shr 8).toByte(), (payload.size + 1).toByte())
                            + payload + byteArrayOf(0))
                        flush()
                    }
                }
            }
            try {
                val c = UvoxClient("127.0.0.1", srv.localPort, 1, "pw", "audio/mpeg")
                // With the port-0 bug this throws ConnectException immediately. Fixed: the
                // handshake proceeds past TCP connect and cipher, and fails only at the (silent)
                // auth step — i.e. NOT a ConnectException.
                val err = runCatching { c.connect(64, "n", "g") }.exceptionOrNull()
                check(err !is java.net.ConnectException) { "dialed port 0: $err" }
                check(accepted) { "listener never saw a connection — client dialed the wrong port" }
            } finally {
                t.interrupt()
            }
        }
    }

    @Test fun icecast_client_dials_configured_port_not_zero() {
        ServerSocket(0, 1).use { srv ->
            srv.soTimeout = 10_000
            var accepted = false
            val t = thread {
                srv.accept().use { sock ->
                    accepted = true
                    // Read the SOURCE request; answer with an ICY error so connect() throws
                    // AFTER TCP connect (proves the port was right).
                    sock.soTimeout = 10_000
                    runCatching { sock.getInputStream().readNBytes(16) }
                    sock.getOutputStream().apply {
                        write("ICY 401 Unauthorized\r\n\r\n".toByteArray()); flush()
                    }
                }
            }
            try {
                val c = IcecastSourceClient("127.0.0.1", srv.localPort, "/", "u", "p")
                val err = runCatching { c.connect(64, "n", "g") }.exceptionOrNull()
                check(err !is java.net.ConnectException) { "dialed port 0: $err" }
                check(accepted) { "listener never saw a connection — client dialed the wrong port" }
            } finally {
                t.interrupt()
            }
        }
    }
}
