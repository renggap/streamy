package com.streamy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec

/**
 * Hallmark "console" control surface: codec toggle, bitrate ladder, sample-rate picker,
 * mono/stereo switch. Disabled wholesale when [enabled] is false (the caller surfaces a
 * "stop to change" hint when Live).
 *
 * HE-AAC guard: AAC 16/24 → mono forced on; the mono/stereo toggle is disabled and a
 * hint explains the HE-AAC v1 reason. The VM also rejects setMono(false) in this regime.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BitrateChooser(
    vm: StreamyViewModel,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val codec by vm.codec.collectAsStateWithLifecycle()
    val bitrate by vm.bitrate.collectAsStateWithLifecycle()
    val sampleRate by vm.sampleRate.collectAsStateWithLifecycle()
    val mono by vm.mono.collectAsStateWithLifecycle()
    val heAac by vm.heAacActive.collectAsStateWithLifecycle()

    val bitrates = if (codec == Codec.AAC) AudioConfigRules.AAC_BITRATES else AudioConfigRules.MP3_BITRATES

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        // ---- Codec toggle ----
        SectionLabel("CODEC")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TogglePill(
                label = "MP3",
                active = codec == Codec.MP3,
                enabled = enabled,
                onClick = { vm.setCodec(Codec.MP3) },
                activeContainer = MaterialTheme.colorScheme.surfaceVariant,
                activeContent = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TogglePill(
                label = "AAC",
                active = codec == Codec.AAC,
                enabled = enabled,
                onClick = { vm.setCodec(Codec.AAC) },
                activeContainer = MaterialTheme.colorScheme.surfaceVariant,
                activeContent = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        // ---- Bitrate ladder ----
        SectionLabel("BITRATE · KBPS")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            bitrates.forEach { kbps ->
                FilterChip(
                    selected = kbps == bitrate,
                    onClick = { if (enabled) vm.setBitrate(kbps) },
                    enabled = enabled,
                    label = { Text("$kbps", style = MaterialTheme.typography.labelLarge) },
                    shape = MaterialTheme.shapes.small,
                    colors = chipColors(),
                )
            }
        }

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

        // ---- Sample rate picker ----
        SectionLabel("SAMPLE RATE")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            AudioConfigRules.SAMPLE_RATES.forEach { hz ->
                FilterChip(
                    selected = hz == sampleRate,
                    onClick = { if (enabled) vm.setSampleRate(hz) },
                    enabled = enabled,
                    label = { Text(hz.toKHz(), style = MaterialTheme.typography.labelLarge) },
                    shape = MaterialTheme.shapes.small,
                    colors = chipColors(),
                )
            }
        }

        // ---- Channels ----
        SectionLabel("CHANNELS")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TogglePill(
                label = "MONO",
                active = mono,
                enabled = enabled && !heAac,
                onClick = { vm.setMono(true) },
                activeContainer = MaterialTheme.colorScheme.secondaryContainer,
                activeContent = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TogglePill(
                label = "STEREO",
                active = !mono,
                enabled = enabled && !heAac,
                onClick = { vm.setMono(false) },
                activeContainer = MaterialTheme.colorScheme.secondaryContainer,
                activeContent = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
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

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)

private fun Int.toKHz(): String {
    val kHz = this / 1000.0
    return if (kHz == kHz.toInt().toDouble()) "${kHz.toInt()} kHz" else "$kHz kHz"
}
