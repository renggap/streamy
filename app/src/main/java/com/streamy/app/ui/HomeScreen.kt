package com.streamy.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.service.StreamState

/*
 * Hallmark · screen: Home · macrostructure: Workbench (broadcast console) · theme: custom
 * (atmospheric cluster · "late-night tally light") — reuses Theme.kt tokens.
 */

/**
 * Single-screen broadcast console. Pulls from [vm] state, emits via vm mutators. The hero
 * element is the tally-lamp Start/Stop control — hard slab, on-air red when armed, signal
 * green when Live.
 *
 * The chooser is bound to the SELECTED profile via [vm]: changing codec/bitrate/sample/
 * mono tunes the profile row in place. When no profile is selected, the chooser is
 * disabled with a "select a profile first" hint.
 *
 * ponytail: VU meter + local recording toggle — add RMS callback to
 * AudioCapture/AudioPipeline, then surface here.
 */
@Composable
fun HomeScreen(
    vm: StreamyViewModel,
    onManageProfiles: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.streamState.collectAsStateWithLifecycle()
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selectedProfile by vm.selectedProfile.collectAsStateWithLifecycle()
    val message by vm.userMessage.collectAsStateWithLifecycle()
    val needsRestart by vm.needsRestartToApply.collectAsStateWithLifecycle()

    val codec by vm.codec.collectAsStateWithLifecycle()
    val bitrate by vm.bitrate.collectAsStateWithLifecycle()
    val sampleRate by vm.sampleRate.collectAsStateWithLifecycle()
    val mono by vm.mono.collectAsStateWithLifecycle()

    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHost.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val isLive = state is StreamState.Live
    val isBusy = state is StreamState.Connecting || state is StreamState.Reconnecting
    val hasProfile = selectedProfile != null
    val chooserEnabled = !isLive && !isBusy && hasProfile

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Masthead(onOpenSettings = onOpenSettings)

            StatePill(state = state)

            HeroControl(
                state = state,
                enabled = !isBusy && hasProfile,
                onStart = vm::start,
                onStop = vm::stop,
            )

            if (needsRestart) {
                Hint(
                    "Settings changed — stop and restart to apply",
                    tone = HintTone.Warn,
                )
            }

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

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

// ---- Masthead ----

@Composable
private fun Masthead(onOpenSettings: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "STREAMY",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 6.sp,
            )
            Text(
                text = "On-air console",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = onOpenSettings,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("SETTINGS", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---- State pill ----

@Composable
private fun StatePill(state: StreamState) {
    val (label, tone) = state.toPill()
    val bgColor = when (tone) {
        PillTone.Live -> MaterialTheme.colorScheme.secondary
        PillTone.Busy -> MaterialTheme.colorScheme.tertiary
        PillTone.Error -> MaterialTheme.colorScheme.primary
        PillTone.Idle -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fgColor = when (tone) {
        PillTone.Live -> MaterialTheme.colorScheme.onSecondary
        PillTone.Busy -> MaterialTheme.colorScheme.onTertiary
        PillTone.Error -> MaterialTheme.colorScheme.onPrimary
        PillTone.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Functional motion: a slow pulse on the dot when Live so the user has peripheral
    // confirmation the stream is up. Cut entirely under prefers-reduced-motion via the
    // system's animation scale (Compose respects this automatically for infinite anims).
    val pulseAlpha = if (tone == PillTone.Live) {
        val t = rememberInfiniteTransition(label = "live-pulse")
        val a by t.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_400),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        a
    } else {
        1f
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(2.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(10.dp)
                .alpha(pulseAlpha)
                .background(fgColor, androidx.compose.foundation.shape.CircleShape),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = fgColor,
        )
    }
}

private enum class PillTone { Idle, Busy, Live, Error }

private fun StreamState.toPill(): Pair<String, PillTone> = when (this) {
    StreamState.Idle -> "OFF AIR" to PillTone.Idle
    StreamState.Connecting -> "CONNECTING" to PillTone.Busy
    StreamState.Live -> "ON AIR" to PillTone.Live
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

    // Tally-lamp palette: red when armed (idle, ready to go), green when Live.
    val lampOn = isLive
    val lampColor = if (isLive) MaterialTheme.colorScheme.secondary
    else MaterialTheme.colorScheme.primary
    val lampLabel = when {
        isLive -> "STOP"
        isBusy -> "WORKING"
        else -> "START"
    }
    val lampAction = if (isLive) onStop else onStart

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = lampAction,
            enabled = enabled && !isBusy,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = lampColor,
                contentColor = if (isLive) MaterialTheme.colorScheme.onSecondary
                else MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(vertical = 28.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Big mono label — the visual anchor of the screen.
            Text(
                lampLabel,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
            )
        }
        Text(
            text = if (lampOn) "Tap to stop broadcasting"
            else "Tap to go on air",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

// ---- Profile chip ----

@Composable
private fun ProfileChip(
    profile: ProfileEntity?,
    profileCount: Int,
    enabled: Boolean,
    onManageProfiles: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "PROFILE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(6.dp),
                )
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile?.name ?: "No profile selected",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (profile != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text("MANAGE", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ---- Empty-state hint (no profile selected) ----

@Composable
private fun NoProfileHint(onManageProfiles: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Select a profile first",
            style = MaterialTheme.typography.titleLarge,
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
            Text("MANAGE PROFILES", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---- Hint banner ----

private enum class HintTone { Warn }

@Composable
private fun Hint(text: String, tone: HintTone) {
    val color = when (tone) {
        HintTone.Warn -> MaterialTheme.colorScheme.tertiary
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .border(width = 1.dp, color = color.copy(alpha = 0.4f), shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, androidx.compose.foundation.shape.CircleShape),
        )
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
