import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import '../services/vpn_channel.dart';

// ── Providers ─────────────────────────────────────────────────────────────────

final vpnStatsProvider = StreamProvider<VpnStats>((ref) => VpnChannel.statsStream);

final isRunningProvider = FutureProvider<bool>((ref) => VpnChannel.isRunning());

// ── Screen ────────────────────────────────────────────────────────────────────

class HomeScreen extends ConsumerWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final statsAsync = ref.watch(vpnStatsProvider);
    final stats = statsAsync.valueOrNull ?? const VpnStats();

    return Scaffold(
      body: SafeArea(
        child: CustomScrollView(
          slivers: [
            SliverAppBar(
              floating: true,
              title: const Text('PrivacyGuard'),
              actions: [
                IconButton(
                  icon: const Icon(Icons.notifications_outlined),
                  onPressed: () => context.go('/settings/alerts'),
                ),
                IconButton(
                  icon: const Icon(Icons.settings_outlined),
                  onPressed: () => context.go('/settings'),
                ),
              ],
            ),
            SliverToBoxAdapter(child: _PrivacyScoreCard(stats: stats)),
            SliverToBoxAdapter(child: _ProtectionToggle(stats: stats)),
            if (stats.isRunning) ...[
              SliverToBoxAdapter(child: _ThroughputRow(stats: stats)),
            ],
            SliverToBoxAdapter(child: _ProtectionLevelPicker(current: stats.protectionLevel)),
            SliverToBoxAdapter(child: _SecurityCardsGrid(stats: stats)),
          ],
        ),
      ),
    );
  }
}

// ── Privacy score card ────────────────────────────────────────────────────────

class _PrivacyScoreCard extends StatelessWidget {
  final VpnStats stats;
  const _PrivacyScoreCard({required this.stats});

  @override
  Widget build(BuildContext context) => Container(
    margin: const EdgeInsets.all(16),
    height: 160,
    decoration: BoxDecoration(
      borderRadius: BorderRadius.circular(24),
      gradient: const LinearGradient(
        colors: [Color(0xFF0F1E30), Color(0xFF0D2819)],
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
      ),
    ),
    child: Padding(
      padding: const EdgeInsets.all(20),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  stats.isRunning ? 'Protected' : 'Not Protected',
                  style: TextStyle(
                    color: stats.isRunning ? const Color(0xFF00E5C4) : Colors.red,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'Privacy Score',
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Colors.grey),
                ),
              ],
            ),
          ),
          Text(
            '${stats.privacyScore}',
            style: const TextStyle(
              fontSize: 64,
              fontWeight: FontWeight.bold,
              color: Colors.white,
            ),
          ),
        ],
      ),
    ),
  );
}

// ── Protection toggle ─────────────────────────────────────────────────────────

class _ProtectionToggle extends StatefulWidget {
  final VpnStats stats;
  const _ProtectionToggle({required this.stats});
  @override
  State<_ProtectionToggle> createState() => _ProtectionToggleState();
}

class _ProtectionToggleState extends State<_ProtectionToggle> {
  bool _loading = false;

  Future<void> _toggle() async {
    setState(() => _loading = true);
    try {
      if (widget.stats.isRunning) {
        await VpnChannel.stopVpn();
      } else {
        await VpnChannel.startVpn();
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 16),
    child: FilledButton.icon(
      onPressed: _loading ? null : _toggle,
      icon: _loading
          ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
          : Icon(widget.stats.isRunning ? Icons.shield : Icons.shield_outlined),
      label: Text(widget.stats.isRunning ? 'Disable Protection' : 'Enable Protection'),
      style: FilledButton.styleFrom(
        minimumSize: const Size(double.infinity, 52),
        backgroundColor: widget.stats.isRunning ? Colors.red.shade900 : const Color(0xFF00E5C4),
        foregroundColor: widget.stats.isRunning ? Colors.red.shade200 : Colors.black,
      ),
    ),
  );
}

// ── Throughput row ────────────────────────────────────────────────────────────

class _ThroughputRow extends StatelessWidget {
  final VpnStats stats;
  const _ThroughputRow({required this.stats});

