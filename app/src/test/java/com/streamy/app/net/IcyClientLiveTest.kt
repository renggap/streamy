package com.streamy.app.net

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Live verification of [IcyClient] against a real DNAS2 server. SKIPPED unless env vars are set,
 * so it never runs in the normal unit suite (no network dependency). Run manually:
 *
 *   STREAMY_LIVE_HOST=... STREAMY_LIVE_PORT=... STREAMY_LIVE_PASSWORD=... \
 *   STREAMY_LIVE_MP3=/tmp/streamy-silence.mp3 \
 *   ./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.IcyClientLiveTest" --rerun-tasks
 *
 * Asserts the server reports STREAMSTATUS=1 while the real Kotlin client is publishing.
 */
class IcyClientLiveTest {

    @Test fun publish_mp3_over_v1_goes_live() {
        val host = System.getenv("STREAMY_LIVE_HOST")
        val port = System.getenv("STREAMY_LIVE_PORT")?.toIntOrNull()
        val password = System.getenv("STREAMY_LIVE_PASSWORD")
        val mp3Path = System.getenv("STREAMY_LIVE_MP3") ?: "/tmp/streamy-silence.mp3"
        assumeTrue("STREAMY_LIVE_HOST/PORT/PASSWORD not set — skipping live test",
            host != null && port != null && password != null)

        val client = IcyClient(host!!, port!!, password!!, mount = "/stream")
        client.connect(bitrateKbps = 128, name = "Streamy Live Test", genre = "Speech")

        val mp3 = File(mp3Path)
        assumeTrue("$mp3Path missing — skipping (generate with ffmpeg)", mp3.exists())

        val running = intArrayOf(1)
        val sender = thread(name = "streamy-live-publish") {
            mp3.inputStream().use { input ->
                val buf = ByteArray(4 * 1024)
                while (running[0] == 1) {
                    val mark = input.available()
                    val n = input.read(buf)
                    if (n <= 0) break
                    client.send(buf.copyOf(n))
                    // ~realtime pacing: 4KB ~0.25s @ 128kbps
                    Thread.sleep(if (mark < buf.size) 200 else 30)
                }
            }
        }

        try {
            Thread.sleep(4_000) // let the source register
            val status = statsField(host!!, port!!, "STREAMSTATUS")
            println("IcyClientLiveTest: STREAMSTATUS=$status")
            check(status == "1") { "expected STREAMSTATUS=1 (live), got '$status'" }
        } finally {
            running[0] = 0
            sender.interrupt()
            client.close()
        }
    }

    private fun statsField(host: String, port: Int, tag: String): String? {
        return try {
            val xml = (URL("http://$host:$port/stats?sid=1").openConnection() as HttpURLConnection)
                .apply { connectTimeout = 5_000; readTimeout = 5_000 }
                .inputStream.use { it.readBytes() }
                .toString(Charsets.UTF_8)
            val lo = xml.indexOf("<$tag>") + tag.length + 2
            val hi = xml.indexOf("</$tag>")
            if (lo > tag.length + 1 && hi > lo) xml.substring(lo, hi) else null
        } catch (e: Throwable) {
            null
        }
    }
}
