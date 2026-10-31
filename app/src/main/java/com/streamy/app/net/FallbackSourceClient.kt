package com.streamy.app.net

import java.io.IOException

/**
 * Tries [primary], and on any connect failure tries [fallback] — used for DJ profiles so one
 * setting covers both SHOUTcast DJ styles seen live: Centova/sc_trans2 panels that advertise
 * a v1 login (primary: [ShoutcastV1Client] on port + 1) and servers that only speak Ultravox
 * on the port as entered (fallback: [UvoxClient]).
 *
 * Sticky: once a protocol connects, reconnects use it alone. Flapping between protocols on a
 * flaky link would mean two different DJ sessions taking over the station in turn.
 */
class FallbackSourceClient(
    private val primary: SourceClient,
    private val fallback: SourceClient,
) : SourceClient {

    @Volatile private var active: SourceClient? = null
    @Volatile private var closed = false

    override fun connect(bitrateKbps: Int, name: String, genre: String) {
        active?.let { it.connect(bitrateKbps, name, genre); return }
        val first = try {
            primary.connect(bitrateKbps, name, genre)
            active = primary
            return
        } catch (e: Throwable) {
            e
        }
        if (closed) throw first   // Stop arrived mid-attempt: don't start a second handshake
        try {
            fallback.connect(bitrateKbps, name, genre)
            active = fallback
        } catch (second: Throwable) {
            throw pickError(first, second)
        }
    }

    override fun send(frame: ByteArray) {
        (active ?: throw IOException("client closed")).send(frame)
    }

    /** Both get it: whichever connects keeps the title (UvoxClient replays it after its handshake).
     *  True when any client reports the server's real confirmation. Both calls always run — no
     *  short-circuit — so the inactive client also keeps the latest title for its reconnect replay. */
    override fun updateNowPlaying(title: String): Boolean {
        val primaryOk = primary.updateNowPlaying(title)
        val fallbackOk = fallback.updateNowPlaying(title)
        return primaryOk || fallbackOk
    }

    override fun close() {
        closed = true
        // Either may be mid-handshake on the transport thread; each close() aborts its own.
        runCatching { primary.close() }
        runCatching { fallback.close() }
    }

    companion object {
        /**
         * Which failure the user sees when both fail. A primary that got as far as refusing
         * the password is the real answer (right server, wrong login) — the fallback's error
         * then is just noise from speaking the wrong protocol. Otherwise the fallback's error
         * is the meaningful one. Pure → unit tested.
         */
        fun pickError(primary: Throwable, fallback: Throwable): Throwable {
            val primaryIsAuth = primary is ProtocolException &&
                primary.message?.startsWith("auth NAK", ignoreCase = true) == true
            val chosen = if (primaryIsAuth) primary else fallback
            val other = if (primaryIsAuth) fallback else primary
            if (other !== chosen) chosen.addSuppressed(other)
            return chosen
        }
    }
}
