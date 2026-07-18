package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IcyClientHandshakeTest {
    @Test fun put_request_for_mp3_is_well_formed() {
        val req = IcyClient.buildHandshake(
            mount = "/stream", host = "srv.example:8000",
            contentType = "audio/mpeg", bitrateKbps = 128,
            name = "Streamy", genre = "Speech", password = "hackme")
        val s = String(req, Charsets.US_ASCII)
        assertThat(s).startsWith("PUT /stream HTTP/1.1\r\n")
        assertThat(s).contains("Host: srv.example:8000\r\n")
        assertThat(s).contains("Authorization: Basic " +
            java.util.Base64.getEncoder().encodeToString("source:hackme".toByteArray()))
        assertThat(s).contains("Content-Type: audio/mpeg\r\n")
        assertThat(s).contains("ice-bitrate: 128\r\n")
        assertThat(s).contains("ice-name: Streamy\r\n")
        assertThat(s).endsWith("\r\n\r\n")
    }
}
