package com.streamy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamy.app.R

/*
 * JokoUI daylight about page: slate page, white rounded-xl FieldGroups.
 */

/**
 * App identity, input device, the "keep screen on while live" preference, the LAME LGPL
 * notice (read raw text), and a destructive "clear all profiles" action.
 *
 * Top-level bottom-nav destination: no Back button (the nav bar and system back cover it).
 *
 * Cleartext policy is now split per build type: debug overlay allows it, release is
 * TLS-only for HTTP clients (see network_security_config.xml). This copy documents that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: StreamyViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val inputDevices by vm.inputDevices.collectAsStateWithLifecycle()
    val selectedDevice = vm.selectedDeviceName.collectAsStateWithLifecycle().value  // plain val (smart-castable)
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val systemDefault = stringResource(R.string.settings_system_default)
    val selectedDeviceLabel = if (selectedDevice == null) systemDefault
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

    // Read once; null = unavailable (resolved to a localized line at render time).
    val lameNotice by produceState<String?>(initialValue = "") {
        value = runCatching {
            context.resources.openRawResource(R.raw.lame_notice).bufferedReader().use { it.readText() }
        }.getOrNull()
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
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_subtitle),
        )

        FieldGroup(stringResource(R.string.settings_group_about)) {
            Text(
                stringResource(R.string.brand_name),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.settings_version, version),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FieldGroup(stringResource(R.string.settings_group_input)) {
            ExposedDropdownMenuBox(
                expanded = deviceExpanded,
                onExpandedChange = { deviceExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedDeviceLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.settings_input_label)) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = deviceExpanded)
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = deviceExpanded,
                    onDismissRequest = { deviceExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(systemDefault, style = MaterialTheme.typography.bodyMedium) },
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
                stringResource(R.string.settings_input_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FieldGroup(stringResource(R.string.settings_group_display)) {
            // Whole row toggles (Switch has no own click handler) so the 48dp target is the
            // row and TalkBack reads one "switch, on/off" node with its label.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = keepScreenOn,
                        role = Role.Switch,
                        onValueChange = vm::setKeepScreenOn,
                    )
                    .padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.settings_keep_screen_on),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.settings_keep_screen_on_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepScreenOn, onCheckedChange = null)
            }
        }

        FieldGroup(stringResource(R.string.settings_group_legal)) {
            Text(
                lameNotice ?: stringResource(R.string.settings_lame_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.settings_cleartext_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        FieldGroup(stringResource(R.string.settings_group_data)) {
            Text(
                pluralStringResource(R.plurals.settings_profiles_stored, profiles.size, profiles.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            JokoOutlineButton(
                text = stringResource(R.string.settings_clear_all),
                onClick = { pendingClear = true },
                enabled = profiles.isNotEmpty(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text(stringResource(R.string.settings_clear_title)) },
            text = {
                Text(
                    stringResource(R.string.settings_clear_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllProfiles()
                    pendingClear = false
                }) {
                    Text(
                        stringResource(R.string.action_clear),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}
