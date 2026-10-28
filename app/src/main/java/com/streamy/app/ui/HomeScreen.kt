package com.streamy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamy.app.R
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.service.RecordState
import com.streamy.app.service.StreamState
import kotlin.math.roundToInt

/*
 * JokoUI daylight console: slate-50 page, white rounded-xl cards, lime primary actions,
 * semantic tally hues (on-air red / amber busy) for stream states.
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
    val streamProfile by vm.streamProfile.collectAsStateWithLifecycle()
    val needsRestart by vm.needsRestartToApply.collectAsStateWithLifecycle()

    val codec by vm.codec.collectAsStateWithLifecycle()
    val bitrate by vm.bitrate.collectAsStateWithLifecycle()
    val sampleRate by vm.sampleRate.collectAsStateWithLifecycle()
    val mono by vm.mono.collectAsStateWithLifecycle()
    val micGain by vm.micGain.collectAsStateWithLifecycle()
    val isRecording by vm.isRecording.collectAsStateWithLifecycle()
    val recordingPath by vm.recordingPath.collectAsStateWithLifecycle()
    val lastSavedPath by vm.lastSavedPath.collectAsStateWithLifecycle()
    val recordState by vm.recordState.collectAsStateWithLifecycle()

    var nowPlaying by rememberSaveable { mutableStateOf("") }

    val isLive = state is StreamState.Live
    val isBusy = state is StreamState.Connecting || state is StreamState.Reconnecting
    val hasProfile = selectedProfile != null
    val chooserEnabled = !isLive && !isBusy && hasProfile
    // Now-playing belongs to the RUNNING stream: while on air it follows the stream's own
    // profile (not the selection); when idle it is offered for the selected profile so the
    // DJ can type the first title before going live.
    val showNowPlaying = if (state.isActive) streamProfile != null else hasProfile

    // Audio being dropped under transport backpressure — the one thing a DJ must know. Only
    // surfaced while Live (drops only happen then) and only when non-zero.
    val droppedFrames = (state as? StreamState.Live)?.droppedFrames ?: 0L

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // U-12: keep the Now-playing field reachable above the keyboard.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Masthead()

            StateBadge(state = state)

            if (isLive && droppedFrames > 0L) {
                val n = droppedFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                JokoAlert(
                    text = pluralStringResource(R.plurals.home_dropped_frames, n, n),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // 1 · Broadcast — the hero tally card.
            JokoSectionHead(index = 1, title = stringResource(R.string.home_section_broadcast))
            JokoCard {
                HeroControl(
                    state = state,
                    enabled = !isBusy && hasProfile,
                    onStart = vm::start,
                    onStop = vm::stop,
                    onRetry = vm::retry,
                )
                if (showNowPlaying) {
                    NowPlayingSection(
                        text = nowPlaying,
                        onTextChange = { nowPlaying = it },
                        isLive = isLive,
                        onUpdate = { vm.updateNowPlaying(nowPlaying) },
                    )
                }
            }

            // 2 · Signal — meter + mic in one card.
            JokoSectionHead(index = 2, title = stringResource(R.string.home_section_signal))
            JokoCard {
                VuMeter(levelsFlow = vm.levels)
                MicVolumeSlider(gain = micGain, onChange = vm::setMicGain)
            }

            // 3 · Recorder.
            JokoSectionHead(index = 3, title = stringResource(R.string.home_section_recorder))
            JokoCard {
                RecordControl(
                    state = state,
                    recordState = recordState,
                    isRecording = isRecording,
                    recordingPath = recordingPath,
                    lastSavedPath = lastSavedPath,
                    hasProfile = hasProfile,
                    onToggleRecorder = vm::toggleStandaloneRecording,
                    onToggleLiveRecording = vm::toggleRecording,
                )
            }

            if (needsRestart) {
                JokoAlert(
                    text = stringResource(R.string.home_needs_restart),
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // 4 · Setup — profile + audio on one card.
            JokoSectionHead(index = 4, title = stringResource(R.string.home_section_setup))
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
                        allowAac = false,   // AAC UI-disabled (AGENTS.md); legacy AAC rows can switch to MP3
                    )
                } else {
                    NoProfileHint(onManageProfiles = onManageProfiles)
                }
            }
        }
    }
}

/** Resolve an [ErrorText] message against the current configuration. */
@Composable
internal fun ErrorText.message(): String =
    if (args.isEmpty()) stringResource(messageRes) else stringResource(messageRes, *args.toTypedArray())

