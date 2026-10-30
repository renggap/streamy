package com.streamy.app.net

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * One-shot HTTP/1.0 GET over a raw socket, for the DJ title-update side channels (Icecast /
 * Liquidsoap `/admin/metadata`, SHOUTcast v1 `/admin.cgi`).
 *
 * Raw socket on purpose: the release network-security-config blocks cleartext for the HTTP
 * stack, which silently killed HttpURLConnection title pushes on every release build. These
 * endpoints are plain http by nature, and the same password already crosses the same server in
 * the clear on the source socket, so the policy protected nothing here — it only broke titles.
 */
object PlainHttp {
    private const val TIMEOUT_MS = 3_000
    private const val MAX_LINE = 512

    /** GET [pathAndQuery] (already URL-encoded) and return the HTTP status code, or -1. */
    fun get(host: String, port: Int, pathAndQuery: String, headers: Map<String, String> = emptyMap()): Int {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            s.getOutputStream().apply {
                write(request(host, port, pathAndQuery, headers).toByteArray(Charsets.UTF_8))
                flush()
            }
            return WireSafety.parseHttpStatus(readLine(s.getInputStream()))
        }
    }

    /**
     * Pure: the request text. "Mozilla" in the User-Agent is load-bearing — SHOUTcast v1 servers
     * historically refuse admin.cgi from non-browser agents (live probe used it too).
     */
    fun request(host: String, port: Int, pathAndQuery: String, headers: Map<String, String>): String =
        buildString {
            append("GET ").append(WireSafety.stripControlChars(pathAndQuery)).append(" HTTP/1.0\r\n")
            append("Host: ").append(WireSafety.headerValue(host)).append(':').append(port).append("\r\n")
            append("User-Agent: Mozilla/5.0 (compatible; Streamy)\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(WireSafety.headerValue(v)).append("\r\n") }
            append("\r\n")
        }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < MAX_LINE) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }
}
