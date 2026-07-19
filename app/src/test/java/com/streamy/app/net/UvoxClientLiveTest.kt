package com.streamy.app.net

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Live verification of [UvoxClient] against a real DNAS2 server. SKIPPED unless env is set.
 * Streams an ADTS AAC file (or any bytes) via the Ultravox 2.1 handshake with mime=audio/aacp
 * and asserts the server reports CONTENT=audio/aacp (the AAC path that v1 source mislabels).
 *
 *   STREAMY_LIVE_HOST=... STREAMY_LIVE_PORT=... STREAMY_LIVE_PASSWORD=... \
 *   STREAMY_LIVE_SID=1 STREAMY_LIVE_AAC=/tmp/uvox.aac \
 *   ./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.UvoxClientLiveTest" --rerun-tasks
 */
class UvoxClientLiveTest {

    @Test fun publish_aac_over_uvox_is_labeled_aacp() {
        val host = System.getenv("STREAMY_LIVE_HOST")
        val port = System.getenv("STREAMY_LIVE_PORT")?.toIntOrNull()
        val password = System.getenv("STREAMY_LIVE_PASSWORD")
        val sid = System.getenv("STREAMY_LIVE_SID")?.toIntOrNull() ?: 1
        val aacPath = System.getenv("STREAMY_LIVE_AAC") ?: "/tmp/uvox.aac"
        assumeTrue("STREAMY_LIVE_HOST/PORT/PASSWORD not set — skipping", host != null && port != null && password != null)

        val client = UvoxClient(host!!, port!!, sid, password!!, mime = "audio/aacp")
        client.connect(bitrateKbps = 64, name = "Streamy Uvox Live", genre = "Speech")
        val aac = File(aacPath)
        assumeTrue("$aacPath missing — skipping", aac.exists())

        val running = intArrayOf(1)
        val sender = thread(name = "uvox-live-publish") {
            aac.inputStream().use { input ->
                val buf = ByteArray(4 * 1024)
                while (running[0] == 1) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    client.send(buf.copyOf(n))
                    Thread.sleep(40)
                }
            }
        }

        try {
            Thread.sleep(4_000)
            val content = statsField(host, port, "CONTENT")
            println("UvoxClientLiveTest: CONTENT=$content")
            check(content == "audio/aacp") { "expected CONTENT=audio/aacp, got '$content'" }
        } finally {
            running[0] = 0
            sender.interrupt()
            client.close()
        }
    }

    private fun statsField(host: String, port: Int, tag: String): String? = try {
        val xml = (URL("http://$host:$port/stats?sid=1").openConnection() as HttpURLConnection)
            .apply { connectTimeout = 5_000; readTimeout = 5_000 }
            .inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        val lo = xml.indexOf("<$tag>") + tag.length + 2
        val hi = xml.indexOf("</$tag>")
        if (lo > tag.length + 1 && hi > lo) xml.substring(lo, hi) else null
    } catch (e: Throwable) { null }
}
