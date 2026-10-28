package com.streamy.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamy.app.R
import com.streamy.app.data.db.ProfileEntity
import com.streamy.app.service.StreamState

/*
 * JokoUI nav: bottom bar with lime soft-fill indicator on the selected tab; the snackbar
 * surfaces at the top so it stays visible above the keyboard / nav bar. A sticky on-air
 * banner (Scaffold topBar) follows the user onto every tab while a stream is not Idle, so
 * "am I on air?" never depends on being on Home (U-10).
 */

object Routes {
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val EDIT_PATTERN = "edit/{id}"
    const val EDIT = "edit"
    const val ID_ARG = "id"
    const val SETTINGS = "settings"

    fun edit(id: Long) = "$EDIT/$id"
}

private data class TopDest(val route: String, @StringRes val labelRes: Int, val icon: ImageVector)

private val TOP_DESTINATIONS = listOf(
    TopDest(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    TopDest(Routes.PROFILES, R.string.nav_profiles, Icons.Filled.Person),
    TopDest(Routes.SETTINGS, R.string.nav_settings, Icons.Filled.Settings),
)

private val TOP_ROUTES = TOP_DESTINATIONS.map { it.route }.toSet()

/**
 * Switch top-level tab the bottom-bar way: one copy of each tab, back always returns to
 * Home, each tab's own state saved/restored. Shared by the bar, Home's "Manage" and the
 * post-selection hop back to Home so none of them stacks duplicate destinations.
 */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Pop only from a RESUMED entry: a double-tap on Back/Save/Delete would otherwise pop twice
 * and drop the user off the tab they came from (or out of the graph entirely).
 */
private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) popBackStack()
}

// OptIn is a no-op where consumeWindowInsets is already stable; kept so older foundation builds compile.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StreamyNavigation(
    vm: StreamyViewModel,
    modifier: Modifier = Modifier,
) {
    val nav: NavHostController = rememberNavController()
    val navStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = navStackEntry?.destination?.route
    val showBar = currentRoute in TOP_ROUTES

    val state by vm.streamState.collectAsStateWithLifecycle()
    val streamProfile by vm.streamProfile.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()

    // One SnackbarHost at the nav level so vm.userMessage surfaces on every tab. Overlaid at the
    // TOP (status-bar-aware) — a bottom snackbar sat under the keyboard / nav bar and was invisible
    // after actions like song-title update, which fire while the user's eyes are on the field.
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val message by vm.userMessage.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        val msg = message ?: return@LaunchedEffect
        // Resolve here, against the current configuration — the VM only holds resource ids.
        val action = msg.action
        val result = snackbarHostState.showSnackbar(
            message = msg.text.resolve(context),
            actionLabel = action?.let { context.getString(it.labelRes) },
            duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        vm.consumeMessage()
        if (result == SnackbarResult.ActionPerformed && action == UserAction.OpenAppSettings) {
            // Permanently denied mic: the system dialog won't show again, so the only way
            // forward is the app's settings page. runCatching: some OEM builds strip it.
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    // Haptic confirmation on the edge INTO Live — the DJ is often looking at the desk, not the
    // phone. wasLive is saveable so a rotation while Live doesn't buzz again.
    val haptics = LocalHapticFeedback.current
    val isLive = state is StreamState.Live
    var wasLive by rememberSaveable { mutableStateOf(isLive) }
    LaunchedEffect(isLive) {
        if (isLive && !wasLive) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        wasLive = isLive
    }

    // Opt-in (Settings): keep the display awake only while on air. View.keepScreenOn is scoped
    // to this window and released automatically when the composition leaves.
    val view = LocalView.current
    val holdScreen = keepScreenOn && isLive
    DisposableEffect(view, holdScreen) {
        view.keepScreenOn = holdScreen
        onDispose { view.keepScreenOn = false }
    }

    Box(modifier) {
        Scaffold(
            topBar = {
                if (state != StreamState.Idle) {
                    OnAirBanner(
                        state = state,
                        profile = streamProfile,
                        // On Home the StateBadge already announces changes; doubling the live
                        // region there would make TalkBack read every transition twice.
                        announce = currentRoute != Routes.HOME,
                        onClick = { if (currentRoute != Routes.HOME) nav.navigateTopLevel(Routes.HOME) },
                    )
                }
            },
            bottomBar = { if (showBar) StreamyBottomBar(nav, currentRoute) },
        ) { innerPadding ->
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                // consumeWindowInsets: screens' imePadding() then only adds the part of the
                // keyboard not already covered by the bottom bar / nav-bar padding.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        vm = vm,
                        onManageProfiles = { nav.navigateTopLevel(Routes.PROFILES) },
                    )
                }
                composable(Routes.PROFILES) {
                    // Top-level tab: no Back (bottom bar + system back cover it).
                    ServerProfilesScreen(
                        vm = vm,
                        onProfileSelected = { nav.navigateTopLevel(Routes.HOME) },
                        onAddProfile = { nav.navigate(Routes.edit(0L)) },
                        onEditProfile = { id -> nav.navigate(Routes.edit(id)) },
                    )
                }
                composable(
                    route = Routes.EDIT_PATTERN,
                    arguments = listOf(
                        navArgument(Routes.ID_ARG) { type = NavType.LongType; defaultValue = 0L },
                    ),
                ) { backStackEntry ->
                    val idArg = backStackEntry.arguments?.getLong(Routes.ID_ARG) ?: 0L
                    EditProfileScreen(
                        vm = vm,
                        profileId = idArg.takeIf { it != 0L },
                        onBack = { nav.popFrom(backStackEntry) },
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(vm = vm)
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

/**
 * Sticky status strip above every tab while the stream is not Idle:
 *  - Live: solid red "ON AIR · <profile>" (broadcast tally colour);
 *  - Connecting / Reconnecting: amber;
 *  - Error: red outline on the page colour — needs attention but nothing is on air.
 * Tapping it jumps to Home, where the controls are.
 */
@Composable
private fun OnAirBanner(
    state: StreamState,
    profile: ProfileEntity?,
    announce: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tone = state.tone()
    val (container, content) = when (tone) {
        PillTone.Live -> scheme.error to scheme.onError
        PillTone.Busy -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        PillTone.Error -> scheme.background to scheme.error
        PillTone.Idle -> scheme.background to scheme.onBackground
    }
    val label = stateLabel(state)
    val text = if (profile != null && tone != PillTone.Error) {
        stringResource(R.string.banner_with_profile, label, profile.name)
    } else {
        label
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(container)
            // Background first, then the status-bar inset: the colour runs under the
            // transparent status bar (edge-to-edge) while the text stays below it.
            .statusBarsPadding()
            .then(
                if (tone == PillTone.Error) Modifier.border(1.dp, scheme.error) else Modifier,
            )
            .clickable(
                onClickLabel = stringResource(R.string.banner_open_home),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (tone == PillTone.Live) Color.White else content, CircleShape),
            )
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (announce) {
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun StreamyBottomBar(nav: NavHostController, currentRoute: String?) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        TOP_DESTINATIONS.forEach { dest ->
            val label = stringResource(dest.labelRes)
            NavigationBarItem(
                selected = currentRoute == dest.route,
                // Pop back to the start destination so back from a top-level tab doesn't
                // stack a deep back-stack; preserve tab state across switches.
                onClick = { nav.navigateTopLevel(dest.route) },
                // The visible label already names the tab; a matching contentDescription
                // would make TalkBack read it twice.
                icon = { Icon(dest.icon, contentDescription = null) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}
