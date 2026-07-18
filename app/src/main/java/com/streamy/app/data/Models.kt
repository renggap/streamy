package com.streamy.app.data

enum class Codec { AAC, MP3 }

/** Bitrate + sample-rate + channels resolved for an encode session. */
data class AudioConfig(
    val codec: Codec,
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val mono: Boolean,
)

/** Validates a chosen AudioConfig against codec rules. Throws on impossible combos. */
object AudioConfigRules {
    val AAC_BITRATES = listOf(16, 24, 32, 48, 64, 96, 128)
    val MP3_BITRATES = listOf(16, 24, 48, 64, 96, 128, 192)
    val SAMPLE_RATES = listOf(16000, 22050, 44100, 48000)

    /** At 16/24 kbps we want HE-AAC v1 (AAC+) and mono + low rate. */
    fun needsHeAac(codec: Codec, bitrateKbps: Int) =
        codec == Codec.AAC && bitrateKbps <= 24

    fun validate(c: AudioConfig): AudioConfig {
        val allowed = if (c.codec == Codec.AAC) AAC_BITRATES else MP3_BITRATES
        require(c.bitrateKbps in allowed) { "bitrate ${c.bitrateKbps} not in $allowed" }
        require(c.sampleRateHz in SAMPLE_RATES) { "sample rate ${c.sampleRateHz}" }
        if (needsHeAac(c.codec, c.bitrateKbps)) require(c.mono) { "16/24k must be mono" }
        return c
    }
}
