package com.streamy.app.net

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.InputStream
import java.net.Socket

/**
 * Live DJ **protocol sniffer**. Given a host/port/user/password/mount, determines which DJ
 * protocol that server speaks, so the same probe works across sc_trans/sc_serv (Ultravox),
 * Icecast / Liquidsoap harbor (Icecast HTTP source), and legacy SHOUTcast v1. Env-gated —
 * SKIPPED unless all vars are set, so it's safe in CI and commits no credentials.
 *
 *   STREAMY_DJ_HOST=... STREAMY_DJ_PORT=... STREAMY_DJ_USER=... STREAMY_DJ_PASSWORD=... \
 *   STREAMY_DJ_MOUNT=/stream  (default /stream) \
 *   ./gradlew :app:testDebugUnitTest --tests "com.streamy.app.net.DjProtocolProbeTest" -i
 *
 * Prints a per-protocol trace and a final verdict. Run it against any of your servers.
 */
class DjProtocolProbeTest {

    private val host: String? = System.getenv("STREAMY_DJ_HOST")
    private val port: Int? = System.getenv("STREAMY_DJ_PORT")?.toIntOrNull()
    private val user: String = System.getenv("STREAMY_DJ_USER") ?: ""
    private val pass: String = System.getenv("STREAMY_DJ_PASSWORD") ?: ""
    private val mount: String = System.getenv("STREAMY_DJ_MOUNT")?.takeIf { it.isNotBlank() } ?: "/stream"

    @Test fun sniff_dj_protocol() {
        assumeTrue(
            "STREAMY_DJ_HOST/PORT/USER/PASSWORD not set — skipping",
            host != null && port != null && user.isNotEmpty() && pass.isNotEmpty(),
        )
        val h = host!!; val p = port!!
        println("\n========== DJ SNIFF → $h:$p  mount=$mount  user=$user ==========")

        val ultravox = sniffUltravox(h, p)
        val icecast = sniffIcecast(h, p, mount, user, pass)
        val v1 = sniffV1(h, p, user, pass)

        println("\n--- VERDICT ---")
        println("Ultravox (sc_trans/sc_serv): $ultravox")
        println("Icecast HTTP source (harbor/Icecast): $icecast")
        println("v1 ICY (legacy SHOUTcast): $v1")
        val winner = when {
            ultravox.startsWith("SPEAKS") -> "ULTRAVOX (sc_trans / sc_serv) — use DJ-mode Ultravox client"
            icecast.startsWith("OK") -> "ICECAST HTTP SOURCE (Liquidsoap harbor / Icecast) — use Icecast source client"
            v1.startsWith("OK") -> "V1 ICY (legacy SHOUTcast) — use v1 source client"
            else -> "NONE matched — check host/port/credentials/mount"
        }
        println(">>> $winner <<<")
    }

    /** Returns "SPEAKS — auth ACK" / "SPEAKS — auth NAK: <resp>" / "silent (not Ultravox)". */
    private fun sniffUltravox(h: String, p: Int): String {
        return try {
            val s = Socket(h, p).apply { tcpNoDelay = true; soTimeout = 4_000 }
            val out = s.getOutputStream(); val inp = s.getInputStream()
            writeUltravox(out, 1, 0x009, "2.1")
            val cipher = readUltravox(inp)
            runCatching { s.close() }
            if (!cipher.startsWith("ACK")) return "silent (cipher resp=\"$cipher\")"
            val key = cipher.substringAfter("ACK:").trimEnd(0.toChar()).toByteArray(Charsets.US_ASCII)
            // DJ auth: djlogin in the UID field, djpassword as the blob (both XTEA with returned key)
            val encUid = UvoxXtea.encipher(user.toByteArray(Charsets.US_ASCII), key)
            val encBlob = UvoxXtea.encipher(pass.toByteArray(Charsets.US_ASCII), key)
            val s2 = Socket(h, p).apply { tcpNoDelay = true; soTimeout = 4_000 }
            val o2 = s2.getOutputStream(); val i2 = s2.getInputStream()
            writeUltravox(o2, 1, 0x009, "2.1"); readUltravox(i2) // fresh cipher per connection
            writeUltravox(o2, 1, 0x001, "2.1:1:$encUid:$encBlob")
            val auth = readUltravox(i2)
            runCatching { s2.close() }
            println("  [Ultravox] cipher=\"$cipher\"  auth=\"$auth\"")
            if (auth.startsWith("ACK")) "SPEAKS — DJ auth ACK ✓" else "SPEAKS — auth NAK ($auth) — protocol right, creds/calendar may be wrong"
        } catch (e: Throwable) { println("  [Ultravox] ${e.javaClass.simpleName}: ${e.message}"); "silent (${e.javaClass.simpleName})" }
    }

