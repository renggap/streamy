package com.streamy.app.ui

import androidx.annotation.StringRes
import com.streamy.app.R
import com.streamy.app.service.StreamState

/**
 * Plain-language, localizable rendering of a terminal error reason.
 *
 * [messageRes] (formatted with [args]) is what the user reads; [detail] is the raw server /
 * exception text, shown small underneath only when we could not classify the reason — so
 * a DJ can still quote it to the station admin without the UI shouting stack-trace copy.
 */
data class ErrorText(
    @StringRes val messageRes: Int,
    val args: List<Any> = emptyList(),
    val detail: String? = null,
)

/**
 * Map a [StreamState.Error.reason] to [ErrorText]. Pure (no Android APIs) — reasons are
 * free-form strings from the service:
 *  - service tokens: `mic_busy`, `profile_missing`, `start_failed`, `audio_failed`;
 *  - transport give-up: `gave_up after N attempts` (StreamTransport);
 *  - ProtocolException texts: Ultravox `cipher|auth|standby|<step> NAK: <server text>`
 *    (UvoxClient) and `icecast source rejected: <HTTP status line>` (IcecastSourceClient);
 *  - raw java.net exception messages (resolve / refused / timeout / unreachable).
 * Order matters: "in use" is checked before the generic auth NAK because DNAS reports a
 * busy stream id through the auth step (`auth NAK: NAK:Stream In Use`).
 */
fun streamErrorText(reason: String): ErrorText {
    val r = reason.trim()
    val lower = r.lowercase()
    GAVE_UP.find(lower)?.let { m ->
        return ErrorText(R.string.err_gave_up, listOf(m.groupValues[1].toIntOrNull() ?: 0))
    }
    return when {
        r == "mic_busy" -> ErrorText(R.string.err_mic_busy)
        r == "profile_missing" -> ErrorText(R.string.err_profile_missing)
        r == "start_failed" || r.isEmpty() -> ErrorText(R.string.err_start_failed)
        r == "audio_failed" -> ErrorText(R.string.err_audio_failed)
        "in use" in lower -> ErrorText(R.string.err_stream_in_use)
        lower.startsWith("icecast source rejected") -> icecastRejection(r, lower)
        lower.startsWith("auth nak") -> ErrorText(R.string.err_auth)
        lower.startsWith("mime nak") -> ErrorText(R.string.err_format_rejected, detail = r)
        " nak" in lower -> ErrorText(R.string.err_server_rejected, detail = r)
        "unable to resolve host" in lower || "no address associated" in lower ||
            "unknownhost" in lower -> ErrorText(R.string.err_unknown_host)
        "econnrefused" in lower || "connection refused" in lower -> ErrorText(R.string.err_conn_refused)
        "etimedout" in lower || "timed out" in lower || "timeout" in lower -> ErrorText(R.string.err_timeout)
        "enetunreach" in lower || "network is unreachable" in lower ||
            "ehostunreach" in lower -> ErrorText(R.string.err_no_network)
        else -> ErrorText(R.string.err_generic, detail = r)
    }
}

/** Standalone-recorder reasons (RecordService) — its own tokens first, then the shared map. */
fun recordErrorText(reason: String): ErrorText = when (reason.trim()) {
    "record_open_failed" -> ErrorText(R.string.err_record_open_failed)
    "record_failed" -> ErrorText(R.string.err_record_failed)
    "record_write_failed" -> ErrorText(R.string.err_record_write_failed)
    "audio_failed" -> ErrorText(R.string.err_audio_failed)
    "profile_missing" -> ErrorText(R.string.err_profile_missing)
    "mic_busy" -> ErrorText(R.string.err_mic_busy)
    else -> ErrorText(R.string.err_record_failed, detail = reason.trim().ifEmpty { null })
}

private fun icecastRejection(raw: String, lower: String): ErrorText {
    val code = HTTP_CODE.find(lower)?.groupValues?.get(1)?.toIntOrNull()
    return when (code) {
        401 -> ErrorText(R.string.err_auth)
        403 -> ErrorText(R.string.err_forbidden, detail = raw)
        404 -> ErrorText(R.string.err_mount_not_found)
        else -> ErrorText(R.string.err_server_rejected, detail = raw)
    }
}

private val GAVE_UP = Regex("""gave_up after (\d+) attempts?""")
private val HTTP_CODE = Regex("""http/\d(?:\.\d)?\s+(\d{3})""")

/** Connecting / Live / Reconnecting — a stream owns the mic and a profile snapshot. */
val StreamState.isActive: Boolean
    get() = this is StreamState.Connecting || this is StreamState.Live || this is StreamState.Reconnecting
