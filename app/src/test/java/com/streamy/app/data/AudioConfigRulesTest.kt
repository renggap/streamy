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

    @Test fun mp3_low_bitrate_needs_mpeg2_sample_rate() {
        assertThat(AudioConfigRules.mp3NeedsMpeg2(Codec.MP3, 16)).isTrue()
        assertThat(AudioConfigRules.mp3NeedsMpeg2(Codec.MP3, 24)).isTrue()
        assertThat(AudioConfigRules.mp3NeedsMpeg2(Codec.MP3, 48)).isFalse()
        assertThat(AudioConfigRules.mp3NeedsMpeg2(Codec.AAC, 24)).isFalse()
    }

    @Test fun clamp_mp3_low_bitrate_down_to_mpeg2() {
        // 24 kbps at 44.1 kHz is not a valid MPEG-1 bitrate (LAME bumps to 32); clamp to 22.05 kHz.
        assertThat(AudioConfigRules.clamp(AudioConfig(Codec.MP3, 24, 44100, mono = true)).sampleRateHz)
            .isEqualTo(22050)
        assertThat(AudioConfigRules.clamp(AudioConfig(Codec.MP3, 16, 48000, mono = true)).sampleRateHz)
            .isEqualTo(22050)
        // Already-valid rates are untouched; higher MP3 bitrates are untouched.
        assertThat(AudioConfigRules.clamp(AudioConfig(Codec.MP3, 24, 22050, mono = true)).sampleRateHz)
            .isEqualTo(22050)
        assertThat(AudioConfigRules.clamp(AudioConfig(Codec.MP3, 128, 44100, mono = true)).sampleRateHz)
            .isEqualTo(44100)
    }

    @Test fun validate_rejects_mp3_24k_at_44khz() {
        assertThrows<IllegalArgumentException> {
            AudioConfigRules.validate(AudioConfig(Codec.MP3, 24, 44100, mono = true))
        }
    }
}

inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
    try { block(); error("expected ${T::class}") }
    catch (e: Throwable) { if (e !is T) throw e }
}
