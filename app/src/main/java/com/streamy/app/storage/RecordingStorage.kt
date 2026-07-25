package com.streamy.app.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
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
