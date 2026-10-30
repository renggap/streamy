package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/** [PlainHttp] (raw-socket title-update GET) against a local fake server — no network. */
class PlainHttpTest {

    @Test fun get_sends_request_and_returns_status() {
        ServerSocket(0).use { server ->
            server.soTimeout = 3_000
            val lines = AtomicReference<List<String>>()
            val fake = Thread {
                server.accept().use { s ->
                    val r = s.getInputStream().bufferedReader()
                    lines.set(generateSequence { r.readLine()?.takeIf { it.isNotEmpty() } }.toList())
                    s.getOutputStream().apply { write("HTTP/1.0 200 OK\r\n\r\nok".toByteArray()); flush() }
                }
            }.apply { start() }

            val code = PlainHttp.get("127.0.0.1", server.localPort, "/admin.cgi?mode=updinfo&song=x",
                mapOf("Authorization" to "Basic abc"))
            fake.join(3_000)

            assertThat(code).isEqualTo(200)
            assertThat(lines.get().first()).isEqualTo("GET /admin.cgi?mode=updinfo&song=x HTTP/1.0")
            assertThat(lines.get()).contains("Authorization: Basic abc")
            assertThat(lines.get().any { it.startsWith("User-Agent: Mozilla") }).isTrue()
        }
    }

    @Test fun header_values_cannot_inject_lines() {
        val req = PlainHttp.request("h", 80, "/x\r\nEvil: 1", mapOf("Authorization" to "a\r\nEvil: 2"))
        assertThat(req.split("\r\n").none { it.startsWith("Evil") }).isTrue()
    }

    @Test fun icecast_admin_metadata_path_is_url_encoded() {
        // Spaces are %20, never `+` (form encoding): harbor/sc_trans title handlers keep a
        // literal `+` — same live finding as ShoutcastV1Client.adminPath.
        assertThat(IcecastSourceClient.adminMetadataPath("/", "Kajian & Tanya Jawab"))
            .isEqualTo("/admin/metadata?mode=updinfo&charset=UTF-8&mount=%2F&song=Kajian%20%26%20Tanya%20Jawab")
    }
}
