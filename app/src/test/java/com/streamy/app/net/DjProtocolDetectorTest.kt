package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import com.streamy.app.data.DjProtocol
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Unit-test the Ultravox-ACK vs Icecast-200 discrimination of [DjProtocolDetector] with a fake
 * server on localhost. The live env-gated probe stays for real servers; this proves the logic.
 *
 * [DjProtocolDetector.detect] probes Ultravox FIRST, then Icecast, so a single test server must
 * be able to answer BOTH kinds of probe (the Ultravox leg gets a non-ACK for the Icecast cases).
 */
class DjProtocolDetectorTest {

    @Test fun detects_ultravox_from_cipher_ack() {
        withServer(
            handle = { sock ->
                val head = firstBytes(sock, 2)
                if (isUltravoxMagic(head)) {
                    // Header is 6 bytes total: 2 magic (already read) + 2 ct + 2 len.
                    val hdr = readN(sock.getInputStream(), 4)
                    val len = ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                    readN(sock.getInputStream(), len)   // payload "2.1\0"
                    readN(sock.getInputStream(), 1)     // trailer
                    writeFrame(sock, "ACK:foobar\u0000")
                } else {
                    sock.close()  // shouldn't happen
                }
            },
            after = { port ->
                DjProtocolDetector.invalidate("127.0.0.1", port)
                assertThat(DjProtocolDetector.detect("127.0.0.1", port, "/", "u", "p"))
                    .isEqualTo(DjProtocol.ULTRAVOX)
            },
        )
    }

    @Test fun detects_icecast_from_http_200() {
        withServer(
            handle = { sock ->
                val head = firstBytes(sock, 2)
                if (isUltravoxMagic(head)) {
                    sock.close()  // not Ultravox — the Icecast leg runs next
                } else {
                    val inp = sock.getInputStream()
                    readLine(inp)                       // SOURCE / ICE/1.0
                    var line: String
                    do { line = readLine(inp) } while (line.isNotEmpty())  // headers through blank
                    readN(inp, 16)                      // probe nudge bytes
                    sock.getOutputStream().write("HTTP/1.0 200 OK\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    sock.getOutputStream().flush()
                    sock.close()
                }
            },
            after = { port ->
                DjProtocolDetector.invalidate("127.0.0.1", port)
                assertThat(DjProtocolDetector.detect("127.0.0.1", port, "/", "u", "p"))
                    .isEqualTo(DjProtocol.ICECAST)
            },
        )
    }

    @Test fun neither_protocol_throws() {
        withServer(
            handle = { sock ->
                // Refuse both probes: close without replying (Ultravox leg times out; the
                // Icecast leg also times out since its probe socket is closed too).
                sock.close()
            },
            after = { port ->
                DjProtocolDetector.invalidate("127.0.0.1", port)
                val e = runCatching { DjProtocolDetector.detect("127.0.0.1", port, "/", "u", "p") }
                    .exceptionOrNull()
                assertThat(e).isNotNull()
            },
        )
    }

    /** Cache semantics: a successful detection is reused across calls (no re-probe / re-write of
     *  the 16-byte nudge), and invalidate() forces the next call to probe again. This is the
     *  reconnect hardening from the review — a flapping connection must not re-sniff on every retry. */
    @Test fun detection_is_cached_until_invalidate() {
        var connects = 0
        withServer(
            handle = { sock ->
                connects++
                val head = firstBytes(sock, 2)
                if (isUltravoxMagic(head)) {
                    val hdr = readN(sock.getInputStream(), 4)
                    val len = ((hdr[2].toInt() and 0xFF) shl 8) or (hdr[3].toInt() and 0xFF)
                    readN(sock.getInputStream(), len)
                    readN(sock.getInputStream(), 1)
                    writeFrame(sock, "ACK:foobar\u0000")
                } else {
                    sock.close()
                }
            },
            after = { port ->
                DjProtocolDetector.invalidate("127.0.0.1", port)
                // First call probes (Ultravox ACK) and caches.
                assertThat(DjProtocolDetector.detect("127.0.0.1", port, "/", "u", "p"))
                    .isEqualTo(DjProtocol.ULTRAVOX)
                val connectsAfterFirst = connects
                // Cached: repeated calls must NOT re-probe (zero new sockets).
                assertThat(DjProtocolDetector.detect("127.0.0.1", port, "/", "u", "p"))
                    .isEqualTo(DjProtocol.ULTRAVOX)
                assertThat(DjProtocolDetector.detect("127.0.0.1", port, "/", "u", "p"))
                    .isEqualTo(DjProtocol.ULTRAVOX)
                assertThat(connects).isEqualTo(connectsAfterFirst)
                // Invalidate: the next call must probe again (one new socket).
                DjProtocolDetector.invalidate("127.0.0.1", port)
                assertThat(DjProtocolDetector.detect("127.0.0.1", port, "/", "u", "p"))
                    .isEqualTo(DjProtocol.ULTRAVOX)
                assertThat(connects).isEqualTo(connectsAfterFirst + 1)
            },
        )
    }

    /** Invalidate for a port that was never probed is a harmless no-op (removing a missing key). */
    @Test fun invalidate_unknown_entry_is_noop() {
        DjProtocolDetector.invalidate("127.0.0.1", 1)
        DjProtocolDetector.invalidate("127.0.0.1", 2)
    }

    // ---- harness ----

    /** Accept connections in a loop and dispatch each to [handle]; then run [after] and close. */
    private fun withServer(handle: (Socket) -> Unit, after: (Int) -> Unit) {
        val server = ServerSocket(0)
        val port = server.localPort
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = thread(name = "fake-dj-server") {
            while (!stop.get()) {
                val sock = try { server.accept() } catch (_: java.io.IOException) { break }
                try { handle(sock) } catch (_: Throwable) { /* probe closed early */ }
                runCatching { sock.close() }
            }
        }
        try {
            after(port)
        } finally {
            stop.set(true)
            runCatching { server.close() }   // unblocks accept()
            t.join(2_000)
        }
    }

    private fun firstBytes(sock: Socket, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        val deadline = System.currentTimeMillis() + 2_000
        while (off < n && System.currentTimeMillis() < deadline) {
            val r = sock.getInputStream().read(buf, off, n - off)
            if (r < 0) break
            off += r
        }
        return buf
    }

    private fun writeFrame(sock: Socket, payloadAsciiZ: String) {
        val p = payloadAsciiZ.toByteArray(Charsets.US_ASCII)
        sock.getOutputStream().write(UvoxClient.message(1, 0x009, p))
        sock.getOutputStream().flush()
    }

    private fun readLine(inp: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0 || c == 0) break
            sb.append(c.toChar())
            if (sb.endsWith("\r\n")) break
        }
        return sb.toString().trimEnd('\r', '\n')
    }

    private fun readN(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw java.io.IOException("socket closed early")
            off += r
        }
        return buf
    }

    private fun isUltravoxMagic(b: ByteArray): Boolean =
        b.size >= 2 && b[0] == 0x5A.toByte() && b[1] == 0x00.toByte()
}
