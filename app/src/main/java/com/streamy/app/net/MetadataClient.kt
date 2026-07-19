package com.streamy.app.net

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

/**
 * Pushes now-playing metadata to a SHOUTcast v2 (DNAS2) server via
 * `admin.cgi?sid=<sid>&mode=updinfo&song=<urlencoded>`.
 *
 * Pure JVM — no Android deps — so [buildUrl] is unit-testable without Robolectric.
 *
 * Verified live: GET with header `Authorization: Basic base64("<adminUser>:<adminPassword>")`
 * → HTTP 200 and the server's `SONGTITLE` updates.
 */
class MetadataClient {

    /**
     * Fire the updinfo request. 5s connect/read timeouts, Basic auth from [adminUser]/
     * [adminPass]. Returns true only on HTTP 200. Any exception (network, auth, parse) → false.
     */
    fun updateNowPlaying(
        host: String,
        port: Int,
        sid: Int,
        adminUser: String,
        adminPass: String,
        title: String,
    ): Boolean = try {
        val url = URL(buildUrl(host, port, sid, title))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            val cred = "$adminUser:$adminPass"
            val basic = Base64.getEncoder().encodeToString(cred.toByteArray(Charsets.UTF_8))
            setRequestProperty("Authorization", "Basic $basic")
        }
        try {
            conn.responseCode == 200
        } finally {
            conn.disconnect()
        }
    } catch (_: Throwable) {
        false
    }

    companion object {
        /**
         * Build the encoded admin.cgi URL. Spaces → `%20` (canonical URL encoding, not the
         * `+` form-encoding [URLEncoder] emits by default — replaced so the assertion and
         * the server both see the same byte sequence).
         */
        fun buildUrl(host: String, port: Int, sid: Int, title: String): String {
            val song = URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            return "http://$host:$port/admin.cgi?sid=$sid&mode=updinfo&song=$song"
        }
    }
}
