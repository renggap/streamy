package com.streamy.app.net

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.streamy.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater backed by the GitHub Release of this repo. CI attaches `update.json`
 * (versionCode / versionName / apkUrl) next to the signed APK on every `v*` tag — see
 * .github/workflows/android.yml. Flow: check on startup → dialog → download APK to the
 * cache dir → hand it to the system package installer (user confirms there).
 *
 * No HTTP client dependency on purpose: two short GETs via HttpURLConnection (the release
 * asset redirect chain is plain https, well within cleartext policy).
 */
object UpdateChecker {

    /** Parsed `update.json`. */
    data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

    /** Parse + null on any malformed/missing field — a bad feed must never crash the app. */
    fun parse(json: String): UpdateInfo? = runCatching {
        val o = JSONObject(json)
        val code = o.optInt("versionCode")
        val name = o.optString("versionName")
        val url = o.optString("apkUrl")
        if (code <= 0 || name.isBlank() || !url.startsWith("https://")) null
        else UpdateInfo(code, name, url)
    }.getOrNull()

    /** Only a strictly newer versionCode counts — versionName is display-only. */
    fun isNewer(info: UpdateInfo, currentVersionCode: Int = BuildConfig.VERSION_CODE): Boolean =
        info.versionCode > currentVersionCode

    /** GET [url], follow redirects, return the body (null on non-2xx / IO error). */
    private fun httpGet(url: String): String? =
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { s ->
                    s.readBytes().toString(Charsets.UTF_8).trim()
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

    /** Fetch the feed; null = no update / offline / feed broken. */
    suspend fun fetchUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        httpGet(BuildConfig.UPDATE_FEED_URL)?.let(::parse)
    }

    /**
     * Download the APK into the cache dir (wiped by the OS when it needs space — an update
     * file must never squat on persistent storage). Single fixed name: a newer update
     * simply overwrites the previous file.
     */
    suspend fun downloadApk(context: Context, info: UpdateInfo): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val out = File(dir, "streamy-update.apk")
                val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    conn.inputStream.use { s ->
                        out.outputStream().use { s.copyTo(it) }
                    }
                } finally {
                    conn.disconnect()
                }
                out
            }.getOrNull()
        }

    /** View intent that opens the system package installer over the downloaded APK. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
