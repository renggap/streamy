package com.streamy.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.streamy.app.MainActivity
import com.streamy.app.audio.AudioCapture
import com.streamy.app.audio.AudioPipeline
import com.streamy.app.audio.Encoder
import com.streamy.app.audio.EncoderFactory
import com.streamy.app.audio.LevelFeed
import com.streamy.app.audio.LevelSnapshot
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec
import com.streamy.app.data.ProfileRepository
import com.streamy.app.data.db.AppDatabase
import com.streamy.app.R
import com.streamy.app.net.SourceClient
import com.streamy.app.net.StreamTransport
import com.streamy.app.net.TransportState
import com.streamy.app.net.UvoxClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground microphone service that owns the live broadcast pipeline:
 * Room lookup -> [EncoderFactory] -> [UvoxClient] over [StreamTransport] -> [AudioPipeline].
 *
 * Lifecycle: [onStartCommand] routes [ACTION_START]/[ACTION_STOP]/[ACTION_META]; [stopAndShutdown]
 * is the single idempotent teardown path used by every error/stop branch.
 *
 * ponytail: skip DI / media-session / separate :stream process — single service, one scope,
 *  add Hilt when config sprawl justifies it (Task 14).
 */
class StreamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    val state: StateFlow<StreamState> = _state.asStateFlow()

    // One LevelFeed instance: passed into AudioPipeline (push side, audio thread) AND
    // exposed here as levels (pull side, UI). Single instance — not two.
    private val levelFeed = LevelFeed()
    val levels: StateFlow<LevelSnapshot?> = levelFeed.flow

    private val db by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "streamy.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
    }

    private var transport: StreamTransport? = null
    private var pipeline: AudioPipeline? = null
    private var client: SourceClient? = null
    private var encoder: Encoder? = null
    private var profileName: String? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var shuttingDown = false
    @Volatile private var foregrounded = false

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: StreamService get() = this@StreamService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // FGS timing: promote BEFORE any blocking work. ~5s budget.
                startForegroundTyped(buildNotification("Connecting…"))
                val id = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
                if (id < 0L) {
                    _state.value = StreamState.Error("profile_missing")
                    stopAndShutdown()
                } else {
                    startWithProfile(id)
                }
            }
            ACTION_STOP -> stopAndShutdown()
            ACTION_META -> {
                val title = intent.getStringExtra(EXTRA_TITLE)
                if (title != null) {
                    scope.launch {
                        (client as? UvoxClient)?.updateNowPlaying(title)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startWithProfile(profileId: Long) {
        // Fresh start: clear any prior shutdown so a subsequent teardown actually runs
        // (matters when the bound service instance is reused across Stop -> Start).
        synchronized(this) { shuttingDown = false }
        scope.launch {
            try {
                val profile = db.profileDao().byId(profileId)
                if (profile == null) {
                    _state.value = StreamState.Error("profile_missing")
                    stopAndShutdown()
                    return@launch
                }
                profileName = profile.name
                // Clamp impossible combos (MP3 16/24 kbps + 44.1/48 kHz → 22.05 kHz MPEG-2) so
                // capture + encoder agree and LAME doesn't bump the bitrate (24→32) silently.
                val cfg = AudioConfigRules.clamp(ProfileRepository.toAudioConfig(profile))

                // Heavy setup OUTSIDE the monitor: native encoder alloc + source-client ctor
                // (no socket yet — connect() runs later from the transport coroutine).
                val enc = EncoderFactory.create(cfg)

                // Ultravox 2.1 source client for BOTH codecs: AAC rides audio/aacp (0x8003),
                // MP3 rides audio/mpeg (0x7000) — live-verified stable on DNAS2 2.6.1.
                // Unifying on UvoxClient also gives MP3 in-stream metadata (0x902) for free.
                val source: SourceClient = UvoxClient(
                    host = profile.host, port = profile.port, sid = profile.sid,
                    password = profile.password,
                    mime = if (cfg.codec == Codec.AAC) "audio/aacp" else "audio/mpeg",
                    sampleRateHz = cfg.sampleRateHz,
                    channels = if (cfg.mono) 1 else 2,
                )

                // Assign + start under the monitor so a concurrent Stop can't orphan the
                // transport / audio thread / native encoder. Re-check shuttingDown in case a
                // Stop arrived during the setup above — if so, tear down what we just built.
                synchronized(this) {
                    if (shuttingDown) {
                        runCatching { enc.close() }
                        runCatching { source.close() }
                        return@launch
                    }
                    encoder = enc
                    client = source
                    _state.value = StreamState.Connecting
                    val t = StreamTransport(
                        scope = scope,
                        connect = { source.connect(cfg.bitrateKbps, profile.name, "Speech") },
                        send = { source.send(it) },
                        onState = { ts -> ts.toStreamState()?.let { s -> _state.value = s } },
                    )
                    transport = t
                    val p = AudioPipeline(
                        capture = AudioCapture(cfg.sampleRateHz, cfg.mono),
                        encoder = enc,
                        transport = t,
                        onMicBusy = {
                            _state.value = StreamState.Error("mic_busy")
                            stopAndShutdown()
                        },
                        levelFeed = levelFeed,
                    )
                    pipeline = p
                    acquireLocks()
                    t.start()
                    p.start()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "start failed", e)
                _state.value = StreamState.Error(e.message ?: "start_failed")
                stopAndShutdown()
            }
        }
    }

    /**
     * Map [TransportState] -> [StreamState]. Returns null for [TransportState.Backpressure]
     * so the UI isn't spammed with drop-count updates (drop-newest only).
     */
    private fun TransportState.toStreamState(): StreamState? = when (this) {
        TransportState.Connecting -> StreamState.Connecting
        TransportState.Live -> {
            updateNotification("Live: ${profileName.orEmpty()}")
            StreamState.Live
        }
        is TransportState.Reconnecting -> StreamState.Reconnecting(attempt)
        is TransportState.Backoff ->
            if (attempt <= 0) StreamState.Connecting else StreamState.Reconnecting(attempt)
        is TransportState.Backpressure -> null
        is TransportState.Failed -> StreamState.Error(reason)
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streamy::stream").apply {
                // Indefinite acquire: a timeout cap would cut off long background broadcasts
                // (the core feature). stopAndShutdown + onDestroy reliably release it.
                acquire()
            }
        }
        if (wifiLock == null) {
            // MODE_FULL_HIGH_PERF (value 3) renamed to WIFI_MODE_FULL_HIGH_PERF in API 26;
            // old name removed from compileSdk 35. Constant value unchanged — works on minSdk 24.
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "streamy::wifi").apply {
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) try { it.release() } catch (_: Throwable) {} }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) try { it.release() } catch (_: Throwable) {} }
        wifiLock = null
    }

    /** Single idempotent teardown path. Safe to call from any thread. */
    @Synchronized
    fun stopAndShutdown() {
        if (shuttingDown) return
        shuttingDown = true
        for (step in listOf(
            { try { pipeline?.stop() } catch (e: Throwable) { Log.e(TAG, "pipeline.stop", e) } },
            { try { transport?.stop() } catch (e: Throwable) { Log.e(TAG, "transport.stop", e) } },
            { try { client?.close() } catch (e: Throwable) { Log.e(TAG, "client.close", e) } },
            { try { encoder?.close() } catch (e: Throwable) { Log.e(TAG, "encoder.close", e) } },
        )) step()
        pipeline = null; transport = null; client = null; encoder = null
        levelFeed.reset()
        releaseLocks()
        _state.value = StreamState.Idle
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregrounded = false
        } catch (e: Throwable) {
            Log.e(TAG, "stopForeground", e)
        }
        stopSelf()
    }

    override fun onDestroy() {
        // Defensive: if process kill/recycle happens before ACTION_STOP, drop everything.
        try { stopAndShutdown() } catch (_: Throwable) {}
        // Close the Room db here (not in stopAndShutdown) so it isn't re-closed on each
        // stop/start cycle within one instance. runCatching guards the `by lazy` init.
        runCatching { db.close() }
        scope.cancel()
        super.onDestroy()
    }

    // ---- Notification ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Streamy Stream", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Throwable) { Log.e(TAG, "notify", e) }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Streamy")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * API 29+ → 3-arg typed overload (required since targetSdk 35 enforces declared FGS type);
     * API 24–28 → 2-arg overload (typed form doesn't exist; no enforcement on those levels).
     */
    private fun startForegroundTyped(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
        foregrounded = true
    }

    companion object {
        private const val TAG = "StreamService"
        private const val CHANNEL_ID = "streamy_stream"

        const val ACTION_START = "com.streamy.app.action.START"
        const val ACTION_STOP = "com.streamy.app.action.STOP"
        const val ACTION_META = "com.streamy.app.action.META"
        const val EXTRA_PROFILE_ID = "com.streamy.app.extra.PROFILE_ID"
        const val EXTRA_TITLE = "com.streamy.app.extra.TITLE"
        const val NOTIF_ID = 0x5bee7
    }
}
