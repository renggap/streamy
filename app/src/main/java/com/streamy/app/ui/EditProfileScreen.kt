package com.streamy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.Codec
import com.streamy.app.data.db.ProfileEntity

/*
 * Hallmark · screen: EditProfile · macrostructure: Workbench form · theme: custom
 * (late-night tally light) — same Surface + hairline outline + mono labels as Home.
 */

/**
 * Create or edit a server profile. New when [profileId] is null or 0; otherwise loads the
 * existing row and populates the form. Required: name, host, port, password.
 *
 * Codec/bitrate/sample/mono use the shared stateless [BitrateChooser]; HE-AAC + mono
 * forcing is applied on each callback so the form mirrors the VM-side rules.
 *
 * State held in local remember — standard for forms; the VM only hears the final save.
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
    var codec by remember(profileId) { mutableStateOf(Codec.MP3) }
    var bitrate by remember(profileId) { mutableStateOf(128) }
    var sampleRate by remember(profileId) { mutableStateOf(44_100) }
    var mono by remember(profileId) { mutableStateOf(true) }

    var showPassword by remember(profileId) { mutableStateOf(false) }
    var pendingDelete by remember(profileId) { mutableStateOf(false) }
    // New profile (isNew) needs no async DB load -> ready immediately. Existing rows start
    // not-loaded and flip after the LaunchedEffect read. (Previously `!isNew` — inverted,
    // which rendered a blank surface for new profiles.)
    var loaded by remember(profileId) { mutableStateOf(isNew) }

    // Load existing profile into the form once.
    LaunchedEffect(profileId) {
        if (!isNew) {
            vm.getProfile(profileId!!)?.let { p ->
                name = p.name
                host = p.host
                port = p.port.toString()
                sid = p.sid.toString()
                password = p.password
                mount = p.mount
                codec = runCatching { Codec.valueOf(p.codec.trim().uppercase()) }
                    .getOrDefault(Codec.MP3)
                bitrate = p.bitrateKbps
                sampleRate = p.sampleRateHz
                mono = p.mono
            }
            loaded = true
        }
    }

    val nameErr = name.isBlank()
    val hostErr = host.isBlank()
    val portErr = port.toIntOrNull()?.let { it <= 0 || it > 65_535 } ?: true
    val sidErr = sid.toIntOrNull()?.let { it <= 0 } ?: true
    val passwordErr = password.isBlank()
    val mountErr = mount.isBlank()
    val formValid = !nameErr && !hostErr && !portErr && !sidErr && !passwordErr && !mountErr &&
        bitrate in (if (codec == Codec.AAC) AudioConfigRules.AAC_BITRATES else AudioConfigRules.MP3_BITRATES) &&
        sampleRate in AudioConfigRules.SAMPLE_RATES

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
            title = if (isNew) "NEW PROFILE" else "EDIT PROFILE",
            subtitle = if (isNew) "SHOUTcast v2 / Icecast"
            else name.ifBlank { "Editing" },
            onBack = onBack,
        )

        FieldGroup("SERVER") {
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
                    label = "Port",
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    placeholder = "8000",
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
            LabeledTextField(
                label = "Password",
                value = password,
                onValueChange = { password = it },
                placeholder = "source password",
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
            LabeledTextField(
                label = "Mount",
                value = mount,
                onValueChange = { mount = it },
                placeholder = "/stream",
                error = if (mountErr) "Required" else null,
                keyboardType = KeyboardType.Uri,
            )
        }

        FieldGroup("AUDIO") {
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
                },
                onBitrate = { kbps ->
                    bitrate = kbps
                    if (AudioConfigRules.needsHeAac(codec, kbps)) mono = true
                },
                onSampleRate = { sampleRate = it },
                onMono = { b ->
                    if (!AudioConfigRules.needsHeAac(codec, bitrate) || b) mono = b
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!isNew) {
                OutlinedButton(
                    onClick = { pendingDelete = true },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("DELETE", style = MaterialTheme.typography.labelLarge)
                }
            }
            Button(
                onClick = {
                    if (!formValid) return@Button
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
                    )
                    vm.saveProfile(entity) { onBack() }
                },
                enabled = formValid,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    if (isNew) "SAVE" else "UPDATE",
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 2.sp,
                )
            }
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
                    val id = profileId ?: 0L
                    if (id != 0L) {
                        vm.deleteProfile(
                            ProfileEntity(
                                id = id, name = name, host = host, port = port.toIntOrNull() ?: 0,
                                sid = sid.toIntOrNull() ?: 1, password = password, mount = mount,
                                codec = codec.name, bitrateKbps = bitrate,
                                sampleRateHz = sampleRate, mono = mono,
                            ),
                        )
                    }
                    pendingDelete = false
                    onBack()
                }) { Text("DELETE", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) {
                    Text("CANCEL", style = MaterialTheme.typography.labelLarge)
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
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            isError = error != null,
            singleLine = true,
            shape = RoundedCornerShape(4.dp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            trailingIcon = trailing,
            textStyle = MaterialTheme.typography.bodyMedium,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.background,
                unfocusedContainerColor = MaterialTheme.colorScheme.background,
                disabledContainerColor = MaterialTheme.colorScheme.background,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                errorIndicatorColor = MaterialTheme.colorScheme.primary,
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
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
