package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MetadataClientUrlTest {

    @Test fun build_url_encodes_title_and_includes_all_params() {
        val url = MetadataClient.buildUrl("host.example", 8000, 1, "Artist - Track Name")

        // All three required query params present, in order.
        assertThat(url).isEqualTo(
            "http://host.example:8000/admin.cgi?sid=1&mode=updinfo&song=Artist%20-%20Track%20Name"
        )
        // Spaces → %20 (canonical URL encoding); dashes are unreserved, stay literal.
        assertThat(url).contains("sid=1")
        assertThat(url).contains("mode=updinfo")
        assertThat(url).contains("song=")
    }
}
