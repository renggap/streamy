package com.streamy.app.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TeardownStateTest {
    @Test fun error_is_preserved_when_preserveTerminal_is_true() {
        assertThat(
            StreamService.resolveTeardownState(StreamState.Error("mic_busy"), preserveTerminal = true)
        ).isEqualTo(StreamState.Error("mic_busy"))
    }

    @Test fun user_stop_clears_to_idle() {
        assertThat(
            StreamService.resolveTeardownState(StreamState.Error("mic_busy"), preserveTerminal = false)
        ).isEqualTo(StreamState.Idle)
    }

    @Test fun idle_stays_idle() {
        assertThat(
            StreamService.resolveTeardownState(StreamState.Idle, preserveTerminal = true)
        ).isEqualTo(StreamState.Idle)
    }
}
