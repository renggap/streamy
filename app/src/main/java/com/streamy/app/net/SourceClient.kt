package com.streamy.app.net

/**
 * A SHOUTcast source-protocol client. [UvoxClient] (Ultravox 2.1 / v2) implements this for
 * BOTH MP3 (audio/mpeg) and AAC (audio/aacp); [com.streamy.app.service.StreamService] is
 * Ultravox-only so it gets in-stream metadata (0x902) for free. Kept as an abstraction so
 * a future Icecast client (HTTP-PUT source) can slot in here without touching the service.
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
