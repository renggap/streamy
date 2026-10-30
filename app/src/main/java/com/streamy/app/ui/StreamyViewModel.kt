package com.streamy.app.ui

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamy.app.R
import com.streamy.app.audio.LevelSnapshot
import com.streamy.app.data.AudioConfig
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec
import com.streamy.app.data.DjProtocol
import com.streamy.app.data.db.DatabaseHolder
import com.streamy.app.data.db.AppDatabase
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.data.ProfileRepository
import com.streamy.app.data.ThemeMode
import com.streamy.app.net.UpdateChecker
import com.streamy.app.service.RecordState
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
 * User-facing copy leaves the VM as [UiText] (resource id + args), never as resolved
 * strings: the VM survives configuration changes, the locale may not.
 */
class StreamyViewModel(app: Application) : AndroidViewModel(app) {

    // Single shared Room instance via DatabaseHolder — same db file as the services.
    private val db: AppDatabase = DatabaseHolder.get(getApplication())

    private val repo = ProfileRepository(db.profileDao())

    // Mic prefs (gain + input device name) — shared with nothing; service gets device via Intent.
    private val prefs = app.getSharedPreferences("streamy_mic", Context.MODE_PRIVATE)

    // UI prefs — last selected profile id survives process death.
    private val uiPrefs = app.getSharedPreferences("streamy_ui", Context.MODE_PRIVATE)

    // ---- Streams from storage / service ----

    val profiles: StateFlow<List<ProfileEntity>> = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedProfileId = MutableStateFlow(
        uiPrefs.getLong(KEY_SELECTED_PROFILE, -1L).takeIf { it >= 0L }
    )
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

    // Standalone recorder (RecordService) — independent of the stream. Idle = not running.
    private val _recordState = MutableStateFlow<RecordState>(RecordState.Idle)
    val recordState: StateFlow<RecordState> = _recordState.asStateFlow()

    /**
     * Most recently *started* file from either recording path (stream tap or standalone
     * recorder). RecordState drops its path on Idle and StreamService's path is "last/active",
     * so neither alone can answer "where did my last recording go?" once both have been used.
     */
    private val _lastSavedPath = MutableStateFlow<String?>(null)
    val lastSavedPath: StateFlow<String?> = _lastSavedPath.asStateFlow()

    /**
     * Snapshot of the selected profile captured at the moment [start] was last invoked.
     * StreamService holds its own equivalent snapshot for the running encode; this mirror
     * lets us compare "what the live stream is using" vs "what the profile row now says"
     * to drive [needsRestartToApply].
     */
    private val _startSnapshot = MutableStateFlow<ProfileEntity?>(null)

    /**
     * Id of the profile the current/last stream was started with. Persisted (not just the
     * in-memory [_startSnapshot]) because the Activity — and this VM — can be destroyed while
     * StreamService keeps broadcasting; a fresh VM must still know which profile is on air
     * to lock it and to route Now-playing.
     */
    private val _streamProfileId = MutableStateFlow(
        uiPrefs.getLong(KEY_STREAM_PROFILE, -1L).takeIf { it >= 0L }
    )

