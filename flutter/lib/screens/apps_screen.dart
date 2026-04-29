import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../services/vpn_channel.dart';

final appsProvider = FutureProvider<List<AppRiskEntry>>((ref) => VpnChannel.getTopRiskApps());

class AppsScreen extends ConsumerWidget {
  const AppsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appsAsync = ref.watch(appsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Apps')),
      body: appsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('Error: $e')),
        data: (apps) => ListView.builder(
          padding: const EdgeInsets.all(12),
          itemCount: apps.length,
          itemBuilder: (ctx, i) => _AppCard(app: apps[i]),
        ),
      ),
    );
  }
}

class _AppCard extends StatelessWidget {
  final AppRiskEntry app;
  const _AppCard({required this.app});

  Color get _gradeColor => switch (app.grade) {
    'A' => const Color(0xFF00E5C4),
    'B' => Colors.blue,
    'C' => Colors.orange,
    'D' || 'F' => Colors.red,
    _ => Colors.grey,
  };

  @override
  Widget build(BuildContext context) => Card(
    margin: const EdgeInsets.only(bottom: 8),
    child: ListTile(
      onTap: () => context.go('/apps/${Uri.encodeComponent(app.packageName)}'),
      leading: CircleAvatar(
        backgroundColor: _gradeColor.withOpacity(0.15),
        child: Text(app.grade, style: TextStyle(color: _gradeColor, fontWeight: FontWeight.bold)),
      ),
      title: Text(app.appName, maxLines: 1, overflow: TextOverflow.ellipsis),
      subtitle: Text(
        '${app.cleartextCount} cleartext · ${app.backgroundCount} bg',
        style: const TextStyle(color: Colors.grey, fontSize: 12),
      ),
      trailing: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text('${app.riskScore}', style: TextStyle(color: _gradeColor, fontWeight: FontWeight.bold)),
          const Text('risk', style: TextStyle(color: Colors.grey, fontSize: 10)),
        ],
      ),
    ),
  );
}
