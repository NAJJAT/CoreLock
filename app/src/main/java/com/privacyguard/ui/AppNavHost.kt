package com.privacyguard.ui

import android.net.Uri
import com.privacyguard.app.ui.theme.CoreLockTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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
import com.privacyguard.app.ui.settings.LicenseText
import com.privacyguard.app.ui.settings.LicenseTextScreen
import com.privacyguard.app.ui.settings.OpenSourceLicensesScreen
import com.privacyguard.app.ui.sleep.SleepReportScreen
import com.privacyguard.app.ui.statistics.StatisticsScreen
import com.privacyguard.ui.flavor.PayloadInspectorUi
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal data class NavTab(val route: String, @androidx.annotation.StringRes val labelRes: Int, val icon: ImageVector)

/**
 * The four tabs of 1.1 (DESIGN_SPEC_1.1.md §4.1). Stats and Payload left the bar: they are
 * Settings › Advanced › Analysis › Statistics and Settings › Advanced › HTTPS inspection,
 * and their 1.0 routes still work. Activity keeps the 1.0 route name "connections" because
 * notifications posted by 1.0 open it (§4.3).
 */
internal val APP_TABS = listOf(
    NavTab("dashboard", com.privacyguard.app.R.string.nav_home, Icons.Rounded.Shield),
    NavTab("apps", com.privacyguard.app.R.string.nav_apps, Icons.Rounded.Apps),
    NavTab("connections", com.privacyguard.app.R.string.nav_traffic, Icons.AutoMirrored.Rounded.ListAlt),
    NavTab("settings", com.privacyguard.app.R.string.nav_settings, Icons.Rounded.Settings),
)

