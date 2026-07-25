package com.streamy.app.ui

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.streamy.app.audio.LevelSnapshot
import com.streamy.app.data.AudioConfig
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec
import com.streamy.app.data.db.AppDatabase
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.data.ProfileRepository
import com.streamy.app.service.ServiceController
import com.streamy.app.service.StreamState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state of record. The audio settings (codec/bitrate/sampleRate/mono) are *derived from
 * the selected profile* — they reflect what is persisted on [ProfileEntity]. The Home
 * chooser therefore tunes the selected profile in place; the bound [StreamService] reads
 * the same profile row via Room at start time, so chooser changes take effect on the next
 * start (the [needsRestartToApply] flow surfaces that when Live).
 *
 * Mic settings (software gain + selected input device NAME) live in SharedPreferences
 * (`streamy_mic`), NOT on the profile — they apply to every stream. Gain is pushed live to
 * the bound service via [ServiceController]/binder; the device name rides the START intent
 * (an apply() on the slider path can't race an intent).
 *
 * State discipline: VM owns source-of-truth; screens pull state in + emit events out.
 * The bound [StreamService.state] / levels / recording flags are pushed in from
 * [MainActivity]'s ServiceConnection via the on*() mirrors — the VM never owns the binding.
 *
 * ponytail: persist selectedProfileId across process death (DataStore). Out of scope 12b.
 */
class StreamyViewModel(app: Application) : AndroidViewModel(app) {

    // Same db name as StreamService so profile writes are visible without a sync layer.
    private val db: AppDatabase = Room.databaseBuilder(
        app, AppDatabase::class.java, "streamy.db",
    ).addMigrations(AppDatabase.MIGRATION_1_2).build()

    private val repo = ProfileRepository(db.profileDao())

    // Mic prefs (gain + input device name) — shared with nothing; service gets device via Intent.
    private val prefs = app.getSharedPreferences("streamy_mic", Context.MODE_PRIVATE)

    // ---- Streams from storage / service ----

    val profiles: StateFlow<List<ProfileEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedProfileId = MutableStateFlow<Long?>(null)
    val selectedProfileId: StateFlow<Long?> = _selectedProfileId.asStateFlow()

    val selectedProfile: StateFlow<ProfileEntity?> = combine(
        profiles, _selectedProfileId,
    ) { list, id -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Mirror of StreamService.state, pushed from MainActivity. Idle until a binder attaches.
    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    // Mirror of StreamService.levels, pushed from MainActivity. Null when idle.
    private val _levels = MutableStateFlow<LevelSnapshot?>(null)
    val levels: StateFlow<LevelSnapshot?> = _levels.asStateFlow()

    // Mirror of StreamService recording flags, pushed from MainActivity.
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    private val _recordingPath = MutableStateFlow<String?>(null)
    val recordingPath: StateFlow<String?> = _recordingPath.asStateFlow()

    /**
     * Snapshot of the selected profile captured at the moment [start] was last invoked.
     * StreamService holds its own equivalent snapshot for the running encode; this mirror
     * lets us compare "what the live stream is using" vs "what the profile row now says"
     * to drive [needsRestartToApply].
     */
    private val _startSnapshot = MutableStateFlow<ProfileEntity?>(null)

    // ---- Mic settings (gain + device) ----

    /** Linear mic gain, 0..2 (1.0 = unity). Seeded from prefs so it survives process death. */
    private val _micGain = MutableStateFlow(1.0f).also {
        it.value = runCatching { prefs.getFloat(KEY_GAIN, 1.0f) }.getOrDefault(1.0f)
    }
    val micGain: StateFlow<Float> = _micGain.asStateFlow()

    /** Selected input device productName (null = system default). Seeded from prefs. */
    private val _selectedDeviceName = MutableStateFlow<String?>(null).also {
        it.value = prefs.getString(KEY_DEVICE, null)?.takeIf { s -> s.isNotBlank() }
    }
    val selectedDeviceName: StateFlow<String?> = _selectedDeviceName.asStateFlow()

    /** Input device captured at start, to detect mid-stream mic swaps ([needsRestartToApply]). */
    private val _startDeviceName = MutableStateFlow<String?>(null)

    /** Enumerated input devices, refreshed off-main by [refreshInputDevices]. */
    private val _inputDevices = MutableStateFlow<List<MicDevice>>(emptyList())
    val inputDevices: StateFlow<List<MicDevice>> = _inputDevices.asStateFlow()

    // ---- Audio settings: derived from the selected profile ----
    //
    // Defaults (MP3 · 128 · 44.1 kHz · mono) surface only when no profile is selected —
    // HomeScreen disables the chooser then, so these never reach a Live stream.

    val codec: StateFlow<Codec> = selectedProfile
        .map { it?.codecEnum() ?: Codec.MP3 }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Codec.MP3)

    val bitrate: StateFlow<Int> = selectedProfile
        .map { it?.bitrateKbps ?: 128 }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 128)

    val sampleRate: StateFlow<Int> = selectedProfile
        .map { it?.sampleRateHz ?: 44_100 }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 44_100)

    val mono: StateFlow<Boolean> = selectedProfile
        .map { it?.mono ?: true }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** HE-AAC v1 forced at AAC ≤ 24 kbps — derived from [AudioConfigRules.needsHeAac]. */
    val heAacActive: StateFlow<Boolean> = combine(codec, bitrate) { c, b ->
        AudioConfigRules.needsHeAac(c, b)
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * True when audio settings on the selected profile OR the selected mic diverge from the
     * snapshot the running stream was started with. The VM does NOT auto-restart — surfacing
     * this hint is enough; user stops + starts to apply.
     */
    val needsRestartToApply: StateFlow<Boolean> = combine(
        streamState, selectedProfile, _startSnapshot, selectedDeviceName, _startDeviceName,
    ) { state, profile, snapshot, dev, startDev ->
        state is StreamState.Live &&
            snapshot != null &&
            profile != null &&
            snapshot.id == profile.id && (
                snapshot.codec != profile.codec ||
                    snapshot.bitrateKbps != profile.bitrateKbps ||
                    snapshot.sampleRateHz != profile.sampleRateHz ||
                    snapshot.mono != profile.mono ||
                    dev != startDev
            )
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // ---- Transient UI signals ----

    private val _userMessage = MutableStateFlow<String?>(null)
    /** Humanized one-liner surfaced as a snackbar; null = nothing to show. */
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    private val _permissionsNeeded = MutableStateFlow<List<String>>(emptyList())
    /** Non-empty → MainActivity should request these and call [onPermissionsResult]. */
    val permissionsNeeded: StateFlow<List<String>> = _permissionsNeeded.asStateFlow()

    /** Profile id awaiting permission grant to start. Cleared on grant or abort. */
    private var pendingStartProfileId: Long? = null

    // ---- Selection ----

    fun selectProfile(id: Long?) {
        _selectedProfileId.value = id
    }

    // ---- Mic settings ----

    /** Set software mic gain; UI updates instantly, prefs persist async (apply, never commit). */
    fun setMicGain(g: Float) {
        _micGain.value = g
        viewModelScope.launch { prefs.edit().putFloat(KEY_GAIN, g).apply() }
    }

    fun setInputDevice(name: String?) {
        _selectedDeviceName.value = name?.takeIf { it.isNotBlank() }
        viewModelScope.launch { prefs.edit().putString(KEY_DEVICE, _selectedDeviceName.value).apply() }
    }

    /** Enumerate input mics off-main. Idempotent; safe to call on Settings open / resume. */
    fun refreshInputDevices() {
        val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
        viewModelScope.launch(Dispatchers.IO) {
            val list = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.isSource && it.type in ALLOWED_INPUT_TYPES }
                .map { MicDevice(it.productName?.toString().orEmpty().ifEmpty { "(unnamed)" }, it.type) }
                .distinctBy { it.name }
            _inputDevices.value = list
        }
    }

    // ---- Audio setters: tune the selected profile in place ----
    //
    // Each setter is a no-op when no profile is selected (chooser is disabled then anyway).
    // Updates persist via repo.update so the next start() picks them up.

    fun setCodec(c: Codec) {
        val p = selectedProfile.value ?: return
        val ladder = if (c == Codec.AAC) AudioConfigRules.AAC_BITRATES else AudioConfigRules.MP3_BITRATES
        val newBitrate = if (p.bitrateKbps in ladder) p.bitrateKbps
        else ladder.minBy { kotlin.math.abs(it - p.bitrateKbps) }
        val newMono = if (AudioConfigRules.needsHeAac(c, newBitrate)) true else p.mono
        // MP3 16/24 kbps needs ≤ 24 kHz (MPEG-2); clamp the sample rate so the encoder actually
        // produces the requested bitrate instead of LAME bumping it (24→32).
        val newSr = AudioConfigRules.clamp(AudioConfig(c, newBitrate, p.sampleRateHz, newMono)).sampleRateHz
        updateProfileAudio(p.copy(codec = c.name, bitrateKbps = newBitrate, mono = newMono, sampleRateHz = newSr))
    }

    fun setBitrate(kbps: Int) {
        val p = selectedProfile.value ?: return
        // I8: reject out-of-ladder bitrates instead of silently persisting an impossible value.
        // Throws IllegalArgumentException — callers surface it as a user message, not a silent write.
        val ladder = if (p.codecEnum() == Codec.AAC) AudioConfigRules.AAC_BITRATES
                     else AudioConfigRules.MP3_BITRATES
        require(kbps in ladder) { "bitrate $kbps not in ladder $ladder" }
        val newMono = if (AudioConfigRules.needsHeAac(p.codecEnum(), kbps)) true else p.mono
        val newSr = AudioConfigRules.clamp(AudioConfig(p.codecEnum(), kbps, p.sampleRateHz, newMono)).sampleRateHz
        updateProfileAudio(p.copy(bitrateKbps = kbps, mono = newMono, sampleRateHz = newSr))
    }

    fun setSampleRate(hz: Int) {
        val p = selectedProfile.value ?: return
        updateProfileAudio(p.copy(sampleRateHz = hz))
    }

    fun setMono(b: Boolean) {
        val p = selectedProfile.value ?: return
        // AAC 16/24 must be mono per AudioConfigRules; the toggle is forced off there.
        if (AudioConfigRules.needsHeAac(p.codecEnum(), p.bitrateKbps) && !b) return
        updateProfileAudio(p.copy(mono = b))
    }

    /** Persists audio-field changes on the selected profile (background). */
    private fun updateProfileAudio(p: ProfileEntity) {
        viewModelScope.launch { repo.update(p) }
    }

    // ---- Profile CRUD ----

    /** Insert (id == 0) or update; selects the saved profile and surfaces a confirmation. */
    fun saveProfile(p: ProfileEntity, onSelect: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = if (p.id == 0L) repo.save(p) else { repo.update(p); p.id }
            _selectedProfileId.value = id
            onSelect(id)
            _userMessage.value = "Saved ${p.name}"
        }
    }

    fun deleteProfile(p: ProfileEntity) {
        viewModelScope.launch {
            repo.delete(p)
            if (_selectedProfileId.value == p.id) _selectedProfileId.value = null
            _userMessage.value = "Deleted ${p.name}"
        }
    }

    fun clearAllProfiles() {
        viewModelScope.launch {
            val all = profiles.value
            all.forEach { repo.delete(it) }
            _selectedProfileId.value = null
            _userMessage.value = if (all.isEmpty()) "No profiles to clear" else "Cleared ${all.size} profile${if (all.size == 1) "" else "s"}"
        }
    }

    suspend fun getProfile(id: Long): ProfileEntity? = repo.byId(id)

    fun consumeMessage() { _userMessage.value = null }

    // ---- Lifecycle: start / stop / record ----

    fun start() {
        val ctx = getApplication<Application>()
        val profileId = _selectedProfileId.value
        if (profileId == null) {
            _userMessage.value = "No profile selected"
            return
        }
        val needed = missingPermissions(ctx)
        if (needed.isNotEmpty()) {
            pendingStartProfileId = profileId
            _permissionsNeeded.value = needed
            return
        }
        // Capture the profile + mic-device snapshot the stream is being started against, so we
        // can detect later divergence while Live.
        _startSnapshot.value = selectedProfile.value
        _startDeviceName.value = _selectedDeviceName.value
        ServiceController.start(ctx, profileId, _selectedDeviceName.value)
    }

    fun stop() {
        ServiceController.stop(getApplication())
    }

    /** Toggle local MP3 recording on the running stream. No-op unless Live. */
    fun toggleRecording() {
        val state = streamState.value
        if (state !is StreamState.Live) {
            _userMessage.value = "Start streaming first"
            return
        }
        ServiceController.setRecording(getApplication(), !_isRecording.value)
    }

    /**
     * Push now-playing metadata via Ultravox 0x902 (admin-free) on the running stream.
     * Routes through [ServiceController] -> [StreamService.ACTION_META] -> [UvoxClient.updateNowPlaying].
     */
    fun updateNowPlaying(title: String) {
        val p = selectedProfile.value
        if (p == null) { _userMessage.value = "No profile selected"; return }
        if (streamState.value !is StreamState.Live) { _userMessage.value = "Start streaming first"; return }
        ServiceController.updateNowPlaying(getApplication(), title)
        _userMessage.value = "Title updated"
    }

    /** Called by MainActivity after the runtime permission flow completes. */
    fun onPermissionsResult(results: Map<String, Boolean>) {
        _permissionsNeeded.value = emptyList()
        val id = pendingStartProfileId
        pendingStartProfileId = null
        if (id == null) return
        if (results.values.all { it }) {
            _startSnapshot.value = selectedProfile.value
            _startDeviceName.value = _selectedDeviceName.value
            ServiceController.start(getApplication(), id, _selectedDeviceName.value)
        } else {
            _userMessage.value = "Microphone permission required to broadcast"
        }
    }

    /** ServiceConnection pushes the bound service's state in here. */
    fun onServiceStateChanged(state: StreamState) {
        _streamState.value = state
    }

    /** ServiceConnection pushes the service's level snapshots in here. */
    fun onLevels(snapshot: LevelSnapshot?) {
        _levels.value = snapshot
    }

    /** ServiceConnection pushes the service's recording flags in here. */
    fun onRecording(isRecording: Boolean, path: String?) {
        _isRecording.value = isRecording
        if (path != null) _recordingPath.value = path
    }

    private fun missingPermissions(context: android.content.Context): List<String> {
        // RECORD_AUDIO always; POST_NOTIFICATIONS on API 33+ (declared in manifest).
        return buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ProfileEntity.codecEnum(): Codec =
        runCatching { Codec.valueOf(codec.trim().uppercase()) }.getOrDefault(Codec.MP3)

    override fun onCleared() {
        super.onCleared()
        // Close the VM-side Room handle so the SQLiteDatabase isn't left open after the VM dies.
        runCatching { db.close() }
    }

    companion object {
        private const val KEY_GAIN = "gain"
        private const val KEY_DEVICE = "input_device_name"

        /** Input device types exposed in the selector (built-in, wired, USB, BT). */
        private val ALLOWED_INPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
    }
}

/** UI-side view of an input [AudioDeviceInfo] — keeps the framework type out of Compose. */
data class MicDevice(val name: String, val type: Int)
