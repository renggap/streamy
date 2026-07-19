package com.streamy.app.audio

import com.streamy.app.data.AudioConfig
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec

/**
 * Constructs the right [Encoder] for an [AudioConfig]. Validates the config first
 * (throws on impossible combos) so callers downstream of the factory get only legal encoders.
 *
 * Selection itself is trivial (`when` on [Codec]); construction needs a device
 * (`MediaCodec` / `System.loadLibrary`), so its test lives under `androidTest`.
 */
object EncoderFactory {
    fun create(cfg: AudioConfig): Encoder {
        // Clamp impossible combos first (e.g. MP3 16/24 kbps + 44.1 kHz → 22.05 kHz MPEG-2),
        // so the encoder always produces the requested bitrate instead of LAME bumping 24→32.
        val c = AudioConfigRules.clamp(cfg)
        AudioConfigRules.validate(c)
        return when (c.codec) {
            Codec.AAC -> AacEncoder(c.sampleRateHz, c.bitrateKbps * 1000, c.mono)
            Codec.MP3 -> Mp3Encoder(c.sampleRateHz, c.bitrateKbps, c.mono)
        }
    }
}
