package com.streamy.app.ui

import com.google.common.truth.Truth.assertThat
import com.streamy.app.R
import com.streamy.app.net.UvoxClient
import org.junit.Test

class ErrorTextTest {

    /** A Centova DJ port ignores Ultravox: the hint must name the Icecast-style option, not the generic timeout. */
    @Test fun uvox_handshake_timeout_points_at_icecast_option() {
        val t = streamErrorText("java.net.SocketTimeoutException: ${UvoxClient.HANDSHAKE_TIMEOUT_TEXT}")
        assertThat(t.messageRes).isEqualTo(R.string.err_handshake_timeout)
    }

    @Test fun plain_timeout_stays_generic() {
        assertThat(streamErrorText("failed to connect: connect timed out").messageRes)
            .isEqualTo(R.string.err_timeout)
    }
}
