package com.streamy.app

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.streamy.app.service.RecordService
import com.streamy.app.service.RecordState
import com.streamy.app.service.ServiceController
import com.streamy.app.service.StreamService
import com.streamy.app.service.StreamState
import com.streamy.app.ui.StreamyNavigation
import com.streamy.app.ui.StreamyTheme
import com.streamy.app.ui.StreamyViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Hosts the [StreamyNavigation] NavHost + the [StreamService] ServiceConnection.
 *
 * Wiring shape:
 * - [StreamyViewModel] is the source of truth for UI state; this Activity owns only the
 *   bits that need a real Activity: the ServiceConnection lifecycle and the runtime
 *   permission launch.
 * - Permission flow is VM-initiated. The VM exposes [StreamyViewModel.permissionsNeeded];
 *   this Activity collects it and launches RequestMultiplePermissions, then pushes the
 *   result map back via [StreamyViewModel.onPermissionsResult]. The VM decides whether
 *   to retry start.
 *
 * ponytail: type-safe nav (kotlinx-serialization routes) when destinations grow.
 *
 * ponytail: swap the manual viewModelFactory shim for Hilt @HiltViewModel.
 */
class MainActivity : ComponentActivity() {

    private val vm: StreamyViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                StreamyViewModel(application as Application) as T
        }
    }

    // registerForActivityResult must be called as a top-level property during Activity init;
    // we keep the launcher instance here and call .launch() from Compose when the VM
    // signals permissions are needed.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ ->
        // Only the mic gates a start (notifications are optional). "Permanently denied" =
        // still not granted AND the system won't show a rationale any more — which only the
        // Activity can ask, so it is computed here and handed to the VM.
        val micDenied = checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        vm.onPermissionsResult(
            micPermanentlyDenied = micDenied &&
                !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO),
        )
    }

    // One collection job per binding. A service can disconnect (process crash) and reconnect
    // within one Activity lifetime; without cancelling the previous job each reconnect would
    // stack another set of collectors pushing into the VM (and into the dead service object).
    private var streamCollectors: Job? = null
    private var recordCollectors: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? StreamService.LocalBinder)?.service ?: return
            streamCollectors?.cancel()
            // repeatOnLifecycle(STARTED): stop pushing 30 fps level snapshots into a UI nobody
            // sees; StateFlows replay their latest value when collection resumes on return.
            streamCollectors = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { s.state.collect { vm.onServiceStateChanged(it) } }
                    launch { s.levels.collect { vm.onLevels(it) } }
                    launch {
                        s.isRecording.combine(s.recordingPath) { on, path -> on to path }
                            .collect { (on, path) -> vm.onRecording(on, path) }
                    }
                    launch { s.recordingFailed.collect { vm.onRecordingFailed(it) } }
                    // Push live mic gain to the service. StateFlow's first emission hydrates the
                    // service's micGain on bind — before any start — so the first frame is never
                    // at unity by mistake. Gain can only change while the UI is visible.
                    launch { vm.micGain.collect { s.setMicGain(it) } }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamCollectors?.cancel()
            streamCollectors = null
            vm.onServiceStateChanged(StreamState.Idle)
        }
    }

    private val recordServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? RecordService.LocalBinder)?.service ?: return
            recordCollectors?.cancel()
            recordCollectors = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { s.state.collect { vm.onRecordStateChanged(it) } }
                    launch { vm.micGain.collect { s.setMicGain(it) } }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordCollectors?.cancel()
            recordCollectors = null
            vm.onRecordStateChanged(RecordState.Idle)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge so the themed Surface fills behind the status/nav bars; the Scaffold in
        // StreamyNavigation pads content by its system-bar insets. Bar icon style follows the
        // in-app theme, not the system's (the first call is the pre-Compose default: light page,
        // dark icons; setContent re-applies it whenever the theme changes).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM),
        )
        // Resize for the IME so WindowInsets.ime reaches Compose on pre-API-30 devices too
        // (imePadding on the profile form / Now-playing field depends on it).
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        // Bind early so the VM mirrors service state as soon as the binder is up. Service
        // binds to itself; if not already started this creates an idle instance (no broadcast).
        ServiceController.bind(this, serviceConnection)
        ServiceController.bindRecord(this, recordServiceConnection)

        setContent {
            val themeMode by vm.themeMode.collectAsStateWithLifecycle()
            val darkTheme = themeMode.isDark(isSystemInDarkTheme())
            // Bars must follow the theme the user picked, which may differ from the system's:
            // dark page → light icons (dark style), light page → dark icons.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) SystemBarStyle.dark(Color.TRANSPARENT)
                        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (darkTheme) SystemBarStyle.dark(DARK_NAV_SCRIM)
                        else SystemBarStyle.light(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM),
                )
                onDispose {}
            }
            StreamyTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val perms by vm.permissionsNeeded.collectAsStateWithLifecycle()
                    LaunchedEffect(perms) {
                        if (perms.isNotEmpty()) {
                            val request = perms.toTypedArray()
                            // Clear first: after a rotation this effect re-runs with whatever the
                            // VM still holds, which would stack a second system dialog.
                            vm.onPermissionRequestLaunched()
                            permissionLauncher.launch(request)
                        }
                    }

                    Box(Modifier.fillMaxSize()) {
                        StreamyNavigation(vm = vm)
                    }
                }
            }
        }
    }

    private companion object {
        // androidx.activity's own defaults for 3-button nav (light: 90% white; dark: 50% #1b1b1b).
        val LIGHT_NAV_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_NAV_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(serviceConnection) }
        runCatching { unbindService(recordServiceConnection) }
    }
}
