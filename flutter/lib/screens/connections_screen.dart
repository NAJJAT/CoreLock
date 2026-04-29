import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/vpn_channel.dart';

final connectionsProvider = FutureProvider<List<ConnectionEntry>>(
    (ref) => VpnChannel.getRecentConnections(limit: 100));

class ConnectionsScreen extends ConsumerWidget {
  const ConnectionsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final connAsync = ref.watch(connectionsProvider);
    return Scaffold(
      appBar: AppBar(title: const Text('Traffic')),
      body: connAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error:   (e, _) => Center(child: Text('Error: $e')),
        data: (conns) => ListView.builder(
          itemCount: conns.length,
          itemBuilder: (ctx, i) => _ConnectionTile(conn: conns[i]),
        ),
      ),
    );
  }
}

class _ConnectionTile extends StatelessWidget {
  final ConnectionEntry conn;
  const _ConnectionTile({required this.conn});

  Color get _color => conn.isBlocked ? Colors.red
      : conn.encryption == 'CLEARTEXT' ? Colors.orange
      : const Color(0xFF00E5C4);

  @override
  Widget build(BuildContext context) => ListTile(
    dense: true,
    leading: Icon(
      conn.isBlocked ? Icons.block : Icons.lock,
      color: _color,
      size: 20,
    ),
    title: Text(conn.appName, maxLines: 1, overflow: TextOverflow.ellipsis),
    subtitle: Text(conn.destination, maxLines: 1, overflow: TextOverflow.ellipsis,
        style: const TextStyle(fontSize: 12)),
    trailing: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (conn.wasBackground)
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
            decoration: BoxDecoration(
              color: Colors.orange.withOpacity(0.2),
              borderRadius: BorderRadius.circular(4),
            ),
            child: const Text('BG', style: TextStyle(color: Colors.orange, fontSize: 10)),
          ),
        const SizedBox(width: 6),
        Text(conn.encryption, style: TextStyle(color: _color, fontSize: 11)),
      ],
    ),
  );
}
