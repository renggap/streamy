package com.streamy.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val sid: Int,                 // SHOUTcast v2 stream id; 1 default
    // ponytail: source/admin passwords sit in plaintext in the app-private DB (backup is disabled,
    // see data_extraction_rules). Wrap with an Android Keystore AES key if the threat model grows.
    val password: String,
    val mount: String,            // used by ICY fallback, e.g. "/stream"
    val codec: String,            // "AAC" | "MP3"
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val mono: Boolean,
    val adminUser: String? = null,     // DNAS2 admin.cgi creds for now-playing metadata
    val adminPassword: String? = null, // (optional — only needed for metadata updates)
    val mode: String = "SOURCE",       // "SOURCE" (DNAS) | "DJ" (sc_trans djport2 / Icecast harbor)
    val djUser: String? = null,        // sc_trans djlogin (UID) OR Icecast HTTP-Basic user; DJ only
    val djPort: Int? = null,           // DJ port (sc_trans djport2 / harbor); null → uses [port]
    val djProtocol: String = "ULTRAVOX",   // DJ sub-protocol: ULTRAVOX (sc_trans) | ICECAST (AzuraCast/Icecast)
    val djMount: String? = null,       // Icecast/harbor mount (e.g. "/"); required for ICECAST
)
