package com.mmcdole.tether.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.mmcdole.tether.ui.rules.RulesScreen
import com.mmcdole.tether.ui.session.SessionScreen
import com.mmcdole.tether.ui.settings.SettingsScreen
import com.mmcdole.tether.ui.theme.TetherTheme
import com.mmcdole.tether.ui.worlds.WorldEditScreen
import com.mmcdole.tether.ui.worlds.WorldsScreen
import kotlinx.coroutines.flow.Flow

/** [openSession] emits when something outside the app, like the notification, asks for the session. */
@Composable
fun TetherApp(openSession: Flow<Unit>) {
    TetherTheme {
        val navController = rememberNavController()
        LaunchedEffect(openSession) { openSession.collect { navController.openSession() } }
        NavHost(navController = navController, startDestination = WorldsRoute) {
            composable<WorldsRoute> {
                WorldsScreen(
                    onOpenSession = { navController.openSession() },
                    onWorldLongClick = { navController.navigate(WorldEditRoute(it)) },
                    onAddWorld = { navController.navigate(WorldEditRoute()) },
                    onSettings = { navController.navigate(SettingsRoute) },
                    onGlobalRules = { navController.navigate(RulesRoute()) },
                )
            }
            composable<WorldEditRoute> { entry ->
                val id = entry.toRoute<WorldEditRoute>().id
                // Popping to Worlds is idempotent, so a back press racing a save can't pop twice.
                WorldEditScreen(
                    onDone = { navController.popBackStack(WorldsRoute, inclusive = false) },
                    onRules = id?.let { { navController.navigate(RulesRoute(it)) } },
                )
            }
            composable<RulesRoute> {
                RulesScreen(onBack = { navController.popBackStack<RulesRoute>(inclusive = true) })
            }
            composable<SessionRoute> {
                SessionScreen(
                    onWorlds = { navController.popBackStack(WorldsRoute, inclusive = false) },
                    onSettings = { navController.navigate(SettingsRoute) },
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = { navController.popBackStack<SettingsRoute>(inclusive = true) })
            }
        }
    }
}

private fun NavController.openSession() = navigate(SessionRoute) {
    popUpTo(WorldsRoute)
    launchSingleTop = true
}
