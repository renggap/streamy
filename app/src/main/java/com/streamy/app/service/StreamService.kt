package com.streamy.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamy.app.MainActivity
import com.streamy.app.audio.AudioCapture
import com.streamy.app.audio.AudioPipeline
import com.streamy.app.audio.Encoder
import com.streamy.app.audio.EncoderFactory
import com.streamy.app.audio.LevelFeed
import com.streamy.app.audio.LevelSnapshot
import com.streamy.app.audio.Mp3DirectSink
import com.streamy.app.audio.Mp3TranscodeSink
import com.streamy.app.audio.StreamSink
import com.streamy.app.data.AudioConfig
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec
import com.streamy.app.data.DjProtocol
import com.streamy.app.data.ProfileRepository
import com.streamy.app.data.db.DatabaseHolder
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.R
import com.streamy.app.net.IcecastSourceClient
import com.streamy.app.net.SourceClient
import com.streamy.app.net.StreamTransport
import com.streamy.app.net.TransportState
import com.streamy.app.net.UvoxClient
import com.streamy.app.storage.RecordingStorage
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
 * Foreground microphone service that owns the live broadcast pipeline:
 * Room lookup -> [EncoderFactory] -> [UvoxClient] over [StreamTransport] -> [AudioPipeline].
 *
 * Lifecycle: [onStartCommand] routes START/STOP/META/RECORD actions; [stopAndShutdown]
 * is the single idempotent teardown path used by every error/stop branch.
 *
 * Live mic settings: [micGain] is held here (not on AudioCapture, which is recreated each
 * start) so it survives across stop→start and seeds the next [AudioCapture]. The selected
 * input device NAME arrives via the START intent (never SharedPreferences — apply() can lose
 * a race with an immediate start) and is resolved to a live [AudioDeviceInfo] per session.
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

    // Local MP3 recording state. recordingPath = last/active file; isRecording = armed.
    private val _recordingPath = MutableStateFlow<String?>(null)
    val recordingPath: StateFlow<String?> = _recordingPath.asStateFlow()
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    // Latched when the armed recording hit a write/encode failure (disk full etc.) and was
    // dropped mid-show; cleared on the next arm. The stream itself keeps going.
    private val _recordingFailed = MutableStateFlow(false)
    val recordingFailed: StateFlow<Boolean> = _recordingFailed.asStateFlow()

    private val db by lazy { DatabaseHolder.get(this) }

    private var transport: StreamTransport? = null
    @Volatile private var pipeline: AudioPipeline? = null
    private var client: SourceClient? = null
    private var encoder: Encoder? = null
    private var profileName: String? = null

    // Last time a Backpressure drop-count made it to the UI (throttled; see toStreamState).
    @Volatile private var lastDropUiEmitMs = 0L

    /** Snapshot of the running encode config — drives MP3-vs-transcode sink selection. */
    @Volatile private var liveCfg: AudioConfig? = null

    /** Mic gain held at service scope so it survives AudioCapture recreation. Seeded by VM via binder. */
    @Volatile private var micGain: Float = 1.0f

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
        if (intent?.action == null) {
            // Defensive only: START_NOT_STICKY means the system never restarts us with a null
            // intent. A stream can't resume after a process kill anyway (no socket, no session),
            // and a sticky restart is NOT an FGS start — promoting here could throw on 31+/34+
            // (background start / revoked mic) and crash-loop. Just go away.
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                // FGS timing: promote BEFORE any blocking work. ~5s budget.
                startForegroundTyped(buildNotification("Connecting…"))
                val id = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
                if (id < 0L) {
                    synchronized(this) { shuttingDown = false }   // fresh start: let fail() tear down
                    fail("profile_missing")
                } else {
                    startWithProfile(id, intent.getStringExtra(EXTRA_DEVICE_NAME))
                }
            }
            ACTION_STOP -> acknowledgeStop()
            ACTION_META -> {
                val title = intent.getStringExtra(EXTRA_TITLE)
                if (title != null) {
                    scope.launch {
                        client?.updateNowPlaying(title)
                    }
                }
            }
            ACTION_RECORD_ON -> startRecording()
            ACTION_RECORD_OFF -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startWithProfile(profileId: Long, deviceName: String?) {
        // Fresh start: clear any prior shutdown so a subsequent teardown actually runs
        // (matters when the bound service instance is reused across Stop -> Start).
        synchronized(this) { shuttingDown = false }
        scope.launch {
            try {
                val profile = db.profileDao().byId(profileId)
                if (profile == null) {
                    fail("profile_missing")
                    return@launch
                }
                profileName = profile.name
                // Clamp impossible combos (MP3 16/24 kbps + 44.1/48 kHz → 22.05 kHz MPEG-2) so
                // capture + encoder agree and LAME doesn't bump the bitrate (24→32) silently.
                val cfg = AudioConfigRules.clamp(ProfileRepository.toAudioConfig(profile))
                liveCfg = cfg

                // Heavy setup OUTSIDE the monitor: native encoder alloc + source-client ctor
                // (no socket yet — connect() runs later from the transport coroutine).
                val enc = EncoderFactory.create(cfg)

                // Resolve the source client. SOURCE → Ultravox to DNAS. DJ → Ultravox (sc_trans)
                // OR Icecast HTTP source (AzuraCast / Icecast / Liquidsoap harbor), per the profile's
                // explicit djProtocol.
                val isDj = profile.mode.equals("DJ", ignoreCase = true)
                val djPort = if (isDj) (profile.djPort ?: profile.port) else profile.port
                val source: SourceClient = if (!isDj) {
                    UvoxClient(
                        host = profile.host, port = profile.port, sid = profile.sid,
                        password = profile.password,
                        mime = if (cfg.codec == Codec.AAC) "audio/aacp" else "audio/mpeg",
                        sampleRateHz = cfg.sampleRateHz, channels = if (cfg.mono) 1 else 2,
                    )
                } else {
                    when (DjProtocol.parse(profile.djProtocol)) {
                        DjProtocol.ULTRAVOX -> UvoxClient(
                            host = profile.host, port = djPort, sid = profile.sid,
                            password = profile.password,
                            mime = if (cfg.codec == Codec.AAC) "audio/aacp" else "audio/mpeg",
                            sampleRateHz = cfg.sampleRateHz, channels = if (cfg.mono) 1 else 2,
                            djUser = profile.djUser,
                        )
                        DjProtocol.ICECAST -> {
                            // Icecast source mounts only accept MP3 here — coerce AAC defensively
                            // (VM + form already block it; this is the last line of defense).
                            val iceCfg = if (cfg.codec == Codec.AAC) cfg.copy(codec = Codec.MP3) else cfg
                            if (iceCfg !== cfg) Log.w(TAG, "AAC coerced to MP3 for Icecast mount")
                            IcecastSourceClient(
                                host = profile.host, port = djPort,
                                mount = profile.djMount ?: "/",
                                user = profile.djUser ?: "",
                                password = profile.password,
                                mime = if (iceCfg.codec == Codec.AAC) "audio/aacp" else "audio/mpeg",
                            )
                        }
                    }
                }

                // Resolve the user-selected input device by NAME for THIS session (ids are not
                // stable across reboot/USB replug). Null → default mic.
                val preferredDevice = resolvePreferredDevice(deviceName)

                // Assign + start under the service monitor so a concurrent Stop can't orphan the
                // transport / audio thread / native encoder. (Lock the SERVICE, not the coroutine
                // scope, so this excludes [stopAndShutdown]'s @Synchronized.) Re-check shuttingDown
                // in case a Stop arrived during setup — and recycle any prior live session so a
                // double ACTION_START can't leak an AudioRecord/encoder/socket.
                synchronized(this@StreamService) {
                    if (shuttingDown) {
                        runCatching { enc.close() }
                        runCatching { source.close() }
                        return@launch
                    }
                    closeOrphans()
                    encoder = enc
                    client = source
                    liveCfg = cfg
                    _state.value = StreamState.Connecting
                    val t = StreamTransport(
                        scope = scope,
                        connect = { source.connect(cfg.bitrateKbps, profile.name, "Speech") },
                        send = { source.send(it) },
                        onState = { ts ->
                            // Mic-busy / teardown race guard: the audio thread may set Error +
                            // call stopAndShutdown() while the transport coroutine is still inside
                            // connect() and about to emit Connecting/Live. Once shuttingDown is
                            // latched, transport state must not overwrite the Error the user saw.
                            if (shuttingDown) return@StreamTransport
                            if (ts is TransportState.Failed) {
                                // Terminal: release the mic, locks and FGS too — not just show
                                // Error while everything stays held. Off the transport coroutine
                                // (teardown stops this very transport).
                                scope.launch { fail(ts.reason) }
                                return@StreamTransport
                            }
                            ts.toStreamState()?.let { s -> _state.value = s }
                        },
                    )
                    transport = t
                    val p = AudioPipeline(
                        capture = AudioCapture(
                            sampleRateHz = cfg.sampleRateHz,
                            mono = cfg.mono,
                            initialGain = micGain,
                            preferredDevice = preferredDevice,
                        ),
                        encoder = enc,
                        transport = t,
                        onMicBusy = { fail("mic_busy") },
                        levelFeed = levelFeed,
                        onFatal = { e ->
                            Log.e(TAG, "audio pipeline failed", e)
                            scope.launch { fail("audio_failed") }
                        },
                        onSinkError = {
                            _isRecording.value = false
                            _recordingFailed.value = true
                        },
                    )
                    pipeline = p
                    acquireLocks()
                    t.start()
                    p.start()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "start failed", e)
                fail(e.message ?: "start_failed")
            }
        }
    }

    /** Push a live mic gain from the UI; applied immediately if streaming, else stashed for next start. */
    fun setMicGain(g: Float) {
        micGain = g
        pipeline?.setGain(g)
    }

    // ---- Local MP3 recording ----

    /** Arm recording. Only valid while Live; file + sink are built off the audio thread.
     *  The [_isRecording] flag is claimed synchronously on the main thread (onStartCommand is
     *  serial) before the IO launch so a rapid double-tap can't arm two sinks; the sink is then
     *  registered under the service monitor so a concurrent stopAndShutdown can't orphan it. */
    private fun startRecording() {
        if (_state.value !is StreamState.Live) return
        if (liveCfg == null) return
        if (_isRecording.value) return          // already armed — ignore double ACTION_RECORD_ON
        _isRecording.value = true
        _recordingFailed.value = false
        val cfg = liveCfg ?: run { _isRecording.value = false; return }
        scope.launch(Dispatchers.IO) {
            var path: String? = null
            val built: StreamSink? = try {
                val name = "streamy-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}-${System.currentTimeMillis() % 1000}.mp3"
                val target = RecordingStorage.open(this@StreamService, name)
                if (target == null) null else {
                    path = target.displayPath
                    // MP3 stream → write frames as-is (zero re-encode). AAC stream → transcode via LAME.
                    try {
                        if (cfg.codec == Codec.MP3) Mp3DirectSink(target.stream)
                        else Mp3TranscodeSink(target.stream, cfg.sampleRateHz, cfg.mono)
                    } catch (e: Throwable) {
                        // LAME init failed: close the stream so the fd and the pending
                        // MediaStore stub (deleted when nothing was written) don't leak.
                        runCatching { target.stream.close() }
                        throw e
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "record file open failed", e); null
            }
            if (built == null) {
                _isRecording.value = false
                _recordingFailed.value = true
                return@launch
            }
            // Arm under the service monitor so a teardown that nulled the pipeline can't leave the
            // sink orphaned; close the just-built sink on bail (fd + native LAME).
            synchronized(this@StreamService) {
                if (shuttingDown || pipeline == null || !_isRecording.value) {
                    runCatching { built.flush(); built.close() }
                    _isRecording.value = false
                } else {
                    pipeline?.armRecord(built)
                    // Only now: a bailed arm must not leave the UI pointing at a deleted file.
                    _recordingPath.value = path
                }
            }
        }
    }

    private fun stopRecording() {
        // Under the service monitor, like the arm in startRecording: otherwise a disarm landing
        // between that block's `_isRecording` check and armRecord() is lost and the recording
        // keeps running while the UI says it stopped.
        synchronized(this) {
            _isRecording.value = false
            pipeline?.disarmRecord()
        }
    }

    /** Resolve a previously-selected input device by productName (ids are not stable across reboots). */
    private fun resolvePreferredDevice(name: String?): AudioDeviceInfo? {
        if (name.isNullOrBlank()) return null
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.isSource && it.productName?.toString() == name }
    }

    /**
     * Map [TransportState] -> [StreamState]. [TransportState.Backpressure] is folded into the
     * current [StreamState.Live] (its drops show up in [StreamState.Live.droppedFrames]) so a
     * DJ sees audio being dropped as it happens — but throttled to ~4 Hz so a sustained stall
     * doesn't spam recompositions. Returns null so the transport's state machine is unchanged.
     */
    private fun TransportState.toStreamState(): StreamState? = when (this) {
        TransportState.Connecting -> StreamState.Connecting
        TransportState.Live -> {
            updateNotification("Live: ${profileName.orEmpty()}")
            val t = transport
            StreamState.Live(droppedFrames = t?.sessionDroppedFrames ?: 0L)
        }
        is TransportState.Reconnecting -> StreamState.Reconnecting(attempt)
        is TransportState.Backoff ->
            if (attempt <= 0) StreamState.Connecting else StreamState.Reconnecting(attempt)
        is TransportState.Backpressure -> {
            val current = _state.value
            val now = System.currentTimeMillis()
            if (current is StreamState.Live && now - lastDropUiEmitMs >= DROP_UI_THROTTLE_MS) {
                val t = transport
                val total = t?.sessionDroppedFrames ?: 0L
                if (total != current.droppedFrames) {
                    lastDropUiEmitMs = now
                    _state.value = StreamState.Live(droppedFrames = total)
                }
            }
            null
        }
        is TransportState.Failed -> StreamState.Error(reason)   // normally routed via fail()
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

    /**
     * Terminal failure: surface [reason] as Error and tear down, keeping the Error visible.
     * No-op once a teardown already ran — a late failure (e.g. encode-after-close while the user
     * is stopping) must not overwrite the Idle/Error the user already saw.
     */
    @Synchronized
    private fun fail(reason: String) {
        if (shuttingDown) return
        _state.value = StreamState.Error(reason)
        stopAndShutdown(preserveTerminal = true)
    }

    /**
     * User STOP. After a terminal error the session is already torn down (shuttingDown latched,
     * Error preserved for the UI) and the service lingers only because the Activity is bound;
     * STOP then means "acknowledged" → back to Idle so Start is reachable again. Before this,
     * STOP in Error was swallowed by the idempotency latch and the UI stayed stuck on Error.
     */
    @Synchronized
    private fun acknowledgeStop() {
        if (shuttingDown) {
            _state.value = StreamState.Idle
            stopSelf()
        } else {
            stopAndShutdown()
        }
    }

    /** Single idempotent teardown path. Safe to call from any thread. */
    @Synchronized
    fun stopAndShutdown(preserveTerminal: Boolean = false) {
        if (shuttingDown) return
        shuttingDown = true
        _isRecording.value = false
        closeOrphans()
        levelFeed.reset()
        releaseLocks()
        _state.value = resolveTeardownState(_state.value, preserveTerminal)
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregrounded = false
        } catch (e: Throwable) {
            Log.e(TAG, "stopForeground", e)
        }
        // A Live emission racing this teardown may have re-posted the ongoing notification via
        // updateNotification() after stopForeground removed it; make sure nothing lingers.
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID) }
        stopSelf()
    }

    /** Stop + null the current session's resources WITHOUT flipping shutdown state, releasing
     *  locks, or stopping the foreground notification — used by [stopAndShutdown] and to recycle
     *  a prior session when a fresh [startWithProfile] arrives (double ACTION_START guard). */
    private fun closeOrphans() {
        for (step in listOf(
            { try { pipeline?.stop() } catch (e: Throwable) { Log.e(TAG, "pipeline.stop", e) } },
            { try { transport?.stop() } catch (e: Throwable) { Log.e(TAG, "transport.stop", e) } },
            { try { client?.close() } catch (e: Throwable) { Log.e(TAG, "client.close", e) } },
            { try { encoder?.close() } catch (e: Throwable) { Log.e(TAG, "encoder.close", e) } },
        )) step()
        pipeline = null; transport = null; client = null; encoder = null; liveCfg = null
    }

    override fun onDestroy() {
        // Defensive: if process kill/recycle happens before ACTION_STOP, drop everything.
        try { stopAndShutdown() } catch (_: Throwable) {}
        // Shared DatabaseHolder singleton is process-owned — never closed here.
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
        if (shuttingDown) return
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

        fun resolveTeardownState(current: StreamState, preserveTerminal: Boolean): StreamState =
            if (preserveTerminal && current is StreamState.Error) current else StreamState.Idle

        const val ACTION_START = "com.streamy.app.action.START"
        const val ACTION_STOP = "com.streamy.app.action.STOP"
        const val ACTION_META = "com.streamy.app.action.META"
        const val ACTION_RECORD_ON = "com.streamy.app.action.RECORD_ON"
        const val ACTION_RECORD_OFF = "com.streamy.app.action.RECORD_OFF"
        const val EXTRA_PROFILE_ID = "com.streamy.app.extra.PROFILE_ID"
        const val EXTRA_TITLE = "com.streamy.app.extra.TITLE"
        const val EXTRA_DEVICE_NAME = "com.streamy.app.extra.DEVICE_NAME"
        const val NOTIF_ID = 0x5bee7

        /** Minimum interval between Backpressure-driven Live emissions (≈4 Hz). */
        const val DROP_UI_THROTTLE_MS = 250L
    }
}
