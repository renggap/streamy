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
