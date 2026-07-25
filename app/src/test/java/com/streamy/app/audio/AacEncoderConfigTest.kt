package com.streamy.app.audio

import android.media.MediaCodecInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AacEncoderConfigTest {
    @Test fun aac_is_always_he_aac_v1() {
        // AAC-LC retired — every AAC bitrate uses HE-AAC v1 (AAC-LC + SBR).
        listOf(16, 24, 32, 48, 64, 96, 128).forEach { kbps ->
            assertThat(AacEncoder.aacObjectType(kbps)).isEqualTo(MediaCodecInfo.CodecProfileLevel.AACObjectHE)
        }
    }
}
