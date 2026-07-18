package com.streamy.app.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AudioConfigRulesTest {
    @Test fun aac_low_rate_needs_heaac_and_mono() {
        assertThat(AudioConfigRules.needsHeAac(Codec.AAC, 16)).isTrue()
        assertThat(AudioConfigRules.needsHeAac(Codec.AAC, 24)).isTrue()
        assertThat(AudioConfigRules.needsHeAac(Codec.AAC, 32)).isFalse()
        assertThat(AudioConfigRules.needsHeAac(Codec.MP3, 16)).isFalse()
    }

    @Test fun validate_accepts_aac_64_stereo() {
        AudioConfigRules.validate(AudioConfig(Codec.AAC, 64, 44100, mono = false))
    }

    @Test fun validate_rejects_16k_stereo() {
        assertThrows<IllegalArgumentException> {
            AudioConfigRules.validate(AudioConfig(Codec.AAC, 16, 22050, mono = false))
        }
    }

    @Test fun validate_rejects_unknown_bitrate() {
        assertThrows<IllegalArgumentException> {
            AudioConfigRules.validate(AudioConfig(Codec.MP3, 77, 44100, mono = true))
        }
    }

    @Test fun validate_rejects_unknown_sample_rate() {
        assertThrows<IllegalArgumentException> {
            AudioConfigRules.validate(AudioConfig(Codec.MP3, 128, 99999, mono = true))
        }
    }
}

inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
    try { block(); error("expected ${T::class}") }
    catch (e: Throwable) { if (e !is T) throw e }
}
