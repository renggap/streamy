package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WireSafetyTest {
    @Test fun parseHttpStatus_extracts_numeric_code() {
        assertThat(WireSafety.parseHttpStatus("HTTP/1.0 200 OK")).isEqualTo(200)
        assertThat(WireSafety.parseHttpStatus("HTTP/1.1 401 Unauthorized")).isEqualTo(401)
        assertThat(WireSafety.parseHttpStatus("HTTP/1.0 404 Not found")).isEqualTo(404)
    }

    @Test fun parseHttpStatus_rejects_substring_and_nonstatus_lines() {
        // The old bug: contains("200") matched "HTTP/1.0 2000 Broken" and body lines.
        assertThat(WireSafety.parseHttpStatus("HTTP/1.0 2000 Broken")).isEqualTo(-1)
        assertThat(WireSafety.parseHttpStatus("garbage with 200 inside")).isEqualTo(-1)
        assertThat(WireSafety.parseHttpStatus("")).isEqualTo(-1)
        assertThat(WireSafety.parseHttpStatus("OK")).isEqualTo(-1)
        assertThat(WireSafety.parseHttpStatus("HTTP/1.0 OK")).isEqualTo(-1)
    }

    @Test fun headerValue_strips_crlf_and_controls() {
        val clean = WireSafety.headerValue("Bad\r\nInjected: header\u0000Name")
        assertThat(clean).isEqualTo("BadInjected: headerName")
        assertThat(clean).doesNotContain("\r")
        assertThat(clean).doesNotContain("\n")
        assertThat(clean).doesNotContain("\u0000")
    }

    @Test fun headerValue_truncates_to_cap() {
        val long = "x".repeat(10_000)
        assertThat(WireSafety.headerValue(long).length).isEqualTo(WireSafety.MAX_HEADER_VALUE)
    }

    @Test fun truncate_splits_at_cap_exactly() {
        assertThat(WireSafety.truncate("abcdef", 3)).isEqualTo("abc")
        assertThat(WireSafety.truncate("abc", 5)).isEqualTo("abc")
        assertThat(WireSafety.truncate("", 5)).isEmpty()
    }

    @Test fun truncate_never_splits_surrogate_pair() {
        // Emoji = 2 chars (high+low surrogate). Cap landing between the halves must back off one
        // char — a lone surrogate on the wire is invalid UTF-16 and corrupts the payload.
        val s = "abc\ud83d\ude00" + "y".repeat(10) // 3 ascii + emoji(pair at 3..4) + 10 ascii
        val out = WireSafety.truncate(s, 4)
        assertThat(out).isEqualTo("abc")              // pair doesn't fit — dropped whole
        assertThat(Character.isHighSurrogate(out.last())).isFalse()

        val out2 = WireSafety.truncate(s, 5)
        assertThat(out2).isEqualTo("abc\ud83d\ude00") // cap includes the full pair
    }

    @Test fun stripControlChars_keeps_utf8_content() {
        val s = "DJ \u00e9\u00e8 Titel\r\n"
        val out = WireSafety.stripControlChars(s)
        assertThat(out).isEqualTo("DJ \u00e9\u00e8 Titel")
        assertThat(out).doesNotContain("\r")
        assertThat(out).doesNotContain("\n")
    }
}
