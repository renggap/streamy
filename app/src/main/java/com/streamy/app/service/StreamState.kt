package com.streamy.app.service

/**
 * Coarse UI-facing stream state. Mirrors the meaningful slices of
 * [com.streamy.app.net.TransportState]; [TransportState.Backpressure] is
 * intentionally NOT represented here (drop-newest only; surfacing it would spam the UI).
 */
sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data object Live : StreamState
    data class Reconnecting(val attempt: Int) : StreamState
    data class Error(val reason: String) : StreamState
}
