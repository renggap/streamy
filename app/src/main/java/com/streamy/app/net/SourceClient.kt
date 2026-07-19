package com.streamy.app.net

/**
 * A SHOUTcast source-protocol client. Both [IcyClient] (v1 protocol, MP3) and [UvoxClient]
 * (Ultravox 2.1 / v2, AAC + MP3) implement this so [com.streamy.app.service.StreamService]
 * can pick the transport by codec. Same shape: [connect] (full handshake incl. auth),
 * [send] (one encoded frame), [close].
 */
interface SourceClient {
    fun connect(bitrateKbps: Int, name: String, genre: String)
    fun send(frame: ByteArray)
    fun close()
}
