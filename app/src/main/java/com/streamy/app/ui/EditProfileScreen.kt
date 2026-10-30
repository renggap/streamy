package com.streamy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.streamy.app.R
import com.streamy.app.data.AudioConfig
import com.streamy.app.data.AudioConfigRules
import com.streamy.app.data.BroadcastMode
import com.streamy.app.data.Codec
import com.streamy.app.data.DjProtocol
import com.streamy.app.data.db.ProfileEntity

/*
 * JokoUI daylight form: slate page, white rounded-xl FieldGroups, lime primary save.
 * State held locally (rememberSaveable — survives rotation / dark-mode / locale changes);
 * the VM only hears the final save.
 */

// Field keys for the touched-set (errors show only after a field was edited or Save tried).
private const val F_NAME = "name"
private const val F_HOST = "host"
private const val F_PORT = "port"
private const val F_SID = "sid"
private const val F_PASSWORD = "password"
private const val F_MOUNT = "mount"
private const val F_DJ_USER = "djUser"

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
    // doesn't show A's stale values. rememberSaveable (not remember) so edits survive a
    // configuration change — the DB load below only SEEDS them once (see `seeded`).
    var name by rememberSaveable(profileId) { mutableStateOf("") }
    var host by rememberSaveable(profileId) { mutableStateOf("") }
    var port by rememberSaveable(profileId) { mutableStateOf("8000") }
    var sid by rememberSaveable(profileId) { mutableStateOf("1") }
    var password by rememberSaveable(profileId) { mutableStateOf("") }
    var mount by rememberSaveable(profileId) { mutableStateOf("/stream") }
    var mode by rememberSaveable(profileId) { mutableStateOf(BroadcastMode.SOURCE) }
    var djUser by rememberSaveable(profileId) { mutableStateOf("") }
    var djMount by rememberSaveable(profileId) { mutableStateOf("/") }
    var isAzuracast by rememberSaveable(profileId) { mutableStateOf(false) }
    var codec by rememberSaveable(profileId) { mutableStateOf(Codec.MP3) }
    var bitrate by rememberSaveable(profileId) { mutableStateOf(128) }
    var sampleRate by rememberSaveable(profileId) { mutableStateOf(44_100) }
    var mono by rememberSaveable(profileId) { mutableStateOf(true) }

    // Validation display state: which fields the user has edited, and whether Save was tried.
    var touched by rememberSaveable(profileId) { mutableStateOf(setOf<String>()) }
    var saveAttempted by rememberSaveable(profileId) { mutableStateOf(false) }

    var showPassword by rememberSaveable(profileId) { mutableStateOf(false) }
    var pendingDelete by rememberSaveable(profileId) { mutableStateOf(false) }
    // U-3: a save is in flight — Save is disabled and onBack fires once (from the VM callback).
    var isSaving by remember(profileId) { mutableStateOf(false) }
    // The DB row as loaded — used by DELETE so deletion matches exactly what's persisted,
    // never a subset reconstructed from (possibly edited) form state. Not saveable (not
    // Parcelable); re-read after a configuration change without touching the form.
    var loadedRow by remember(profileId) { mutableStateOf<ProfileEntity?>(null) }
    // Form fields were seeded from the DB row. Saveable, so a rotation after editing does
    // NOT re-seed and wipe the edits. New profiles need no load -> seeded immediately.
    var seeded by rememberSaveable(profileId) { mutableStateOf(isNew) }

    LaunchedEffect(profileId) {
        if (!isNew) {
            val p = vm.getProfile(profileId!!)
            loadedRow = p
            if (!seeded && p != null) {
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
            seeded = true
        }
    }

    val isDj = mode == BroadcastMode.DJ

    /** Switch codec and re-fit bitrate / channels / sample rate to its rules. */
    fun applyCodec(c: Codec) {
        codec = c
        val ladder = if (c == Codec.AAC) AudioConfigRules.AAC_BITRATES else AudioConfigRules.MP3_BITRATES
        if (bitrate !in ladder) bitrate = ladder.minBy { kotlin.math.abs(it - bitrate) }
        if (AudioConfigRules.needsHeAac(c, bitrate)) mono = true
        sampleRate = AudioConfigRules.clamp(AudioConfig(c, bitrate, sampleRate, mono)).sampleRateHz
    }

    // Icecast mounts (AzuraCast) and sc_trans DJ feeds are MP3-only: coerce instead of
    // silently disabling Save while a hint claims MP3 "will be used".
    fun coerceCodecForMode() {
        if (codec == Codec.AAC && mode == BroadcastMode.DJ) applyCodec(Codec.MP3)
    }

    fun normalizeHostField() {
        val n = normalizeHost(host)
        host = n.host
        if (n.port != null) {
            port = n.port
            touched = touched + F_PORT
        }
    }

    val nameErr = name.isBlank()
    val hostErr = host.isBlank()
    val portErr = port.toIntOrNull()?.let { it <= 0 || it > 65_535 } ?: true
    val sidErr = sid.toIntOrNull()?.let { it <= 0 } ?: true
    val passwordErr = password.isBlank()
    val mountErr = !isDj && mount.isBlank()        // mount is a DNAS concept; hidden in DJ mode
    val djUserErr = isDj && djUser.isBlank()       // sc_trans djlogin / Icecast Basic-auth user
    fun shows(key: String, err: Boolean) = err && (saveAttempted || key in touched)
    fun touch(key: String) { if (key !in touched) touched = touched + key }

    if (!seeded) {
        // nothing — blank surface while the row loads (instant in practice)
        return
    }

    val required = stringResource(R.string.field_required)

    Column(
        modifier = modifier
            .fillMaxWidth()
            // U-12: shrink the scroll viewport by the keyboard so the focused field and the
            // Save row can scroll into view instead of sitting under the IME.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(
            title = stringResource(if (isNew) R.string.edit_title_new else R.string.edit_title_edit),
            subtitle = if (isNew) stringResource(R.string.edit_subtitle_new)
            else name.ifBlank { stringResource(R.string.edit_subtitle_editing) },
            onBack = onBack,
        )

        FieldGroup(stringResource(R.string.edit_group_server)) {
            // MODE: SOURCE pushes to DNAS (Streamy is the source); DJ pushes to sc_trans djport2
            // (sc_trans re-encodes the feed). DJ v1 = MP3 (sc_trans AAC acceptance unverified).
            SectionLabel(stringResource(R.string.edit_mode))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.selectableGroup(),
            ) {
                listOf(
                    BroadcastMode.SOURCE to stringResource(R.string.mode_source),
                    BroadcastMode.DJ to stringResource(R.string.mode_dj),
                ).forEach { (m, label) ->
                    TogglePill(
                        label = label,
                        active = mode == m,
                        enabled = true,
                        onClick = {
                            mode = m
                            coerceCodecForMode()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (isDj) {
                // Whole row toggles (label included) — a 20dp checkbox alone is a poor target
                // and TalkBack would read the box without its label.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = isAzuracast,
                            role = Role.Checkbox,
                            onValueChange = {
                                isAzuracast = it
                                coerceCodecForMode()
                            },
                        ),
                ) {
                    Checkbox(checked = isAzuracast, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.edit_azuracast), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.edit_azuracast_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                LabeledTextField(
                    label = stringResource(R.string.edit_dj_mount),
                    value = djMount,
                    onValueChange = { djMount = it },
                    placeholder = "/",
                    keyboardType = KeyboardType.Uri,
                    onFocusLost = { djMount = normalizeMount(djMount).ifEmpty { "/" } },
                )
            }
            LabeledTextField(
                label = stringResource(R.string.edit_name),
                value = name,
                onValueChange = { name = it; touch(F_NAME) },
                placeholder = stringResource(R.string.edit_name_placeholder),
                error = if (shows(F_NAME, nameErr)) required else null,
            )
            LabeledTextField(
                label = stringResource(R.string.edit_host),
                value = host,
                onValueChange = { host = it; touch(F_HOST) },
                placeholder = stringResource(R.string.edit_host_placeholder),
                error = if (shows(F_HOST, hostErr)) required else null,
                keyboardType = KeyboardType.Uri,
                onFocusLost = ::normalizeHostField,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledTextField(
                    label = stringResource(if (isDj) R.string.edit_dj_port else R.string.edit_port),
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5); touch(F_PORT) },
                    placeholder = if (isDj) "8505" else "8000",
                    error = if (shows(F_PORT, portErr)) stringResource(R.string.edit_port_error) else null,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
                LabeledTextField(
                    label = stringResource(R.string.edit_sid),
                    value = sid,
                    onValueChange = { sid = it.filter(Char::isDigit).take(5); touch(F_SID) },
                    placeholder = "1",
                    error = if (shows(F_SID, sidErr)) stringResource(R.string.edit_sid_error) else null,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(1f),
                )
            }
            if (isDj) {
                LabeledTextField(
                    label = stringResource(R.string.edit_dj_user),
                    value = djUser,
                    onValueChange = { djUser = it; touch(F_DJ_USER) },
                    placeholder = "djlogin",
                    error = if (shows(F_DJ_USER, djUserErr)) required else null,
                )
            }
            LabeledTextField(
                label = stringResource(if (isDj) R.string.edit_dj_password else R.string.edit_password),
                value = password,
                onValueChange = { password = it; touch(F_PASSWORD) },
                placeholder = stringResource(
                    if (isDj) R.string.edit_dj_password_placeholder else R.string.edit_password_placeholder,
                ),
                error = if (shows(F_PASSWORD, passwordErr)) required else null,
                keyboardType = KeyboardType.Password,
                visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailing = {
                    // An icon, not a text button: the long label ("Tampilkan kata sandi")
                    // squeezed the input to nothing. The label lives on as the a11y name.
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            painterResource(if (showPassword) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                            contentDescription = stringResource(
                                if (showPassword) R.string.edit_password_hide else R.string.edit_password_show,
                            ),
                        )
                    }
                },
            )
            if (!isDj) {
                LabeledTextField(
                    label = stringResource(R.string.edit_mount),
                    value = mount,
                    onValueChange = { mount = it; touch(F_MOUNT) },
                    placeholder = "/stream",
                    error = if (shows(F_MOUNT, mountErr)) required else null,
                    keyboardType = KeyboardType.Uri,
                    onFocusLost = { mount = normalizeMount(mount) },
                )
            }
        }

        FieldGroup(stringResource(R.string.edit_group_audio)) {
            BitrateChooser(
                codec = codec,
                bitrate = bitrate,
                sampleRate = sampleRate,
                mono = mono,
                enabled = true,
                onCodec = ::applyCodec,
                onBitrate = { kbps ->
                    bitrate = kbps
                    if (AudioConfigRules.needsHeAac(codec, kbps)) mono = true
                    sampleRate = AudioConfigRules.clamp(AudioConfig(codec, kbps, sampleRate, mono)).sampleRateHz
                },
                onSampleRate = { sampleRate = it },
                onMono = { b ->
                    if (!AudioConfigRules.needsHeAac(codec, bitrate) || b) mono = b
                },
                allowAac = false,   // AAC UI-disabled (AGENTS.md); legacy AAC rows can switch to MP3
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!isNew) {
                JokoOutlineButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        // On-air profile: explain instead of offering a delete that would be refused.
                        if (!vm.isLockedByStream(profileId!!)) pendingDelete = true
                    },
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
            JokoPrimaryButton(
                text = stringResource(if (isNew) R.string.action_save else R.string.action_save_changes),
                onClick = onClick@{
                    // Read-and-set in the click itself: a second tap in the same frame sees
                    // isSaving before recomposition disables the button (no duplicate insert,
                    // no double pop).
                    if (isSaving) return@onClick
                    saveAttempted = true
                    normalizeHostField()
                    mount = normalizeMount(mount)
                    djMount = normalizeMount(djMount).ifEmpty { "/" }
                    coerceCodecForMode()
                    val valid = name.isNotBlank() && host.isNotBlank() &&
                        port.toIntOrNull()?.let { it in 1..65_535 } == true &&
                        sid.toIntOrNull()?.let { it >= 1 } == true &&
                        password.isNotBlank() &&
                        (isDj || mount.isNotBlank()) &&
                        (!isDj || djUser.isNotBlank()) &&
                        bitrate in (if (codec == Codec.AAC) AudioConfigRules.AAC_BITRATES else AudioConfigRules.MP3_BITRATES) &&
                        sampleRate in AudioConfigRules.SAMPLE_RATES
                    if (!valid) return@onClick
                    isSaving = true
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
                enabled = !isSaving,
                modifier = Modifier.weight(1f),
                verticalPadding = 14.dp,
            )
        }
    }

    if (pendingDelete && !isNew) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text(stringResource(R.string.edit_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.edit_delete_body,
                        name.ifBlank { stringResource(R.string.edit_delete_this_profile) },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!pendingDelete) return@TextButton   // double-tap: pop only once
                    pendingDelete = false
                    val row = loadedRow
                    // The exact persisted row, not form-derived. Leave only if it was deleted.
                    if (row == null || vm.deleteProfile(row)) onBack()
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge)
                }
            },
        )
    }
}

