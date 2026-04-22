package com.privacyguard.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.privacyguard.app.ui.apps.AppsScreen
import com.privacyguard.app.ui.connections.ConnectionsScreen
import com.privacyguard.app.ui.dashboard.DashboardScreen
import com.privacyguard.app.ui.settings.SettingsScreen
import com.privacyguard.app.ui.statistics.StatisticsScreen
import com.privacyguard.app.ui.theme.PrivacyGuardTheme

sealed class Screen(
    val route: String,
    val titleRes: Int,
    val icon: ImageVector
) {
    data object Dashboard : Screen("dashboard", R.string.dashboard, Icons.Default.Home)
    data object Connections : Screen("connections", R.string.connections, Icons.AutoMirrored.Filled.List)
    data object Apps : Screen("apps", R.string.apps, Icons.Default.Apps)
    data object Statistics : Screen("statistics", R.string.statistics, Icons.Default.BarChart)
    data object Settings : Screen("settings", R.string.settings, Icons.Default.Settings)
}

class MainActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PrivacyGuardTheme {
                PrivacyGuardApp(
                    onLanguageChanged = { recreate() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyGuardApp(
    onLanguageChanged: () -> Unit
) {
    val navController = rememberNavController()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    Screen.Dashboard,
                    Screen.Connections,
                    Screen.Apps,
                    Screen.Statistics,
                    Screen.Settings
                ).forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route)
                            }
                        },
                        icon = { Icon(screen.icon, contentDescription = null) },
                        label = { Text(stringResource(screen.titleRes)) }
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(onOpenSettings = { navController.navigate(Screen.Settings.route) })
            }
            composable(Screen.Connections.route) { ConnectionsScreen() }
            composable(Screen.Apps.route) { AppsScreen() }
            composable(Screen.Statistics.route) { StatisticsScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onLanguageChanged = onLanguageChanged
                )
            }
        }
    }
}
