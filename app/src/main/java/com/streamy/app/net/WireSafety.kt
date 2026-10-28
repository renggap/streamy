package com.streamy.app.net

/**
 * Shared sanitizers for values that cross a wire protocol boundary as part of a text frame
 * (Ultravox messages, Icecast HTTP headers, ICY inline metadata). User-controlled strings
 * (profile name, mount, now-playing title) must not be able to inject CR/LF header lines or
 * blow a u16 length field.
 */
object WireSafety {
    /** Longest now-playing title / header value we ever put on the wire. */
    const val MAX_HEADER_VALUE = 512

    /** Longest ICY inline metadata title (fits easily inside the 4080-byte block limit). */
    const val MAX_TITLE = 1024

    /**
     * Strip ASCII control characters (CR, LF, NUL, etc.) — header/line injection guard — and
     * truncate to [max] chars (surrogate-safe). Non-ASCII text is kept: callers encode the
     * result as UTF-8 on the wire, so this is an injection/length guard, not an ASCII filter.
     */
    fun headerValue(value: String, max: Int = MAX_HEADER_VALUE): String =
        truncate(stripControlChars(value), max)

    /** Strip ASCII control chars (0x00–0x1F and 0x7F) but keep UTF-8 content bytes intact. */
    fun stripControlChars(value: String): String =
        value.filter { it.code >= 0x20 && it.code != 0x7F }

    /** Truncate to [max] chars without splitting a surrogate pair (would emit a lone surrogate). */
    fun truncate(value: String, max: Int): String {
        if (value.length <= max) return value
        var end = max
        if (Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
        return value.substring(0, end)
    }

    /**
     * Parse the numeric HTTP status code out of a status line like `HTTP/1.0 200 OK`.
     * Returns the code, or -1 when the line isn't a valid status line. Never matches on a
     * substring — "HTTP/1.0 2000 Broken" or a body line containing "200" must NOT pass.
     */
    fun parseHttpStatus(statusLine: String): Int {
        val parts = statusLine.trim().split(Regex("\\s+"))
        if (parts.size < 2) return -1
        val code = parts[1].toIntOrNull() ?: return -1
        return if (code in 100..999) code else -1
    }
}
