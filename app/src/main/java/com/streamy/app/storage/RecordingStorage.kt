package com.streamy.app.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Writable target for a local recording + a human-readable path for the UI. */
data class RecordingTarget(val stream: OutputStream, val displayPath: String)

/**
 * Opens a recording under the system media collection — **no runtime permission** on any tier
 * (an app owns files it inserts into MediaStore on API 29+; app-private external needs nothing).
 *
 *  - **API 31+**: `Recordings/Streamy`. `Recordings/` is a recognized audio top-level dir only
 *    from API 31 ([Environment.DIRECTORY_RECORDINGS]); the user-visible "Recordings" folder.
 *  - **API 29-30**: `Music/Streamy`. MediaStore.Audio **rejects** `Recordings/` on Q/R — the
 *    allow-list is only [Alarms, Music, Notifications, Podcasts, Ringtones] (`IllegalArgumentException`).
 *  - **API 24-28**: app-private `getExternalFilesDir(MUSIC)/Streamy`. [MediaStore.Audio.Media.RELATIVE_PATH]
 *    is API 29+, and the legacy public dir would need WRITE_EXTERNAL_STORAGE (not held).
 *
 * On MediaStore targets, closing [RecordingTarget.stream] flips `IS_PENDING` 1→0 (publishing the
 * file to other apps) — or deletes the empty stub if nothing was written.
 */
object RecordingStorage {

    fun open(context: Context, displayName: String, mimeType: String = "audio/mpeg"): RecordingTarget? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = context.contentResolver
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val topDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Recordings" else "Music"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "$topDir/Streamy")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(collection, values) ?: return null
            val out = try {
                cr.openOutputStream(uri, "w") ?: return deleteAndNull(cr, uri)
            } catch (e: Throwable) {
                return deleteAndNull(cr, uri)
            }
            // Wrap so close() publishes (IS_PENDING=0) when bytes were written, else deletes the stub.
            val stream = PendingFinalizingStream(out) { dirty ->
                runCatching {
                    if (dirty) {
                        cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                    } else {
                        cr.delete(uri, null, null)
                    }
                }
            }
            return RecordingTarget(stream, "$topDir/Streamy/$displayName")
        }
        // API 24-28: app-private external (no permission). Not visible to other apps, but on a
        // pre-Q device this is the only no-permission option.
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return null
        val dir = File(base, "Streamy").apply { mkdirs() }
        val file = File(dir, displayName)
        val fout = try { FileOutputStream(file) } catch (e: Throwable) { return null }
        return RecordingTarget(fout, file.absolutePath)
    }

    /**
     * Publishes recordings left `IS_PENDING=1` by a process death mid-recording (OOM/force-stop
     * skips the close that would flip the flag) — otherwise they stay invisible to every other
     * app forever and MediaStore eventually purges them. Empty stubs are deleted instead.
     *
     * All recording happens in-process, so a pending item added before this process started
     * cannot be in use. [processStartSec] (captured in Application.onCreate) fences off a
     * recording the user starts while this sweep is still running on its background thread —
     * strict `<`, so a same-second insert is left alone. Runs blocking; call off-main.
     */
    fun publishOrphans(context: Context, processStartSec: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cr = context.contentResolver
        val selection = "${MediaStore.Audio.Media.IS_PENDING}=1" +
            " AND ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE 'streamy-%'" +
            " AND ${MediaStore.Audio.Media.DATE_ADDED} < $processStartSec"
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val ids = mutableListOf<Long>()
        try {
            // Pending rows are hidden from plain queries; opting in differs by release.
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cr.query(collection, projection, Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }, null)
            } else {
                @Suppress("DEPRECATION")
                cr.query(MediaStore.setIncludePending(collection), projection, selection, null, null)
            }
            cursor?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        } catch (e: Throwable) {
            Log.w("RecordingStorage", "orphan query failed", e); return
        }
        for (id in ids) {
            val uri = android.content.ContentUris.withAppendedId(collection, id)
            runCatching {
                val size = cr.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                if (size > 0) {
                    cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                } else {
                    cr.delete(uri, null, null)
                }
            }.onFailure { Log.w("RecordingStorage", "orphan $id", it) }
        }
    }

    private fun deleteAndNull(cr: ContentResolver, uri: Uri): RecordingTarget? {
        runCatching { cr.delete(uri, null, null) }
        return null
    }

    /** Delegating stream that tracks whether any byte was written, then finalizes on close. */
    private class PendingFinalizingStream(
        private val delegate: OutputStream,
        private val onClosed: (dirty: Boolean) -> Unit,
    ) : OutputStream() {
        private var dirty = false
        override fun write(b: Int) { dirty = true; delegate.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { dirty = true; delegate.write(b, off, len) }
        override fun flush() = delegate.flush()
        override fun close() {
            try { delegate.close() } finally { onClosed(dirty) }
        }
    }
}
