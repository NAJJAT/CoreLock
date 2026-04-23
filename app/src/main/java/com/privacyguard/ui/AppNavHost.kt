package com.privacyguard.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.privacyguard.app.ui.dashboard.DashboardScreen
import com.privacyguard.app.ui.connections.ConnectionsScreen
import com.privacyguard.app.ui.statistics.StatisticsScreen
import com.privacyguard.app.ui.rules.RulesScreen
import com.privacyguard.app.ui.apps.AppsScreen
import com.privacyguard.app.ui.settings.SettingsScreen

@Composable
fun AppNavHost(
    onRequestVpn: () -> Unit = {},
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "dashboard",
    ) {
        composable("dashboard") {
            DashboardScreen(
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("connections") {
            ConnectionsScreen()
        }
        composable("statistics") {
            StatisticsScreen()
        }
        composable("rules") {
            RulesScreen()
        }
        composable("apps") {
            AppsScreen()
        }
        composable("settings") {
            SettingsScreen(
                onLanguageChanged = { /* recreate activity if needed */ },
            )
        }
    }
}
