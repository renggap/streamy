package com.streamy.app.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamy.app.R

/*
 * Hallmark · screen: Settings · macrostructure: Workbench about/list · theme: custom
 * (late-night tally light) — same Surface + hairline outline cards.
 */

/**
 * App identity, the LAME LGPL notice (read raw text), and a destructive "clear all
 * profiles" action. No new persistence — just reads [vm.profiles] and calls vm.clear.
 *
 * Cleartext policy is now split per build type: debug overlay allows it, release is
 * TLS-only for HTTP clients (see network_security_config.xml). This copy documents that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: StreamyViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val inputDevices by vm.inputDevices.collectAsStateWithLifecycle()
    val selectedDevice = vm.selectedDeviceName.collectAsStateWithLifecycle().value  // plain val (smart-castable)
    val selectedDeviceLabel = if (selectedDevice == null) "System default"
        else inputDevices.firstOrNull { it.name == selectedDevice }?.label ?: selectedDevice
    var deviceExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshInputDevices() }
    var pendingClear by remember { mutableStateOf(false) }

    val version by produceState(initialValue = "—") {
        value = runCatching {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(context.packageName, 0)
            "${info.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode})"
        }.getOrDefault("—")
    }

    val lameNotice by produceState(initialValue = "") {
        value = runCatching {
            context.resources.openRawResource(R.raw.lame_notice).bufferedReader().use { it.readText() }
        }.getOrDefault("LAME notice unavailable.")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(
            title = "SETTINGS",
            subtitle = "About · legal · data",
            onBack = onBack,
        )

        FieldGroup("ABOUT") {
            Text(
                "Streamy",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Broadcast console for SHOUTcast v2 sources.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Version $version",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FieldGroup("INPUT DEVICE") {
            ExposedDropdownMenuBox(
                expanded = deviceExpanded,
                onExpandedChange = { deviceExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedDeviceLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    shape = RoundedCornerShape(4.dp),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceExpanded)
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                        disabledContainerColor = MaterialTheme.colorScheme.background,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = deviceExpanded,
                    onDismissRequest = { deviceExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("System default", style = MaterialTheme.typography.bodyMedium) },
                        onClick = { vm.setInputDevice(null); deviceExpanded = false },
                    )
                    inputDevices.forEach { d ->
                        DropdownMenuItem(
                            text = { Text(d.label, style = MaterialTheme.typography.bodyMedium) },
                            onClick = { vm.setInputDevice(d.name); deviceExpanded = false },
                        )
                    }
                }
            }
            Text(
                "Selected mic applies on the next stream start. The device is matched by name " +
                    "each session — replug/ reboot safe.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FieldGroup("LEGAL") {
            Text(
                lameNotice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Cleartext HTTP is enabled in debug builds for LAN dev servers. " +
                    "Release builds restrict HTTP clients to TLS; the raw-socket source " +
                    "protocol (SHOUTcast/Ultravox/Icecast) is unaffected.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        FieldGroup("DATA") {
            Text(
                "${profiles.size} profile${if (profiles.size == 1) "" else "s"} stored on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedButton(
                onClick = { pendingClear = true },
                enabled = profiles.isNotEmpty(),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "CLEAR ALL PROFILES",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text("Clear all profiles?") },
            text = {
                Text(
                    "This deletes every server profile on this device. The servers themselves " +
                        "are not affected.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllProfiles()
                    pendingClear = false
                }) { Text("CLEAR", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) {
                    Text("CANCEL", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}