    /** Returns "OK (HTTP 200)" / "auth failed (401)" / "mount not registered (404)" / "silent". */
    private fun sniffIcecast(h: String, p: Int, mount: String, user: String, pass: String): String {
        return try {
            val s = Socket(h, p).apply { tcpNoDelay = true; soTimeout = 5_000 }
            val out = s.getOutputStream(); val inp = s.getInputStream()
            val auth = java.util.Base64.getEncoder().encodeToString("$user:$pass".toByteArray(Charsets.US_ASCII))
            val req = "SOURCE $mount ICE/1.0\r\n" +
                "Authorization: Basic $auth\r\n" +
                "Content-Type: audio/mpeg\r\n" +
                "icy-name: Streamy Sniff\r\n icy-genre: Speech\r\n icy-pub: 1\r\n icy-br: 128\r\n\r\n"
            out.write(req.toByteArray(Charsets.US_ASCII)); out.flush()
            out.write(ByteArray(2048)); out.flush() // nudge harbor with a little "audio"
            val resp = readHttpHead(inp)
            runCatching { s.close() }
            println("  [Icecast] SOURCE $mount → ${resp.first} ${resp.second}")
            when (resp.first) {
                200 -> "OK (HTTP 200) — Icecast source works on $mount ✓"
                401, 403 -> "auth failed (${resp.first}) — protocol right, bad user/pass"
                404 -> "mount $mount not registered (404) — port is harbor but mount name is wrong/inactive"
                else -> "HTTP ${resp.first} ${resp.second}"
            }
        } catch (e: Throwable) { println("  [Icecast] ${e.javaClass.simpleName}: ${e.message}"); "silent (${e.javaClass.simpleName})" }
    }

    /** Returns "OK (OK2)" / "rejected" / "silent". */
    private fun sniffV1(h: String, p: Int, user: String, pass: String): String {
        return try {
            val s = Socket(h, p).apply { tcpNoDelay = true; soTimeout = 5_000 }
            val out = s.getOutputStream(); val inp = s.getInputStream()
            out.write("$user:$pass\r\n".toByteArray(Charsets.US_ASCII)); out.flush()
            val line = readLine(inp, 3_000)
            runCatching { s.close() }
            println("  [v1] login → \"$line\"")
            when {
                line.startsWith("OK2") -> "OK (OK2) — v1 ICY source works ✓"
                line.isEmpty() -> "silent"
                else -> "rejected: \"$line\""
            }
        } catch (e: Throwable) { println("  [v1] ${e.javaClass.simpleName}: ${e.message}"); "silent (${e.javaClass.simpleName})" }
    }

    // ---- Ultravox framing (reuses UvoxClient.message + UvoxXtea) ----
    private fun writeUltravox(out: java.io.OutputStream, cls: Int, type: Int, payload: String) {
        val p = payload.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        out.write(UvoxClient.message(cls, type, p)); out.flush()
    }
    private fun readUltravox(inp: InputStream): String {
        val h = readN(inp, 6)
        val len = ((h[4].toInt() and 0xFF) shl 8) or (h[5].toInt() and 0xFF)
        val payload = readN(inp, len); readN(inp, 1)
        return String(payload, Charsets.US_ASCII).trimEnd(0.toChar())
    }

    // ---- HTTP / text readers ----
    private fun readHttpHead(inp: InputStream): Pair<Int, String> {
        val status = readLine(inp, 3_500)
        // e.g. "HTTP/1.0 200 OK" or "HTTP/1.0 404 Not found"
        val parts = status.split(" ", limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull() ?: -1
        val reason = parts.drop(2).joinToString(" ").ifBlank { status }
        return code to reason
    }
    private fun readLine(inp: InputStream, timeoutMs: Long): String {
        val sb = StringBuilder(); val buf = ByteArray(1)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val r = inp.read(buf)
            if (r <= 0) break
            val c = buf[0].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
            if (sb.endsWith("\r\n")) break
        }
        return sb.toString().trimEnd('\r', '\n')
    }
    private fun readN(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw java.io.IOException("socket closed mid-message")
            off += r
        }
        return buf
    }
}
