package com.streamy.app

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.streamy.app.service.ServiceController
import com.streamy.app.service.StreamService
import com.streamy.app.service.StreamState
import com.streamy.app.ui.StreamyNavigation
import com.streamy.app.ui.StreamyTheme
import com.streamy.app.ui.StreamyViewModel
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
 * ponytail: swap the manual viewModelFactory shim for Hilt @HiltViewModel in Task 14.
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
    ) { results -> vm.onPermissionsResult(results) }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as? StreamService.LocalBinder)?.service ?: return
            // lifecycleScope auto-cancels on destroy, so the collector dies with the Activity.
            lifecycleScope.launch { s.state.collect { vm.onServiceStateChanged(it) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            vm.onServiceStateChanged(StreamState.Idle)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge so the themed Surface fills behind the status/nav bars; content is then
        // lifted into the safe area via safeDrawingPadding below (fixes cramped top controls).
        enableEdgeToEdge()

        // Bind early so the VM mirrors service state as soon as the binder is up. Service
        // binds to itself; if not already started this creates an idle instance (no broadcast).
        ServiceController.bind(this, serviceConnection)

        setContent {
            StreamyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val perms by vm.permissionsNeeded.collectAsStateWithLifecycle()
                    LaunchedEffect(perms) {
                        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
                    }

                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        StreamyNavigation(vm = vm)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(serviceConnection) }
    }
}
