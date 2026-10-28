package com.streamy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamy.app.R
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec

/*
 * JokoUI chooser: codec as two soft-fill toggles (active = lime-100 bg + lime-800 text),
 * bitrate / sample-rate / channels as rounded-lg dropdowns. Stateless: caller passes value
 * + callbacks. Two call sites — HomeScreen (bound to VM) and EditProfileScreen (bound to
 * local form state) — share the codec/bitrate/sample/mono rules (HE-AAC at AAC ≤ 24 →
 * mono forced) from this single source.
 */

/**
 * Codec toggle (pills) + bitrate / sample-rate / channels dropdowns. Pure — pulls nothing
 * from the VM. Caller enforces [enabled] (Home disables when Live or when no profile).
 *
 * HE-AAC guard: AAC 16/24 → mono forced on; the channels dropdown is disabled and a hint
 * explains the HE-AAC v1 reason. Callers mirror this when applying [onCodec] / [onBitrate]
 * (see [AudioConfigRules.needsHeAac]).
 *
 * AAC is UI-disabled (AGENTS.md: DNAS quirks — re-verify against a real DNAS2 before
 * flipping [allowAac]). With it off, the AAC pill is hidden — except when a legacy profile
 * already uses AAC: then it shows as the selected-but-disabled option so the user can see
 * what the row holds and switch to MP3 (one way only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitrateChooser(
    codec: Codec,
    bitrate: Int,
    sampleRate: Int,
    mono: Boolean,
    enabled: Boolean,
    onCodec: (Codec) -> Unit,
    onBitrate: (Int) -> Unit,
    onSampleRate: (Int) -> Unit,
    onMono: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    allowAac: Boolean = false,   // AAC UI-disabled; see KDoc
) {
    val heAac = AudioConfigRules.needsHeAac(codec, bitrate)
    val bitrates = if (codec == Codec.AAC) AudioConfigRules.AAC_BITRATES else AudioConfigRules.MP3_BITRATES

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- Codec toggle (soft fills, per JokoUI badge language) ----
        SectionLabel(stringResource(R.string.chooser_codec))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.selectableGroup(),
        ) {
            TogglePill(
                label = "MP3",
                active = codec == Codec.MP3,
                enabled = enabled,
                onClick = { onCodec(Codec.MP3) },
                modifier = Modifier.weight(1f),
            )
            // AAC streams as HE-AAC over Ultravox (audio/aacp, data msg 0x8003) — see AacEncoder.
            // Hidden while allowAac=false unless the row already holds AAC (see KDoc).
            if (allowAac || codec == Codec.AAC) {
                TogglePill(
                    label = "AAC",
                    active = codec == Codec.AAC,
                    enabled = enabled && allowAac,
                    onClick = { onCodec(Codec.AAC) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!allowAac && codec == Codec.AAC) {
            Text(
                stringResource(R.string.chooser_aac_legacy_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        // ---- Bitrate ladder (dropdown) ----
        val kbps = stringResource(R.string.unit_kbps_format)
        DropdownField(
            label = stringResource(R.string.chooser_bitrate),
            selected = kbps.format(bitrate),
            enabled = enabled,
            options = bitrates.map { it to kbps.format(it) },
            onSelect = { onBitrate(it) },
        )

        if (heAac) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Dot(color = MaterialTheme.colorScheme.tertiary)
                Text(
                    stringResource(R.string.chooser_heaac_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        // ---- Sample rate (dropdown) ----
        DropdownField(
            label = stringResource(R.string.chooser_sample_rate),
            selected = sampleRate.toKHz(),
            enabled = enabled,
            options = AudioConfigRules.validSampleRatesFor(codec, bitrate).map { it to it.toKHz() },
            onSelect = { onSampleRate(it) },
        )

        // ---- Channels (dropdown; disabled while HE-AAC forces mono) ----
        val monoLabel = stringResource(R.string.channels_mono)
        val stereoLabel = stringResource(R.string.channels_stereo)
        DropdownField(
            label = stringResource(R.string.chooser_channels),
            selected = if (mono) monoLabel else stereoLabel,
            enabled = enabled && !heAac,
            options = listOf(true to monoLabel, false to stereoLabel),
            onSelect = { onMono(it) },
        )
    }
}

/**
 * Labeled read-only [ExposedDropdownMenuBox] styled to match JokoUI inputs
 * (rounded-lg, slate containers).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> DropdownField(
    label: String,
    selected: String,
    enabled: Boolean,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(label)
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it },
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                enabled = enabled,
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    disabledContainerColor = MaterialTheme.colorScheme.background,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                    disabledIndicatorColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One option of a single-choice pill group (codec, broadcast mode). Built on
 * [Modifier.selectable] with [Role.RadioButton] — not a Button — so TalkBack announces
 * "selected, radio button, 1 of 2" instead of an unlabelled push button. Callers wrap the
 * row in [Modifier.selectableGroup]. A disabled-but-active pill keeps its active fill so a
 * locked chooser (Live, or legacy AAC) still shows the current value.
 */
@Composable
internal fun TogglePill(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    activeContent: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val shape = RoundedCornerShape(8.dp)
    val container = if (active) activeContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = when {
        active -> activeContent
        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .background(container, shape)
            .selectable(
                selected = active,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = content,
        )
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/** Unit symbols aren't translated (kHz is kHz in Bahasa Indonesia too). */
internal fun Int.toKHz(): String {
    val kHz = this / 1000.0
    return if (kHz == kHz.toInt().toDouble()) "${kHz.toInt()} kHz" else "$kHz kHz"
}
