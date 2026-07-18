package com.streamy.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec
import com.streamy.app.data.db.AppDatabase
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.data.ProfileRepository
import com.streamy.app.service.ServiceController
import com.streamy.app.service.StreamState
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
 * UI state of record for the Home screen. Speech-friendly defaults (MP3 · 128 kbps ·
 * 44.1 kHz · mono) — matches the "podcaster / talk broadcaster" primary use case.
 *
 * State discipline: codec/bitrate/sampleRate/mono live here as source-of-truth; the UI
 * never holds a local copy. The bound [StreamService.state] is pushed in from
 * [MainActivity]'s ServiceConnection via [onServiceStateChanged] — the VM never owns the
 * binding (Activity lifecycle owns it).
 *
 * ponytail: persist the "currently selected profile + last codec/bitrate" as user prefs
 * (DataStore) so a process death doesn't reset to defaults. Out of scope for 12a.
 */
class StreamyViewModel(app: Application) : AndroidViewModel(app) {

    // Same db name as StreamService so profile writes are visible without a sync layer.
    private val db: AppDatabase = Room.databaseBuilder(
        app, AppDatabase::class.java, "streamy.db",
    ).build()

    private val repo = ProfileRepository(db.profileDao())

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

    // ---- Codec / bitrate / sample-rate / mono (speech defaults) ----

    private val _codec = MutableStateFlow(Codec.MP3)
    val codec: StateFlow<Codec> = _codec.asStateFlow()

    private val _bitrate = MutableStateFlow(128)
    val bitrate: StateFlow<Int> = _bitrate.asStateFlow()

    private val _sampleRate = MutableStateFlow(44_100)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    private val _mono = MutableStateFlow(true)
    val mono: StateFlow<Boolean> = _mono.asStateFlow()

    /** HE-AAC v1 forced at AAC ≤ 24 kbps — derived from [AudioConfigRules.needsHeAac]. */
    val heAacActive: StateFlow<Boolean> = combine(_codec, _bitrate) { c, b ->
        AudioConfigRules.needsHeAac(c, b)
    }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * True when audio settings diverge from the profile that's currently Live.
     * Changing them requires a stop+start to take effect — the VM does NOT auto-restart.
     *
     * Implementation note: bundled into (state, profile) pair first so the whole
     * derivation stays within kotlinx-coroutines' 5-arity [combine] overload.
     */
    val needsRestartToApply: StateFlow<Boolean> = combine(
        combine(streamState, selectedProfile) { s, p -> s to p },
        _codec,
        _bitrate,
        _sampleRate,
        _mono,
    ) { (state, profile), codec, bitrate, sr, mono ->
        state is StreamState.Live && profile != null && (
            profile.codec != codec.name ||
                profile.bitrateKbps != bitrate ||
                profile.sampleRateHz != sr ||
                profile.mono != mono
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

    // ---- Mutators ----

    fun selectProfile(id: Long?) {
        _selectedProfileId.value = id
    }

    fun setCodec(c: Codec) {
        // Clamp bitrate into the new codec's ladder so the chooser never shows an invalid value.
        val ladder = if (c == Codec.AAC) AudioConfigRules.AAC_BITRATES else AudioConfigRules.MP3_BITRATES
        _codec.value = c
        if (_bitrate.value !in ladder) {
            _bitrate.value = ladder.minBy { kotlin.math.abs(it - _bitrate.value) }
        }
        enforceHeAac(c, _bitrate.value)
    }

    fun setBitrate(kbps: Int) {
        _bitrate.value = kbps
        enforceHeAac(_codec.value, kbps)
    }

    fun setSampleRate(hz: Int) { _sampleRate.value = hz }

    fun setMono(b: Boolean) {
        // AAC 16/24 must be mono per AudioConfigRules; the toggle is forced off there.
        if (AudioConfigRules.needsHeAac(_codec.value, _bitrate.value) && !b) return
        _mono.value = b
    }

    private fun enforceHeAac(codec: Codec, bitrate: Int) {
        if (AudioConfigRules.needsHeAac(codec, bitrate)) {
            _mono.value = true
        }
    }

    fun consumeMessage() { _userMessage.value = null }

    // ---- Lifecycle: start / stop ----

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
        ServiceController.start(ctx, profileId)
    }

    fun stop() {
        ServiceController.stop(getApplication())
    }

    /** Called by MainActivity after the runtime permission flow completes. */
    fun onPermissionsResult(results: Map<String, Boolean>) {
        _permissionsNeeded.value = emptyList()
        val id = pendingStartProfileId
        pendingStartProfileId = null
        if (id == null) return
        if (results.values.all { it }) {
            ServiceController.start(getApplication(), id)
        } else {
            _userMessage.value = "Microphone permission required to broadcast"
        }
    }

    /** ServiceConnection pushes the bound service's state in here. */
    fun onServiceStateChanged(state: StreamState) {
        _streamState.value = state
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
}
