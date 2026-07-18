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
        AudioConfigRules.validate(cfg)
        return when (cfg.codec) {
            Codec.AAC -> AacEncoder(cfg.sampleRateHz, cfg.bitrateKbps * 1000, cfg.mono)
            Codec.MP3 -> Mp3Encoder(cfg.sampleRateHz, cfg.bitrateKbps, cfg.mono)
        }
    }
}
