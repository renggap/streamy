package com.streamy.app.net

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Live reproduction probe for "title updates while live streaming" — env-gated (skipped unless
 * STREAMY_LIVE_HOST/PORT/PASSWORD are set; commits no creds).
 *
 * Exactly the app's SOURCE-mode flow: connect, stream MP3 continuously, then push a title
 * WHILE the audio is flowing (twice — the second update is the reported failure mode) and read
 * SONGTITLE back from the server's /stats each time.
 *
 *   STREAMY_LIVE_HOST=… STREAMY_LIVE_PORT=… STREAMY_LIVE_PASSWORD=… STREAMY_LIVE_SID=2 \
 *   STREAMY_LIVE_MP3=/tmp/uvox.mp3 STREAMY_LIVE_STATS_PORT=… \
 *   ./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.TitleUpdateLiveTest" --rerun-tasks
 */
class TitleUpdateLiveTest {

    @Test fun title_updates_while_live_streaming() {
        val host = System.getenv("STREAMY_LIVE_HOST")
        val port = System.getenv("STREAMY_LIVE_PORT")?.toIntOrNull()
        val password = System.getenv("STREAMY_LIVE_PASSWORD")
        val sid = System.getenv("STREAMY_LIVE_SID")?.toIntOrNull() ?: 1
        val mp3Path = System.getenv("STREAMY_LIVE_MP3") ?: "/tmp/uvox.mp3"
        val statsPort = System.getenv("STREAMY_LIVE_STATS_PORT")?.toIntOrNull() ?: port ?: -1
        assumeTrue("STREAMY_LIVE_HOST/PORT/PASSWORD not set — skipping", host != null && port != null && password != null)
        val mp3 = File(mp3Path)
        assumeTrue("$mp3Path missing — skipping", mp3.exists())

        val client = UvoxClient(host!!, port!!, sid, password!!, mime = "audio/mpeg")
        client.connect(bitrateKbps = 128, name = "Streamy Title Probe", genre = "Speech")

        val running = intArrayOf(1)
        val sender = thread(name = "title-probe-audio") {
            val frames = mp3.readBytes()
            var pos = 0
            while (running[0] == 1) {
                val n = minOf(4 * 1024, frames.size - pos)
                if (n <= 0) pos = 0
                else {
                    client.send(frames.copyOfRange(pos, pos + n))
                    pos = (pos + n) % frames.size
                }
                Thread.sleep(30)
            }
        }

        try {
            Thread.sleep(3_000) // audio flowing — the source is live
            println("TitleUpdateLiveTest: baseline SONGTITLE=${statsField(host, statsPort, sid, "SONGTITLE")}")

            // First update while live (what the UI's UPDATE button does).
            client.updateNowPlaying("Streamy Probe Alpha")
            val first = pollTitle(host, statsPort, sid, "Streamy Probe Alpha")
            println("TitleUpdateLiveTest: after 1st update while live → $first")

            Thread.sleep(3_000) // keep streaming
            // Second update while live — the reported failure ("cannot update while live streaming").
            client.updateNowPlaying("Streamy Probe Beta")
            val second = pollTitle(host, statsPort, sid, "Streamy Probe Beta")
            println("TitleUpdateLiveTest: after 2nd update while live → $second")

            check(first != null && first.contains("Streamy Probe Alpha")) {
                "1st title update while live did not reach SONGTITLE (last: $first)"
            }
            check(second != null && second.contains("Streamy Probe Beta")) {
                "2nd title update while live did not reach SONGTITLE (last: $second)"
            }
        } finally {
            running[0] = 0
            sender.interrupt()
            client.close()
        }
    }

    /** Poll /stats SONGTITLE for up to ~5 s until it contains [want]; returns the last value seen. */
    private fun pollTitle(host: String, port: Int, sid: Int, want: String): String? {
        var title: String? = null
        for (i in 1..10) {
            title = statsField(host, port, sid, "SONGTITLE")
            if (title != null && title.contains(want)) return title
            Thread.sleep(500)
        }
        return title
    }

    private fun statsField(host: String, port: Int, sid: Int, tag: String): String? = try {
        val xml = (URL("http://$host:$port/stats?sid=$sid").openConnection() as HttpURLConnection)
            .apply { connectTimeout = 5_000; readTimeout = 5_000 }
            .inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        Regex("<$tag>([^<]*)</$tag>").find(xml)?.groupValues?.get(1)
    } catch (e: Throwable) {
        println("statsField($tag) failed: $e")
        null
    }
}
