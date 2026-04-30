package com.privacyguard.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.privacyguard.app.BuildConfig  // FIXED: Correct import path
import com.privacyguard.app.data.db.AppDatabase
import com.privacyguard.app.data.local.preferences.SettingsPreferences
import com.privacyguard.app.ui.alerts.AlertInboxScreen
import com.privacyguard.app.ui.apps.AppDetailScreen
import com.privacyguard.app.ui.apps.AppsScreen
import com.privacyguard.app.ui.ads.AdsScreen
import com.privacyguard.app.ui.connections.ConnectionsScreen
import com.privacyguard.app.ui.dashboard.DashboardScreen
import com.privacyguard.app.ui.onboarding.OnboardingScreen
import com.privacyguard.app.ui.security.SecurityAnalysisScreen
import com.privacyguard.app.ui.settings.SettingsScreen
import com.privacyguard.app.ui.statistics.StatisticsScreen
import com.privacyguard.app.ui.theme.PgBackgroundAlt
import com.privacyguard.app.ui.theme.PgBorder
import com.privacyguard.app.ui.theme.PgTextFaint
import com.privacyguard.app.ui.theme.PgTextMuted
import com.privacyguard.ui.mitm.MitmScreen

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AppNavHost(
    onRequestVpn: () -> Unit = {},
    initialRoute: String? = null,
) {
    val context = LocalContext.current
    val settingsPreferences = remember(context) { SettingsPreferences.getInstance(context) }

    val tabs = remember {
        buildList {
            add(NavTab("dashboard", "Home",    Icons.Default.Home))
            add(NavTab("apps",      "Apps",    Icons.Default.Apps))
            add(NavTab("connections","Traffic", Icons.Default.Wifi))
            add(NavTab("statistics","Stats",   Icons.Default.BarChart))
            if (BuildConfig.MITM_AVAILABLE) {
                add(NavTab("payloads",  "Payload", Icons.AutoMirrored.Filled.ManageSearch))
            }
            add(NavTab("settings",  "Settings",Icons.Default.Settings))
        }
    }

    val topLevelRoutes = remember(tabs) { tabs.map { it.route }.toSet() }
    val onboardingCompleted by settingsPreferences.onboardingCompleted.collectAsState()
    val db = remember(context) { AppDatabase.getInstance(context) }
    val ja3ThreatCount by db.tlsAlertDao().ja3ThreatCountFlow().collectAsState(initial = 0)
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

    // Navigate to a specific tab when launched from a notification deep-link
    LaunchedEffect(initialRoute) {
        if (initialRoute != null && onboardingCompleted) {
            navController.navigate(initialRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
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
                            icon = {
                                if (tab.route == "settings" && ja3ThreatCount > 0) {
                                    BadgedBox(badge = {
                                        Badge { Text(ja3ThreatCount.coerceAtMost(99).toString()) }
                                    }) {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                } else {
                                    Icon(tab.icon, contentDescription = tab.label)
                                }
                            },
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
                    onEnableVpn = { tlsAccepted ->
                        settingsPreferences.recordVpnConsent()
                        if (tlsAccepted) settingsPreferences.recordTlsConsent()
                        settingsPreferences.setOnboardingCompleted(true)
                        navController.navigate("dashboard") {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                        onRequestVpn()
                    },
                    onGetStarted = { tlsAccepted ->
                        settingsPreferences.recordVpnConsent()
                        if (tlsAccepted) settingsPreferences.recordTlsConsent()
                        settingsPreferences.setOnboardingCompleted(true)
                        navController.navigate("dashboard") {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable("dashboard") {
                DashboardScreen(
                    onRequestVpn = onRequestVpn,
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenAlerts = { navController.navigate("alerts") },
                    onOpenConnections = { navController.navigate("connections") },
                    onOpenApps = { navController.navigate("apps") },
                )
            }

            composable("alerts") {
                AlertInboxScreen(onBack = { navController.popBackStack() })
            }

            composable("connections") {
                ConnectionsScreen(
                    onAppClick = { pkg, name ->
                        navController.navigate("appDetail/${Uri.encode(pkg)}?appName=${Uri.encode(name)}")
                    }
                )
            }

            composable("apps") {
                AppsScreen(
                    onAppClick = { pkg, name ->
                        navController.navigate("appDetail/${Uri.encode(pkg)}?appName=${Uri.encode(name)}")
                    }
                )
            }

            composable("ads") {
                AdsScreen()
            }

            composable("statistics") {
                StatisticsScreen()
            }

            composable("crypto") {
                SecurityAnalysisScreen(onOpenAlerts = { navController.navigate("alerts") })
            }

            // MITM Screen - only in enterprise build
            if (BuildConfig.MITM_AVAILABLE) {
                composable("payloads") {
                    MitmScreen()
                }
            }

            composable("settings") {
                val settingsContext = LocalContext.current
                val flutterLauncher: (() -> Unit)? = remember(settingsContext) {
                    try {
                        val flutterActivityClass = Class.forName("com.privacyguard.app.FlutterMainActivity")
                        ({ settingsContext.startActivity(Intent(settingsContext, flutterActivityClass)) })
                    } catch (_: ClassNotFoundException) {
                        null
                    }
                }
                SettingsScreen(
                    onLanguageChanged = {},
                    onOpenSecurityAnalysis = { navController.navigate("crypto") },
                    onOpenAds = { navController.navigate("ads") },
                    onOpenPayloads = if (BuildConfig.MITM_AVAILABLE) {
                        { navController.navigate("payloads") }
                    } else null,
                    onOpenFlutterUi = flutterLauncher,
                )
            }

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
