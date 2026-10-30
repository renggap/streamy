package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/** [ShoutcastV1Client] against a local fake v1 DJ port — byte-level, no network. */
class ShoutcastV1ClientTest {

    @Test fun login_line_is_user_colon_password_or_bare_password() {
        assertThat(ShoutcastV1Client.loginLine("madiundj", "secret")).isEqualTo("madiundj:secret\r\n")
        assertThat(ShoutcastV1Client.loginLine(" ", "secret")).isEqualTo("secret\r\n")
    }

    @Test fun ok2_login_sends_icy_headers_then_raw_audio() {
        ServerSocket(0).use { server ->
            val login = AtomicReference<String>()
            val headers = AtomicReference<List<String>>()
            val audio = AtomicReference<ByteArray>()
            val fake = Thread {
                server.accept().use { s ->
                    val input = s.getInputStream()
                    login.set(readLine(input))
                    s.getOutputStream().apply { write("OK2\r\nicy-caps:11\r\n\r\n".toByteArray()); flush() }
                    headers.set(generateSequence { readLine(input).takeIf { it.isNotEmpty() } }.toList())
                    audio.set(input.readNBytes(4))
                }
            }.apply { start() }

            val c = ShoutcastV1Client("127.0.0.1", server.localPort, user = "dj", password = "pw")
            c.connect(24, "My Show", "Speech")
            c.send(byteArrayOf(1, 2, 3, 4))
            fake.join(3_000)
            c.close()

            assertThat(login.get()).isEqualTo("dj:pw")
            assertThat(headers.get()).containsAtLeast("icy-name:My Show", "icy-genre:Speech", "icy-br:24", "icy-pub:1")
            assertThat(audio.get()).isEqualTo(byteArrayOf(1, 2, 3, 4))
        }
    }

    @Test fun invalid_password_is_an_auth_nak() {
        val err = connectAgainst("invalid password\r\n")
        assertThat(err).isInstanceOf(ProtocolException::class.java)
        assertThat(err!!.message).startsWith("auth NAK")
    }

    @Test fun http_reply_means_not_a_v1_port() {
        // What an sc_trans "Weblet" / any web port answers to a v1 login line.
        val err = connectAgainst("HTTP/1.0 400 Bad Request\r\n\r\n")
        assertThat(err).isInstanceOf(ProtocolException::class.java)
        assertThat(err!!.message).startsWith("v1 NAK")
    }

    @Test fun close_aborts_an_in_flight_login() {
        ServerSocket(0).use { server ->
            Thread { runCatching { server.accept() } }.apply { isDaemon = true; start() }
            val c = ShoutcastV1Client("127.0.0.1", server.localPort, user = "dj", password = "pw")
            val err = AtomicReference<Throwable?>()
            val connector = Thread { err.set(runCatching { c.connect(24, "n", "g") }.exceptionOrNull()) }
            connector.start()
            Thread.sleep(200)          // blocked reading the (never-sent) login reply
            c.close()
            connector.join(2_000)
            assertThat(connector.isAlive).isFalse()
            assertThat(err.get()).isNotNull()
        }
    }

    /** Fake server that answers the login line with [reply]; returns connect()'s failure. */
    private fun connectAgainst(reply: String): Throwable? = ServerSocket(0).use { server ->
        val fake = Thread {
            server.accept().use { s ->
                readLine(s.getInputStream())
                s.getOutputStream().apply { write(reply.toByteArray()); flush() }
                Thread.sleep(100)
            }
        }.apply { start() }
        val err = runCatching {
            ShoutcastV1Client("127.0.0.1", server.localPort, user = "dj", password = "pw").connect(24, "n", "g")
        }.exceptionOrNull()
        fake.join(3_000)
        err
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }
}
