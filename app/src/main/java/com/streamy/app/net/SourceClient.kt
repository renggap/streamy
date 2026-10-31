package com.streamy.app.net

/**
 * A source-protocol client. Implementations: [UvoxClient] (Ultravox 2.1, MP3 + AAC — SOURCE
 * mode and Ultravox DJ ports), [ShoutcastV1Client] (SHOUTcast v1 DJ login, MP3),
 * [IcecastSourceClient] (ICE/1.0 for AzuraCast / Liquidsoap DJ ports), and
 * [FallbackSourceClient], which tries one then another (DJ: v1, then Ultravox).
 * Same shape: [connect] (full handshake incl. auth), [send] (one encoded frame), [close].
 */
interface SourceClient {
    fun connect(bitrateKbps: Int, name: String, genre: String)
    fun send(frame: ByteArray)
    fun close()
    /** Push a now-playing title; returns true ONLY when the server confirmed the update —
     *  [UvoxClient] reports a successful in-stream write on a connected session, [IcecastSourceClient]
     *  an accepted `/admin/metadata` GET, [ShoutcastV1Client] the Weblet's `Title Updated` reply.
     *  False when not connected, deferred (replayed after the handshake), or the server refused —
     *  the UI must never claim success on false. A title set before the handshake is replayed
     *  once it completes (that replay is fire-and-forget). */
    fun updateNowPlaying(title: String): Boolean = false
}
