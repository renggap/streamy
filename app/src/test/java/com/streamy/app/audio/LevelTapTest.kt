package com.streamy.app.audio

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LevelTapTest {
    @Test fun rmsDb_silence_is_floor_and_fullscale_is_zero() {
        assertThat(LevelTap.rmsDb(ShortArray(1024))).isEqualTo(-90.0f)
        assertThat(LevelTap.rmsDb(ShortArray(1024) { 32767 })).isWithin(0.01f).of(0.0f)
    }
    @Test fun rmsDb_half_amplitude_is_minus_six() {
        assertThat(LevelTap.rmsDb(ShortArray(1024) { -16384 })).isWithin(0.05f).of(-6.02f)
    }
    @Test fun rmsDb_of_empty_frame_is_floor_not_NaN() {
        // Regression: AudioCapture's dead-mic signal is an empty frame; 0/0 = NaN crashed the
        // VU meter's roundToInt() mid-broadcast.
        val db = LevelTap.rmsDb(ShortArray(0))
        assertThat(db.isNaN()).isFalse()
        assertThat(db).isEqualTo(-90.0f)
    }
    @Test fun decimate_produces_normalized_fixed_length() {
        val pcm = ShortArray(1024) { (it % 256 * 256 - 32768).toShort() }
        val out = LevelTap.decimate(pcm, points = 96)
        assertThat(out.size).isEqualTo(96)
        assertThat(out.min()).isAtLeast(-1.0f)
        assertThat(out.max()).isAtMost(1.0f)
    }
}
