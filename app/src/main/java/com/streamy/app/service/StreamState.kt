package com.streamy.app.service

/**
 * Coarse UI-facing stream state. Mirrors the meaningful slices of
 * [com.streamy.app.net.TransportState].
 *
 * [Live] carries [Live.droppedFrames] — the transport's drop-newest counter since the last
 * reconnect — so a DJ can SEE that audio is being dropped under backpressure instead of it
 * being invisible (the pre-fix behavior: Backpressure states were mapped to null).
 */
sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data class Live(val droppedFrames: Long = 0L) : StreamState
    data class Reconnecting(val attempt: Int) : StreamState
    data class Error(val reason: String) : StreamState
}

/**
 * Result of one Now-playing push. [ok] is the SOURCE protocol's real answer — the Weblet's
 * `Title Updated`, an accepted `/admin/metadata`, or a clean in-stream write — never assumed.
 * [id] is a monotonic sequence so the UI toasts once per push (StateFlows replay on rebind).
 */
data class TitleAck(val id: Long, val title: String, val ok: Boolean)
