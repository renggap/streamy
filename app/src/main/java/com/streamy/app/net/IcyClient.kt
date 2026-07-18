package com.streamy.app.net

import java.io.OutputStream
import java.net.Socket

/** Legacy HTTP/ICY source client. DNAS2 (v2) accepts it in legacy mode; Icecast2 accepts it. */
class IcyClient(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val mount: String,
) {
    private var socket: Socket? = null
    private var out: OutputStream? = null

    /** @param contentType "audio/mpeg" or "audio/aacp" */
    fun connect(contentType: String, bitrateKbps: Int, name: String, genre: String) {
        val s = Socket(host, port); socket = s; s.tcpNoDelay = true
        val req = buildHandshake(mount, "$host:$port", contentType, bitrateKbps, name, genre, password)
        val o = s.getOutputStream(); o.write(req); o.flush(); out = o
        val reader = s.getInputStream().bufferedReader()
        val status = reader.readLine() ?: throw ProtocolException("empty response")
        if (!status.contains("200")) throw ProtocolException("server said: $status")
        // Drain remaining response headers (read until blank line) so the socket's input
        // buffer doesn't hold stale header bytes for the lifetime of the source connection.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
    }

    fun send(frame: ByteArray) { out!!.write(frame); out!!.flush() }

    fun close() { try { socket?.close() } finally { socket = null; out = null } }

    companion object {
        fun buildHandshake(
            mount: String, host: String, contentType: String,
            bitrateKbps: Int, name: String, genre: String, password: String,
        ): ByteArray {
            val auth = java.util.Base64.getEncoder().encodeToString("source:$password".toByteArray())
            val sb = StringBuilder()
            sb.append("PUT ").append(mount).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(host).append("\r\n")
            sb.append("Authorization: Basic ").append(auth).append("\r\n")
            sb.append("Content-Type: ").append(contentType).append("\r\n")
            sb.append("ice-bitrate: ").append(bitrateKbps).append("\r\n")
            sb.append("ice-name: ").append(name).append("\r\n")
            sb.append("ice-genre: ").append(genre).append("\r\n")
            sb.append("ice-public: 1\r\n")
            sb.append("ice-url: https://streamy.app\r\n")
            sb.append("\r\n")
            return sb.toString().toByteArray(Charsets.US_ASCII)
        }
    }
}
