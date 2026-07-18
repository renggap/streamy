package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Golden-byte test for the SHOUTcast v1 source header block. The full login sequence
 * (password line -> OK2 -> headers -> raw MP3) was verified live against DNAS2 2.6.1 —
 * see docs/plans/2026-07-18-uvox-finding.md. [IcyClient.buildHeaderBlock] is pure so it's
 * JVM-testable; the socket path is exercised on-device in Task 13.
 */
class IcyClientHandshakeTest {

    @Test fun v1_header_block_is_well_formed() {
        val bytes = IcyClient.buildHeaderBlock(bitrateKbps = 128, name = "Streamy", genre = "Speech")
        val s = String(bytes, Charsets.US_ASCII)
        assertThat(s).contains("icy-name:Streamy\r\n")
        assertThat(s).contains("icy-genre:Speech\r\n")
        assertThat(s).contains("icy-br:128\r\n")
        assertThat(s).contains("icy-pub:1\r\n")
        assertThat(s).endsWith("\r\n\r\n")
        // v1 protocol, not Icecast HTTP-PUT: no request line
        assertThat(s.startsWith("PUT") || s.startsWith("SOURCE") || s.startsWith("GET")).isFalse()
    }
}