/** Host + optional port split out of what the user typed into the Host field. */
internal data class HostPort(val host: String, val port: String?)

/**
 * Users paste URLs ("http://radio.example.com:8000/") into Host. The source protocols want
 * a bare host, so strip the scheme, any path / trailing slash and user-info, and move a
 * `host:port` suffix into the Port field. IPv6 literals keep their colons (only the
 * bracketed `[addr]:port` form is split). Pure — unit-testable.
 */
internal fun normalizeHost(raw: String): HostPort {
    var h = raw.trim()
    h = h.replace(SCHEME, "")
    h = h.substringBefore('/').substringBefore('?').substringBefore('#')
    h = h.substringAfterLast('@')
    var port: String? = null
    if (h.startsWith("[")) {
        val close = h.indexOf(']')
        if (close > 0) {
            val rest = h.substring(close + 1)
            if (rest.startsWith(":")) port = rest.drop(1).takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            h = h.substring(1, close)
        }
    } else if (h.count { it == ':' } == 1) {
        val p = h.substringAfter(':')
        if (p.isEmpty() || p.all(Char::isDigit)) {
            port = p.takeIf { it.isNotEmpty() }?.take(5)
            h = h.substringBefore(':')
        }
    }
    return HostPort(h.trim(), port)
}

/** Mounts are absolute paths on the server: ensure a single leading "/". Blank stays blank. */
internal fun normalizeMount(raw: String): String {
    val m = raw.trim()
    if (m.isEmpty()) return m
    return "/" + m.trimStart('/')
}

private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

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

/**
 * Outlined field using M3's own label / supportingText / isError slots, so TalkBack reads
 * "Host, edit box, Required" as one node (a separate Text above/below was never associated
 * with the field). [onFocusLost] runs normalisation when the user leaves the field.
 */
@Composable
internal fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
    onFocusLost: (() -> Unit)? = null,
) {
    // onFocusChanged also fires once on attach (unfocused) — only a real focused→unfocused
    // transition should normalise.
    var hadFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = {
            Text(
                placeholder,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        isError = error != null,
        supportingText = error?.let { e -> { Text(e) } },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailing,
        textStyle = MaterialTheme.typography.bodyMedium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            errorBorderColor = MaterialTheme.colorScheme.error,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedPlaceholderColor = MaterialTheme.colorScheme.outlineVariant,
            unfocusedPlaceholderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (state.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    onFocusLost?.invoke()
                }
            },
    )
}
