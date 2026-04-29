import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'screens/home_screen.dart';
import 'screens/apps_screen.dart';
import 'screens/connections_screen.dart';
import 'screens/stats_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/alerts_screen.dart';
import 'screens/app_detail_screen.dart';

void main() {
  runApp(const ProviderScope(child: PrivacyGuardApp()));
}

final _router = GoRouter(
  initialLocation: '/',
  routes: [
    StatefulShellRoute.indexedStack(
      builder: (context, state, shell) => MainScaffold(shell: shell),
      branches: [
        StatefulShellBranch(routes: [GoRoute(path: '/',           builder: (_, __) => const HomeScreen())]),
        StatefulShellBranch(routes: [GoRoute(path: '/apps',       builder: (_, __) => const AppsScreen(),
          routes: [GoRoute(path: ':pkg', builder: (ctx, s) => AppDetailScreen(packageName: s.pathParameters['pkg']!))])]),
        StatefulShellBranch(routes: [GoRoute(path: '/traffic',    builder: (_, __) => const ConnectionsScreen())]),
        StatefulShellBranch(routes: [GoRoute(path: '/stats',      builder: (_, __) => const StatsScreen())]),
        StatefulShellBranch(routes: [GoRoute(path: '/settings',   builder: (_, __) => const SettingsScreen(),
          routes: [GoRoute(path: 'alerts', builder: (_, __) => const AlertsScreen())])]),
      ],
    ),
  ],
);

class PrivacyGuardApp extends StatelessWidget {
  const PrivacyGuardApp({super.key});
  @override
  Widget build(BuildContext context) => MaterialApp.router(
    title: 'PrivacyGuard',
    theme: ThemeData.dark(useMaterial3: true).copyWith(
      colorScheme: ColorScheme.fromSeed(
        seedColor: const Color(0xFF00E5C4),
        brightness: Brightness.dark,
      ).copyWith(surface: const Color(0xFF080C12)),
    ),
    routerConfig: _router,
  );
}

class MainScaffold extends StatelessWidget {
  final StatefulNavigationShell shell;
  const MainScaffold({super.key, required this.shell});

  @override
  Widget build(BuildContext context) => Scaffold(
    body: shell,
    bottomNavigationBar: NavigationBar(
      selectedIndex: shell.currentIndex,
      onDestinationSelected: shell.goBranch,
      destinations: const [
        NavigationDestination(icon: Icon(Icons.home_outlined),       selectedIcon: Icon(Icons.home),       label: 'Home'),
        NavigationDestination(icon: Icon(Icons.apps_outlined),       selectedIcon: Icon(Icons.apps),       label: 'Apps'),
        NavigationDestination(icon: Icon(Icons.wifi_outlined),       selectedIcon: Icon(Icons.wifi),       label: 'Traffic'),
        NavigationDestination(icon: Icon(Icons.bar_chart_outlined),  selectedIcon: Icon(Icons.bar_chart),  label: 'Stats'),
        NavigationDestination(icon: Icon(Icons.settings_outlined),   selectedIcon: Icon(Icons.settings),   label: 'Settings'),
      ],
    ),
  );
}
