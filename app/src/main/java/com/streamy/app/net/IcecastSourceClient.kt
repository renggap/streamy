package com.streamy.app.net

import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

/**
 * Icecast / Liquidsoap-harbor **HTTP source** client — the second DJ sub-mode (the first being
 * Ultravox [UvoxClient] for sc_trans). Live-verified against a Liquidsoap `input.harbor` on
 * az1.radioislam.my.id:8035 with mount `/`: `SOURCE <mount> ICE/1.0` + HTTP Basic auth → `200 OK`.
 *
 * Unlike Ultravox, this is plain text HTTP-style: one request line + headers, then the raw
 * encoded audio (MP3) is written as the response body. There is no binary framing, no cipher.
 *
 * Now-playing is pushed two ways: (1) **ICY inline metadata** — the source declares
 * `icy-metaint` and interleaves `StreamTitle='...'\0` blocks every N audio bytes, which Icecast
 * and harbor parse from the stream (the only source-side mechanism a harbor supports); and
 * (2) a best-effort **Icecast admin** `/admin/metadata` GET, which works on real Icecast servers
 * (harbor 404s it — ignored). Auth is HTTP Basic [user]:[password].
 */
class IcecastSourceClient(
    private val host: String,
    private val port: Int,
    private val mount: String,                      // e.g. "/" or "/stream"
    private val user: String,
    private val password: String,
    private val mime: String = "audio/mpeg",        // harbor verified with audio/mpeg (MP3)
) : SourceClient {

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var metaStream: IcyMetadataStream? = null

    override fun connect(bitrateKbps: Int, name: String, genre: String) {
        try { socket?.close() } catch (_: Throwable) {}
        socket = null; out = null; metaStream = null
        // Same as UvoxClient: unqualified `port` inside Socket.apply{} binds to Socket.getPort()
        // (=0, unconnected) instead of the constructor param — connect would hit host:0.
        val addr = InetSocketAddress(host, port)
        val s = Socket().apply {
            tcpNoDelay = true
            connect(addr, HANDSHAKE_TIMEOUT_MS)
            soTimeout = HANDSHAKE_TIMEOUT_MS
        }
        try {
            val raw = s.getOutputStream()
            // android.util.Base64 (API 1+), NOT java.util.Base64 (API 26+; minSdk is 24).
            // NO_WRAP: without it Android inserts a newline every 76 chars, breaking the header.
            val auth = Base64.encodeToString("$user:$password".toByteArray(Charsets.US_ASCII), Base64.NO_WRAP)
            // Header values are user-controlled (profile name/mount): strip CR/LF + NUL so a
            // malicious name can't inject extra header lines, and truncate to the wire cap.
            val safeMount = WireSafety.headerValue(mount)
            val safeName = WireSafety.headerValue(name)
            val safeGenre = WireSafety.headerValue(genre)
            val req = StringBuilder().apply {
                append("SOURCE ").append(safeMount).append(" ICE/1.0\r\n")
                append("Authorization: Basic ").append(auth).append("\r\n")
                append("Content-Type: ").append(mime).append("\r\n")
                append("icy-name: ").append(safeName).append("\r\n")
                append("icy-genre: ").append(safeGenre).append("\r\n")
                append("icy-url: http://streamy.app\r\n")
                append("icy-pub: 1\r\n")
                append("icy-br: ").append(bitrateKbps).append("\r\n")
                append("icy-metaint: ").append(METAINT).append("\r\n")  // we'll inline StreamTitle blocks
                append("\r\n")
            }.toString()
            raw.write(req.toByteArray(Charsets.US_ASCII))
            raw.flush()

            val status = readLine(s.getInputStream())
            // Parse the numeric code, never substring-match: `HTTP/1.0 2000 Broken` or a body
            // line containing "200" must not pass. 2xx is success for a source handshake.
            val code = WireSafety.parseHttpStatus(status)
            if (code !in 200..299) {
                throw ProtocolException("icecast source rejected: $status")
            }
            s.soTimeout = 0  // streaming: writes own the socket; no read timeout

            val wrapper = IcyMetadataStream(raw, METAINT)
            socket = s
            metaStream = wrapper
            out = wrapper
        } catch (e: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            socket = null; out = null; metaStream = null
            throw e
        }
    }

    /** Write one encoded frame (raw MP3 bytes). The wrapper flushes + injects ICY metadata. */
    override fun send(frame: ByteArray) {
        val o = out ?: throw IOException("client closed")
        o.write(frame)
        o.flush()
    }

    /** Push now-playing: set the ICY inline title (next injected block) + best-effort Icecast admin. */
    override fun updateNowPlaying(title: String) {
        // CR/LF stripped so a title can't break out of the StreamTitle='...' block; truncated
        // so the padded block stays far under the 4080-byte ICY metadata limit.
        val safe = WireSafety.stripControlChars(title).take(WireSafety.MAX_TITLE)
        metaStream?.setTitle(safe)
        runCatching { adminUpdateMetadata(safe) }  // works on Icecast; harbor 404s — ignored
    }

    override fun close() {
        runCatching { out?.flush() }
        runCatching { socket?.close() }
        socket = null; out = null; metaStream = null
    }

    /** Icecast admin metadata update (HTTP GET). Liquidsoap harbor has no admin endpoint — ignore failure. */
    private fun adminUpdateMetadata(title: String) {
        val q = "mode=updinfo&mount=" + URLEncoder.encode(mount, "UTF-8") +
            "&song=" + URLEncoder.encode(title, "UTF-8")
        val conn = (URL("http://$host:$port/admin/metadata?$q").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000; readTimeout = 3_000
            val auth = Base64.encodeToString("$user:$password".toByteArray(Charsets.US_ASCII), Base64.NO_WRAP)
            setRequestProperty("Authorization", "Basic $auth")
        }
        try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        val buf = ByteArray(1)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < HANDSHAKE_TIMEOUT_MS) {
            val r = input.read(buf)
            if (r <= 0) break
            val c = buf[0].toInt() and 0xFF
            if (c == 0) break
            sb.append(c.toChar())
            if (sb.endsWith("\r\n")) break
        }
        return sb.toString().trimEnd('\r', '\n')
    }

    /**
     * Counts audio bytes written and, every [metaint] bytes, injects an ICY metadata block
     * (`StreamTitle='...';\0`, padded to a 16-byte boundary, prefixed by a length byte). This is
     * the standard SHOUTcast/Icecast inline-metadata interleaving the server parses from the stream.
     */
    private class IcyMetadataStream(
        private val delegate: OutputStream,
        private val metaint: Int,
    ) : OutputStream() {
        @Volatile private var title: String = ""
        private var count: Int = 0  // audio bytes written since the last metadata block

        fun setTitle(t: String) { title = t }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remaining = len
            while (remaining > 0) {
                val chunk = minOf(remaining, metaint - count)
                delegate.write(b, o, chunk)
                count += chunk
                o += chunk
                remaining -= chunk
                if (count >= metaint) {
                    writeMetadataBlock()
                    count = 0
                }
            }
        }

        private fun writeMetadataBlock() {
            val t = title
            // length byte = (metadata byte-length) / 16; 0 means "no metadata this block".
            if (t.isEmpty()) {
                delegate.write(0)
                return
            }
            val raw = ("StreamTitle='" + t.replace("\\", "\\\\").replace("'", "\\'") + "';")
                .toByteArray(Charsets.US_ASCII)
            val paddedLen = ((raw.size + 15) / 16) * 16
            val block = ByteArray(paddedLen)
            System.arraycopy(raw, 0, block, 0, raw.size)
            delegate.write(paddedLen / 16)
            delegate.write(block)
        }

        override fun flush() = delegate.flush()
        override fun close() { delegate.flush(); delegate.close() }
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 6_000
        private const val METAINT = 8_192  // bytes of audio between ICY metadata blocks
    }
}
