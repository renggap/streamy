package com.streamy.app.net

import com.streamy.app.data.DjProtocol
import java.io.InputStream
import java.net.Socket
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the DJ sub-protocol for AUTO profiles by sniffing the server: Ultravox (sc_trans /
 * sc_serv) replies with an `ACK:<key>` to the 0x1009 cipher request; Icecast / Liquidsoap harbor
 * replies `HTTP 200` to an Icecast SOURCE handshake. Same logic as the [DjProtocolProbeTest]
 * sniffer, callable from [com.streamy.app.service.StreamService] at connect time.
 *
 * Worst case ~2 probe round-trips (Ultravox timeout + Icecast status). Throws if neither matches.
 *
 * Results are cached per (host, port) — the first successful detection is reused across
 * reconnects within the process, so a flapping connection doesn't re-sniff (and re-write the
 * 2 KB probe payload) on every retry.
 */
object DjProtocolDetector {

    // (host, port) -> protocol, once a probe succeeds. Bounded in practice by the number of
    // distinct DJ servers a user configures; never cleared — a server changing protocol is
    // rarer than a wrong cache entry being harmless (the cached value still gets validated at
    // connect, which throws and falls back to a fresh sniff on the next start attempt).
    private val cache = ConcurrentHashMap<String, DjProtocol>()

    /** Invalidate a cached detection (e.g. after the user edits the profile / changes the server). */
    fun invalidate(host: String, port: Int) { cache.remove("$host:$port") }

    fun detect(host: String, port: Int, mount: String, user: String, password: String): DjProtocol {
        cache["$host:$port"]?.let { return it }
        val resolved = if (speaksUltravox(host, port)) {
            DjProtocol.ULTRAVOX
        } else if (icecastSourceAccepted(host, port, mount, user, password)) {
            DjProtocol.ICECAST
        } else {
            error("Could not determine DJ protocol at $host:$port (not Ultravox, not Icecast). " +
                "Set the profile's DJ protocol explicitly.")
        }
        cache["$host:$port"] = resolved
        return resolved
    }

    /** True if the port answers the Ultravox 0x1009 cipher request with an ACK. Read-only probe. */
    private fun speaksUltravox(host: String, port: Int): Boolean = try {
        val s = Socket(host, port).apply { tcpNoDelay = true; soTimeout = PROBE_MS }
        val out = s.getOutputStream(); val inp = s.getInputStream()
        out.write(UvoxClient.message(1, 0x009, "2.1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)))
        out.flush()
        val resp = readUltravox(inp)
        runCatching { s.close() }
        resp.startsWith("ACK")
    } catch (_: Throwable) { false }

    /** True if `SOURCE <mount> ICE/1.0` + Basic auth returns an HTTP 2xx. */
    private fun icecastSourceAccepted(host: String, port: Int, mount: String, user: String, password: String): Boolean = try {
        val s = Socket(host, port).apply { tcpNoDelay = true; soTimeout = PROBE_MS }
        val out = s.getOutputStream(); val inp = s.getInputStream()
        val auth = Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.US_ASCII))
        // Sanitize the mount the same way the real client does (header injection guard).
        val safeMount = WireSafety.headerValue(mount)
        val req = "SOURCE $safeMount ICE/1.0\r\nAuthorization: Basic $auth\r\n" +
            "Content-Type: audio/mpeg\r\nicy-name: Streamy\r\n icy-br: 128\r\n\r\n"
        out.write(req.toByteArray(Charsets.US_ASCII)); out.flush()
        // Keep the probe payload minimal (16 bytes of silence nudge) — some harbors emit 200
        // only after the first bytes; 16B is enough to trip the check without dumping 2 KB of
        // garbage into a production source path if this is the WRONG port.
        out.write(ByteArray(PROBE_NUDGE_BYTES)); out.flush()
        val status = readLine(inp)
        runCatching { s.close() }
        WireSafety.parseHttpStatus(status) in 200..299
    } catch (_: Throwable) { false }

    private fun readUltravox(inp: InputStream): String {
        val h = readN(inp, 6)
        val len = ((h[4].toInt() and 0xFF) shl 8) or (h[5].toInt() and 0xFF)
        val payload = readN(inp, len); readN(inp, 1)
        return String(payload, Charsets.US_ASCII).trimEnd(0.toChar())
    }
    private fun readLine(inp: InputStream): String {
        val sb = StringBuilder(); val buf = ByteArray(1)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < PROBE_MS) {
            val r = inp.read(buf); if (r <= 0) break
            val c = buf[0].toInt() and 0xFF; if (c == 0) break
            sb.append(c.toChar()); if (sb.endsWith("\r\n")) break
        }
        return sb.toString().trimEnd('\r', '\n')
    }
    private fun readN(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off); if (r < 0) break
            off += r
        }
        return buf
    }

    private const val PROBE_MS = 2_500
    private const val PROBE_NUDGE_BYTES = 16
}
