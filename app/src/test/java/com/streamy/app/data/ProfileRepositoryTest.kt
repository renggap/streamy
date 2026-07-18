package com.streamy.app.data

import com.google.common.truth.Truth.assertThat
import com.streamy.app.data.db.ProfileDao
import com.streamy.app.data.db.ProfileEntity
import kotlinx.coroutines.flow.Flow
import org.junit.Test

class ProfileRepositoryTest {

    private val repo = ProfileRepository(dao = throwingDao())

    private fun profile(
        codec: String = "AAC",
        bitrateKbps: Int = 64,
        sampleRateHz: Int = 44100,
        mono: Boolean = false,
        name: String = "live",
    ) = ProfileEntity(
        id = 1,
        name = name,
        host = "example.com",
        port = 8000,
        sid = 1,
        password = "changeme",
        mount = "/stream",
        codec = codec,
        bitrateKbps = bitrateKbps,
        sampleRateHz = sampleRateHz,
        mono = mono,
    )

    @Test fun toAudioConfig_maps_aac_stereo_round_trip() {
        val cfg = repo.toAudioConfig(profile(codec = "AAC", bitrateKbps = 64, mono = false))
        assertThat(cfg.codec).isEqualTo(Codec.AAC)
        assertThat(cfg.bitrateKbps).isEqualTo(64)
        assertThat(cfg.sampleRateHz).isEqualTo(44100)
        assertThat(cfg.mono).isFalse()
    }

    @Test fun toAudioConfig_maps_mp3_mono() {
        val cfg = repo.toAudioConfig(profile(codec = "MP3", bitrateKbps = 48, mono = true))
        assertThat(cfg.codec).isEqualTo(Codec.MP3)
        assertThat(cfg.mono).isTrue()
    }

    @Test fun toAudioConfig_codec_is_case_insensitive() {
        val lower = repo.toAudioConfig(profile(codec = "aac", bitrateKbps = 64))
        val mixed = repo.toAudioConfig(profile(codec = "mP3", bitrateKbps = 48, mono = true))
        assertThat(lower.codec).isEqualTo(Codec.AAC)
        assertThat(mixed.codec).isEqualTo(Codec.MP3)
    }

    @Test fun toAudioConfig_rejects_invalid_bitrate() {
        assertThrows<IllegalArgumentException> {
            repo.toAudioConfig(profile(codec = "MP3", bitrateKbps = 77, mono = true))
        }
    }

    @Test fun toAudioConfig_rejects_16k_stereo() {
        assertThrows<IllegalArgumentException> {
            repo.toAudioConfig(profile(codec = "AAC", bitrateKbps = 16, mono = false))
        }
    }

    @Test fun toAudioConfig_rejects_unknown_codec() {
        assertThrows<IllegalArgumentException> {
            repo.toAudioConfig(profile(codec = "OPUS", bitrateKbps = 64))
        }
    }

    /** DAO is never hit by these tests; hand it something that explodes if touched. */
    private fun throwingDao(): ProfileDao = object : ProfileDao {
        override fun observeAll(): Flow<List<ProfileEntity>> =
            throw UnsupportedOperationException("pure-mapper test")
        override suspend fun byId(id: Long): ProfileEntity =
            throw UnsupportedOperationException("pure-mapper test")
        override suspend fun insert(p: ProfileEntity): Long =
            throw UnsupportedOperationException("pure-mapper test")
        override suspend fun update(p: ProfileEntity) =
            throw UnsupportedOperationException("pure-mapper test")
        override suspend fun delete(p: ProfileEntity) =
            throw UnsupportedOperationException("pure-mapper test")
    }
}
