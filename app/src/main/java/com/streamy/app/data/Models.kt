package com.streamy.app.data

enum class Codec { AAC, MP3 }

/**
 * Broadcast mode. SOURCE = push encoded audio to DNAS2 (sc_serv) — Streamy IS the source.
 * DJ = push encoded audio to sc_trans (the Transcoder) on its djport2 — sc_trans re-encodes and
 * is the real DNAS source. The wire delta is one Ultravox auth field (djlogin in the 0x1001 UID).
 */
enum class BroadcastMode { SOURCE, DJ }

/**
 * DJ sub-protocol (only meaningful when [BroadcastMode] is DJ). ULTRAVOX = sc_trans / sc_serv
 * (binary Ultravox, djlogin in the auth UID). ICECAST = Icecast / Liquidsoap harbor HTTP source
 * (`SOURCE <mount> ICE/1.0` + HTTP Basic auth). AUTO = sniff at connect time ([DjProtocolDetector]).
 */
enum class DjProtocol { AUTO, ULTRAVOX, ICECAST }

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
    val MP3_BITRATES = listOf(16, 24, 32, 48, 64, 96, 128, 192)
    val SAMPLE_RATES = listOf(16000, 22050, 44100, 48000)
    /** MP3 at 16/24 kbps needs MPEG-2 Layer III (sample rate ≤ 24 kHz); LAME bumps to 32 otherwise. */
    val MPEG2_SAMPLE_RATES = listOf(16000, 22050)

    /** At 16/24 kbps we want HE-AAC v1 (AAC+) and mono + low rate. */
    fun needsHeAac(codec: Codec, bitrateKbps: Int) =
        codec == Codec.AAC && bitrateKbps <= 24

    /** MP3 at 16/24 kbps is only a valid MPEG-2 Layer III bitrate (needs ≤ 24 kHz). */
    fun mp3NeedsMpeg2(codec: Codec, bitrateKbps: Int) =
        codec == Codec.MP3 && bitrateKbps <= 24

    /** Sample rates the picker should offer for a codec+bitrate. */
    fun validSampleRatesFor(codec: Codec, bitrateKbps: Int): List<Int> =
        if (mp3NeedsMpeg2(codec, bitrateKbps)) MPEG2_SAMPLE_RATES else SAMPLE_RATES

    /** Auto-correct impossible combos — never throws. MP3 16/24 + >24 kHz → clamp to the nearest ≤ 24 kHz. */
    fun clamp(c: AudioConfig): AudioConfig {
        if (mp3NeedsMpeg2(c.codec, c.bitrateKbps) && c.sampleRateHz !in MPEG2_SAMPLE_RATES) {
            val nearest = MPEG2_SAMPLE_RATES.minBy { kotlin.math.abs(it - c.sampleRateHz) }!!
            return c.copy(sampleRateHz = nearest)
        }
        return c
    }

    fun validate(c: AudioConfig): AudioConfig {
        val allowed = if (c.codec == Codec.AAC) AAC_BITRATES else MP3_BITRATES
        require(c.bitrateKbps in allowed) { "bitrate ${c.bitrateKbps} not in $allowed" }
        require(c.sampleRateHz in SAMPLE_RATES) { "sample rate ${c.sampleRateHz}" }
        if (needsHeAac(c.codec, c.bitrateKbps)) require(c.mono) { "16/24k must be mono" }
        if (mp3NeedsMpeg2(c.codec, c.bitrateKbps))
            require(c.sampleRateHz in MPEG2_SAMPLE_RATES) { "MP3 ${c.bitrateKbps}kbps needs ≤ 24 kHz (MPEG-2)" }
        return c
    }
}
