package com.streamy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamy.app.R
import com.streamy.app.data.db.ProfileEntity

/*
 * JokoUI daylight list: slate page, white rounded-xl rows, lime primary action.
 *
 * Lists all server profiles. Tap a row → select + back to Home. Edit trailing → EditProfile
 * with that id. Delete trailing → confirm dialog. "Add profile" → EditProfile (new).
 *
 * Top-level bottom-nav destination: no Back button (the nav bar and system back cover it).
 * While a stream is on air, its profile is marked and locked: the VM refuses selecting
 * another row or deleting the live one, with a message (U-5).
 *
 * Stateless: reads [vm.profiles] + [vm.selectedProfileId], emits selections + CRUD via vm.
 */
@Composable
fun ServerProfilesScreen(
    vm: StreamyViewModel,
    onProfileSelected: () -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val selectedId by vm.selectedProfileId.collectAsStateWithLifecycle()
    val streamProfile by vm.streamProfile.collectAsStateWithLifecycle()
    val liveId = streamProfile?.id

    var pendingDelete by remember { mutableStateOf<ProfileEntity?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenHeader(
            title = stringResource(R.string.profiles_title),
            subtitle = if (profiles.isEmpty()) stringResource(R.string.profiles_none_yet)
            else pluralStringResource(R.plurals.profiles_configured, profiles.size, profiles.size),
        )

        if (profiles.isEmpty()) {
            EmptyState(onAddProfile = onAddProfile)
        } else {
            // weight(fill = false): the list takes only the space it needs, but never more than
            // what's left — a long list scrolls instead of pushing "Add profile" off-screen.
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(profiles, key = { it.id }) { p ->
                    ProfileRow(
                        profile = p,
                        selected = p.id == selectedId,
                        onAir = p.id == liveId,
                        onClick = {
                            if (vm.selectProfile(p.id)) onProfileSelected()
                        },
                        onEdit = { onEditProfile(p.id) },
                        onDelete = {
                            if (!vm.isLockedByStream(p.id)) pendingDelete = p
                        },
                    )
                }
            }
        }

        JokoPrimaryButton(
            text = stringResource(R.string.profiles_add),
            onClick = onAddProfile,
            modifier = Modifier.fillMaxWidth(),
            verticalPadding = 14.dp,
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.profiles_delete_title, target.name)) },
            text = {
                Text(
                    stringResource(R.string.profiles_delete_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProfile(target)
                    pendingDelete = null
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

/**
 * Screen title block. [onBack] = null on top-level (bottom-nav) destinations — a Back
 * button there duplicates the nav bar and pops in surprising directions.
 */
@Composable
internal fun ScreenHeader(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (onBack != null) {
            // Offset by the IconButton's inner padding so the arrow lines up with the title.
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { heading() },
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
    onAir: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 2.dp, shape = shape)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface, shape)
            .border(
                width = 1.dp,
                color = when {
                    onAir -> MaterialTheme.colorScheme.error
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
                shape = shape,
            )
            // Radio semantics: TalkBack reads "selected" + position in the group, which the
            // border colour alone never conveyed.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
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
                if (onAir) {
                    JokoSoftBadge(
                        stringResource(R.string.badge_on_air),
                        MaterialTheme.colorScheme.error,
                        textColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else if (selected) {
                    JokoSoftBadge(
                        stringResource(R.string.badge_selected),
                        MaterialTheme.colorScheme.primary,
                        textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                stringResource(
                    R.string.profile_row_summary,
                    stringResource(
                        if (profile.mode.equals("DJ", ignoreCase = true)) R.string.mode_dj
                        else R.string.mode_source,
                    ),
                    profile.host,
                    profile.port,
                    profile.codec,
                    profile.bitrateKbps,
                    stringResource(if (profile.mono) R.string.channels_mono else R.string.channels_stereo),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowIconBtn(
            icon = Icons.Outlined.Edit,
            label = stringResource(R.string.cd_edit_profile, profile.name),
            onClick = onEdit,
        )
        RowIconBtn(
            icon = Icons.Outlined.Delete,
            label = stringResource(R.string.cd_delete_profile, profile.name),
            onClick = onDelete,
            tint = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SelectionDot(selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary
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
            stringResource(R.string.profiles_none_yet),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            stringResource(R.string.profiles_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        JokoOutlineButton(
            text = stringResource(R.string.profiles_add_first),
            onClick = onAddProfile,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