// ---- Masthead: eyebrow pill + foreground title + muted subtitle ----

@Composable
private fun Masthead() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        JokoEyebrow(stringResource(R.string.home_eyebrow))
        Text(
            text = stringResource(R.string.brand_name),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.home_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- State badge (JokoUI dot badge with tally semantics) ----

@Composable
private fun StateBadge(state: StreamState) {
    val tone = state.tone()
    val label = stateLabel(state)
    // ON AIR is red — the broadcast tally colour, matching the Stop button and the sticky
    // banner (green read as "OK/idle" and clashed with the red Stop).
    val dotColor = when (tone) {
        PillTone.Live -> MaterialTheme.colorScheme.error
        PillTone.Busy -> MaterialTheme.colorScheme.tertiary
        PillTone.Error -> MaterialTheme.colorScheme.error
        PillTone.Idle -> MaterialTheme.colorScheme.outlineVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        JokoDotBadge(
            text = label,
            dotColor = dotColor,
            pulse = tone == PillTone.Live,
            // Polite live region: TalkBack announces "On air" / "Reconnecting…" as it happens,
            // without the user having to move focus to the badge.
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (tone == PillTone.Busy) {
            JokoPulseRing(dotColor, size = 12.dp)
        }
    }
}

internal enum class PillTone { Idle, Busy, Live, Error }

internal fun StreamState.tone(): PillTone = when (this) {
    StreamState.Idle -> PillTone.Idle
    StreamState.Connecting, is StreamState.Reconnecting -> PillTone.Busy
    is StreamState.Live -> PillTone.Live
    is StreamState.Error -> PillTone.Error
}

/** Short status label shared by the Home badge and the sticky on-air banner. */
@Composable
internal fun stateLabel(state: StreamState): String = when (state) {
    StreamState.Idle -> stringResource(R.string.state_off_air)
    StreamState.Connecting -> stringResource(R.string.state_connecting)
    is StreamState.Live -> stringResource(R.string.state_on_air)
    is StreamState.Reconnecting -> stringResource(R.string.state_reconnecting, state.attempt)
    is StreamState.Error -> stringResource(R.string.state_error)
}

// ---- Hero Start/Stop control (tally lamp) ----

@Composable
private fun HeroControl(
    state: StreamState,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    val isLive = state is StreamState.Live
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    // Stop is reachable from any non-Idle state so the user can abort during Connecting /
    // Reconnecting (the Stop-reconnect fix #2). Tap mapping:
    //   - Idle      -> Start
    //   - Live      -> confirm, then Stop (U-6: ending a show must not be one stray tap)
    //   - Busy      -> Stop / abort, one tap (nothing is on air yet to protect)
    //   - Error     -> Retry (same profile) or Dismiss (Stop resets the service to Idle)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Tally semantics: red-600 to stop a live broadcast (destructive), lime primary
        // to start, slate-100 secondary to abort a busy state.
        when (state) {
            is StreamState.Live -> JokoPrimaryButton(
                text = stringResource(R.string.action_stop),
                onClick = { confirmStop = true },
                container = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                verticalPadding = 20.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            StreamState.Idle -> JokoPrimaryButton(
                text = stringResource(R.string.action_start),
                onClick = onStart,
                enabled = enabled,
                verticalPadding = 20.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            is StreamState.Error -> ErrorPanel(reason = state.reason, onRetry = onRetry, onDismiss = onStop)
            else -> JokoSecondaryButton(
                text = stringResource(R.string.action_stop),
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state !is StreamState.Error) {
            Text(
                text = stringResource(
                    if (state is StreamState.Idle) R.string.home_hint_start else R.string.home_hint_stop,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }

    if (confirmStop && isLive) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.home_stop_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.home_stop_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    onStop()
                }) {
                    Text(
                        stringResource(R.string.home_stop_confirm_action),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) {
                    Text(stringResource(R.string.home_stop_confirm_keep), style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
    // If the stream drops on its own while the dialog is up, don't resurrect it on the next Live.
    LaunchedEffect(isLive) { if (!isLive) confirmStop = false }
}

/** U-7: plain-language cause + Retry / Dismiss instead of upper-cased exception text. */
@Composable
private fun ErrorPanel(reason: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val err = remember(reason) { streamErrorText(reason) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        JokoAlert(text = err.message(), color = MaterialTheme.colorScheme.error)
        if (err.detail != null) {
            Text(
                stringResource(R.string.err_detail, err.detail),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JokoSecondaryButton(
                text = stringResource(R.string.action_dismiss),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            JokoPrimaryButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
                modifier = Modifier.weight(1f),
            )
        }
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
        SectionLabel(stringResource(R.string.home_profile))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(
                        if (profile != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile?.name ?: stringResource(R.string.home_no_profile_selected),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val summary = when {
                    profile != null -> stringResource(
                        R.string.home_profile_summary,
                        profile.host, profile.port, profile.codec, profile.bitrateKbps,
                    )
                    profileCount == 0 -> stringResource(R.string.home_create_profile_hint)
                    else -> pluralStringResource(R.plurals.home_profiles_available, profileCount, profileCount)
                }
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
                    stringResource(R.string.home_manage),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
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
            stringResource(R.string.home_select_profile_first),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.home_select_profile_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onManageProfiles,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text(
                stringResource(R.string.home_manage_profiles),
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
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        label = { Text(stringResource(R.string.home_now_playing)) },
        placeholder = {
            Text(
                stringResource(R.string.home_now_playing_placeholder),
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
                modifier = Modifier.padding(end = 6.dp),
            ) {
                Text(stringResource(R.string.action_update), style = MaterialTheme.typography.labelLarge)
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

// ---- Mic volume (software gain) ----

@Composable
private fun MicVolumeSlider(gain: Float, onChange: (Float) -> Unit) {
    val pct = (gain * 100f).roundToInt()
    val label = stringResource(R.string.home_mic_volume)
    val pctText = stringResource(R.string.home_mic_volume_value, pct)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SectionLabel(label)
            Text(
                pctText,
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
            // The range is 0..2, so TalkBack's default "% of range" would read 50 % at unity
            // gain. Announce the same number the screen shows, and name the control.
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = pctText
            },
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
    lastSavedPath: String?,
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
            SectionLabel(stringResource(R.string.home_section_recorder))
            if (recorderActive) {
                JokoSoftBadge(
                    stringResource(R.string.badge_recording),
                    MaterialTheme.colorScheme.error,
                    textColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else if (isRecording) {
                JokoSoftBadge(
                    stringResource(R.string.badge_tapping_stream),
                    MaterialTheme.colorScheme.primary,
                    textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        // Standalone recorder: primary action, available whether Idle or Live (records in
        // parallel with a broadcast). Needs a profile for the encode config.
        JokoPrimaryButton(
            text = stringResource(if (recorderActive) R.string.record_stop else R.string.record_start),
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
                text = stringResource(if (isRecording) R.string.record_stream_stop else R.string.record_stream_start),
                onClick = onToggleLiveRecording,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // "Saving to" only while a file is actually being written; afterwards the same line
        // says where the last file went (it used to keep saying "Saving to:" after Stop).
        val savingPath = (recordState as? RecordState.Recording)?.path
            ?: recordingPath?.takeIf { isRecording }
        when {
            savingPath != null -> Text(
                stringResource(R.string.record_saving_to, savingPath),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lastSavedPath != null -> Text(
                stringResource(R.string.record_saved_to, lastSavedPath),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recorderError != null) {
            val err = remember(recorderError.reason) { recordErrorText(recorderError.reason) }
            JokoAlert(text = err.message(), color = MaterialTheme.colorScheme.error)
            if (err.detail != null) {
                Text(
                    stringResource(R.string.err_detail, err.detail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
