package com.streamy.app.service

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import androidx.core.content.ContextCompat

/**
 * Static entry points for the UI layer. Keeps the [Intent] extras + action keys in
 * one place so callers never touch raw strings.
 */
object ServiceController {

    /**
     * Component-explicit [Intent] + [ContextCompat.startForegroundService].
     * [deviceName] is the user-selected input device's productName (or null for default);
     * passed via Intent — NOT SharedPreferences — so an immediate start after selection can't
     * lose to an in-flight apply().
     */
    fun start(context: Context, profileId: Long, deviceName: String? = null) {
        val intent = Intent(context, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_PROFILE_ID, profileId)
            deviceName?.let { putExtra(StreamService.EXTRA_DEVICE_NAME, it) }
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, StreamService::class.java).apply {
            action = StreamService.ACTION_STOP
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Push now-playing metadata to the running stream via Ultravox 0x902 (no admin creds).
     * Service must already be foreground (Live); just delivers the action — `startService`
     * is a no-op restart if the service is already running.
     */
    fun updateNowPlaying(context: Context, title: String) {
        val intent = Intent(context, StreamService::class.java).apply {
            action = StreamService.ACTION_META
            putExtra(StreamService.EXTRA_TITLE, title)
        }
        context.startService(intent)
    }

    /** Toggle local MP3 recording on the running (Live) stream. No-op if not Live. */
    fun setRecording(context: Context, on: Boolean) {
        val intent = Intent(context, StreamService::class.java).apply {
            action = if (on) StreamService.ACTION_RECORD_ON else StreamService.ACTION_RECORD_OFF
        }
        context.startService(intent)
    }

    /** Convenience for binders; UI owns the [ServiceConnection] lifecycle. */
    fun bind(context: Context, conn: ServiceConnection) {
        context.bindService(
            Intent(context, StreamService::class.java),
            conn,
            Context.BIND_AUTO_CREATE,
        )
    }
}