    /**
     * The profile the running stream belongs to — null unless Connecting / Live /
     * Reconnecting. Keys the on-air banner, Now-playing and the U-5 locks on what is actually
     * broadcasting, not on whatever row is selected.
     */
    val streamProfile: StateFlow<ProfileEntity?> = combine(
        _streamState, _streamProfileId, profiles, _startSnapshot,
    ) { state, id, list, snap ->
        if (!state.isActive || id == null) null
        else list.firstOrNull { it.id == id } ?: snap?.takeIf { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Keep-screen-on while Live (Settings toggle). Default off: it costs battery. */
    private val _keepScreenOn = MutableStateFlow(uiPrefs.getBoolean(KEY_KEEP_SCREEN_ON, false))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    // Double-tap guards: START intents are async, so the UI keeps showing Start until the
    // service reports a new state. A second tap inside that window would send a second START.
    // Time-boxed rather than a plain flag so a start the service silently drops can't wedge
    // the button forever.
    private var streamStartGuardUntil = 0L
    private var recorderStartGuardUntil = 0L

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
        streamState, profiles, _startSnapshot, selectedDeviceName, _startDeviceName,
    ) { state, list, snapshot, dev, startDev ->
        // Compare against the on-air profile's CURRENT row (not the selection): while Live the
        // profile can still be edited (server fields included — the handshake already used the
        // old ones), and any persisted change only applies on the next start.
        val current = snapshot?.let { s -> list.firstOrNull { it.id == s.id } }
        state is StreamState.Live && snapshot != null && current != null &&
            (current != snapshot || dev != startDev)
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Persist selection (single source of truth — every setter flows through here).
        viewModelScope.launch {
            _selectedProfileId.collect { id ->
                uiPrefs.edit().apply {
                    if (id != null) putLong(KEY_SELECTED_PROFILE, id) else remove(KEY_SELECTED_PROFILE)
                }.apply()
            }
        }
        // Self-heal: if the persisted id references a profile that no longer exists once Room
        // loads, drop it so a stale id can't trip a "profile_missing" start.
        viewModelScope.launch {
            profiles.collect { list ->
                val id = _selectedProfileId.value
                if (id != null && list.isNotEmpty() && list.none { it.id == id }) {
                    _selectedProfileId.value = null
                }
            }
        }
        // Auto-update: silent check on startup. Any failure (offline, bad feed, same version)
        // stays invisible — the dialog only appears when there is something to install.
        viewModelScope.launch {
            val info = UpdateChecker.fetchUpdate() ?: return@launch
            if (UpdateChecker.isNewer(info)) _update.value = UpdateDialog(info)
        }
    }

    // ---- Transient UI signals ----

    private val _userMessage = MutableStateFlow<UserMessage?>(null)
    /** Localizable one-liner surfaced as a snackbar; null = nothing to show. */
    val userMessage: StateFlow<UserMessage?> = _userMessage.asStateFlow()

    private fun message(@StringRes id: Int, vararg args: Any) {
        _userMessage.value = UserMessage(UiText.Res(id, args.toList()))
    }

    private val _permissionsNeeded = MutableStateFlow<List<String>>(emptyList())
    /** Non-empty → MainActivity should request these and call [onPermissionsResult]. */
    val permissionsNeeded: StateFlow<List<String>> = _permissionsNeeded.asStateFlow()

    // ---- Auto-update ----

    /** Dialog payload: a newer version is available on GitHub Releases. */
    data class UpdateDialog(val info: UpdateChecker.UpdateInfo, val downloading: Boolean = false)

    private val _update = MutableStateFlow<UpdateDialog?>(null)
    val update: StateFlow<UpdateDialog?> = _update.asStateFlow()

    fun dismissUpdate() {
        _update.value = null
    }

    /** One in-flight download guard: double-tap on Update must not stack downloads. */
    private var updateDownloading = false

    /**
     * Download the APK then emit an event the Activity executes (starting activities is
     * Activity work, not VM work). The UI layer reads [pendingInstall] once and clears it.
     * Failure surfaces as the standard snackbar; the dialog stays up so the user can retry.
     */
    private val _pendingInstall = MutableStateFlow<java.io.File?>(null)
    val pendingInstall: StateFlow<java.io.File?> = _pendingInstall.asStateFlow()

    fun downloadUpdate(context: Context) {
        val dialog = _update.value ?: return
        if (updateDownloading) return
        updateDownloading = true
        _update.value = dialog.copy(downloading = true)
        viewModelScope.launch {
            val apk = UpdateChecker.downloadApk(context, dialog.info)
            updateDownloading = false
            if (apk == null) {
                _update.value = dialog.copy(downloading = false)
                message(R.string.update_download_failed)
            } else {
                _update.value = null
                _pendingInstall.value = apk
            }
        }
    }

    fun consumePendingInstall() {
        _pendingInstall.value = null
    }

    /**
     * "Install unknown apps" grant missing: park the installer intent and tell the user via
     * snackbar with a Settings deep link. When they return and tap Update again the (cached)
     * APK re-downloads instantly and installs.
     */
    fun onInstallPermissionNeeded() {
        _userMessage.value = UserMessage(
            UiText.Res(R.string.update_needs_install_permission),
            action = UserAction.OpenInstallPermissions,
        )
    }

    /** Profile id awaiting permission grant to start. Cleared on grant or abort. */
    private var pendingStartProfileId: Long? = null

    /** True when the pending permission grant was for the standalone recorder, not the stream. */
    private var pendingStartIsRecorder = false

    // ---- Selection ----

    /**
     * Select a profile. Refused (with a message) while a stream is on air with a different
     * profile: Home's chooser, needsRestart and the summary all follow the selection, so
     * swapping it mid-broadcast would describe a stream that isn't running.
     * Returns true when the selection was applied.
     */
    fun selectProfile(id: Long?): Boolean {
        val live = liveProfileId()
        if (live != null && id != live) {
            message(R.string.msg_stop_before_switch)
            return false
        }
        _selectedProfileId.value = id
        return true
    }

    /** Profile currently owned by a running stream, or null when nothing is on air. */
    private fun liveProfileId(): Long? =
        if (_streamState.value.isActive) _streamProfileId.value else null

    /**
     * True (and a message is posted) when [id] is the on-air profile and must not be deleted.
     * Screens call this before opening their delete confirmation, so the user gets the reason
     * immediately instead of confirming a delete that is then refused.
     */
    fun isLockedByStream(id: Long): Boolean {
        if (id != liveProfileId()) return false
        message(R.string.msg_profile_on_air_locked)
        return true
    }

    /**
     * Now-playing title. Persisted so the title the DJ sent stays in the field across tab
     * switches, Stop → Start and app restarts instead of resetting to blank, and is re-sent
     * when the next stream starts.
     */
    private val _nowPlaying = MutableStateFlow(uiPrefs.getString(KEY_NOW_PLAYING, "") ?: "")
    val nowPlaying: StateFlow<String> = _nowPlaying.asStateFlow()

    fun setNowPlaying(text: String) {
        _nowPlaying.value = text
        uiPrefs.edit().putString(KEY_NOW_PLAYING, text).apply()
    }

    fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        uiPrefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, on).apply()
    }

    /** Theme preference (Settings → Display). Read at construction so the first frame is right. */
    private val _themeMode = MutableStateFlow(ThemeMode.parse(uiPrefs.getString(KEY_THEME_MODE, null)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        uiPrefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
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
                // "(unnamed)" is a matching KEY (round-trips to StreamService), not display copy —
                // it must stay locale-independent. The label is what the dropdown shows.
                .map { MicDevice(it.productName?.toString().orEmpty().ifEmpty { "(unnamed)" }, labelFor(it), it.type) }
                .distinctBy { it.name }
            _inputDevices.value = list
        }
    }

    // ---- Audio setters: tune the selected profile in place ----
    //
    // Each setter is a no-op when no profile is selected (chooser is disabled then anyway).
    // Updates persist via repo.update so the next start() picks them up.

    fun setCodec(c: Codec) {
        // AAC is UI-disabled (AGENTS.md: DNAS quirks, re-verify against a real DNAS2 first).
        // Refuse it here too so no caller can re-enable it by accident; the encoder path stays.
        if (c == Codec.AAC) return
        val p = selectedProfile.value ?: return
        // Icecast mounts don't accept AAC — coerce to MP3 so the persisted row stays playable.
        val protocol = DjProtocol.parse(p.djProtocol)
        val effectiveCodec = if (c == Codec.AAC && protocol == DjProtocol.ICECAST) Codec.MP3 else c
        val ladder = if (effectiveCodec == Codec.AAC) AudioConfigRules.AAC_BITRATES else AudioConfigRules.MP3_BITRATES
        val newBitrate = if (p.bitrateKbps in ladder) p.bitrateKbps
        else ladder.minBy { kotlin.math.abs(it - p.bitrateKbps) }
        val newMono = if (AudioConfigRules.needsHeAac(effectiveCodec, newBitrate)) true else p.mono
        // MP3 16/24 kbps needs ≤ 24 kHz (MPEG-2); clamp the sample rate so the encoder actually
        // produces the requested bitrate instead of LAME bumping it (24→32).
        val newSr = AudioConfigRules.clamp(AudioConfig(effectiveCodec, newBitrate, p.sampleRateHz, newMono)).sampleRateHz
        updateProfileAudio(p.copy(codec = effectiveCodec.name, bitrateKbps = newBitrate, mono = newMono, sampleRateHz = newSr))
    }

    fun setBitrate(kbps: Int) {
        val p = selectedProfile.value ?: return
        // Out-of-ladder values are ignored (defensive: the chooser only offers ladder values);
        // this runs from a UI event, so it must never throw — a stray value is a no-op.
        val ladder = if (p.codecEnum() == Codec.AAC) AudioConfigRules.AAC_BITRATES
                     else AudioConfigRules.MP3_BITRATES
        if (kbps !in ladder) return
        val newMono = if (AudioConfigRules.needsHeAac(p.codecEnum(), kbps)) true else p.mono
        val newSr = AudioConfigRules.clamp(AudioConfig(p.codecEnum(), kbps, p.sampleRateHz, newMono)).sampleRateHz
        updateProfileAudio(p.copy(bitrateKbps = kbps, mono = newMono, sampleRateHz = newSr))
    }

    fun setSampleRate(hz: Int) {
        val p = selectedProfile.value ?: return
        val clamped = AudioConfigRules.clamp(AudioConfig(p.codecEnum(), p.bitrateKbps, hz, p.mono))
        updateProfileAudio(p.copy(sampleRateHz = clamped.sampleRateHz))
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

    /**
     * Insert (id == 0) or update, then surface a confirmation. Selects the saved profile —
     * except while a stream is on air, where the selection is pinned to the live profile
     * (see [selectProfile]); the message then says what happens instead.
     */
    fun saveProfile(p: ProfileEntity, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = if (p.id == 0L) repo.save(p) else { repo.update(p); p.id }
            val live = liveProfileId()
            when {
                live == null -> {
                    _selectedProfileId.value = id
                    message(R.string.msg_saved, p.name)
                }
                live == id -> message(R.string.msg_saved_restart_to_apply, p.name)
                else -> message(R.string.msg_saved_not_selected, p.name)
            }
            onSaved(id)
        }
    }

    /** Delete [p]. Refused while it is the on-air profile. Returns true when deletion started. */
    fun deleteProfile(p: ProfileEntity): Boolean {
        if (isLockedByStream(p.id)) return false
        viewModelScope.launch {
            repo.delete(p)
            if (_selectedProfileId.value == p.id) _selectedProfileId.value = null
            message(R.string.msg_deleted, p.name)
        }
        return true
    }

    fun clearAllProfiles() {
        // Clearing would delete the on-air profile out from under the stream.
        if (_streamState.value.isActive) {
            message(R.string.msg_stop_before_clear)
            return
        }
        viewModelScope.launch {
            val all = profiles.value
            all.forEach { repo.delete(it) }
            _selectedProfileId.value = null
            _userMessage.value = if (all.isEmpty()) UserMessage(UiText.Res(R.string.msg_no_profiles_to_clear))
            else UserMessage(UiText.Plural(R.plurals.msg_cleared_profiles, all.size, listOf(all.size)))
        }
    }

    suspend fun getProfile(id: Long): ProfileEntity? = repo.byId(id)

    fun consumeMessage() { _userMessage.value = null }

    // ---- Lifecycle: start / stop / record ----

    fun start() = startStream(_selectedProfileId.value)

    /**
     * Retry from Error with the profile that failed (not whatever is selected now — the
     * selection is only pinned while on air, and Error is not on air). Re-selects it so Home
     * describes the stream that is about to start.
     */
    fun retry() {
        val id = _streamProfileId.value ?: _selectedProfileId.value
        if (id != null && profiles.value.isNotEmpty() && profiles.value.none { it.id == id }) {
            message(R.string.msg_profile_gone)
            return
        }
        if (id != null) _selectedProfileId.value = id
        startStream(id)
    }

    private fun startStream(profileId: Long?) {
        val ctx = getApplication<Application>()
        if (profileId == null) {
            message(R.string.msg_no_profile_selected)
            return
        }
        if (SystemClock.elapsedRealtime() < streamStartGuardUntil) return
        val needed = missingPermissions(ctx)
        if (needed.isNotEmpty()) {
            pendingStartProfileId = profileId
            pendingStartIsRecorder = false
            _permissionsNeeded.value = needed
            return
        }
        launchStream(profileId)
    }

    private fun launchStream(profileId: Long) {
        // Capture the profile + mic-device snapshot the stream is being started against, so we
        // can detect later divergence while Live.
        _startSnapshot.value = profiles.value.firstOrNull { it.id == profileId }
            ?: selectedProfile.value?.takeIf { it.id == profileId }
        _streamProfileId.value = profileId
        uiPrefs.edit().putLong(KEY_STREAM_PROFILE, profileId).apply()
        _startDeviceName.value = _selectedDeviceName.value
        streamStartGuardUntil = SystemClock.elapsedRealtime() + START_GUARD_MS
        ServiceController.start(
            getApplication(), profileId, _selectedDeviceName.value,
            title = _nowPlaying.value.takeIf { it.isNotBlank() },
        )
    }

    fun stop() {
        ServiceController.stop(getApplication())
    }

    /** Toggle local MP3 recording on the running stream. No-op unless Live. */
    fun toggleRecording() {
        val state = streamState.value
        if (state !is StreamState.Live) {
            message(R.string.msg_start_streaming_first)
            return
        }
        ServiceController.setRecording(getApplication(), !_isRecording.value)
    }

    /**
     * Toggle the STANDALONE recorder — works whether or not a stream is running. Uses the
     * selected profile for encode config (codec/bitrate/rate), like the stream does.
     * Mic permission is required (recording captures the mic); routes through the same
     * permission flow as start() when a grant is needed.
     */
    fun toggleStandaloneRecording() {
        val ctx = getApplication<Application>()
        if (recordState.value is RecordState.Recording) {
            ServiceController.stopRecording(ctx)
            return
        }
        // A start is already in flight (service hasn't reported Recording yet): a second
        // ACTION_START would race the first one's file/sink setup.
        if (SystemClock.elapsedRealtime() < recorderStartGuardUntil) return
        val profileId = _selectedProfileId.value
        if (profileId == null) {
            message(R.string.msg_select_profile_for_recorder)
            return
        }
        val needed = missingPermissions(ctx)
        if (needed.isNotEmpty()) {
            pendingStartProfileId = profileId
            pendingStartIsRecorder = true
            _permissionsNeeded.value = needed
            return
        }
        launchRecorder(profileId)
    }

    private fun launchRecorder(profileId: Long) {
        _startDeviceName.value = _selectedDeviceName.value
        recorderStartGuardUntil = SystemClock.elapsedRealtime() + START_GUARD_MS
        ServiceController.startRecording(getApplication(), profileId, _selectedDeviceName.value)
    }

    /** ServiceConnection pushes the bound RecordService state in here. */
    fun onRecordStateChanged(state: RecordState) {
        _recordState.value = state
        if (state !is RecordState.Idle) recorderStartGuardUntil = 0L
        if (state is RecordState.Recording) _lastSavedPath.value = state.path
    }

    /**
     * Push now-playing metadata via Ultravox 0x902 (admin-free) on the running stream.
     * Routes through [ServiceController] -> [StreamService.ACTION_META] -> [UvoxClient.updateNowPlaying].
     * Gated on the RUNNING stream, not the selection: the title belongs to what is on air.
     */
    fun updateNowPlaying(title: String) {
        if (streamState.value !is StreamState.Live || streamProfile.value == null) {
            message(R.string.msg_start_streaming_first)
            return
        }
        ServiceController.updateNowPlaying(getApplication(), title)
        message(R.string.msg_title_updated)
    }

    /**
     * MainActivity is about to launch the system permission dialog. Clearing the request
     * here (the pending profile id stays) means a rotation while the dialog is up doesn't
     * re-collect a non-empty list and launch a second, stacked request; the result is still
     * delivered to the recreated Activity's launcher.
     */
    fun onPermissionRequestLaunched() {
        if (NOTIFICATIONS_PERMISSION != null && NOTIFICATIONS_PERMISSION in _permissionsNeeded.value) {
            uiPrefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        }
        _permissionsNeeded.value = emptyList()
    }

    /**
     * Called by MainActivity after the runtime permission flow completes. Only RECORD_AUDIO
     * gates the start: POST_NOTIFICATIONS is requested alongside but optional — the
     * foreground service runs fine without it (its notification is just hidden).
     *
     * [micPermanentlyDenied] needs Activity.shouldShowRequestPermissionRationale, hence it is
     * computed by MainActivity; when true the system will no longer show a dialog, so the
     * message carries a deep link to the app's settings page instead.
     */
    fun onPermissionsResult(micPermanentlyDenied: Boolean) {
        _permissionsNeeded.value = emptyList()
        val id = pendingStartProfileId
        val forRecorder = pendingStartIsRecorder
        pendingStartProfileId = null
        pendingStartIsRecorder = false
        if (id == null) return
        val ctx = getApplication<Application>()
        val micGranted = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        when {
            micGranted -> if (forRecorder) launchRecorder(id) else launchStream(id)
            micPermanentlyDenied -> _userMessage.value = UserMessage(
                UiText.Res(R.string.msg_mic_permission_blocked),
                action = UserAction.OpenAppSettings,
            )
            else -> message(R.string.msg_mic_permission_required)
        }
    }

    /** ServiceConnection pushes the bound service's state in here. */
    fun onServiceStateChanged(state: StreamState) {
        if (state != _streamState.value) streamStartGuardUntil = 0L
        _streamState.value = state
    }

    /** ServiceConnection pushes the service's level snapshots in here. */
    fun onLevels(snapshot: LevelSnapshot?) {
        _levels.value = snapshot
    }

    /** ServiceConnection pushes the service's recording flags in here. */
    fun onRecording(isRecording: Boolean, path: String?) {
        _isRecording.value = isRecording
        // Only a NEW path counts as "last saved": the service re-emits its last/active path on
        // every (re)bind, which must not override a newer standalone-recorder file.
        if (path != null && path != _recordingPath.value) {
            _recordingPath.value = path
            _lastSavedPath.value = path
        }
    }

    // Edge detector for the service's latched recordingFailed: the flow replays `true` on every
    // rebind/resume, but the snackbar must fire once per failure (VM outlives rotation).
    private var recordingFailedSeen = false

    /** The live stream's armed recording died (disk full, storage gone); the stream continues. */
    fun onRecordingFailed(failed: Boolean) {
        if (failed && !recordingFailedSeen) message(R.string.err_record_write_failed)
        recordingFailedSeen = failed
    }

    private fun missingPermissions(context: android.content.Context): List<String> {
        // RECORD_AUDIO always; POST_NOTIFICATIONS on API 33+ (declared in manifest) but only
        // asked once — it's optional, and re-prompting on every Start after a "no" is nagging.
        val askNotifications = NOTIFICATIONS_PERMISSION != null &&
            !uiPrefs.getBoolean(KEY_NOTIF_ASKED, false)
        return buildList {
            add(android.Manifest.permission.RECORD_AUDIO)
            if (askNotifications) add(NOTIFICATIONS_PERMISSION!!)
        }.filter {
            context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ProfileEntity.codecEnum(): Codec =
        runCatching { Codec.valueOf(codec.trim().uppercase()) }.getOrDefault(Codec.MP3)

    override fun onCleared() {
        super.onCleared()
        // Shared DatabaseHolder singleton is process-owned — never closed here.
    }

    companion object {
        private const val KEY_GAIN = "gain"
        private const val KEY_DEVICE = "input_device_name"
        private const val KEY_SELECTED_PROFILE = "selected_profile_id"
        private const val KEY_STREAM_PROFILE = "stream_profile_id"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on_live"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_NOTIF_ASKED = "notifications_asked"
        private const val KEY_NOW_PLAYING = "now_playing_title"

        /** Double-tap window for START intents (see [streamStartGuardUntil]). */
        private const val START_GUARD_MS = 3_000L

        private val NOTIFICATIONS_PERMISSION: String? =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                android.Manifest.permission.POST_NOTIFICATIONS
            } else null

        /** Input device types exposed in the selector (built-in, wired, USB, BT classic + LE). */
        private val ALLOWED_INPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )

    }

    /**
     * Human-readable label: trust productName when it's a real device name, else derive from
     * type. There is no AudioDeviceInfo.getName(); getProductName() returns Build.MODEL when the
     * port has no real name (typical for TYPE_BUILTIN_MIC) — that's the unreadable string users
     * saw. Detect that substitution and fall back to a type-derived label (localized — resolved
     * at refresh time; Settings re-runs the refresh whenever it is recomposed fresh).
     */
    private fun labelFor(dev: AudioDeviceInfo): String {
        val raw = dev.productName?.toString()?.trim().orEmpty()
        val plausible = raw.isNotEmpty() &&
            raw != android.os.Build.MODEL &&
            raw != android.os.Build.DEVICE &&
            raw != android.os.Build.PRODUCT
        return if (plausible) raw else getApplication<Application>().getString(typeLabelRes(dev.type))
    }

    @StringRes
    private fun typeLabelRes(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> R.string.mic_type_builtin
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.mic_type_usb
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> R.string.mic_type_wired
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> R.string.mic_type_bluetooth
        else -> R.string.mic_type_generic
    }
}

/**
 * UI-side view of an input [AudioDeviceInfo] — keeps the framework type out of Compose.
 * [name] is the raw productName (the round-trip key StreamService matches against); [label] is
 * the human-readable string the dropdown renders. They differ because [AudioDeviceInfo.getProductName]
 * returns Build.MODEL (unreadable) when the port has no real name — see [labelFor].
 */
data class MicDevice(val name: String, val label: String, val type: Int)