  String _fmt(int bps) {
    if (bps >= 1048576) return '${(bps / 1048576).toStringAsFixed(1)} MB/s';
    if (bps >= 1024)    return '${bps ~/ 1024} KB/s';
    return '$bps B/s';
  }

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
    child: Row(children: [
      _StatChip(label: 'Throughput', value: _fmt(stats.throughputBytesPerSec), color: const Color(0xFF00E5C4)),
      const SizedBox(width: 12),
      _StatChip(label: 'Active', value: '${stats.activeConnections} conn', color: Colors.blue),
      const SizedBox(width: 12),
      _StatChip(label: 'Blocked', value: '${stats.trackersBlocked}', color: Colors.red),
    ]),
  );
}

class _StatChip extends StatelessWidget {
  final String label, value;
  final Color color;
  const _StatChip({required this.label, required this.value, required this.color});

  @override
  Widget build(BuildContext context) => Expanded(
    child: Container(
      padding: const EdgeInsets.symmetric(vertical: 10),
      decoration: BoxDecoration(
        color: color.withOpacity(0.1),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        children: [
          Text(value, style: TextStyle(color: color, fontWeight: FontWeight.bold)),
          Text(label, style: const TextStyle(color: Colors.grey, fontSize: 11)),
        ],
      ),
    ),
  );
}

// ── Protection level picker ───────────────────────────────────────────────────

class _ProtectionLevelPicker extends StatelessWidget {
  final String current;
  const _ProtectionLevelPicker({required this.current});

  @override
  Widget build(BuildContext context) {
    const levels = [
      ('MINIMAL',  'Minimal',  Colors.blue),
      ('STANDARD', 'Standard', Color(0xFF00E5C4)),
      ('STRICT',   'Strict',   Colors.red),
    ];
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Protection Level', style: Theme.of(context).textTheme.labelMedium?.copyWith(color: Colors.grey)),
          const SizedBox(height: 8),
          Row(
            children: levels.map((l) {
              final selected = current == l.$1;
              return Expanded(
                child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 3),
                  child: GestureDetector(
                    onTap: () => VpnChannel.setProtectionLevel(l.$1),
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 200),
                      padding: const EdgeInsets.symmetric(vertical: 10),
                      decoration: BoxDecoration(
                        color: selected ? (l.$3 as Color).withOpacity(0.18) : Colors.white10,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      alignment: Alignment.center,
                      child: Text(
                        l.$2,
                        style: TextStyle(
                          color: selected ? (l.$3 as Color) : Colors.grey,
                          fontWeight: selected ? FontWeight.bold : FontWeight.normal,
                        ),
                      ),
                    ),
                  ),
                ),
              );
            }).toList(),
          ),
        ],
      ),
    );
  }
}

// ── Security cards ────────────────────────────────────────────────────────────

class _SecurityCardsGrid extends StatelessWidget {
  final VpnStats stats;
  const _SecurityCardsGrid({required this.stats});

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.all(16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Security Status', style: Theme.of(context).textTheme.labelMedium?.copyWith(color: Colors.grey)),
        const SizedBox(height: 10),
        GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          mainAxisSpacing: 10,
          crossAxisSpacing: 10,
          childAspectRatio: 1.5,
          children: [
            _SecurityCard('Trackers Blocked', '${stats.trackersBlocked}', Colors.red, Icons.block),
            _SecurityCard('Cleartext', '${stats.cleartextCount}', Colors.orange, Icons.lock_open),
            _SecurityCard('Privacy Score', '${stats.privacyScore}/100', const Color(0xFF00E5C4), Icons.security),
            _SecurityCard('Mode', stats.protectionLevel.toLowerCase(), Colors.blue, Icons.shield),
          ],
        ),
      ],
    ),
  );
}

class _SecurityCard extends StatelessWidget {
  final String title, value;
  final Color color;
  final IconData icon;
  const _SecurityCard(this.title, this.value, this.color, this.icon);

  @override
  Widget build(BuildContext context) => Container(
    decoration: BoxDecoration(
      color: color.withOpacity(0.08),
      borderRadius: BorderRadius.circular(14),
    ),
    padding: const EdgeInsets.all(12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Icon(icon, color: color, size: 20),
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(value, style: TextStyle(color: color, fontWeight: FontWeight.bold, fontSize: 18)),
            Text(title, style: const TextStyle(color: Colors.grey, fontSize: 11)),
          ],
        ),
      ],
    ),
  );
}

// ignore_for_file: depend_on_referenced_packages
extension _GoExt on BuildContext {
  void go(String route) => GoRouter.of(this).go(route);
}
