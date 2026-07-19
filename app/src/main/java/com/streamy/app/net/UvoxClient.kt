package com.streamy.app.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * SHOUTcast 2 / Ultravox 2.1 source client. Live-verified against DNAS2 2.6.1 for BOTH
 * audio/mpeg (MP3, data msg 0x7000) and audio/aacp (AAC, data msg 0x8003): the server reports
 * the correct CONTENT type — AAC no longer gets mislabeled as MP3 (the v1-source failure).
 * See docs/plans/2026-07-18-uvox-finding.md.
 *
 * Handshake (every step ACKed; NAK -> ProtocolException):
 *   0x1009 Request Cipher  -> key (DNAS2 returns "foobar")
 *   0x1001 Authenticate    -> "2.1:<sid>:<xtea(uid)>:<xtea(password)>" (uid empty)
 *   0x1040 Stream Mime Type, 0x1002 Setup, 0x1003 Buffer, 0x1100..0x1103 ICY config,
 *   0x1008 Max Payload (16377), 0x1004 Standby -> "ACK:Data transfer mode"
 * Then each [send] wraps raw encoded bytes in a data message (class 7 type 0 for MP3,
 * class 8 type 3 for AAC/AACP) and writes it.
 *
 * Ultravox gets the codec from the mime declaration, so raw (ADTS-stripped) AAC frames are
 * accepted as-is — the app's [com.streamy.app.audio.AacEncoder] raw output works directly.
 */
