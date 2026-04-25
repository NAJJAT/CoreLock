package com.privacyguard.ui

import androidx.compose.foundation.BorderStroke
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.ui.apps.AppDetailScreen
import com.privacyguard.app.ui.apps.AppsScreen
import com.privacyguard.app.ui.ads.AdsScreen
import com.privacyguard.app.ui.connections.ConnectionsScreen
import com.privacyguard.app.ui.dashboard.DashboardScreen
import com.privacyguard.app.ui.onboarding.OnboardingScreen
import com.privacyguard.app.ui.settings.SettingsScreen
import com.privacyguard.app.ui.statistics.StatisticsScreen
import com.privacyguard.app.ui.theme.PgBackgroundAlt
import com.privacyguard.app.ui.theme.PgBorder
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import androidx.compose.ui.platform.LocalContext

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    NavTab("dashboard", "Home", Icons.Default.Home),
    NavTab("connections", "Traffic", Icons.Default.Wifi),
    NavTab("apps", "Apps", Icons.Default.Apps),
    NavTab("ads", "Ads", Icons.Default.MonetizationOn),
    NavTab("statistics", "Stats", Icons.Default.BarChart),
    NavTab("settings", "Prefs", Icons.Default.Settings),
)

private val topLevelRoutes = tabs.map { it.route }.toSet()

@Composable
fun AppNavHost(
    onRequestVpn: () -> Unit = {},
) {
    val context = LocalContext.current
    val settingsPreferences = remember(context) { SettingsPreferences.getInstance(context) }
    val onboardingCompleted by settingsPreferences.onboardingCompleted.collectAsState()
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentDest = navBackStack?.destination
    val currentRoute = currentDest?.route?.substringBefore('/')

    LaunchedEffect(onboardingCompleted) {
        val target = if (onboardingCompleted) "dashboard" else "onboarding"
        if (currentRoute != target) {
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = {
            if (currentRoute in topLevelRoutes) {
                NavigationBar(
                    containerColor = PgBackgroundAlt,
                    contentColor = PgTextMuted,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                ) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentDest?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = {
                                Text(
                                    text = tab.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    softWrap = false,
                                    fontSize = 11.sp,
                                )
                            },
                            alwaysShowLabel = true,
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                indicatorColor = PgBorder,
                                unselectedIconColor = PgTextFaint,
                                unselectedTextColor = PgTextFaint
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (onboardingCompleted) "dashboard" else "onboarding",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("onboarding") {
                OnboardingScreen(
                    onEnableVpn = onRequestVpn,
                    onGetStarted = {
                        settingsPreferences.setOnboardingCompleted(true)
                        navController.navigate("dashboard") {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable("dashboard") {
                DashboardScreen(onRequestVpn = onRequestVpn, onOpenSettings = { navController.navigate("settings") })
            }
            composable("connections") { ConnectionsScreen() }
            composable("apps") {
                AppsScreen(onAppClick = { pkg, name ->
                    navController.navigate("appDetail/${Uri.encode(pkg)}?appName=${Uri.encode(name)}")
                })
            }
            composable("ads") { AdsScreen() }
            composable("statistics") { StatisticsScreen() }
            composable("settings") { SettingsScreen(onLanguageChanged = {}) }
            composable("appDetail/{packageName}?appName={appName}") { backStack ->
                val pkg = backStack.arguments?.getString("packageName") ?: ""
                val appName = backStack.arguments?.getString("appName") ?: pkg
                AppDetailScreen(
                    packageName = pkg,
                    appName = appName,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
