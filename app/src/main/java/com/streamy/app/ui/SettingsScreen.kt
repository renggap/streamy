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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 * ponytail: move cleartext-traffic note into a proper network-security-config doc in
 * Task 14 release config. Mentioned here as a dev-only warning.
 */
@Composable
fun SettingsScreen(
    vm: StreamyViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profiles by vm.profiles.collectAsStateWithLifecycle()
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
                "Broadcast console for SHOUTcast v2 / Icecast sources.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Version $version",
                style = MaterialTheme.typography.labelMedium,
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
                "Cleartext HTTP is enabled in debug builds for LAN dev servers. Disable before release.",
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
