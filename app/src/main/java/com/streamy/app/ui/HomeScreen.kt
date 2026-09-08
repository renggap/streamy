package com.streamy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.service.RecordState
import com.streamy.app.service.StreamState
import kotlin.math.roundToInt

/*
 * JokoUI daylight console: slate-50 page, white rounded-xl cards, lime-600 primary actions,
 * semantic tally hues (on-air red / live green / amber) for stream states.
 *
 * Single-screen broadcast console. Pulls from [vm] state, emits via vm mutators.
 * The chooser is bound to the SELECTED profile via [vm]: changing codec/bitrate/sample/
 * mono tunes the profile row in place. When no profile is selected, the chooser is
 * disabled with a "select a profile first" hint.
 */
@Composable
fun HomeScreen(
    vm: StreamyViewModel,
    onManageProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.streamState.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selectedProfile by vm.selectedProfile.collectAsStateWithLifecycle()
    val needsRestart by vm.needsRestartToApply.collectAsStateWithLifecycle()

    val codec by vm.codec.collectAsStateWithLifecycle()
    val bitrate by vm.bitrate.collectAsStateWithLifecycle()
    val sampleRate by vm.sampleRate.collectAsStateWithLifecycle()
    val mono by vm.mono.collectAsStateWithLifecycle()
    val micGain by vm.micGain.collectAsStateWithLifecycle()
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()
    val recordingPath by vm.recordingPath.collectAsStateWithLifecycle()
    val recordState by vm.recordState.collectAsStateWithLifecycle()

    var nowPlaying by rememberSaveable { mutableStateOf("") }

    val isLive = state is StreamState.Live
    val isBusy = state is StreamState.Connecting || state is StreamState.Reconnecting
    val hasProfile = selectedProfile != null
    val chooserEnabled = !isLive && !isBusy && hasProfile

    // Audio being dropped under transport backpressure — the one thing a DJ must know. Only
    // surfaced while Live (drops only happen then) and only when non-zero.
    val droppedFrames = (state as? StreamState.Live)?.droppedFrames ?: 0L

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Masthead()

            StateBadge(state = state)

            if (isLive && droppedFrames > 0L) {
                JokoAlert(
                    text = "Dropped $droppedFrames frame${if (droppedFrames == 1L) "" else "s"} — network can't keep up; listeners may hear gaps",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // 1 · Broadcast — the hero tally card.
            JokoSectionHead(index = 1, title = "Broadcast")
            JokoCard {
                HeroControl(
                    state = state,
                    enabled = !isBusy && hasProfile,
                    onStart = vm::start,
                    onStop = vm::stop,
                )
                if (hasProfile) {
                    NowPlayingSection(
                        text = nowPlaying,
                        onTextChange = { nowPlaying = it },
                        isLive = isLive,
                        onUpdate = { vm.updateNowPlaying(nowPlaying) },
                    )
                }
            }

            // 2 · Signal — meter + mic in one card.
            JokoSectionHead(index = 2, title = "Signal")
            JokoCard {
                VuMeter(levelsFlow = vm.levels)
                MicVolumeSlider(gain = micGain, onChange = vm::setMicGain)
            }

            // 3 · Recorder.
            JokoSectionHead(index = 3, title = "Recorder")
            JokoCard {
                RecordControl(
                    state = state,
                    recordState = recordState,
                    isRecording = isRecording,
                    recordingPath = recordingPath,
                    hasProfile = hasProfile,
                    onToggleRecorder = vm::toggleStandaloneRecording,
                    onToggleLiveRecording = vm::toggleRecording,
                )
            }

            if (needsRestart) {
                JokoAlert(
                    text = "Settings changed — stop and restart to apply",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // 4 · Setup — profile + audio on one card.
            JokoSectionHead(index = 4, title = "Setup")
            JokoCard {
                ProfileChip(
                    profile = selectedProfile,
                    profileCount = profiles.size,
                    enabled = !isLive && !isBusy,
                    onManageProfiles = onManageProfiles,
                )
                if (hasProfile) {
                    BitrateChooser(
                        codec = codec,
                        bitrate = bitrate,
                        sampleRate = sampleRate,
                        mono = mono,
                        enabled = chooserEnabled,
                        onCodec = vm::setCodec,
                        onBitrate = vm::setBitrate,
                        onSampleRate = vm::setSampleRate,
                        onMono = vm::setMono,
                    )
                } else {
                    NoProfileHint(onManageProfiles = onManageProfiles)
                }
            }
        }
    }
}

// ---- Masthead: eyebrow pill + foreground title + muted subtitle ----

@Composable
private fun Masthead() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        JokoEyebrow("On-air console")
        Text(
            text = "Streamy",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Broadcast live speech to your SHOUTcast server.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- State badge (JokoUI dot badge with tally semantics) ----

@Composable
private fun StateBadge(state: StreamState) {
    val (label, tone) = state.toPill()
    val dotColor = when (tone) {
        PillTone.Live -> MaterialTheme.colorScheme.secondary
        PillTone.Busy -> MaterialTheme.colorScheme.tertiary
        PillTone.Error -> MaterialTheme.colorScheme.error
        PillTone.Idle -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        JokoDotBadge(text = label, dotColor = dotColor, pulse = tone == PillTone.Live)
        if (tone == PillTone.Busy) {
            JokoPulseRing(dotColor, size = 12.dp)
        }
    }
}

private enum class PillTone { Idle, Busy, Live, Error }

private fun StreamState.toPill(): Pair<String, PillTone> = when (this) {
    StreamState.Idle -> "OFF AIR" to PillTone.Idle
    StreamState.Connecting -> "CONNECTING" to PillTone.Busy
    is StreamState.Live -> "ON AIR" to PillTone.Live
    is StreamState.Reconnecting -> "RECONNECTING · attempt $attempt" to PillTone.Busy
    is StreamState.Error -> reason.humanize() to PillTone.Error
}

/** Map the service's terse error reasons to readable copy. */
private fun String.humanize(): String = when (this) {
    "mic_busy" -> "MICROPHONE BUSY"
    "profile_missing" -> "NO PROFILE SELECTED"
    else -> uppercase().replace('_', ' ').ifEmpty { "STREAM ERROR" }
}

// ---- Hero Start/Stop control (tally lamp) ----

@Composable
private fun HeroControl(
    state: StreamState,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val isLive = state is StreamState.Live
    val isBusy = state is StreamState.Connecting || state is StreamState.Reconnecting

    // Stop is reachable from any non-Idle state so the user can abort during Connecting /
    // Reconnecting / Error (the Stop-reconnect fix #2). Start is only reachable from Idle.
    // Tap mapping:
    //   - Idle      -> onStart (Start)
    //   - Live      -> onStop  (Stop)
    //   - Busy/Err  -> onStop  (Stop / abort)
    val lampAction: () -> Unit = if (state is StreamState.Idle) onStart else onStop
    val lampLabel = when {
        isLive -> "STOP"
        state is StreamState.Idle -> "START"
        else -> "STOP"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Tally semantics: red-600 to stop a live broadcast (destructive), lime-600 primary
        // to start, slate-100 secondary to abort a busy/error state.
        when {
            isLive -> JokoPrimaryButton(
                text = lampLabel,
                onClick = lampAction,
                container = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                verticalPadding = 20.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            state is StreamState.Idle -> JokoPrimaryButton(
                text = lampLabel,
                onClick = lampAction,
                enabled = enabled,
                verticalPadding = 20.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> JokoSecondaryButton(
                text = lampLabel,
                onClick = lampAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            text = if (state is StreamState.Idle) "Tap to go on air"
            else "Tap to stop broadcasting",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

// ---- Profile summary (flat rows inside the Setup card) ----

@Composable
private fun ProfileChip(
    profile: ProfileEntity?,
    profileCount: Int,
    enabled: Boolean,
    onManageProfiles: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Profile",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        if (profile != null) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.outlineVariant,
                        androidx.compose.foundation.shape.CircleShape,
                    ),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile?.name ?: "No profile selected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val summary = profile?.let {
                    "${it.host}:${it.port} · ${it.codec} ${it.bitrateKbps} kbps"
                } ?: if (profileCount == 0) "Create a profile to broadcast"
                else "$profileCount profile${if (profileCount == 1) "" else "s"} available"
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = onManageProfiles,
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                Text(
                    "Manage",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ---- Empty-state hint (flat copy inside the Setup card) ----

@Composable
private fun NoProfileHint(onManageProfiles: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Select a profile first",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Codec, bitrate, sample rate, and channels tune the selected server profile. " +
                "Add or pick one to start broadcasting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onManageProfiles,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text(
                "Manage profiles",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ---- Now-playing metadata (flat field inside the Broadcast card) ----

@Composable
private fun NowPlayingSection(
    text: String,
    onTextChange: (String) -> Unit,
    isLive: Boolean,
    onUpdate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Now playing",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                    "Artist - Track",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
            trailingIcon = {
                Button(
                    onClick = onUpdate,
                    enabled = isLive,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.outlineVariant,
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("Update", style = MaterialTheme.typography.labelLarge)
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ---- Mic volume (software gain) ----

@Composable
private fun MicVolumeSlider(gain: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Mic volume",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${(gain * 100f).roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = gain,
            onValueChange = onChange,
            // 0 (mute) – 200 % (+6 dB, hard-clips past 100 %). steps = (range/step) - 1 = 39.
            valueRange = 0f..2f,
            steps = 39,
        )
    }
}

// ---- Local MP3 recording ----
//
// Two recording paths, one control surface:
//  - Standalone recorder (RecordService): mic → MP3 file, NO stream needed. Works when Idle.
//    Runs alongside a live broadcast if the user wants both.
//  - Stream recording (StreamService): taps the running broadcast — only when Live.

@Composable
private fun RecordControl(
    state: StreamState,
    recordState: RecordState,
    isRecording: Boolean,
    recordingPath: String?,
    hasProfile: Boolean,
    onToggleRecorder: () -> Unit,
    onToggleLiveRecording: () -> Unit,
) {
    val isLive = state is StreamState.Live
    val recorderActive = recordState is RecordState.Recording
    val recorderError = recordState as? RecordState.Error

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Recorder",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (recorderActive) {
                JokoSoftBadge("Recording", MaterialTheme.colorScheme.error)
            } else if (isRecording) {
                JokoSoftBadge("Tapping stream", MaterialTheme.colorScheme.primary)
            }
        }
        // Standalone recorder: primary action, available whether Idle or Live (records in
        // parallel with a broadcast). Needs a profile for the encode config.
        JokoPrimaryButton(
            text = if (recorderActive) "Stop recording" else "Record MP3",
            onClick = onToggleRecorder,
            enabled = hasProfile,
            container = if (recorderActive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.fillMaxWidth(),
            verticalPadding = 14.dp,
        )
        if (isLive) {
            // While Live, offer the stream tap (encoded frames as-is / transcode) too.
            JokoOutlineButton(
                text = if (isRecording) "Stop stream recording" else "Record this stream",
                onClick = onToggleLiveRecording,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val activePath = (recordState as? RecordState.Recording)?.path ?: recordingPath
        if (activePath != null) {
            Text(
                "Saving to: $activePath",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recorderError != null) {
            JokoAlert(
                text = "Recorder error: ${recorderError.reason.humanize().lowercase()}",
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
