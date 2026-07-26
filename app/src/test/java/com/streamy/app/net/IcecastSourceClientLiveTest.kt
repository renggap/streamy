package com.streamy.app.net

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live verification of [IcecastSourceClient] against a real Icecast / Liquidsoap harbor. SKIPPED
 * unless env is set. Connects with `SOURCE <mount> ICE/1.0` + Basic auth, writes a few KB of
 * audio, and asserts the source registered (no exception) — the same path the sniffer confirmed
 * returns `HTTP/1.0 200 OK`.
 *
 *   STREAMY_ICE_HOST=... STREAMY_ICE_PORT=... STREAMY_ICE_USER=... STREAMY_ICE_PASSWORD=... \
 *   STREAMY_ICE_MOUNT=/  ./gradlew :app:testDebugUnitTest \
 *     --tests "com.streamy.app.net.IcecastSourceClientLiveTest" --rerun-tasks
 */
class IcecastSourceClientLiveTest {

    @Test fun source_connect_returns_200_and_accepts_audio() {
        val host = System.getenv("STREAMY_ICE_HOST")
        val port = System.getenv("STREAMY_ICE_PORT")?.toIntOrNull()
        val user = System.getenv("STREAMY_ICE_USER") ?: ""
        val pass = System.getenv("STREAMY_ICE_PASSWORD") ?: ""
        val mount = System.getenv("STREAMY_ICE_MOUNT")?.takeIf { it.isNotBlank() } ?: "/"
        assumeTrue("STREAMY_ICE_HOST/PORT/USER/PASSWORD not set — skipping",
            host != null && port != null && user.isNotEmpty() && pass.isNotEmpty())

        val client = IcecastSourceClient(host!!, port!!, mount, user, pass, mime = "audio/mpeg")
        client.connect(bitrateKbps = 128, name = "Streamy Ice Live", genre = "Speech")
        try {
            // Feed raw bytes; harbor relays whatever the source sends. A few KB is enough to prove
            // the source path is live (a rejection would throw on write or surface in /stats).
            val chunk = ByteArray(8 * 1024)
            repeat(5) { client.send(chunk) }
            println("IcecastSourceClientLiveTest: connected to $host:$port$mount, wrote ${chunk.size * 5} bytes")
        } finally {
            client.close()
        }
    }
}
