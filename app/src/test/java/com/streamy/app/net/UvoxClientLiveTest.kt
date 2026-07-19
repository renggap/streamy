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

    @Test fun publish_aac_over_uvox_is_labeled_aacp_with_correct_bitrate() {
        val host = System.getenv("STREAMY_LIVE_HOST")
        val port = System.getenv("STREAMY_LIVE_PORT")?.toIntOrNull()
        val password = System.getenv("STREAMY_LIVE_PASSWORD")
        val sid = System.getenv("STREAMY_LIVE_SID")?.toIntOrNull() ?: 1
        val aacPath = System.getenv("STREAMY_LIVE_AAC") ?: "/tmp/uvox.aac"
        assumeTrue("STREAMY_LIVE_HOST/PORT/PASSWORD not set — skipping", host != null && port != null && password != null)

        // Strip ADTS -> raw AUs, exactly as the app's AacEncoder emits (raw). UvoxClient.send
        // re-wraps each AU in ADTS — without that, DNAS drops AAC after ~2s and shows bitrate 0.
        val rawAus = stripAdts(File(aacPath).readBytes())
        assumeTrue("$aacPath missing or no AAC frames — skipping", rawAus.isNotEmpty())

        val client = UvoxClient(host!!, port!!, sid, password!!, mime = "audio/aacp", sampleRateHz = 44_100, channels = 1)
        client.connect(bitrateKbps = 64, name = "Streamy Uvox Live", genre = "Speech")

        val running = intArrayOf(1)
        val sender = thread(name = "uvox-live-publish") {
            var i = 0
            while (running[0] == 1) {
                client.send(rawAus[i % rawAus.size])
                i++
                Thread.sleep(23) // ~43 AU/sec (mic frame rate)
            }
        }

        try {
            Thread.sleep(4_000)
            val content = statsField(host, port, "CONTENT")
            val bitrate = statsField(host, port, "BITRATE")
            println("UvoxClientLiveTest: CONTENT=$content BITRATE=$bitrate")
            check(content == "audio/aacp") { "expected CONTENT=audio/aacp, got '$content'" }
            check(bitrate == "64") { "expected BITRATE=64 (ADTS fix), got '$bitrate'" }
        } finally {
            running[0] = 0
            sender.interrupt()
            client.close()
        }
    }

    /** Split an ADTS AAC byte stream into its raw access units (drop the ADTS headers). */
    private fun stripAdts(adts: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 7 <= adts.size) {
            if ((adts[i].toInt() and 0xFF) == 0xFF && (adts[i + 1].toInt() and 0xFF and 0xF6) == 0xF0) {
                val fl = ((adts[i + 3].toInt() and 0xFF and 0x3) shl 11) or
                    ((adts[i + 4].toInt() and 0xFF) shl 3) or
                    ((adts[i + 5].toInt() and 0xFF) ushr 5)
                val h = if ((adts[i + 1].toInt() and 0x1) == 1) 7 else 9
                if (fl in 1..(adts.size - i)) {
                    out.add(adts.copyOfRange(i + h, i + fl))
                    i += fl
                } else i++
            } else i++
        }
        return out
    }

    @Test fun publish_mp3_and_push_metadata_via_uvox_0x902() {
        val host = System.getenv("STREAMY_LIVE_HOST")
        val port = System.getenv("STREAMY_LIVE_PORT")?.toIntOrNull()
        val password = System.getenv("STREAMY_LIVE_PASSWORD")
        val sid = System.getenv("STREAMY_LIVE_SID")?.toIntOrNull() ?: 1
        val mp3Path = System.getenv("STREAMY_LIVE_MP3") ?: "/tmp/uvox.mp3"
        assumeTrue("STREAMY_LIVE_HOST/PORT/PASSWORD not set — skipping", host != null && port != null && password != null)

        val client = UvoxClient(host!!, port!!, sid, password!!, mime = "audio/mpeg")
        client.connect(bitrateKbps = 128, name = "Streamy Uvox MP3 Meta", genre = "Speech")
        val mp3 = File(mp3Path)
        assumeTrue("$mp3Path missing — skipping", mp3.exists())

        val running = intArrayOf(1)
        val sender = thread(name = "uvox-live-mp3-publish") {
            mp3.inputStream().use { input ->
                val buf = ByteArray(4 * 1024)
                while (running[0] == 1) {
                    val mark = input.available()
                    val n = input.read(buf)
                    if (n <= 0) break
                    client.send(buf.copyOf(n))
                    Thread.sleep(if (mark < buf.size) 200 else 30)
                }
            }
        }

        try {
            Thread.sleep(3_000) // let the source register
            client.updateNowPlaying("Streamy Unit Title")
            // Server applies the SONGTITLE update synchronously on the next flush; poll briefly.
            var title: String? = null
            for (i in 1..10) {
                title = statsField(host, port, "SONGTITLE")
                if (title != null && title.contains("Streamy Unit Title")) break
                Thread.sleep(500)
            }
            println("UvoxClientLiveTest: SONGTITLE=$title")
            check(title != null && title.contains("Streamy Unit Title")) {
                "expected SONGTITLE to contain 'Streamy Unit Title', got '$title'"
            }
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
