package com.streamy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/*
 * Hallmark · nav graph — minimal. Routes:
 *   home       — on-air console
 *   profiles   — server list
 *   edit/{id}  — edit (id == 0 means new)
 *   settings   — about + legal + clear-data
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

@Composable
fun StreamyNavigation(
    vm: StreamyViewModel,
    modifier: Modifier = Modifier,
) {
    val nav: NavHostController = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onManageProfiles = { nav.navigate(Routes.PROFILES) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
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
