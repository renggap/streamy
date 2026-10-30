package com.streamy.app.net

import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import kotlin.concurrent.thread

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
 * parses from the stream; and (2) the **admin** `/admin/metadata?mode=updinfo` GET with HTTP
 * Basic [user]:[password] — the call BUTT/Mixxx make, and the one a Liquidsoap harbor acts on.
 * (2) goes over a raw socket ([PlainHttp]): through HttpURLConnection the release build's
 * cleartext policy silently dropped it, which left harbor DJs with no title at all.
 */
class IcecastSourceClient(
    private val host: String,
    private val port: Int,
    private val mount: String,                      // e.g. "/" or "/stream"
    private val user: String,
    private val password: String,
    private val mime: String = "audio/mpeg",        // harbor verified with audio/mpeg (MP3)
) : SourceClient {

    private val stateLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var metaStream: IcyMetadataStream? = null
    // Latched by close(). The socket used to be published only after `200 OK`, so a Stop during
    // the handshake closed nothing; the handshake then completed into an authenticated source
    // that held the mount until the server timed it out — and the next Start got "in use".
    @Volatile private var closed = false
    // Last now-playing title, seeded into every new ICY stream so a reconnect keeps it.
    @Volatile private var lastTitle: String = ""

    override fun connect(bitrateKbps: Int, name: String, genre: String) {
        try { socket?.close() } catch (_: Throwable) {}
        socket = null; out = null; metaStream = null
        // Same as UvoxClient: resolve from the constructor params — inside a Socket.apply{} an
        // unqualified `port` binds to Socket.getPort() (=0, unconnected) and dials host:0.
        val addr = InetSocketAddress(host, port)
        val s = Socket()
        synchronized(stateLock) {
            if (closed) throw IOException("client closed")
            socket = s   // published before the blocking connect so close() can abort it
        }
        try {
            s.tcpNoDelay = true
            s.connect(addr, HANDSHAKE_TIMEOUT_MS)
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            val raw = s.getOutputStream()
            // android.util.Base64 (API 1+), NOT java.util.Base64 (API 26+; minSdk is 24).
            // NO_WRAP: without it Android inserts a newline every 76 chars, breaking the header.
            // UTF-8 credentials (RFC 7617 charset=UTF-8; identical bytes for ASCII).
            val auth = Base64.encodeToString("$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
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
            // UTF-8: Icecast/harbor store icy-name/genre bytes verbatim and serve them as UTF-8;
            // US_ASCII turned every non-ASCII character into '?'. ASCII input is byte-identical.
            raw.write(req.toByteArray(Charsets.UTF_8))
            raw.flush()

            val status = readLine(s.getInputStream())
            // Parse the numeric code, never substring-match: `HTTP/1.0 2000 Broken` or a body
            // line containing "200" must not pass. 2xx is success for a source handshake.
            val code = WireSafety.parseHttpStatus(status)
            if (code !in 200..299) {
                throw ProtocolException("icecast source rejected: $status")
            }
            s.soTimeout = 0  // streaming: writes own the socket; no read timeout

            val wrapper = IcyMetadataStream(raw, METAINT).apply { setTitle(lastTitle) }
            synchronized(stateLock) {
                if (closed) throw IOException("client closed")
                metaStream = wrapper
                out = wrapper
            }
            // A title set while connecting (or before a reconnect) only reached the inline
            // stream; the harbor needs the admin GET, sent once the mount exists. Off the
            // transport thread so a slow reply can't delay the audio.
            lastTitle.takeIf { it.isNotEmpty() }?.let { t ->
                thread(isDaemon = true, name = "ice-title") { adminUpdateMetadata(t) }
            }
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
        val safe = WireSafety.truncate(WireSafety.stripControlChars(title), WireSafety.MAX_TITLE)
        lastTitle = safe
        metaStream?.setTitle(safe)
        adminUpdateMetadata(safe)
    }

    override fun close() {
        val s: Socket?
        val o: OutputStream?
        synchronized(stateLock) {
            closed = true
            s = socket
            o = out
        }
        runCatching { o?.flush() }
        // Also aborts an in-flight connect()/status read on the transport thread.
        runCatching { s?.close() }
        socket = null; out = null; metaStream = null
    }

    /**
     * Admin metadata update (HTTP GET), best-effort. Only while the source is up: the server
     * applies it to the live mount, and before that there's no mount to update (connect()
     * replays it). Failures are ignored — the inline ICY title still covers Icecast.
     */
    private fun adminUpdateMetadata(title: String) {
        if (out == null) return
        runCatching {
            val auth = Base64.encodeToString("$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            PlainHttp.get(host, port, adminMetadataPath(mount, title), mapOf("Authorization" to "Basic $auth"))
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
            val raw = icyTitleBlock(t)
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

        /**
         * Pure: the unpadded `StreamTitle='...';` bytes. ICY has no escape syntax — players and
         * Icecast scan for the closing `';`, and a backslash escape (`\'`) is shown literally.
         * So only the one sequence that would end the field early (`';`) is defused, using a
         * typographic apostrophe. UTF-8, the de-facto ICY charset (Icecast is told via the
         * admin `charset=UTF-8`; harbor/players assume it). Pure → unit tested.
         */
        fun icyTitleBlock(title: String): ByteArray =
            ("StreamTitle='" + title.replace("';", "\u2019;") + "';").toByteArray(Charsets.UTF_8)
        private const val METAINT = 8_192  // bytes of audio between ICY metadata blocks

        /** Pure: the `/admin/metadata` update path for [mount]. */
        fun adminMetadataPath(mount: String, title: String): String =
            "/admin/metadata?mode=updinfo&charset=UTF-8&mount=" + URLEncoder.encode(mount, "UTF-8") +
                "&song=" + URLEncoder.encode(title, "UTF-8")
    }
}
