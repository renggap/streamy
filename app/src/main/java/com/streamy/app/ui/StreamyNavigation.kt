package com.streamy.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/*
 * Hallmark · nav graph — top-level destinations share a bottom NavigationBar.
 *   home       — on-air console
 *   profiles   — server list
 *   edit/{id}  — edit detail (id == 0 means new); bottom bar hidden
 *   settings   — about + legal + audio input + clear-data
 *
 * The Scaffold pads the NavHost by innerPadding so every screen clears both the status
 * bar (top) and the NavigationBar (bottom); screens keep their own internal padding.
 *
 * ponytail: type-safe navigation (kotlinx-serialization routes) when destinations grow.
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

private data class TopDest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TOP_DESTINATIONS = listOf(
    TopDest(Routes.HOME, "Home", Icons.Filled.Home),
    TopDest(Routes.PROFILES, "Profiles", Icons.Filled.Person),
    TopDest(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
)

@Composable
fun StreamyNavigation(
    vm: StreamyViewModel,
    modifier: Modifier = Modifier,
) {
    val nav: NavHostController = rememberNavController()
    val navStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = navStackEntry?.destination?.route
    val showBar = currentRoute in TOP_DESTINATIONS.map { it.route }.toSet()

    // One SnackbarHost at the nav level so vm.userMessage surfaces on every tab. Overlaid at the
    // TOP (status-bar-aware) — a bottom snackbar sat under the keyboard / nav bar and was invisible
    // after actions like song-title update, which fire while the user's eyes are on the field.
    val snackbarHostState = remember { SnackbarHostState() }
    val message by vm.userMessage.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { snackbarHostState.showSnackbar(it); vm.consumeMessage() }
    }

    Box(modifier) {
        Scaffold(
            bottomBar = { if (showBar) StreamyBottomBar(nav, currentRoute) },
        ) { innerPadding ->
            NavHost(
                navController = nav,
                startDestination = Routes.HOME,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
            composable(Routes.HOME) {
                HomeScreen(
                    vm = vm,
                    onManageProfiles = { nav.navigate(Routes.PROFILES) },
                )
            }
            composable(Routes.PROFILES) {
                ServerProfilesScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
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
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    vm = vm,
                    onBack = { nav.popBackStack() },
                )
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

@Composable
private fun StreamyBottomBar(nav: NavHostController, currentRoute: String?) {
    NavigationBar {
        TOP_DESTINATIONS.forEach { dest ->
            NavigationBarItem(
                selected = currentRoute == dest.route,
                onClick = {
                    nav.navigate(dest.route) {
                        // Pop back to the start destination so back from a top-level tab doesn't
                        // stack a deep back-stack; preserve tab state across switches.
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) },
            )
        }
    }
}
