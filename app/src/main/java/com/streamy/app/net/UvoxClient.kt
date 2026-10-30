package com.streamy.app.net

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

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
    private val sampleRateHz: Int = 44_100, // used for AAC ADTS framing
    private val channels: Int = 1,          // 1 = mono, 2 = stereo (AAC ADTS channel config)
    /**
     * DJ mode (sc_trans): when non-null, this is the sc_trans `djlogin` and goes into the 0x1001
     * auth UID field (XTEA-enciphered), and [password] is the `djpassword`. Null = SOURCE mode
     * (DNAS), UID field blank. The only wire difference between SOURCE and DJ — see [connect].
     */
    private val djUser: String? = null,
) : SourceClient {

    private val dataClass: Int
    private val dataType: Int

    init {
        if (mime == "audio/aacp" || mime == "audio/aac") { dataClass = 8; dataType = 0x003 } // AACP
        else { dataClass = 7; dataType = 0x000 } // MP3
    }

    // Concurrency: send() runs on the transport coroutine, updateNowPlaying() on its own IO
    // coroutine, and close()'s Terminate on a helper thread — all writing one socket. [writeLock]
    // serialises whole messages so frames can't interleave mid-message (DNAS drops the source on
    // a corrupt frame). [out] is published only after the Standby ACK, so nothing but handshake
    // traffic can ever land between a handshake request and its reply.
    private val writeLock = Any()
    private val stateLock = Any()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    // Latched by close(): a close() during connect() (user hit Stop mid-handshake) must abort the
    // handshake, not let it finish into an authenticated source nobody will ever close — the
    // server would hold the sid "in use" and NAK the next Start.
    @Volatile private var closed = false
    // Last now-playing title, replayed after every (re)connect: DNAS forgets it with the source.
    @Volatile private var lastTitle: String? = null

    override fun connect(bitrateKbps: Int, name: String, genre: String) {
        // Close any prior socket so a reconnect never leaks.
        try { socket?.close() } catch (_: Throwable) {}
        socket = null; out = null
        // Resolve the address from the constructor params, never inside a `Socket().apply { }`
        // scope: there an unqualified `port` binds to Socket.getPort() — 0 on an unconnected
        // socket — silently dialing host:0. Live-verified regression against DNAS2 2.6.
        val addr = InetSocketAddress(host, port)
        val s = Socket()
        // Publish the socket BEFORE the blocking connect so a concurrent close() can abort it.
        synchronized(stateLock) {
            if (closed) throw IOException("client closed")
            socket = s
        }
        try {
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(addr, HANDSHAKE_TIMEOUT_MS)
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            val o = s.getOutputStream()
            val input = s.getInputStream()
            fun request(cls: Int, type: Int, payload: String?) = exchange(o, input, cls, type, payload)

            // 1) Request Cipher -> key. Most servers return "foobar"; Centova and others leave
            //    uvoxcipherkey BLANK and the response is empty. Use the server's actual key
            //    (empty is valid — XTEA pads it to a 16-zero key, which the server also uses).
            //    Do NOT default to "foobar": that encrypts with the wrong key and the server
            //    decrypts garbage -> "Bad password" denial.
            val cipherResp = request(1, 0x009, "2.1")
            if (!cipherResp.startsWith("ACK")) throw ProtocolException("cipher NAK: $cipherResp")
            val cipherKey = cipherResp.substringAfter("ACK:").trimEnd(NULL_CHAR)
                .toByteArray(Charsets.US_ASCII)

            // 2) Authenticate: UID = djlogin (DJ mode) or blank (SOURCE); AuthBlob = password
            //    (stream password for SOURCE, djpassword for DJ). Both XTEA-enciphered, hex-emitted.
            val encUid = UvoxXtea.encipher(
                (djUser ?: "").toByteArray(Charsets.UTF_8), cipherKey
            )
            // UTF-8 (identical to ASCII for ASCII passwords): US_ASCII turned every non-ASCII
            // character into '?', so such a password could never match.
            val encBlob = UvoxXtea.encipher(password.toByteArray(Charsets.UTF_8), cipherKey)
            val authResp = request(1, 0x001, "2.1:$sid:$encUid:$encBlob")
            if (!authResp.startsWith("ACK")) throw ProtocolException("auth NAK: $authResp")

            // 3) Stream config (order per spec). Values are user-controlled (profile name/genre):
            //    strip CR/LF + NUL so a hostile name can't inject control characters into the
            //    ASCIIZ payload, and truncate so the u16 length field can't overflow.
            requireAck(request(1, 0x040, mime), "mime")
            requireAck(request(1, 0x002, "$bitrateKbps:$bitrateKbps"), "setup")
            requireAck(request(1, 0x003, "2048:2048"), "buffer")
            requireAck(request(1, 0x100, WireSafety.headerValue(name)), "icy-name")
            requireAck(request(1, 0x101, WireSafety.headerValue(genre)), "icy-genre")
            requireAck(request(1, 0x102, "http://streamy.app"), "icy-url")
            // Public visibility (0x103 = "1"). Streamy always broadcasts publicly (listeners
            // join from the directory); if non-public streams are ever supported, plumb a
            // profile field through here instead of hardcoding "1".
            requireAck(request(1, 0x103, ICY_PUB), "icy-pub")
            requireAck(request(1, 0x008, "16377:16377"), "max-payload")

            // 4) Standby -> "ACK:Data transfer mode".
            val standby = request(1, 0x004, null)
            if (!standby.startsWith("ACK")) throw ProtocolException("standby NAK: $standby")

            s.soTimeout = 0 // streaming: writes own the socket; no read timeout
            synchronized(stateLock) {
                if (closed) throw IOException("client closed")
                out = o
            }
        } catch (e: Throwable) {
            try { s.close() } catch (_: Throwable) {}
            socket = null; out = null
            throw e
        }
        lastTitle?.let { updateNowPlaying(it) }
    }

    /**
     * Wrap one encoded frame in a data message and write it. For AAC (audio/aacp) each raw AAC
     * access unit is wrapped in a 7-byte ADTS header first — DNAS needs ADTS framing to parse
     * AAC frame boundaries and bitrate; raw AAC is rejected after ~2s (live-verified) and the
     * bitrate shows 0. ADTS fixes both the disconnect and the bitrate display. MP3 is sent raw
     * (DNAS reads MP3 frame headers natively).
     */
    override fun send(frame: ByteArray) {
        val o = out ?: throw IOException("client closed")
        val payload = if (dataClass == 8) wrapAdts(frame) else frame
        val msg = message(dataClass, dataType, payload)
        synchronized(writeLock) {
            o.write(msg)
            o.flush()
        }
    }

    /** Wrap one AU in an ADTS header — see [UvoxClient.adtsHeader].
     *
     *  HE-AAC v1 (used for ALL AAC bitrates — see AacEncoder) carries SBR implicitly, so the ADTS
     *  header MUST declare the AAC-LC CORE rate (sampleRateHz / 2), NOT the SBR-doubled output
     *  rate. DNAS2 derives stream BITRATE from ADTS sample_rate / 1024 × frame_count (libshout_aac);
     *  declaring the output rate doubles the computed frame rate and DNAS reports 2× the configured
     *  bitrate (e.g. 48 kbps for a 24 kbps stream). Refs: ISO/IEC 14496-3 §1.6.5.2; wiki.multimedia.cx/ADTS. */
    private fun wrapAdts(au: ByteArray): ByteArray = adtsHeader(au, sampleRateHz / 2, channels)

    override fun close() {
        // Terminated is best-effort: if the peer died without ACKing the close (half-open TCP),
        // the write can block in kernel retransmit for minutes. Daemon thread + join cap keeps
        // teardown (stopAndShutdown runs on the main thread) bounded; a stuck close can't ANR.
        val o: OutputStream?
        val s: Socket?
        synchronized(stateLock) {
            closed = true
            o = out
            s = socket
        }
        val sendThread = Thread {
            if (o != null) runCatching {
                synchronized(writeLock) {
                    o.write(message(1, 0x005, ByteArray(0))) // Terminate
                    o.flush()
                }
            }
        }
        sendThread.isDaemon = true
        sendThread.start()
        try { sendThread.join(CLOSE_JOIN_MS) } catch (_: InterruptedException) {}
        sendThread.interrupt() // no-op for a blocked socket write; releases nothing but is harmless
        // Closing the socket also aborts an in-flight connect()/handshake read on another thread.
        runCatching { s?.close() }
        socket = null; out = null
    }

    /**
     * Admin-free in-stream metadata via Ultravox class 3 / type 0x902. Live-verified against
     * DNAS2 2.6.1: one message with payload `[MetadataID u16 BE][Span u16 BE][Index u16 BE][XML]`
     * updates SONGTITLE with NO admin creds. `<TIT2>title</TIT2>` alone sets SONGTITLE = title.
     * Best-effort: no-op if not connected. The frame trailer added by [message] is correct —
     * do NOT add a NUL inside the payload.
     */
    override fun updateNowPlaying(title: String) {
        // Strip CR/LF + other control chars (a title can't break the XML) and cap at 512 chars
        // so 6 + xml.size never overflows the u16 payload length in [message].
        val safe = WireSafety.truncate(WireSafety.stripControlChars(title), WireSafety.MAX_HEADER_VALUE)
        lastTitle = safe
        val xml = ("<?xml version=\"1.0\" encoding=\"UTF-8\" ?><metadata><TIT2>"
            + safe.xmlEsc() + "</TIT2></metadata>").toByteArray(Charsets.UTF_8)
        val payload = ByteArray(6 + xml.size)
        payload[1] = 1; payload[3] = 1; payload[5] = 1 // MetadataID=1, Span=1, Index=1 (BE u16, high byte 0)
        System.arraycopy(xml, 0, payload, 6, xml.size)
        val o = out ?: return   // not connected yet: replayed after the handshake
        val msg = message(3, 0x902, payload)
        runCatching {
            synchronized(writeLock) {
                o.write(msg)
                o.flush()
            }
        }
    }

    private fun String.xmlEsc() =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---- Ultravox framing ----

    /** Send a message with an ASCIIZ string payload (empty payload if [payload] == null); return the response payload string. */
    private fun exchange(o: OutputStream, input: InputStream, cls: Int, type: Int, payload: String?): String {
        // ASCIIZ: payload bytes include the terminating NUL; the frame adds its own 0x00 trailer.
        // UTF-8 so a non-ASCII station name/genre isn't sent as '?' (identical bytes for ASCII).
        val p: ByteArray =
            if (payload != null) payload.toByteArray(Charsets.UTF_8) + byteArrayOf(0) else ByteArray(0)
        o.write(message(cls, type, p))
        o.flush()
        // The TCP connect succeeded, so a silent server here is speaking a different protocol
        // (e.g. a Centova/sc_trans DJ port that wants an Icecast-style SOURCE login and ignores
        // Ultravox bytes). Distinct text lets the UI point at the fix; still an IOException, so
        // it stays retryable like any other network timeout.
        try {
            return readPayload(input)
        } catch (e: SocketTimeoutException) {
            throw SocketTimeoutException(HANDSHAKE_TIMEOUT_TEXT)
        }
    }

    private fun readPayload(input: InputStream): String {
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
        /** icy-pub value (0x103): "1" = public (directory-listed). Streamy is always public. */
        private const val ICY_PUB = "1"

        /** Frame classifier bits — see the class doc for the layout. */
        private const val HANDSHAKE_TIMEOUT_MS = 6_000

        /** Message of the timeout raised when the server never answers an Ultravox request. */
        const val HANDSHAKE_TIMEOUT_TEXT = "uvox handshake timed out"

        /** Cap on close()'s best-effort Terminate write before force-closing the socket. */
        private const val CLOSE_JOIN_MS = 500L
        private val NULL_CHAR = 0.toChar() // ASCIIZ terminator used in Ultravox payloads
        // ADTS profile field = AAC-LC base layer; carries HE-AAC backward-compatibly (see wrapAdts).
        private const val AAC_LC_PROFILE = 1

        /**
         * Pure: build a 7-byte (no-CRC) ADTS header for one AAC access unit. Profile field =
         * AAC-LC base layer (carries HE-AAC backward-compatibly — implicit SBR). Channel
         * configuration's low 2 bits live in byte 3 bits 7-6 (NOT 5-4); a wrong shift decodes
         * as channel_configuration = 0 and the stream is unplayable. Pure → unit tested.
         */
        fun adtsHeader(au: ByteArray, sampleRateHz: Int, channels: Int): ByteArray {
            val freqIdx = adtsFreqIndex(sampleRateHz)
            val frameLen = au.size + 7
            val out = ByteArray(7 + au.size)
            out[0] = 0xFF.toByte()
            out[1] = 0xF1.toByte() // MPEG-4, layer 0, protection_absent (no CRC)
            out[2] = (((AAC_LC_PROFILE and 0x3) shl 6) or (freqIdx shl 2) or (channels ushr 2)).toByte()
            out[3] = (((channels and 0x3) shl 6) or ((frameLen ushr 11) and 0x3)).toByte()
            out[4] = ((frameLen ushr 3) and 0xFF).toByte()
            out[5] = (((frameLen and 0x7) shl 5) or 0x1F).toByte()
            out[6] = 0xFC.toByte()
            System.arraycopy(au, 0, out, 7, au.size)
            return out
        }

        private fun adtsFreqIndex(sr: Int): Int = when (sr) {
            96_000 -> 0; 88_200 -> 1; 64_000 -> 2; 48_000 -> 3; 44_100 -> 4
            32_000 -> 5; 24_000 -> 6; 22_050 -> 7; 16_000 -> 8; 12_000 -> 9
            11_025 -> 10; 8_000 -> 11; 7_350 -> 12; else -> 4
        }

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
