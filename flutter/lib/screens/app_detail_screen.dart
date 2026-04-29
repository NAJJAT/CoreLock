import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';
import '../services/vpn_channel.dart';

class AppDetailScreen extends StatefulWidget {
  final String packageName;
  const AppDetailScreen({super.key, required this.packageName});

  @override
  State<AppDetailScreen> createState() => _AppDetailScreenState();
}

class _AppDetailScreenState extends State<AppDetailScreen>
    with SingleTickerProviderStateMixin {

  late TabController _tabs;
  List<ConnectionEntry> _connections = [];
  bool _isBlocked = false;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 3, vsync: this);
    _load();
  }

  Future<void> _load() async {
    final conns = await VpnChannel.getRecentConnections(limit: 200);
    setState(() {
      _connections = conns.where((c) => c.packageName == widget.packageName).toList();
    });
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(
      title: Text(widget.packageName.split('.').last),
      leading: BackButton(onPressed: () => context.pop()),
      actions: [
        Switch(
          value: _isBlocked,
          onChanged: (v) async {
            if (v) {
              await VpnChannel.blockPackage(widget.packageName);
            } else {
              await VpnChannel.unblockPackage(widget.packageName);
            }
            setState(() => _isBlocked = v);
          },
          activeColor: Colors.red,
        ),
      ],
      bottom: TabBar(
        controller: _tabs,
        tabs: const [
          Tab(text: 'Connections'),
          Tab(text: 'Mismatch'),
          Tab(text: 'SDKs'),
        ],
      ),
    ),
    body: TabBarView(
      controller: _tabs,
      children: [
        _ConnectionsTab(connections: _connections),
        const Center(child: Text('Permission mismatch analysis')),
        const Center(child: Text('Detected tracker SDKs')),
      ],
    ),
  );
}

class _ConnectionsTab extends StatelessWidget {
  final List<ConnectionEntry> connections;
  const _ConnectionsTab({required this.connections});

  @override
  Widget build(BuildContext context) {
    if (connections.isEmpty) {
      return const Center(child: Text('No connections recorded yet'));
    }
    return ListView.builder(
      padding: const EdgeInsets.all(8),
      itemCount: connections.length,
      itemBuilder: (ctx, i) {
        final c = connections[i];
        return ListTile(
          dense: true,
          title: Text(c.destination, maxLines: 1, overflow: TextOverflow.ellipsis),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (c.wasBackground)
                const Text('BG', style: TextStyle(color: Colors.orange, fontSize: 11)),
              const SizedBox(width: 6),
              Text(
                c.encryption,
                style: TextStyle(
                  color: c.encryption == 'CLEARTEXT' ? Colors.red : const Color(0xFF00E5C4),
                  fontSize: 11,
                ),
              ),
            ],
          ),
        );
      },
    );
  }
}
