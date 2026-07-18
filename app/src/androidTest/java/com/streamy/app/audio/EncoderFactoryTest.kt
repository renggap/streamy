package com.streamy.app.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamy.app.data.AudioConfig
import com.streamy.app.data.Codec
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented — instantiating either encoder needs a real device (`MediaCodec` for AAC,
 * `System.loadLibrary("streamy_lame")` for MP3), so this runs under connectedAndroidTest,
 * not the JVM unit-test task. Compiled here; executed in Task 13 / on a device.
 */
@RunWith(AndroidJUnit4::class)
class EncoderFactoryTest {

    @Test fun selects_aac_and_sets_mime() {
        EncoderFactory.create(AudioConfig(Codec.AAC, 64, 44100, mono = false)).use { e ->
            assertThat(e.mime).isEqualTo("audio/aac")
        }
    }

    @Test fun selects_mp3_and_sets_mime() {
        EncoderFactory.create(AudioConfig(Codec.MP3, 128, 44100, mono = true)).use { e ->
            assertThat(e.mime).isEqualTo("audio/mpeg")
        }
    }
}
