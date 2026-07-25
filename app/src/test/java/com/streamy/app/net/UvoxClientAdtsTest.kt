package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the AAC ADTS header layout produced by [UvoxClient.adtsHeader]. A prior build shifted the
 * channel-configuration bits into byte 3 bits 5-4 (original_copy/home) instead of 7-6, which
 * silently decoded as channel_configuration=0 and made every AAC stream unplayable — while DNAS2
 * still reported the right CONTENT/BITRATE (it doesn't decode audio), giving false confidence.
 */
class UvoxClientAdtsTest {
    private fun channelConfig(hdr: ByteArray): Int {
        val hi = (hdr[2].toInt() and 0x1)                 // byte 2 bit 0 = chan_config MSB
        val lo = (hdr[3].toInt() and 0xC0).ushr(6) and 0x3 // byte 3 bits 7-6 = chan_config low 2 bits
        return (hi shl 2) or lo
    }

    private fun frameLength(hdr: ByteArray): Int =
        ((hdr[3].toInt() and 0x3) shl 11) or
            ((hdr[4].toInt() and 0xFF) shl 3) or
            ((hdr[5].toInt() and 0xE0).ushr(5))

    @Test fun adts_header_carries_correct_channel_configuration() {
        val au = ByteArray(64)
        assertThat(channelConfig(UvoxClient.adtsHeader(au, 44_100, 1))).isEqualTo(1)
        assertThat(channelConfig(UvoxClient.adtsHeader(au, 44_100, 2))).isEqualTo(2)
    }

    @Test fun adts_frame_length_is_au_plus_header() {
        val au = ByteArray(100)
        assertThat(frameLength(UvoxClient.adtsHeader(au, 22_050, 1))).isEqualTo(107)
    }

    @Test fun adts_sync_and_protection_absent() {
        val h = UvoxClient.adtsHeader(ByteArray(32), 44_100, 1)
        assertThat(h[0].toInt() and 0xFF).isEqualTo(0xFF)
        assertThat(h[1].toInt() and 0xFF).isEqualTo(0xF1) // MPEG-4, layer 0, protection_absent=1
    }
}
