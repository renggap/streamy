package com.streamy.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SanityTest {
    @Test
    fun sanity() {
        assertThat(1 + 1).isEqualTo(2)
    }
}
