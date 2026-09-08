package com.streamy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.streamy.app.data.AudioConfig
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.BroadcastMode
import com.streamy.app.data.Codec
import com.streamy.app.data.DjProtocol
import com.streamy.app.data.db.ProfileEntity

/*
 * JokoUI daylight form: slate page, white rounded-xl FieldGroups, lime primary save.
 * State held in local remember — standard for forms; the VM only hears the final save.
 */

/**
 * Create or edit a server profile. New when [profileId] is null or 0; otherwise loads the
 * existing row and populates the form.
 */
@Composable
fun EditProfileScreen(
    vm: StreamyViewModel,
    profileId: Long?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNew = profileId == null || profileId == 0L

    // I7: key every editable field on profileId so navigating edit/A -> back -> edit/B
    // doesn't show A's stale values. When profileId changes, remember re-initializes.
    var name by remember(profileId) { mutableStateOf("") }
    var host by remember(profileId) { mutableStateOf("") }
    var port by remember(profileId) { mutableStateOf("8000") }
    var sid by remember(profileId) { mutableStateOf("1") }
    var password by remember(profileId) { mutableStateOf("") }
    var mount by remember(profileId) { mutableStateOf("/stream") }
    var mode by remember(profileId) { mutableStateOf(BroadcastMode.SOURCE) }
    var djUser by remember(profileId) { mutableStateOf("") }
    var djMount by remember(profileId) { mutableStateOf("/") }
    var isAzuracast by remember(profileId) { mutableStateOf(false) }
    var codec by remember(profileId) { mutableStateOf(Codec.MP3) }
    var bitrate by remember(profileId) { mutableStateOf(128) }
    var sampleRate by remember(profileId) { mutableStateOf(44_100) }
    var mono by remember(profileId) { mutableStateOf(true) }

    var showPassword by remember(profileId) { mutableStateOf(false) }
    var pendingDelete by remember(profileId) { mutableStateOf(false) }
    // The DB row as loaded — used by DELETE so deletion matches exactly what's persisted,
    // never a subset reconstructed from (possibly edited) form state.
    var loadedRow by remember(profileId) { mutableStateOf<ProfileEntity?>(null) }
    // New profile (isNew) needs no async DB load -> ready immediately. Existing rows start
    // not-loaded and flip after the LaunchedEffect read. (Previously `!isNew` — inverted,
    // which rendered a blank surface for new profiles.)
    var loaded by remember(profileId) { mutableStateOf(isNew) }

    // Load existing profile into the form once.
    LaunchedEffect(profileId) {
        if (!isNew) {
            vm.getProfile(profileId!!)?.let { p ->
                loadedRow = p
                name = p.name
                host = p.host
                port = p.port.toString()
                sid = p.sid.toString()
                password = p.password
                mount = p.mount
                mode = runCatching { BroadcastMode.valueOf(p.mode.trim().uppercase()) }
                    .getOrDefault(BroadcastMode.SOURCE)
                djUser = p.djUser.orEmpty()
                djMount = p.djMount?.takeIf { it.isNotBlank() } ?: "/"
                isAzuracast = p.djProtocol.trim().equals("ICECAST", ignoreCase = true)
                codec = runCatching { Codec.valueOf(p.codec.trim().uppercase()) }
                    .getOrDefault(Codec.MP3)
                bitrate = p.bitrateKbps
                sampleRate = p.sampleRateHz
                mono = p.mono
            }
            loaded = true
        }
    }

    val isDj = mode == BroadcastMode.DJ
    val nameErr = name.isBlank()
    val hostErr = host.isBlank()
    val portErr = port.toIntOrNull()?.let { it <= 0 || it > 65_535 } ?: true
    val sidErr = sid.toIntOrNull()?.let { it <= 0 } ?: true
    val passwordErr = password.isBlank()
    val mountErr = !isDj && mount.isBlank()        // mount is a DNAS concept; hidden in DJ mode
    val djUserErr = isDj && djUser.isBlank()       // sc_trans djlogin / Icecast Basic-auth user
    val formValid = !nameErr && !hostErr && !portErr && !sidErr && !passwordErr && !mountErr && !djUserErr &&
        bitrate in (if (codec == Codec.AAC) AudioConfigRules.AAC_BITRATES else AudioConfigRules.MP3_BITRATES) &&
        sampleRate in AudioConfigRules.SAMPLE_RATES &&
        (codec != Codec.AAC || !isDj || !isAzuracast)

    if (!loaded) {
        // nothing — blank surface while the row loads (instant in practice)
        return
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
            title = if (isNew) "New profile" else "Edit profile",
            subtitle = if (isNew) "SHOUTcast v2"
            else name.ifBlank { "Editing" },
            onBack = onBack,
        )

        FieldGroup("Server") {
            // MODE: SOURCE pushes to DNAS (Streamy is the source); DJ pushes to sc_trans djport2
            // (sc_trans re-encodes the feed). DJ v1 = MP3 (sc_trans AAC acceptance unverified).
            Text(
                "Mode",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(BroadcastMode.SOURCE to "Source", BroadcastMode.DJ to "DJ").forEach { (m, label) ->
                    Button(
                        onClick = {
                            mode = m
                            if (m == BroadcastMode.DJ && codec == Codec.AAC) codec = Codec.MP3
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (mode == m) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (mode == m) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (mode == m) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
            if (isDj) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAzuracast, onCheckedChange = { isAzuracast = it })
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("AzuraCast server", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Uses Icecast DJ protocol instead of SHOUTcast Ultravox. " +
                                "Leave unchecked for sc_trans / DNAS.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                LabeledTextField(
                    label = "DJ mount",
                    value = djMount,
                    onValueChange = { djMount = it },
                    placeholder = "/",
                    keyboardType = KeyboardType.Uri,
                )
            }
            LabeledTextField(
                label = "Name",
                value = name,
                onValueChange = { name = it },
                placeholder = "My station",
                error = if (nameErr) "Required" else null,
            )
            LabeledTextField(
                label = "Host",
                value = host,
                onValueChange = { host = it },
                placeholder = "host or IP",
                error = if (hostErr) "Required" else null,
                keyboardType = KeyboardType.Uri,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledTextField(
                    label = if (isDj) "DJ port" else "Port",
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    placeholder = if (isDj) "8505" else "8000",
                    error = if (portErr) "1–65535" else null,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                LabeledTextField(
                    label = "Stream id",
                    value = sid,
                    onValueChange = { sid = it.filter(Char::isDigit).take(5) },
                    placeholder = "1",
                    error = if (sidErr) "≥ 1" else null,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
            }
            if (isDj) {
                LabeledTextField(
                    label = "DJ user",
                    value = djUser,
                    onValueChange = { djUser = it },
                    placeholder = "djlogin",
                    error = if (djUserErr) "Required" else null,
                )
            }
            LabeledTextField(
                label = if (isDj) "DJ password" else "Password",
                value = password,
                onValueChange = { password = it },
                placeholder = if (isDj) "djpassword" else "source password",
                error = if (passwordErr) "Required" else null,
                keyboardType = KeyboardType.Password,
                visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailing = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(
                            if (showPassword) "HIDE" else "SHOW",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
            )
            if (!isDj) {
                LabeledTextField(
                    label = "Mount",
                    value = mount,
                    onValueChange = { mount = it },
                    placeholder = "/stream",
                    error = if (mountErr) "Required" else null,
                    keyboardType = KeyboardType.Uri,
                )
            }
        }

        FieldGroup("Audio") {
            BitrateChooser(
                codec = codec,
                bitrate = bitrate,
                sampleRate = sampleRate,
                mono = mono,
                enabled = true,
                onCodec = { c ->
                    codec = c
                    val ladder = if (c == Codec.AAC) AudioConfigRules.AAC_BITRATES
                    else AudioConfigRules.MP3_BITRATES
                    if (bitrate !in ladder) {
                        bitrate = ladder.minBy { kotlin.math.abs(it - bitrate) }
                    }
                    if (AudioConfigRules.needsHeAac(c, bitrate)) mono = true
                    sampleRate = AudioConfigRules.clamp(AudioConfig(c, bitrate, sampleRate, mono)).sampleRateHz
                },
                onBitrate = { kbps ->
                    bitrate = kbps
                    if (AudioConfigRules.needsHeAac(codec, kbps)) mono = true
                    sampleRate = AudioConfigRules.clamp(AudioConfig(codec, kbps, sampleRate, mono)).sampleRateHz
                },
                onSampleRate = { sampleRate = it },
                onMono = { b ->
                    if (!AudioConfigRules.needsHeAac(codec, bitrate) || b) mono = b
                },
            )
            if (codec == Codec.AAC && isDj && isAzuracast) {
                Text(
                    "AAC is not supported on Icecast mounts — MP3 will be used.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!isNew) {
                JokoOutlineButton(
                    text = "Delete",
                    onClick = { pendingDelete = true },
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
            JokoPrimaryButton(
                text = if (isNew) "Save" else "Update",
                onClick = {
                    if (!formValid) return@JokoPrimaryButton
                    val entity = ProfileEntity(
                        id = if (isNew) 0L else profileId!!,
                        name = name.trim(),
                        host = host.trim(),
                        port = port.toInt(),
                        sid = (sid.toIntOrNull() ?: 1).coerceAtLeast(1),
                        password = password,
                        mount = mount.trim().ifBlank { "/stream" },
                        codec = codec.name,
                        bitrateKbps = bitrate,
                        sampleRateHz = sampleRate,
                        mono = mono,
                        // adminUser/adminPassword left default (null): in-stream metadata
                        // now flows through Ultravox 0x902 — no admin creds needed.
                        mode = mode.name,
                        djUser = if (isDj) djUser.trim() else null,
                        djProtocol = if (isDj && isAzuracast) DjProtocol.ICECAST.name else DjProtocol.ULTRAVOX.name,
                        djMount = if (isDj) djMount.trim().ifBlank { "/" } else null,
                    )
                    vm.saveProfile(entity) { onBack() }
                },
                enabled = formValid,
                modifier = Modifier.weight(1f),
                verticalPadding = 14.dp,
            )
        }
    }

    if (pendingDelete && !isNew) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete this profile?") },
            text = {
                Text(
                    "Removing “${name.ifBlank { "this profile" }}” cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val row = loadedRow
                    if (row != null) {
                        vm.deleteProfile(row)   // the exact persisted row, not form-derived
                    }
                    pendingDelete = false
                    onBack()
                }) {
                    Text(
                        "Delete",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) {
                    Text("Cancel", style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

@Composable
internal fun FieldGroup(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = RoundedCornerShape(12.dp),
            )
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

@Composable
internal fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            },
            isError = error != null,
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            trailingIcon = trailing,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                errorIndicatorColor = MaterialTheme.colorScheme.error,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
