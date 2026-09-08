package com.streamy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamy.app.data.db.ProfileEntity

/*
 * JokoUI daylight list: slate page, white rounded-xl rows, lime primary action.
 *
 * Lists all server profiles. Tap a row → select + back to Home. Edit trailing → EditProfile
 * with that id. Delete trailing → confirm dialog. "Add profile" → EditProfile (new).
 *
 * Stateless: reads [vm.profiles] + [vm.selectedProfileId], emits selections + CRUD via vm.
 */
@Composable
fun ServerProfilesScreen(
    vm: StreamyViewModel,
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selectedId by vm.selectedProfileId.collectAsStateWithLifecycle()

    var pendingDelete by remember { mutableStateOf<ProfileEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = "Servers",
            subtitle = if (profiles.isEmpty()) "No servers yet"
            else "${profiles.size} configured",
            onBack = onBack,
        )

        if (profiles.isEmpty()) {
            EmptyState(onAddProfile = onAddProfile)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(profiles, key = { it.id }) { p ->
                    ProfileRow(
                        profile = p,
                        selected = p.id == selectedId,
                        onClick = {
                            vm.selectProfile(p.id)
                            onBack()
                        },
                        onEdit = { onEditProfile(p.id) },
                        onDelete = { pendingDelete = p },
                    )
                }
            }
        }

        JokoPrimaryButton(
            text = "Add profile",
            onClick = onAddProfile,
            modifier = Modifier.fillMaxWidth(),
            verticalPadding = 14.dp,
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${target.name}?") },
            text = {
                Text(
                    "This removes the profile from Streamy. The server itself is not affected.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProfile(target)
                    pendingDelete = null
                }) {
                    Text(
                        "Delete",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            modifier = Modifier.align(Alignment.Start),
        ) {
            Text(
                "‹ Back",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileRow(
    profile: ProfileEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
            )
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        SelectionDot(selected = selected)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (selected) {
                    JokoSoftBadge("Selected", MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "${if (profile.mode.equals("DJ", ignoreCase = true)) "DJ" else "Source"} · " +
                    "${profile.host}:${profile.port} · ${profile.codec} ${profile.bitrateKbps} kbps" +
                    (if (profile.mono) " · mono" else " · stereo"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowIconBtn(icon = Icons.Outlined.Edit, label = "Edit", onClick = onEdit)
        RowIconBtn(
            icon = Icons.Outlined.Delete,
            label = "Delete",
            onClick = onDelete,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SelectionDot(selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.secondary
    else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun RowIconBtn(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

@Composable
private fun EmptyState(onAddProfile: () -> Unit) {
    JokoCard {
        Text(
            "No servers yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Add a SHOUTcast server to start broadcasting. Streamy keeps the list " +
                "on this device — nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        JokoOutlineButton(
            text = "Add your first profile",
            onClick = onAddProfile,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
