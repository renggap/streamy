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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec

/*
 * Hallmark · component: chooser · genre: atmospheric · theme: custom (late-night tally light)
 * states: default · hover · focus · active · disabled · loading(n/a) · error(n/a) · success(n/a)
 *
 * Stateless: caller passes value + callbacks. Two call sites — HomeScreen (bound to VM)
 * and EditProfileScreen (bound to local form state) — share the codec/bitrate/sample/mono
 * rules (HE-AAC at AAC ≤ 24 → mono forced) from this single source.
 *
 * Codec stays as two pills (MP3/AAC); bitrate / sample-rate / channels collapse to
 * ExposedDropdownMenuBox dropdowns to save vertical space. Same public signature, so both
 * call sites compile unchanged.
 */

/**
 * Codec toggle (pills) + bitrate / sample-rate / channels dropdowns. Pure — pulls nothing
 * from the VM. Caller enforces [enabled] (Home disables when Live or when no profile).
 *
 * HE-AAC guard: AAC 16/24 → mono forced on; the channels dropdown is disabled and a hint
 * explains the HE-AAC v1 reason. Callers mirror this when applying [onCodec] / [onBitrate]
 * (see [AudioConfigRules.needsHeAac]).
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
) {
    val heAac = AudioConfigRules.needsHeAac(codec, bitrate)
    val bitrates = if (codec == Codec.AAC) AudioConfigRules.AAC_BITRATES else AudioConfigRules.MP3_BITRATES

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ---- Codec toggle (pills, per spec) ----
        SectionLabel("CODEC")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TogglePill(
                label = "MP3",
                active = codec == Codec.MP3,
                enabled = enabled,
                onClick = { onCodec(Codec.MP3) },
                activeContainer = MaterialTheme.colorScheme.surfaceVariant,
                activeContent = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // AAC temporarily disabled in the UI — keep the encoder + UvoxClient AAC code for
            // when it's revisited. Pill is greyed/non-selectable; MP3 remains the active path.
            TogglePill(
                label = "AAC",
                active = codec == Codec.AAC,
                enabled = false,
                onClick = { onCodec(Codec.AAC) },
                activeContainer = MaterialTheme.colorScheme.surfaceVariant,
                activeContent = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        // ---- Bitrate ladder (dropdown) ----
        DropdownField(
            label = "BITRATE · KBPS",
            selected = "$bitrate kbps",
            enabled = enabled,
            options = bitrates.map { it to "$it kbps" },
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
                    "HE-AAC v1 · mono forced — sub-32 kbps speech mode",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        // ---- Sample rate (dropdown) ----
        DropdownField(
            label = "SAMPLE RATE",
            selected = sampleRate.toKHz(),
            enabled = enabled,
            options = AudioConfigRules.validSampleRatesFor(codec, bitrate).map { it to it.toKHz() },
            onSelect = { onSampleRate(it) },
        )

        // ---- Channels (dropdown; disabled while HE-AAC forces mono) ----
        DropdownField(
            label = "CHANNELS",
            selected = if (mono) "Mono" else "Stereo",
            enabled = enabled && !heAac,
            options = listOf(true to "Mono", false to "Stereo"),
            onSelect = { onMono(it) },
        )
    }
}

/**
 * Labeled read-only [ExposedDropdownMenuBox] styled to match [LabeledTextField] elsewhere
 * in the app (hairline outline, background container, small rounded shape).
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
                shape = RoundedCornerShape(4.dp),
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
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TogglePill(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    activeContainer: Color,
    activeContent: Color,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) activeContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (active) activeContent else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(vertical = 14.dp),
        modifier = modifier,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = if (active) FontWeight.Black else FontWeight.Medium,
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

internal fun Int.toKHz(): String {
    val kHz = this / 1000.0
    return if (kHz == kHz.toInt().toDouble()) "${kHz.toInt()} kHz" else "$kHz kHz"
}