@Composable
fun AppNavHost(
    onRequestVpn: () -> Unit = {},
    initialRoute: String? = null,
) {
    val context = LocalContext.current
    val settingsPreferences = remember(context) { SettingsPreferences.getInstance(context) }

    val tabs = APP_TABS

    val topLevelRoutes = remember(tabs) { tabs.map { it.route }.toSet() }
    val onboardingCompleted by settingsPreferences.onboardingCompleted.collectAsStateWithLifecycle()
    val db = remember(context) { AppDatabase.getInstance(context) }
    val ja3ThreatCount by db.tlsAlertDao().ja3ThreatCountFlow().collectAsStateWithLifecycle(initialValue = 0)
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentDest = navBackStack?.destination
    val currentRoute = currentDest?.route?.substringBefore('/')

    // FLAG_SECURE follows the screen shown (V-08): on, except for NOT_SENSITIVE routes,
    // and applied at once when "Allow screenshots" changes in Settings.
    val allowScreenshots by settingsPreferences.allowScreenshots.collectAsStateWithLifecycle()
    LaunchedEffect(currentDest?.route, allowScreenshots) {
        SecureScreens.userAllowsScreenshots = allowScreenshots
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
            ?.let { SecureScreens.apply(it.window, currentDest?.route) }
    }

    LaunchedEffect(onboardingCompleted) {
        val target = onboardingRedirect(onboardingCompleted, currentRoute)
        if (target != null) {
            navController.navigate(target) {
                popUpTo(navController.graph.findStartDestination().id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Navigate to a specific tab when launched from a notification deep-link
    // Once per launch: the intent survives rotation, and handling it again would jump back.
    var deepLinkHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialRoute) {
        if (initialRoute != null && onboardingCompleted && !deepLinkHandled) {
            deepLinkHandled = true
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
                PgBottomBar(
                    tabs = tabs,
                    isSelected = { tab -> currentDest?.hierarchy?.any { it.route == tab.route } == true },
                    settingsBadge = ja3ThreatCount,
                    onSelect = { tab ->
                        navController.navigate(tab.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = if (onboardingCompleted) "dashboard" else "onboarding",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("onboarding") {
                val level by settingsPreferences.protectionLevel.collectAsStateWithLifecycle()
                OnboardingScreen(
                    initialLevel = level,
                    onVpnConsent = settingsPreferences::recordVpnConsent,
                    onRequestVpn = onRequestVpn,
                    onFinish = { level ->
                        settingsPreferences.setProtectionLevel(level)
                        settingsPreferences.setOnboardingCompleted(true)
                        navController.navigate("dashboard") {
                            popUpTo("onboarding") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable("dashboard") {
                com.privacyguard.app.ui.home.HomeScreen(
                    onRequestVpn = onRequestVpn,
                    // Activity opens on Blocked; "Today" there counts from midnight, like this number.
                    onOpenBlocked = { navController.navigate("connections") { launchSingleTop = true } },
                    onOpenAlerts = { navController.navigate("alerts") },
                    onOpenStatistics = { navController.navigate("statistics") { launchSingleTop = true } },
                    onOpenPayloads = { navController.navigate("payloads") { launchSingleTop = true } },
                    onOpenFix = { navController.navigate("fix") },
                )
            }

            // TEMPORARY (Phase 3 → 6): the 1.0 Home, whole, until its cards move to
            // Settings › Advanced › Analysis (DESIGN_SPEC_1.1.md §5.2).
            composable("securityOverview") {
                Pushed(navController) { DashboardScreen(
                    onRequestVpn = onRequestVpn,
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenAlerts = { navController.navigate("alerts") },
                    onOpenConnections = { navController.navigate("connections") },
                    onOpenApps = { navController.navigate("apps") },
                    onOpenSleepReport = { at -> navController.navigate("sleepReport?at=$at") },
                    onOpenEvidence = { kind, at -> navController.navigate("evidence/${kind.name}?at=$at") },
                    onOpenStatistics = { navController.navigate("statistics") },
                    onOpenPayloads = { navController.navigate("payloads") },
                ) }
            }

            // Drill-downs behind Home numbers: same window as the number, so the counts match.
            composable(
                "evidence/{kind}?at={at}",
                arguments = listOf(navArgument("at") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) {
                com.privacyguard.app.ui.traffic.EvidenceScreen(
                    onBack = { navController.popBackStack() },
                    onOpenApp = { pkg -> navController.navigate(appTrafficRoute(pkg)) },
                    onOpenDomain = { host -> navController.navigate("domain/${Uri.encode(host)}") },
                )
            }

            composable(
                "appTraffic/{packageName}?from={from}&to={to}&idle={idle}",
                arguments = listOf(
                    navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("to") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("idle") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { backStack ->
                val trafficPkg = backStack.arguments?.getString("packageName").orEmpty()
                com.privacyguard.app.ui.traffic.AppTrafficScreen(
                    onBack = { navController.popBackStack() },
                    onOpenDomain = { host -> navController.navigate("domain/${Uri.encode(host)}?app=${Uri.encode(trafficPkg)}") },
                )
            }

            // "app": opened from one app's traffic, so per-app Allow and Block apply. The
            // plain "domain/{host}" route of 1.0 still matches (the argument is optional).
            composable(
                "domain/{host}?app={app}",
                arguments = listOf(navArgument("app") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) {
                com.privacyguard.app.ui.traffic.DomainDetailScreen(
                    onBack = { navController.popBackStack() },
                    onOpenApp = { pkg -> navController.navigate(appTrafficRoute(pkg)) },
                )
            }

            composable("alerts") {
                AlertInboxScreen(onBack = { navController.popBackStack() })
            }

            composable(
                "sleepReport?at={at}",
                arguments = listOf(navArgument("at") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) { backStack ->
                SleepReportScreen(
                    at = backStack.arguments?.getString("at")?.toLongOrNull(),
                    onBack = { navController.popBackStack() },
                    onOpenApp = { pkg, from, to -> navController.navigate(appTrafficRoute(pkg, from, to, idleOnly = true)) },
                    onOpenDomain = { host -> navController.navigate("domain/${Uri.encode(host)}") },
                )
            }

            // The Activity tab (DESIGN_SPEC_1.1.md §6.2). The route keeps its 1.0 name: blocked-
            // connection notifications posted by 1.0 open "connections" (§4.3).
            composable("connections") {
                com.privacyguard.app.ui.activity.ActivityScreen(
                    onOpenFix = { navController.navigate("fix") },
                    onRequestVpn = onRequestVpn,
                    onOpenDomain = { host -> navController.navigate("domain/${Uri.encode(host)}") },
                    onOpenApp = { pkg, name -> navController.navigate("appDetail/${Uri.encode(pkg)}?appName=${Uri.encode(name)}") },
                    onOpenLive = { navController.navigate("trafficLive") },
                )
            }

            // "Something not working?" (§6.2): from Home and from Activity.
            composable("fix") {
                com.privacyguard.app.ui.activity.FixFlowScreen(
                    onClose = { navController.popBackStack() },
                    onOpenDomain = { host -> navController.navigate("domain/${Uri.encode(host)}") },
                    onOpenApp = { pkg, name -> navController.navigate("appDetail/${Uri.encode(pkg)}?appName=${Uri.encode(name)}") },
                    // D1: back to Home, where the hero shows the pause and its end time.
                    onPause = {
                        val paused = com.privacyguard.app.vpn.ProtectionPause.pause(context)
                        android.widget.Toast.makeText(
                            context,
                            if (paused) com.privacyguard.app.R.string.ui_paused_toast else com.privacyguard.app.R.string.ui_action_failed,
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        if (paused) navController.navigate("dashboard") {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                )
            }

            // The 1.0 Traffic screen, whole (live sessions, speed, search, name lookup), until
            // Phase 6 moves live speed to Statistics (parity R6). Reached from Activity's menu.
            // The 1.0 Traffic screen, now pushed from Activity: it has no top bar of its own,
            // so it gets one here with a back arrow (until Phase 6 moves it).
            composable("trafficLive") {
                androidx.compose.foundation.layout.Column {
                    com.privacyguard.app.ui.components.CoreLockTopBar(
                        title = androidx.compose.ui.res.stringResource(com.privacyguard.app.R.string.ui_live_connections),
                        onBack = { navController.popBackStack() },
                    )
                    ConnectionsScreen(
                        onAppClick = { pkg, name ->
                            navController.navigate("appDetail/${Uri.encode(pkg)}?appName=${Uri.encode(name)}")
                        }
                    )
                }
            }

            composable("apps") {
                AppsScreen(
                    onAppClick = { pkg, name ->
                        navController.navigate("appDetail/${Uri.encode(pkg)}?appName=${Uri.encode(name)}")
                    }
                )
            }

            composable("ads") {
                Pushed(navController) { AdsScreen() }
            }

            composable("adPrivacy") {
                Pushed(navController) { com.privacyguard.app.adprivacy.AdPrivacyScreen() }
            }

            composable("rules") {
                com.privacyguard.app.ui.rules.RulesScreen(onBack = { navController.popBackStack() })
            }

            // Advanced › Analysis › Statistics (parity R8; was the Stats tab).
            composable("statistics") {
                Pushed(navController) {
                    StatisticsScreen(
                        onOpenEvidence = { kind, at -> navController.navigate("evidence/${kind.name}?at=$at") },
                        onOpenDomain = { host -> navController.navigate("domain/${Uri.encode(host)}") },
                        onOpenApp = { pkg -> navController.navigate(appTrafficRoute(pkg)) },
                        onOpenBlocked = { navController.navigate("connections") { launchSingleTop = true } },
                    )
                }
            }

            composable("crypto") {
                Pushed(navController) { SecurityAnalysisScreen(onOpenAlerts = { navController.navigate("alerts") }) }
            }

            // Advanced › HTTPS inspection (parity R9; was the Payload tab).
            composable("payloads") {
                PayloadInspectorUi.Screen(onBack = { navController.backOrHome() })
            }

            composable("settings") {
                com.privacyguard.app.ui.settings.SettingsHomeScreen(
                    com.privacyguard.app.ui.settings.SettingsNav(
                        onOpenRules = { navController.navigate("rules") },
                        onOpenBlocklists = { navController.navigate("settings/blocklists") },
                        onOpenKillSwitch = { navController.navigate("settings/killswitch") },
                        onOpenLicenses = { navController.navigate("licenses") },
                        onOpenAdvanced = { navController.navigate("advanced") },
                    ),
                )
            }

            composable("settings/blocklists") {
                com.privacyguard.app.ui.settings.BlocklistsScreen(onBack = { navController.popBackStack() })
            }

            composable("settings/killswitch") {
                com.privacyguard.app.ui.settings.KillSwitchScreen(onBack = { navController.popBackStack() })
            }

            // Settings › Advanced (DESIGN_SPEC_1.1.md §6.6). Every expert setting of the 1.0
            // Settings screen is on one of these four screens (parity table §5.6).
            composable("advanced") {
                com.privacyguard.app.ui.settings.AdvancedScreen(
                    com.privacyguard.app.ui.settings.AdvancedNav(
                        onBack = { navController.popBackStack() },
                        onOpenNetwork = { navController.navigate("advanced/network") },
                        onOpenHttpsInspection = { navController.navigate("payloads") },
                        onOpenAnalysis = { navController.navigate("advanced/analysis") },
                        onOpenDiagnostics = { navController.navigate("advanced/diagnostics") },
                    ),
                )
            }

            composable("advanced/network") {
                com.privacyguard.app.ui.settings.NetworkScreen(
                    onBack = { navController.popBackStack() },
                    onOpenProxies = { navController.navigate("advanced/proxies") },
                )
            }

            composable("advanced/proxies") {
                com.privacyguard.app.ui.settings.ProxyChainScreen(onBack = { navController.popBackStack() })
            }

            composable("advanced/analysis") {
                com.privacyguard.app.ui.settings.AnalysisScreen(
                    com.privacyguard.app.ui.settings.AnalysisNav(
                        onBack = { navController.popBackStack() },
                        onOpenSecurityAnalysis = { navController.navigate("crypto") },
                        onOpenStatistics = { navController.navigate("statistics") },
                        onOpenAlerts = { navController.navigate("alerts") },
                        onOpenSecuritySystems = { navController.navigate("securityOverview") },
                        onOpenAdPrivacy = { navController.navigate("adPrivacy") },
                        onOpenAds = { navController.navigate("ads") },
                    ),
                )
            }

            composable("advanced/diagnostics") {
                com.privacyguard.app.ui.settings.DiagnosticsScreen(onBack = { navController.popBackStack() })
            }

            composable("licenses") {
                Pushed(navController) { OpenSourceLicensesScreen(onOpenText = { text -> navController.navigate("licenseText/${text.name}") }) }
            }

            composable("licenseText/{key}") { backStack ->
                val key = backStack.arguments?.getString("key")
                Pushed(navController) { LicenseTextScreen(LicenseText.entries.firstOrNull { it.name == key }) }
            }

            composable("appDetail/{packageName}?appName={appName}") { backStack ->
                val pkg = backStack.arguments?.getString("packageName") ?: ""
                val appName = backStack.arguments?.getString("appName") ?: pkg
                AppDetailScreen(
                    packageName = pkg,
                    appName = appName,
                    onBack = { navController.popBackStack() },
                    onOpenTraffic = { navController.navigate(appTrafficRoute(pkg)) },
                    onOpenDomain = { host -> navController.navigate("domain/${Uri.encode(host)}?app=${Uri.encode(pkg)}") },
                )
            }
        }
    }
}

/** A 1.0 screen pushed from a 1.1 one: its title row gets a back arrow (see LocalScreenBack). */
@Composable
private fun Pushed(navController: androidx.navigation.NavHostController, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        com.privacyguard.app.ui.components.LocalScreenBack provides { navController.backOrHome() },
        content = content,
    )
}

/** Back, or Home when this screen is the only one (opened from a notification). */
private fun androidx.navigation.NavHostController.backOrHome() {
    if (!popBackStack()) navigate("dashboard") { launchSingleTop = true }
}

/** Route to an app's traffic; a blank package (unattributed lookups) uses DnsHandler's "Unknown". */
private fun appTrafficRoute(pkg: String, from: Long? = null, to: Long? = null, idleOnly: Boolean = false): String {
    val p = Uri.encode(pkg.ifBlank { com.privacyguard.app.ui.traffic.TrafficRows.UNKNOWN_PACKAGE })
    val q = listOfNotNull(from?.let { "from=$it" }, to?.let { "to=$it" }, if (idleOnly) "idle=1" else null)
    return if (q.isEmpty()) "appTraffic/$p" else "appTraffic/$p?" + q.joinToString("&")
}

/**
 * Each tab is one TalkBack stop: NavigationBarItem merges it into a node with role Tab,
 * its selected state, a click action and the label as its text. With a label shown it
 * clears the icon's semantics, so the badge count would never be read; the tab itself
 * says what the badge counts.
 */
@Composable
internal fun PgBottomBar(
    tabs: List<NavTab>,
    isSelected: (NavTab) -> Boolean,
    settingsBadge: Int,
    onSelect: (NavTab) -> Unit,
) {
    NavigationBar(
        containerColor = CoreLockTheme.colors.surface,
        contentColor = CoreLockTheme.colors.onSurfaceMuted,
        tonalElevation = 0.dp,
    ) {
        tabs.forEach { tab ->
            val badge = if (tab.route == "settings") settingsBadge else 0
            val spoken = if (badge > 0) {
                pluralStringResource(com.privacyguard.app.R.plurals.nav_badge_threats, badge, badge)
            } else null
            NavigationBarItem(
                selected = isSelected(tab),
                onClick = { onSelect(tab) },
                modifier = if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier,
                icon = {
                    if (badge > 0) {
                        BadgedBox(badge = {
                            Badge { Text(if (badge > 99) "99+" else badge.toString()) }
                        }) {
                            Icon(tab.icon, contentDescription = null)
                        }
                    } else {
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = {
                    // Four tabs leave room for the label at the user's font size; where a long
                    // word still doesn't fit (200 %, "Instellingen"), it shrinks, but never below
                    // its 100 % size, instead of being cut ("Réglag" in 1.0).
                    val style = androidx.compose.material3.MaterialTheme.typography.labelMedium
                    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
                    Text(
                        text = stringResource(tab.labelRes),
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                        // A small gap so neighbouring long labels never touch each other or the screen edge.
                        modifier = Modifier.padding(horizontal = 4.dp),
                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                            minFontSize = style.fontSize / fontScale.coerceAtLeast(1f),
                            maxFontSize = style.fontSize,
                        ),
                    )
                },
                alwaysShowLabel = true,
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                    selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    indicatorColor = CoreLockTheme.colors.surfaceVariant,
                    unselectedIconColor = CoreLockTheme.colors.onSurfaceMuted,
                    unselectedTextColor = CoreLockTheme.colors.onSurfaceMuted
                )
            )
        }
    }
}

/**
 * Where to send the user when onboarding status is known, or null to stay. Only a real
 * mismatch moves: finished onboarding still on its screen, or not finished anywhere else.
 * A restored screen (after rotation or process death) is left alone; comparing with
 * "dashboard" sent every rotation back to Home. Null [currentRoute]: the graph isn't
 * there yet and its start destination already matches.
 */
internal fun onboardingRedirect(completed: Boolean, currentRoute: String?): String? = when {
    currentRoute == null -> null
    completed && currentRoute == "onboarding" -> "dashboard"
    !completed && currentRoute != "onboarding" -> "onboarding"
    else -> null
}
