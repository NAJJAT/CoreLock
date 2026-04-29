import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/vpn_channel.dart';

class StatsScreen extends ConsumerWidget {
  const StatsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final statsAsync = ref.watch(vpnStatsProvider);
    final stats = statsAsync.valueOrNull ?? const VpnStats();

    return Scaffold(
      appBar: AppBar(title: const Text('Statistics')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _StatCard('Privacy Score', '${stats.privacyScore}/100'),
          const SizedBox(height: 12),
          _EncryptionPieCard(stats: stats),
          const SizedBox(height: 12),
          _TopAppsCard(),
        ],
      ),
    );
  }
}

class _StatCard extends StatelessWidget {
  final String label, value;
  const _StatCard(this.label, this.value);

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          Text(label, style: const TextStyle(color: Colors.grey)),
          Text(value, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 18)),
        ],
      ),
    ),
  );
}

class _EncryptionPieCard extends StatelessWidget {
  final VpnStats stats;
  const _EncryptionPieCard({required this.stats});

  @override
  Widget build(BuildContext context) => Card(
    child: Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Encryption breakdown', style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 12),
          SizedBox(
            height: 160,
            child: PieChart(PieChartData(sections: [
              PieChartSectionData(
                value: stats.cleartextCount.toDouble().clamp(1, double.infinity),
                color: Colors.red,
                title: 'Clear',
                titleStyle: const TextStyle(fontSize: 11),
              ),
              PieChartSectionData(
                value: (stats.trackersBlocked + 10).toDouble(),
                color: const Color(0xFF00E5C4),
                title: 'TLS',
                titleStyle: const TextStyle(fontSize: 11),
              ),
            ])),
          ),
        ],
      ),
    ),
  );
}

class _TopAppsCard extends ConsumerWidget {
  const _TopAppsCard();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final appsAsync = ref.watch(appsProvider);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        children: [
          const Text('Top risk apps', style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 12),
          appsAsync.when(
            loading: () => const LinearProgressIndicator(),
            error:   (_, __) => const Text('Failed to load apps'),
            data: (apps) => Column(
              children: apps.take(5).map((a) => Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  children: [
                    Expanded(child: Text(a.appName, maxLines: 1, overflow: TextOverflow.ellipsis)),
                    Text('${a.riskScore}', style: const TextStyle(color: Colors.red, fontWeight: FontWeight.bold)),
                  ],
                ),
              )).toList(),
            ),
          ),
        ],
      ),
    );
  }
}

// Re-export providers from home screen
final vpnStatsProvider = StreamProvider<VpnStats>((ref) => VpnChannel.statsStream);
final appsProvider = FutureProvider<List<AppRiskEntry>>((ref) => VpnChannel.getTopRiskApps());
