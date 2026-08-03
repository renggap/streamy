package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Golden vectors for [UvoxXtea.encipher] — captured from a reference Python port of the
 * spec's C++ XTEA (key "foobar", the value DNAS2 returns from the 0x1009 Request Cipher).
 * Locks the Ultravox auth encoding so a future refactor of the encipher can't silently break
 * the server handshake.
 */
class UvoxXteaTest {

    @Test fun encipher_matches_reference_vectors() {
        assertThat(UvoxXtea.encipher("test".toByteArray(), "foobar".toByteArray()))
            .isEqualTo("0797c1d44e31a1f3")
        assertThat(UvoxXtea.encipher("6naZQ3gy".toByteArray(), "foobar".toByteArray()))
            .isEqualTo("dcd4cb55b273478d")
    }

    @Test fun encipher_empty_input_is_empty() {
        // uid is empty -> no 8-byte block -> empty hex string (auth payload's UID field is blank).
        assertThat(UvoxXtea.encipher(ByteArray(0), "foobar".toByteArray())).isEqualTo("")
    }

    @Test fun encipher_pads_short_keys_to_16_bytes() {
        // Golden vectors from a reference port of the spec's C++ XTEA (scripts/xtea_ref.py),
        // covering the key-padding path the review flagged: keys < 16 bytes zero-pad to 16.
        assertThat(UvoxXtea.encipher("test".toByteArray(), "ab".toByteArray()))
            .isEqualTo("1b12ddeaacf76b53")
        assertThat(UvoxXtea.encipher("test".toByteArray(), "abcdefghijklmno".toByteArray()))
            .isEqualTo("cc86e8c176b00346")
        assertThat(UvoxXtea.encipher("test".toByteArray(), "0123456789abcdef".toByteArray()))
            .isEqualTo("6c302719dd265d3f")
    }

    @Test fun encipher_truncates_long_keys_to_16_bytes() {
        // A 20-byte key must behave exactly like its first 16 bytes (the copyOf(16) path).
        val sixteen = UvoxXtea.encipher("test".toByteArray(), "0123456789abcdef".toByteArray())
        val twenty = UvoxXtea.encipher("test".toByteArray(), "0123456789abcdefGHIJ".toByteArray())
        assertThat(twenty).isEqualTo(sixteen)
        assertThat(twenty).isEqualTo("6c302719dd265d3f")
    }

    @Test fun encipher_multiple_blocks_are_concatenated() {
        // 16-byte input = two 8-byte blocks -> two 16-hex words concatenated (no separator).
        assertThat(UvoxXtea.encipher("abcdefghijklmnop".toByteArray(), "foobar".toByteArray()))
            .isEqualTo("95b8f749a8590cb6d546ae4022b542e7")
    }
}
