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
}
