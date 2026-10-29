package com.streamy.app.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the update.json contract (versionCode / versionName / apkUrl) that CI writes on every
 * tag release and [UpdateChecker] consumes. Network paths are env-gated/manual, not unit tested.
 */
class UpdateCheckerTest {

    private fun info(code: Int) = UpdateChecker.UpdateInfo(code, "1.2.3", "https://example.com/a.apk")

    @Test
    fun `parses well-formed feed`() {
        val json = """{"versionCode": 13, "versionName": "1.12", "apkUrl": "https://github.com/x/releases/download/v1.12/app-release.apk"}"""
        val info = UpdateChecker.parse(json)!!
        assertThat(info.versionCode).isEqualTo(13)
        assertThat(info.versionName).isEqualTo("1.12")
        assertThat(info.apkUrl).contains("app-release.apk")
    }

    @Test
    fun `rejects malformed feeds`() {
        assertThat(UpdateChecker.parse("not json")).isNull()
        assertThat(UpdateChecker.parse("{}")).isNull()
        assertThat(UpdateChecker.parse("""{"versionCode": 0, "versionName": "x", "apkUrl": "https://e.com/a"}""")).isNull()
        assertThat(UpdateChecker.parse("""{"versionCode": 13, "versionName": "", "apkUrl": "https://e.com/a"}""")).isNull()
        // Cleartext APK URL would trip the release cleartext policy — reject up front.
        assertThat(UpdateChecker.parse("""{"versionCode": 13, "versionName": "x", "apkUrl": "http://e.com/a"}""")).isNull()
    }

    @Test
    fun `only strictly newer versionCode counts as update`() {
        assertThat(UpdateChecker.isNewer(info(13), 12)).isTrue()
        // Same version re-shipped (hot re-release of the same build) must not re-prompt.
        assertThat(UpdateChecker.isNewer(info(12), 12)).isFalse()
        assertThat(UpdateChecker.isNewer(info(11), 12)).isFalse()
    }
}
