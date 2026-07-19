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
    val password: String,
    val mount: String,            // used by ICY fallback, e.g. "/stream"
    val codec: String,            // "AAC" | "MP3"
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val mono: Boolean,
    val adminUser: String? = null,     // DNAS2 admin.cgi creds for now-playing metadata
    val adminPassword: String? = null, // (optional — only needed for metadata updates)
)
