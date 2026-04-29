import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/vpn_channel.dart';

final alertsProvider = FutureProvider<List<AlertEntry>>((ref) => VpnChannel.getAlerts(limit: 100));

class AlertsScreen extends ConsumerWidget {
  const AlertsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final alertsAsync = ref.watch(alertsProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Alert Inbox')),
      body: alertsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error:   (e, _) => Center(child: Text('Error: $e')),
        data: (alerts) {
          if (alerts.isEmpty) {
            return const Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(Icons.check_circle_outline, size: 64, color: Color(0xFF00E5C4)),
                  SizedBox(height: 12),
                  Text('No alerts', style: TextStyle(fontSize: 18)),
                  Text('All traffic looks normal', style: TextStyle(color: Colors.grey)),
                ],
              ),
            );
          }
          return ListView.builder(
            itemCount: alerts.length,
            itemBuilder: (ctx, i) => _AlertTile(alert: alerts[i]),
          );
        },
      ),
    );
  }
}

class _AlertTile extends StatelessWidget {
  final AlertEntry alert;
  const _AlertTile({required this.alert});

  Color get _color => alert.severity >= 9 ? Colors.red
      : alert.severity >= 7 ? Colors.orange
      : const Color(0xFF00E5C4);

  IconData get _icon => switch (alert.type) {
    'JA3_THREAT'   => Icons.fingerprint,
    'WEAK_CIPHER'  => Icons.vpn_key,
    'CT_NEW_CERT'  => Icons.verified_user,
    'CLEARTEXT'    => Icons.lock_open,
    _              => Icons.warning_amber_outlined,
  };

  @override
  Widget build(BuildContext context) => ListTile(
    leading: CircleAvatar(
      backgroundColor: _color.withOpacity(0.15),
      child: Icon(_icon, color: _color, size: 20),
    ),
    title: Text(alert.title, maxLines: 1, overflow: TextOverflow.ellipsis),
    subtitle: Text(alert.detail, maxLines: 2, overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 12)),
    trailing: Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
      decoration: BoxDecoration(
        color: _color.withOpacity(0.15),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text('SEV ${alert.severity}', style: TextStyle(color: _color, fontSize: 11)),
    ),
  );
}