class UvoxClient(
    private val host: String,
    private val port: Int,
    private val sid: Int,
    private val password: String,
    private val mime: String, // "audio/mpeg" or "audio/aacp"
) : SourceClient {

    private val dataClass: Int
    private val dataType: Int

    init {
        if (mime == "audio/aacp" || mime == "audio/aac") { dataClass = 8; dataType = 0x003 } // AACP
        else { dataClass = 7; dataType = 0x000 } // MP3
    }

    private var socket: Socket? = null
    private var out: OutputStream? = null

    override fun connect(bitrateKbps: Int, name: String, genre: String) {
        // Close any prior socket so a reconnect never leaks.
        try { socket?.close() } catch (_: Throwable) {}
        socket = null; out = null
        val s = Socket(host, port).apply {
            tcpNoDelay = true
            keepAlive = true
            soTimeout = HANDSHAKE_TIMEOUT_MS
        }
        try {
            socket = s
            val o = s.getOutputStream(); out = o

            // 1) Request Cipher -> key (DNAS2 returns "foobar").
            val cipherResp = request(1, 0x009, "2.1")
            if (!cipherResp.startsWith("ACK")) throw ProtocolException("cipher NAK: $cipherResp")
            val cipherKey = cipherResp.substringAfter("ACK:").trimEnd(NULL_CHAR)
                .ifEmpty { "foobar" }.toByteArray(Charsets.US_ASCII)

            // 2) Authenticate: UID empty, AuthBlob = password, both XTEA-enciphered.
            val encUid = UvoxXtea.encipher(ByteArray(0), cipherKey)
            val encBlob = UvoxXtea.encipher(password.toByteArray(Charsets.US_ASCII), cipherKey)
            val authResp = request(1, 0x001, "2.1:$sid:$encUid:$encBlob")
            if (!authResp.startsWith("ACK")) throw ProtocolException("auth NAK: $authResp")

            // 3) Stream config (order per spec).
            requireAck(request(1, 0x040, mime), "mime")
            requireAck(request(1, 0x002, "$bitrateKbps:$bitrateKbps"), "setup")
            requireAck(request(1, 0x003, "2048:2048"), "buffer")
            requireAck(request(1, 0x100, name), "icy-name")
            requireAck(request(1, 0x101, genre), "icy-genre")
            requireAck(request(1, 0x102, "http://streamy.app"), "icy-url")
            requireAck(request(1, 0x103, "1"), "icy-pub")
            requireAck(request(1, 0x008, "16377:16377"), "max-payload")

            // 4) Standby -> "ACK:Data transfer mode".
            val standby = request(1, 0x004, null)
            if (!standby.startsWith("ACK")) throw ProtocolException("standby NAK: $standby")

            s.soTimeout = 0 // streaming: writes own the socket; no read timeout
        } catch (e: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            socket = null; out = null
            throw e
        }
    }

    /** Wrap one encoded frame in a data message and write it. */
    override fun send(frame: ByteArray) {
        val o = out ?: throw IOException("client closed")
        o.write(message(dataClass, dataType, frame))
        o.flush()
    }

    override fun close() {
        runCatching { if (out != null) out!!.write(message(1, 0x005, ByteArray(0))) } // Terminate
        runCatching { out?.flush() }
        runCatching { socket?.close() }
        socket = null; out = null
    }

    /**
     * Admin-free in-stream metadata via Ultravox class 3 / type 0x902. Live-verified against
     * DNAS2 2.6.1: one message with payload `[MetadataID u16 BE][Span u16 BE][Index u16 BE][XML]`
     * updates SONGTITLE with NO admin creds. `<TIT2>title</TIT2>` alone sets SONGTITLE = title.
     * Best-effort: no-op if not connected. The frame trailer added by [message] is correct —
     * do NOT add a NUL inside the payload.
     */
    fun updateNowPlaying(title: String) {
        val xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\" ?><metadata><TIT2>"
            + title.xmlEsc() + "</TIT2></metadata>").toByteArray(Charsets.UTF_8)
        val payload = ByteArray(6 + xml.size)
        payload[1] = 1; payload[3] = 1; payload[5] = 1 // MetadataID=1, Span=1, Index=1 (BE u16, high byte 0)
        System.arraycopy(xml, 0, payload, 6, xml.size)
        runCatching { out?.write(message(3, 0x902, payload)); out?.flush() }
    }

    private fun String.xmlEsc() =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---- Ultravox framing ----

    /** Send a message with an ASCIIZ string payload (empty payload if [payload] == null); return the response payload string. */
    private fun request(cls: Int, type: Int, payload: String?): String {
        // ASCIIZ: payload bytes include the terminating NUL; the frame adds its own 0x00 trailer.
        val p: ByteArray =
            if (payload != null) payload.toByteArray(Charsets.US_ASCII) + byteArrayOf(0) else ByteArray(0)
        out!!.write(message(cls, type, p))
        out!!.flush()
        return readPayload()
    }

    private fun readPayload(): String {
        val input = socket!!.getInputStream()
        val header = readN(input, 6)
        val len = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
        val payload = readN(input, len)
        readN(input, 1) // trailing 0x00 frame byte
        return String(payload, Charsets.US_ASCII).trimEnd(NULL_CHAR)
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw IOException("socket closed mid-message")
            off += r
        }
        return buf
    }

    private fun requireAck(resp: String, label: String) {
        if (!resp.startsWith("ACK")) throw ProtocolException("$label NAK: $resp")
    }

    companion object {
        private const val HANDSHAKE_TIMEOUT_MS = 6_000
        private val NULL_CHAR = 0.toChar() // ASCIIZ terminator used in Ultravox payloads

        /** Pure: build a full Ultravox wire message (header + payload + 0x00 trailer). */
        fun message(cls: Int, type: Int, payload: ByteArray): ByteArray {
            val ct = ((cls and 0xF) shl 12) or (type and 0xFFF)
            val out = ByteArray(6 + payload.size + 1)
            out[0] = 0x5A
            out[1] = 0x00
            out[2] = ((ct shr 8) and 0xFF).toByte()
            out[3] = (ct and 0xFF).toByte()
            out[4] = ((payload.size shr 8) and 0xFF).toByte()
            out[5] = (payload.size and 0xFF).toByte()
            System.arraycopy(payload, 0, out, 6, payload.size)
            out[out.lastIndex] = 0x00
            return out
        }
    }
}
