package com.streamy.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamy.app.MainActivity
import com.streamy.app.R
import com.streamy.app.audio.AudioCapture
import com.streamy.app.audio.AudioPipeline
import com.streamy.app.audio.Encoder
import com.streamy.app.audio.EncoderFactory
import com.streamy.app.audio.Mp3TranscodeSink
import com.streamy.app.audio.Mp3DirectSink
import com.streamy.app.audio.StreamSink
import com.streamy.app.data.AudioConfig
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec
import com.streamy.app.data.ProfileRepository
import com.streamy.app.data.db.AppDatabase
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.storage.RecordingStorage
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Standalone recorder: captures the mic, encodes (direct MP3 pass-through or AAC→MP3
 * transcode), writes a local MP3. Fully independent of [StreamService] — the user records
 * without broadcasting, and both can run at once (two AudioRecords on the same input is
 * legal; the platform mixes or routes per capture config).
 *
 * Owns: its own foreground notification (microphone type), a partial wake lock, one
 * [AudioPipeline] with a pre-armed [StreamSink]. Teardown is the single idempotent
 * [stopRecording] path.
 *
 * Sink lifecycle note: unlike StreamService (which arms the sink after Live because the
 * codec choice comes from the live transport), here the config is known before start, so
 * the sink is built up-front on the IO dispatcher and armed before capture begins.
 */
class RecordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<RecordState>(RecordState.Idle)
    val state: StateFlow<RecordState> = _state.asStateFlow()

    private val db by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "streamy.db")
            .addMigrations(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
            )
            .build()
    }

    @Volatile private var pipeline: AudioPipeline? = null
    private var encoder: Encoder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var shuttingDown = false
    private var profileName: String? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: RecordService get() = this@RecordService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundTyped(buildNotification("Preparing…"))
                val id = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
                if (id < 0L) {
                    _state.value = RecordState.Error("profile_missing")
                    stopRecording()
                } else {
                    startWithProfile(id, intent.getStringExtra(EXTRA_DEVICE_NAME))
                }
            }
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    private fun startWithProfile(profileId: Long, deviceName: String?) {
        synchronized(this) { shuttingDown = false }
        scope.launch {
            try {
                val profile = db.profileDao().byId(profileId)
                if (profile == null) {
                    _state.value = RecordState.Error("profile_missing")
                    stopRecording()
                    return@launch
                }
                profileName = profile.name
                val cfg = AudioConfigRules.clamp(ProfileRepository.toAudioConfig(profile))
                val enc = EncoderFactory.create(cfg)

                // Build the file + sink off-thread (MediaStore insert / LAME init are slow),
                // BEFORE arming — so capture starts with recording already live.
                val name = "streamy-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.mp3"
                val target = RecordingStorage.open(this@RecordService, name)
                if (target == null) {
                    runCatching { enc.close() }
                    _state.value = RecordState.Error("record_open_failed")
                    stopRecording()
                    return@launch
                }
                val sink: StreamSink = if (cfg.codec == Codec.MP3)
                    Mp3DirectSink(target.stream)
                else Mp3TranscodeSink(target.stream, cfg.sampleRateHz, cfg.mono)

                val preferredDevice = resolvePreferredDevice(deviceName)

                synchronized(this@RecordService) {
                    if (shuttingDown) {
                        runCatching { sink.flush(); sink.close() }
                        runCatching { enc.close() }
                        return@launch
                    }
                    closeOrphans()
                    encoder = enc
                    _state.value = RecordState.Recording(target.displayPath)
                    val p = AudioPipeline(
                        capture = AudioCapture(
                            sampleRateHz = cfg.sampleRateHz,
                            mono = cfg.mono,
                            preferredDevice = preferredDevice,
                        ),
                        encoder = enc,
                        transport = null,
                        sink = sink,
                        onMicBusy = {
                            _state.value = RecordState.Error("mic_busy")
                            stopRecording(preserveTerminal = true)
                        },
                        levelFeed = null,
                    )
                    pipeline = p
                    acquireLock()
                    p.start()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "record start failed", e)
                _state.value = RecordState.Error(e.message ?: "record_failed")
                stopRecording(preserveTerminal = true)
            }
        }
    }

    private fun resolvePreferredDevice(name: String?): android.media.AudioDeviceInfo? {
        if (name.isNullOrBlank()) return null
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.isSource && it.productName?.toString() == name }
    }

    fun setMicGain(g: Float) {
        pipeline?.setGain(g)
    }

    /** Single idempotent teardown. Safe from any thread. */
    @Synchronized
    fun stopRecording(preserveTerminal: Boolean = false) {
        if (shuttingDown) return
        shuttingDown = true
        closeOrphans()
        releaseLock()
        _state.value = if (preserveTerminal && _state.value is RecordState.Error) _state.value else RecordState.Idle
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Log.e(TAG, "stopForeground", e)
        }
        stopSelf()
    }

    private fun closeOrphans() {
        try { pipeline?.stop() } catch (e: Throwable) { Log.e(TAG, "pipeline.stop", e) }
        try { encoder?.close() } catch (e: Throwable) { Log.e(TAG, "encoder.close", e) }
        pipeline = null; encoder = null
    }

    private fun acquireLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "streamy::record").apply {
                // Same reasoning as StreamService: a cap would end long recordings mid-take.
                acquire()
            }
        }
    }

    private fun releaseLock() {
        wakeLock?.let { if (it.isHeld) try { it.release() } catch (_: Throwable) {} }
        wakeLock = null
    }

    override fun onDestroy() {
        try { stopRecording() } catch (_: Throwable) {}
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
                    NotificationChannel(CHANNEL_ID, "Streamy Recorder", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Streamy Recorder")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundTyped(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val TAG = "RecordService"
        private const val CHANNEL_ID = "streamy_record"
        const val ACTION_START = "com.streamy.app.action.RECORD_START"
        const val ACTION_STOP = "com.streamy.app.action.RECORD_STOP"
        const val EXTRA_PROFILE_ID = "com.streamy.app.extra.PROFILE_ID"
        const val EXTRA_DEVICE_NAME = "com.streamy.app.extra.DEVICE_NAME"
        const val NOTIF_ID = 0x5bee8
    }
}

/** UI-facing state of the standalone recorder. */
sealed interface RecordState {
    data object Idle : RecordState
    data class Recording(val path: String) : RecordState
    data class Error(val reason: String) : RecordState
}
