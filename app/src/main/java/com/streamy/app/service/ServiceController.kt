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
    /** [title], when set, is the now-playing title the stream announces as soon as it connects. */
    fun start(context: Context, profileId: Long, deviceName: String? = null, title: String? = null) {
        val intent = Intent(context, StreamService::class.java).apply {
            action = StreamService.ACTION_START
            putExtra(StreamService.EXTRA_PROFILE_ID, profileId)
            deviceName?.let { putExtra(StreamService.EXTRA_DEVICE_NAME, it) }
            title?.let { putExtra(StreamService.EXTRA_TITLE, it) }
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        deliverStop(context, Intent(context, StreamService::class.java).apply {
            action = StreamService.ACTION_STOP
        })
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

    /** Start the standalone recorder (independent of streaming). Foreground-service safe. */
    fun startRecording(context: Context, profileId: Long, deviceName: String? = null) {
        val intent = Intent(context, RecordService::class.java).apply {
            action = RecordService.ACTION_START
            putExtra(RecordService.EXTRA_PROFILE_ID, profileId)
            deviceName?.let { putExtra(RecordService.EXTRA_DEVICE_NAME, it) }
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** Stop the standalone recorder. */
    fun stopRecording(context: Context) {
        deliverStop(context, Intent(context, RecordService::class.java).apply {
            action = RecordService.ACTION_STOP
        })
    }

    /**
     * STOP is a plain `startService`, never `startForegroundService`: the latter promises a
     * startForeground() within ~5 s, which a service that is already torn down (or just stops)
     * never keeps → ForegroundServiceDidNotStartInTimeException. STOP is only sent from the
     * visible UI (or the service's own notification), so a background-start refusal is not
     * expected; if one happens anyway there is nothing running worth stopping.
     */
    private fun deliverStop(context: Context, intent: Intent) {
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            android.util.Log.w("ServiceController", "stop not delivered", e)
        }
    }

    /** Convenience for binders; UI owns the [ServiceConnection] lifecycle. */
    fun bind(context: Context, conn: ServiceConnection) {
        context.bindService(
            Intent(context, StreamService::class.java),
            conn,
            Context.BIND_AUTO_CREATE,
        )
    }

    /** Bind the standalone recorder so the UI mirrors its state (BIND_AUTO_CREATE keeps an
     *  idle instance alive; recording only runs after an explicit startRecording). */
    fun bindRecord(context: Context, conn: ServiceConnection) {
        context.bindService(
            Intent(context, RecordService::class.java),
            conn,
            Context.BIND_AUTO_CREATE,
        )
    }
}
