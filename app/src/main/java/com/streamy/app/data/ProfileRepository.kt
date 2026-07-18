package com.streamy.app.data

import com.streamy.app.data.db.ProfileDao
import com.streamy.app.data.db.ProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Thin wrapper over [ProfileDao]. Holds no Android-context deps.
 * `toAudioConfig` is a pure, JVM-testable mapper that fails loudly on
 * invalid profiles via [AudioConfigRules.validate].
 */
class ProfileRepository(private val dao: ProfileDao) {

    fun observeAll(): Flow<List<ProfileEntity>> = dao.observeAll()

    suspend fun byId(id: Long): ProfileEntity? = dao.byId(id)

    suspend fun save(p: ProfileEntity): Long = dao.insert(p)

    suspend fun delete(p: ProfileEntity) = dao.delete(p)

    /**
     * Resolve a stored profile into a validated [AudioConfig]. Codec string is
     * matched case-insensitively. Throws [IllegalArgumentException] for unknown
     * codecs or impossible bitrate/sample-rate/mono combos.
     */
    fun toAudioConfig(p: ProfileEntity): AudioConfig {
        val codec = Codec.valueOf(p.codec.uppercase())
        return AudioConfigRules.validate(
            AudioConfig(
                codec = codec,
                bitrateKbps = p.bitrateKbps,
                sampleRateHz = p.sampleRateHz,
                mono = p.mono,
            )
        )
    }
}
