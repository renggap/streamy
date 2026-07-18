package com.streamy.app.net

import java.io.InputStream
import java.net.Socket

/**
 * SHOUTcast **v1 source** client (the "ICY" source protocol). Verified against a live
 * DNAS2 v2.6.1 server: DNAS2 accepts this legacy source protocol and goes live with raw
 * MP3 bytes — no Ultravox framing required for MP3. (See docs/plans/2026-07-18-uvox-finding.md.)
 *
 * Wire sequence:
 *  1. connect TCP
 *  2. send `<password>\r\n`
 *  3. server replies `OK2\r\nicy-caps:<flags>\r\n\r\n`
 *  4. send icy headers (`icy-name`, `icy-genre`, `icy-br`, `icy-pub`, `icy-url`, …) + `\r\n\r\n`
 *  5. stream raw encoded frames via [send]
 *
 * AAC-over-v1 on DNAS2 is unverified (MP3 is the proven path); do not assume this client
 * works for AAC against DNAS2 without a live check. `send()` propagates IOException on a broken
 * socket so [StreamTransport] can reconnect.
 */
class IcyClient(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val mount: String,   // unused by the v1 login (DNAS2 maps by source creds/sid); kept for Icecast parity / future use
) {
    private var socket: Socket? = null
    private var out: java.io.OutputStream? = null
    private var inStream: InputStream? = null

    fun connect(bitrateKbps: Int, name: String, genre: String) {
        // Close any prior socket so a reconnect never leaks the old one (also nukes stale
        // out/inStream so a concurrent send() throws "client closed" instead of writing to a
        // dead stream).
        try { socket?.close() } catch (_: Throwable) {}
        socket = null; out = null; inStream = null

        val s = Socket(host, port).apply {
            tcpNoDelay = true
            keepAlive = true   // I2: let TCP probe black-holed peers eventually
            soTimeout = LOGIN_TIMEOUT_MS
        }
        try {
            socket = s
            val o = s.getOutputStream(); out = o
            val input = s.getInputStream(); inStream = input

            // 1) password line
            o.write((password + "\r\n").toByteArray(Charsets.US_ASCII)); o.flush()
            // 2) read OK2 + caps, up to the blank line
            val hello = readUntilBlankLine(input)
            if (!hello.contains("OK2", ignoreCase = true)) {
                throw ProtocolException("source login rejected: ${hello.take(120)}")
            }
            // 3) icy headers
            o.write(buildHeaderBlock(bitrateKbps, name, genre)); o.flush()
            // Server goes silent and waits for audio; drop the read timeout so send() isn't bounded by it.
            s.soTimeout = 0
        } catch (e: Throwable) {
            // Login failure: close the new socket so we don't leak it, clear state, rethrow
            // so StreamTransport's reconnect loop sees the error.
            try { s.close() } catch (_: Throwable) {}
            socket = null; out = null; inStream = null
            throw e
        }
    }

    // ponytail: no write-side timeout — keepAlive probes only fire after the OS's TCP_KEEPIDLE
    // (~2h on Linux), so a black-holed peer can wedge send() indefinitely. Wrap send() in
    // withTimeout once the transport owns a coroutine-based writer; deferred (Task: writer-timeout).
    fun send(frame: ByteArray) {
        val o = out ?: throw java.io.IOException("client closed")
        o.write(frame); o.flush()
    }

    fun close() {
        try { socket?.close() } catch (_: Throwable) {} finally { socket = null; out = null; inStream = null }
    }

    /** Read ASCII until a blank line (\r\n\r\n). Returns what was read. */
    private fun readUntilBlankLine(input: InputStream): String {
        val sb = StringBuilder()
        val buf = ByteArray(256)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            sb.append(String(buf, 0, n, Charsets.US_ASCII))
            if (sb.contains("\r\n\r\n") || sb.length > MAX_HELLO_LEN) break
        }
        return sb.toString()
    }

    companion object {
        private const val LOGIN_TIMEOUT_MS = 5_000
        private const val MAX_HELLO_LEN = 4_096

        /** Pure: builds the v1 icy-header block (testable without a socket). */
        fun buildHeaderBlock(bitrateKbps: Int, name: String, genre: String): ByteArray {
            val sb = StringBuilder()
            sb.append("icy-name:").append(name).append("\r\n")
            sb.append("icy-genre:").append(genre).append("\r\n")
            sb.append("icy-br:").append(bitrateKbps).append("\r\n")
            sb.append("icy-pub:1\r\n")
            sb.append("icy-url:http://streamy.app\r\n")
            sb.append("icy-irc:N/A\r\n")
            sb.append("icy-icq:N/A\r\n")
            sb.append("icy-aim:N/A\r\n")
            sb.append("\r\n")
            return sb.toString().toByteArray(Charsets.US_ASCII)
        }
    }
}
