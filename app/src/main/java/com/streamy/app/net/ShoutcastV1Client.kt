package com.streamy.app.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * SHOUTcast **v1** source client — the legacy DJ login sc_trans2 exposes and Centova panels
 * advertise as "Protocol: Shoutcast (v1)". Live-verified against centova1.radioislam.my.id
 * (sc_trans2): the panel lists port 8092, the login is accepted on 8092 + 1 = 8093.
 *
 * Wire: one text line `djlogin:djpassword\r\n` (or just the password when [user] is blank) →
 * `OK2\r\nicy-caps:11\r\n\r\n` on success, `invalid password\r\n` on a bad login. Then `icy-*`
 * header lines + a blank line, then raw MP3 bytes. No framing, no cipher.
 *
 * [port] is the port this client DIALS — v1 sources connect to the panel/listener port + 1;
 * the caller does that arithmetic. No in-stream now-playing: v1 metadata needs the server's
 * admin password, which a DJ doesn't have, so [updateNowPlaying] stays the default no-op.
 */
class ShoutcastV1Client(
    private val host: String,
    private val port: Int,
    private val user: String,
    private val password: String,
) : SourceClient {

    private val stateLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    // Latched by close(): a Stop mid-handshake must abort it, not let it finish into an
    // authenticated source that holds the DJ slot until the server times it out.
    @Volatile private var closed = false

    override fun connect(bitrateKbps: Int, name: String, genre: String) {
        try { socket?.close() } catch (_: Throwable) {}
        socket = null; out = null
        // Resolve from the constructor params, never inside Socket().apply{} (port would bind
        // to Socket.getPort() = 0) — same trap as UvoxClient/IcecastSourceClient.
        val addr = InetSocketAddress(host, port)
        val s = Socket()
        synchronized(stateLock) {
            if (closed) throw IOException("client closed")
            socket = s   // published before the blocking connect so close() can abort it
        }
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(addr, HANDSHAKE_TIMEOUT_MS)
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            val o = s.getOutputStream()
            val input = s.getInputStream()

            // CR/LF/NUL stripped: a credential can't smuggle a second line into the login.
            val u = WireSafety.stripControlChars(user)
            val p = WireSafety.stripControlChars(password)
            o.write(loginLine(u, p).toByteArray(Charsets.UTF_8))
            o.flush()

            val status = readLine(input)
            if (!status.startsWith("OK")) {
                // "invalid password" is a real v1 DJ port refusing the login; anything else
                // (an HTTP status line, EOF) means this isn't a v1 port at all. The "auth NAK"
                // prefix lets the UI and the fallback tell the two apart.
                throw ProtocolException(
                    if (status.contains("invalid password", ignoreCase = true)) "auth NAK: $status"
                    else "v1 NAK: ${status.ifEmpty { "<no reply>" }}",
                )
            }
            // Drain the rest of the OK2 header block (icy-caps:…) up to its blank line. Short
            // cap: a server that ends after the status line must not stall the start for the
            // full handshake timeout.
            s.soTimeout = DRAIN_TIMEOUT_MS
            try {
                while (readLine(input).isNotEmpty()) Unit
            } catch (_: SocketTimeoutException) {
            }

            val headers = StringBuilder().apply {
                append("icy-name:").append(WireSafety.headerValue(name)).append("\r\n")
                append("icy-genre:").append(WireSafety.headerValue(genre)).append("\r\n")
                append("icy-url:http://streamy.app\r\n")
                append("icy-pub:1\r\n")
                append("icy-br:").append(bitrateKbps).append("\r\n")
                append("content-type:audio/mpeg\r\n")
                append("\r\n")
            }.toString()
            o.write(headers.toByteArray(Charsets.UTF_8))
            o.flush()

            s.soTimeout = 0  // streaming: writes own the socket; no read timeout
            synchronized(stateLock) {
                if (closed) throw IOException("client closed")
                out = o
            }
        } catch (e: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            socket = null; out = null
            throw e
        }
    }

    /** Write one encoded MP3 frame, raw (v1 has no framing). */
    override fun send(frame: ByteArray) {
        val o = out ?: throw IOException("client closed")
        o.write(frame)
        o.flush()
    }

    override fun close() {
        val s: Socket?
        synchronized(stateLock) {
            closed = true
            s = socket
        }
        // Also aborts an in-flight connect()/login read on the transport thread.
        runCatching { s?.close() }
        socket = null; out = null
    }

    /** One CRLF-terminated line (ASCII), bounded by the handshake soTimeout and [MAX_LINE]. */
    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < MAX_LINE) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 6_000
        private const val DRAIN_TIMEOUT_MS = 500
        private const val MAX_LINE = 512

        /** Pure: the v1 login line — `user:password`, or the bare password when there's no user. */
        fun loginLine(user: String, password: String): String =
            (if (user.isBlank()) password else "$user:$password") + "\r\n"
    }
}
