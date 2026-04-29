import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Settings')),
    body: ListView(
      children: [
        _NavTile(
          icon: Icons.notifications_outlined,
          title: 'Alert Inbox',
          subtitle: 'JA3 threats, anomalies, cleartext events',
          onTap: () => context.go('/settings/alerts'),
        ),
        _NavTile(
          icon: Icons.security,
          title: 'Security Analysis',
          subtitle: 'TLS fingerprinting and cipher alerts',
          onTap: () {},
        ),
        _NavTile(
          icon: Icons.ad_units,
          title: 'Ad & Tracker Blocker',
          subtitle: 'Manage blocklists and categories',
          onTap: () {},
        ),
        const Divider(),
        _SwitchTile(title: 'DNS over HTTPS', subtitle: 'Encrypt DNS queries', initial: true),
        _SwitchTile(title: 'Kill Switch', subtitle: 'Block all traffic if VPN drops', initial: false),
        _SwitchTile(title: 'Block Cleartext', subtitle: 'Deny all HTTP connections', initial: false),
        const Divider(),
        _NavTile(
          icon: Icons.vpn_lock,
          title: 'Dual VPN',
          subtitle: 'Chain two SOCKS5 hops for maximum anonymity',
          onTap: () {},
        ),
      ],
    ),
  );
}

class _NavTile extends StatelessWidget {
  final IconData icon;
  final String title, subtitle;
  final VoidCallback onTap;
  const _NavTile({required this.icon, required this.title, required this.subtitle, required this.onTap});

  @override
  Widget build(BuildContext context) => ListTile(
    leading: Icon(icon, color: const Color(0xFF00E5C4)),
    title: Text(title),
    subtitle: Text(subtitle, style: const TextStyle(fontSize: 12, color: Colors.grey)),
    trailing: const Icon(Icons.chevron_right, color: Colors.grey),
    onTap: onTap,
  );
}

class _SwitchTile extends StatefulWidget {
  final String title, subtitle;
  final bool initial;
  const _SwitchTile({required this.title, required this.subtitle, required this.initial});
  @override
  State<_SwitchTile> createState() => _SwitchTileState();
}

class _SwitchTileState extends State<_SwitchTile> {
  late bool _value = widget.initial;
  @override
  Widget build(BuildContext context) => SwitchListTile(
    title: Text(widget.title),
    subtitle: Text(widget.subtitle, style: const TextStyle(fontSize: 12, color: Colors.grey)),
    value: _value,
    onChanged: (v) => setState(() => _value = v),
    activeColor: const Color(0xFF00E5C4),
  );
}
