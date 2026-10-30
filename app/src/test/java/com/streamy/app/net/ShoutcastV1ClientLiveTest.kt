package com.streamy.app.net

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Live verification of the SHOUTcast v1 DJ title path against a real sc_trans (TRANSCast 2.x)
 * DJ port: connect as a DJ, stream MP3, push a now-playing title WHILE live, then poll the
 * DNAS `/stats` SONGTITLE until it lands. SKIPPED unless env is set (commits no creds).
 *
 * What it pins: the title the server airs is EXACTLY the typed title (spaces intact) — the
 * `+`-form-encoding regression aired "Kajian+Pagi" instead (see WireSafety.urlEncode).
 * sc_trans relays DJ metadata with its audio pipeline delay, so the poll allows ~60 s.
 *
 *   STREAMY_DJ_HOST=… STREAMY_DJ_PORT=<djport+1> STREAMY_DJ_USER=dj STREAMY_DJ_PASSWORD=… \
 *   STREAMY_DJ_ADMIN_PORT=<djport> STREAMY_DJ_STATS_URL=http://…:…/stats?sid=1 \
 *   ./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.ShoutcastV1ClientLiveTest" --rerun-tasks
 */
class ShoutcastV1ClientLiveTest {

    @Test fun dj_title_lands_on_air_verbatim() {
        val host = System.getenv("STREAMY_DJ_HOST")
        val port = System.getenv("STREAMY_DJ_PORT")?.toIntOrNull()
        val user = System.getenv("STREAMY_DJ_USER") ?: ""
        val pass = System.getenv("STREAMY_DJ_PASSWORD")
        val adminPort = System.getenv("STREAMY_DJ_ADMIN_PORT")?.toIntOrNull()
        val statsUrl = System.getenv("STREAMY_DJ_STATS_URL")
        assumeTrue("STREAMY_DJ_HOST/PORT/PASSWORD/ADMIN_PORT/STATS_URL not set — skipping",
            host != null && port != null && pass != null && adminPort != null && statsUrl != null)

        val mp3 = File(System.getenv("STREAMY_LIVE_MP3") ?: "/tmp/uvox.mp3")
        assumeTrue("${mp3.path} missing — skipping", mp3.exists())

        val title = "Kajian Pagi ${System.currentTimeMillis() % 100000}"
        val client = ShoutcastV1Client(host!!, port!!, user, pass!!, adminPort = adminPort!!)
        client.connect(bitrateKbps = 128, name = "Streamy v1 Live Probe", genre = "Speech")

        val running = intArrayOf(1)
        val sender = thread(name = "v1-live-audio") {
            val frames = mp3.readBytes()
            var pos = 0
            while (running[0] == 1) {
                val n = minOf(4 * 1024, frames.size - pos)
                if (n <= 0) pos = 0
                else {
                    client.send(frames.copyOfRange(pos, pos + n))
                    pos = (pos + n) % frames.size
                }
                Thread.sleep(250) // ~128 kbps real-time
            }
        }

        try {
            Thread.sleep(3_000) // DJ live before the first title push
            client.updateNowPlaying(title)
            var seen = ""
            for (i in 1..120) {   // sc_trans relays DJ metadata with its audio-pipeline delay
                seen = statsField(statsUrl!!) ?: ""
                if (seen == title) break
                Thread.sleep(500)
            }
            println("ShoutcastV1ClientLiveTest: sent $title, on-air SONGTITLE=$seen")
            check(seen == title) { "expected on-air SONGTITLE '$title', got '$seen'" }
        } finally {
            running[0] = 0
            sender.interrupt()
            client.close()
        }
    }

    private fun statsField(url: String): String? = try {
        val xml = (URL(url).openConnection() as HttpURLConnection)
            .apply { connectTimeout = 5_000; readTimeout = 5_000 }
            .inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        Regex("<SONGTITLE>([^<]*)</SONGTITLE>").find(xml)?.groupValues?.get(1)
    } catch (e: Throwable) {
        println("statsField failed: $e")
        null
    }
}
