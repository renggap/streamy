package com.streamy.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.streamy.app.data.db.AppDatabase
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.service.ServiceController
import com.streamy.app.service.StreamService
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Full-pipeline instrumented integration test: real mic -> Mp3Encoder (native lib) ->
 * StreamService (foreground) -> UvoxClient (Ultravox 2.1, audio/mpeg) -> the real DNAS2 server.
 *
 * Requires BuildConfig.TEST_HOST/PORT/PASSWORD (injected from local.properties). If absent,
 * the test is skipped (assumption failure -> skipped, not failed). Run on a connected device:
 *
 *   ./gradlew :app:connectedDebugAndroidTest --tests "com.streamy.app.IntegrationTest"
 *
 * The device must be able to reach the server host over http (it's a public host here).
 */
@RunWith(AndroidJUnit4::class)
class IntegrationTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val perms: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    private var profileId: Long = -1L

    @Before
    fun seedProfile() {
        assumeTrue(
            "BuildConfig.TEST_HOST/PORT not set — inject from local.properties to run",
            BuildConfig.TEST_HOST.isNotBlank() && BuildConfig.TEST_PORT != 0,
        )
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "streamy.db")
            .allowMainThreadQueries().build()
        profileId = runBlocking {
            db.profileDao().insert(
                ProfileEntity(
                    name = "Integration Test",
                    host = BuildConfig.TEST_HOST,
                    port = BuildConfig.TEST_PORT,
                    sid = BuildConfig.TEST_SID,
                    password = BuildConfig.TEST_PASSWORD,
                    mount = "/stream",
                    codec = "MP3",
                    bitrateKbps = 128,
                    sampleRateHz = 44_100,
                    mono = true,
                )
            )
        }
        db.close()
    }

    @After
    fun cleanupProfile() {
        if (profileId < 0) return
        runCatching {
            val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "streamy.db")
                .allowMainThreadQueries().build()
            runBlocking {
                db.profileDao().byId(profileId)?.let { db.profileDao().delete(it) }
            }
            db.close()
        }
        // also drop the file-backed db so the app starts clean next time
        runCatching { ctx.deleteDatabase("streamy.db") }
    }

    @Test
    fun fullPipeline_goesLiveOnServer() {
        ServiceController.start(ctx, profileId)

        val service = awaitBoundService(timeoutMs = 15_000)
        awaitState(service, timeoutMs = 20_000)

        // let audio flow for a few seconds
        Thread.sleep(6_000)

        val status = statsField("STREAMSTATUS")
        assertTrue("server STREAMSTATUS should be 1 (live), got '$status' — full pipeline failed", status == "1")

        ServiceController.stop(ctx)
    }

    // --- helpers ---

    private fun awaitBoundService(timeoutMs: Long): StreamService {
        val deadline = System.currentTimeMillis() + timeoutMs
        var bound: StreamService? = null
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
                bound = (binder as StreamService.LocalBinder).service
            }
            override fun onServiceDisconnected(name: android.content.ComponentName?) {}
        }
        val intent = android.content.Intent(ctx, StreamService::class.java)
        ctx.bindService(intent, conn, android.content.Context.BIND_AUTO_CREATE)
        while (bound == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("service did not bind within ${timeoutMs}ms", bound != null)
        return bound!!
    }

    private fun awaitState(service: StreamService, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = service.state.value
            // Live is a data class (droppedFrames payload): match on type, not equality.
            if (s is com.streamy.app.service.StreamState.Live) return
            if (s is com.streamy.app.service.StreamState.Error) {
                throw AssertionError("service entered Error state: ${s.reason} before reaching Live")
            }
            Thread.sleep(300)
        }
        throw AssertionError("service never reached Live within ${timeoutMs}ms (last=${service.state.value})")
    }

    private fun statsField(tag: String): String? = try {
        val xml = (URL("http://${BuildConfig.TEST_HOST}:${BuildConfig.TEST_PORT}/stats?sid=${BuildConfig.TEST_SID}")
            .openConnection() as HttpURLConnection)
            .apply { connectTimeout = 5_000; readTimeout = 5_000 }
            .inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
        val lo = xml.indexOf("<$tag>") + tag.length + 2
        val hi = xml.indexOf("</$tag>")
        if (lo > tag.length + 1 && hi > lo) xml.substring(lo, hi) else null
    } catch (e: Throwable) { null }
}
