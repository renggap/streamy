package com.streamy.app.audio

import android.media.MediaCodecInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AacEncoderConfigTest {
    @Test fun low_rate_maps_to_heaac_object_type() {
        assertThat(AacEncoder.aacObjectType(16)).isEqualTo(MediaCodecInfo.CodecProfileLevel.AACObjectHE)
        assertThat(AacEncoder.aacObjectType(24)).isEqualTo(MediaCodecInfo.CodecProfileLevel.AACObjectHE)
        assertThat(AacEncoder.aacObjectType(64)).isEqualTo(MediaCodecInfo.CodecProfileLevel.AACObjectLC)
    }
}
