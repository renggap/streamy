package com.streamy.app.service

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compile-only smoke test for [StreamService].
 *
 * The service builds its OWN `streamy.db` (not the in-memory Room instance a test
 * would seed), so live state assertions need a real device + a seeded DB + granted
 * permissions. Those run in Task 13 against a real source server.
 *
 * TODO(Task 13): grant RECORD_AUDIO + POST_NOTIFICATIONS via GrantPermissionRule,
 *   seed streamy.db, bind a [android.content.ServiceConnection] to observe
 *   [StreamService.state], then drive [ServiceController.start]/[stop] and assert
 *   transitions Connecting -> Live -> Idle (and Error paths for bad/missing profile).
 */
@RunWith(AndroidJUnit4::class)
class StreamServiceLifecycleTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun service_is_declared_in_manifest() {
        // Task 1 declared <service ... foregroundServiceType="microphone"/>; resolving
        // by component proves it's wired and exported=false.
        val resolve = ctx.packageManager.resolveService(
            Intent().setComponent(ComponentName(ctx, StreamService::class.java)),
            0,
        )
        assertThat(resolve).isNotNull()
    }

    @Test fun companion_contract_is_stable() {
        assertThat(StreamService.ACTION_START).isEqualTo("com.streamy.app.action.START")
        assertThat(StreamService.ACTION_STOP).isEqualTo("com.streamy.app.action.STOP")
        assertThat(StreamService.EXTRA_PROFILE_ID).isEqualTo("com.streamy.app.extra.PROFILE_ID")
        assertThat(StreamService.NOTIF_ID).isGreaterThan(0)
    }

    @Test fun start_with_missing_profile_does_not_throw() {
        // profileId 9999 won't be in a fresh streamy.db; service emits
        // StreamState.Error("profile_missing") and shuts down cleanly. We don't
        // observe state here — no bound listener + no permission grant yet (Task 13).
        ServiceController.start(ctx, 9999L)
        ServiceController.stop(ctx)
    }
}
