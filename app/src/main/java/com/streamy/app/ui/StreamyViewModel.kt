package com.streamy.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import com.streamy.app.audio.LevelSnapshot
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec
import com.streamy.app.data.db.AppDatabase
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.data.ProfileRepository
import com.streamy.app.net.MetadataClient
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
 * UI state of record. The audio settings (codec/bitrate/sampleRate/mono) are *derived from
 * the selected profile* — they reflect what is persisted on [ProfileEntity]. The Home
 * chooser therefore tunes the selected profile in place; the bound [StreamService] reads
 * the same profile row via Room at start time, so chooser changes take effect on the next
 * start (the [needsRestartToApply] flow surfaces that when Live).
 *
 * When no profile is selected, the derived flows fall back to speech-friendly defaults
 * (MP3 · 128 · 44.1 kHz · mono); HomeScreen disables the chooser in that case.
 *
 * State discipline: VM owns source-of-truth; screens pull state in + emit events out.
 * The bound [StreamService.state] is pushed in from [MainActivity]'s ServiceConnection
 * via [onServiceStateChanged] — the VM never owns the binding.
 *
 * ponytail: persist selectedProfileId across process death (DataStore). Out of scope 12b.
 */
class StreamyViewModel(app: Application) : AndroidViewModel(app) {

    // Same db name as StreamService so profile writes are visible without a sync layer.
    private val db: AppDatabase = Room.databaseBuilder(
        app, AppDatabase::class.java, "streamy.db",
    ).addMigrations(AppDatabase.MIGRATION_1_2).build()

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

    // Mirror of StreamService.levels, pushed from MainActivity's second collector. Null when idle.
    private val _levels = MutableStateFlow<LevelSnapshot?>(null)
    val levels: StateFlow<LevelSnapshot?> = _levels.asStateFlow()

    /**
     * Snapshot of the selected profile captured at the moment [start] was last invoked.
     * StreamService holds its own equivalent snapshot for the running encode; this mirror
     * lets us compare "what the live stream is using" vs "what the profile row now says"
     * to drive [needsRestartToApply].
     */
    private val _startSnapshot = MutableStateFlow<ProfileEntity?>(null)

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
     * True when audio settings on the selected profile diverge from the snapshot the
     * running stream was started with. The VM does NOT auto-restart — surfacing this hint
     * is enough; user stops + starts to apply.
     */
    val needsRestartToApply: StateFlow<Boolean> = combine(
        streamState, selectedProfile, _startSnapshot,
    ) { state, profile, snapshot ->
        state is StreamState.Live &&
            snapshot != null &&
            profile != null &&
            snapshot.id == profile.id && (
                snapshot.codec != profile.codec ||
                    snapshot.bitrateKbps != profile.bitrateKbps ||
                    snapshot.sampleRateHz != profile.sampleRateHz ||
                    snapshot.mono != profile.mono
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
        updateProfileAudio(p.copy(codec = c.name, bitrateKbps = newBitrate, mono = newMono))
    }

    fun setBitrate(kbps: Int) {
        val p = selectedProfile.value ?: return
        // I8: reject out-of-ladder bitrates instead of silently persisting an impossible value.
        // Throws IllegalArgumentException — callers surface it as a user message, not a silent write.
        val ladder = if (p.codecEnum() == Codec.AAC) AudioConfigRules.AAC_BITRATES
                     else AudioConfigRules.MP3_BITRATES
        require(kbps in ladder) { "bitrate $kbps not in ladder $ladder" }
        val newMono = if (AudioConfigRules.needsHeAac(p.codecEnum(), kbps)) true else p.mono
        updateProfileAudio(p.copy(bitrateKbps = kbps, mono = newMono))
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

    // ---- Lifecycle: start / stop ----

    fun start() {
        val ctx = getApplication<Application>()
        val profileId = _selectedProfileId.value
        if (profileId == null) {
            _userMessage.value = "No profile selected"
            return
        }
        // AAC now streams via the Ultravox 2.1 source client (UvoxClient) in StreamService —
        // DNAS2 reports it as audio/aacp. MP3 rides the v1 source client. No codec guard needed.
        val needed = missingPermissions(ctx)
        if (needed.isNotEmpty()) {
            pendingStartProfileId = profileId
            _permissionsNeeded.value = needed
            return
        }
        // Capture the profile snapshot the stream is being started against, so we can
        // detect later divergence while Live (chooser tuning the profile mid-stream).
        _startSnapshot.value = selectedProfile.value
        ServiceController.start(ctx, profileId)
    }

    fun stop() {
        ServiceController.stop(getApplication())
    }

    /**
     * Push now-playing metadata to the selected profile's DNAS2 via admin.cgi?mode=updinfo.
     * No-op with a user message if the profile has no admin creds or nothing is selected.
     */
    fun updateNowPlaying(title: String) {
        val p = selectedProfile.value ?: run { _userMessage.value = "No profile selected"; return }
        val user = p.adminUser; val pass = p.adminPassword
        if (user.isNullOrBlank() || pass.isNullOrBlank()) {
            _userMessage.value = "Set admin user + password on the profile to update metadata"; return
        }
        viewModelScope.launch {
            val ok = MetadataClient().updateNowPlaying(p.host, p.port, p.sid, user, pass, title)
            _userMessage.value = if (ok) "Now playing updated" else "Metadata update failed"
        }
    }

    /** Called by MainActivity after the runtime permission flow completes. */
    fun onPermissionsResult(results: Map<String, Boolean>) {
        _permissionsNeeded.value = emptyList()
        val id = pendingStartProfileId
        pendingStartProfileId = null
        if (id == null) return
        if (results.values.all { it }) {
            _startSnapshot.value = selectedProfile.value
            ServiceController.start(getApplication(), id)
        } else {
            _userMessage.value = "Microphone permission required to broadcast"
        }
    }

    /** ServiceConnection pushes the bound service's state in here. */
    fun onServiceStateChanged(state: StreamState) {
        _streamState.value = state
    }

    /** ServiceConnection pushes the bound service's level snapshots in here. */
    fun onLevels(snapshot: LevelSnapshot?) {
        _levels.value = snapshot
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
}
