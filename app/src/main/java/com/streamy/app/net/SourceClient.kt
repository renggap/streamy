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
    /** Push a now-playing title. Default no-op; [UvoxClient] sends Ultravox 0x902, [IcecastSourceClient]
     *  injects ICY inline metadata (+ best-effort Icecast admin update). Harmless if unsupported. */
    fun updateNowPlaying(title: String) {}
}
