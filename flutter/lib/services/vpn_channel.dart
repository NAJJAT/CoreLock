/// Platform channel bridging Flutter UI ↔ native VPN engine.
///
/// Android: calls into PrivacyVpnService via MethodChannel
/// iOS:     calls into NEPacketTunnelProvider via MethodChannel
/// macOS:   same as iOS
/// Windows: calls into WinDivert service via named pipe / MethodChannel
/// Linux:   calls into TUN daemon via MethodChannel / socket

import 'package:flutter/services.dart';

class VpnChannel {
  static const _ch = MethodChannel('com.privacyguard/vpn');

  static const _eventCh = EventChannel('com.privacyguard/vpn_events');

  static Future<void> startVpn() => _ch.invokeMethod('startVpn');
  static Future<void> stopVpn()  => _ch.invokeMethod('stopVpn');

  static Future<bool> isRunning() async =>
      (await _ch.invokeMethod<bool>('isRunning')) ?? false;

  static Future<VpnStats> getStats() async {
    final raw = await _ch.invokeMapMethod<String, dynamic>('getStats');
    return VpnStats.fromMap(raw ?? {});
  }

  static Future<List<AppRiskEntry>> getTopRiskApps() async {
    final raw = await _ch.invokeListMethod<Map>('getTopRiskApps');
    return (raw ?? []).map((m) => AppRiskEntry.fromMap(Map<String, dynamic>.from(m))).toList();
  }

  static Future<List<ConnectionEntry>> getRecentConnections({int limit = 50}) async {
    final raw = await _ch.invokeListMethod<Map>(
      'getRecentConnections', {'limit': limit});
    return (raw ?? []).map((m) => ConnectionEntry.fromMap(Map<String, dynamic>.from(m))).toList();
  }

  static Future<List<AlertEntry>> getAlerts({int limit = 50}) async {
    final raw = await _ch.invokeListMethod<Map>('getAlerts', {'limit': limit});
    return (raw ?? []).map((m) => AlertEntry.fromMap(Map<String, dynamic>.from(m))).toList();
  }

  static Future<void> blockPackage(String packageName) =>
      _ch.invokeMethod('blockPackage', {'packageName': packageName});

  static Future<void> unblockPackage(String packageName) =>
      _ch.invokeMethod('unblockPackage', {'packageName': packageName});

  static Future<void> addDomainRule(String domain, {bool block = true}) =>
      _ch.invokeMethod('addDomainRule', {'domain': domain, 'block': block});

  static Future<void> setProtectionLevel(String level) =>
      _ch.invokeMethod('setProtectionLevel', {'level': level});

  /// Stream of live stats updates (fires every second when VPN is active).
  static Stream<VpnStats> get statsStream => _eventCh
      .receiveBroadcastStream()
      .map((e) => VpnStats.fromMap(Map<String, dynamic>.from(e as Map)));
}

// ── Data models ───────────────────────────────────────────────────────────────

class VpnStats {
  final bool isRunning;
  final int  trackersBlocked;
  final int  cleartextCount;
  final int  privacyScore;
  final int  activeConnections;
  final int  throughputBytesPerSec;
  final String protectionLevel;

  const VpnStats({
    this.isRunning             = false,
    this.trackersBlocked       = 0,
    this.cleartextCount        = 0,
    this.privacyScore          = 50,
    this.activeConnections     = 0,
    this.throughputBytesPerSec = 0,
    this.protectionLevel       = 'STANDARD',
  });

  factory VpnStats.fromMap(Map<String, dynamic> m) => VpnStats(
    isRunning             : m['isRunning']             as bool?   ?? false,
    trackersBlocked       : m['trackersBlocked']       as int?    ?? 0,
    cleartextCount        : m['cleartextCount']        as int?    ?? 0,
    privacyScore          : m['privacyScore']          as int?    ?? 50,
    activeConnections     : m['activeConnections']     as int?    ?? 0,
    throughputBytesPerSec : m['throughputBytesPerSec'] as int?    ?? 0,
    protectionLevel       : m['protectionLevel']       as String? ?? 'STANDARD',
  );
}

class AppRiskEntry {
  final String appName;
  final String packageName;
  final int    riskScore;
  final String grade;
  final int    cleartextCount;
  final int    backgroundCount;

  const AppRiskEntry({
    required this.appName,
    required this.packageName,
    required this.riskScore,
    required this.grade,
    required this.cleartextCount,
    required this.backgroundCount,
  });

  factory AppRiskEntry.fromMap(Map<String, dynamic> m) => AppRiskEntry(
    appName        : m['appName']        as String? ?? '',
    packageName    : m['packageName']    as String? ?? '',
    riskScore      : m['riskScore']      as int?    ?? 0,
    grade          : m['grade']          as String? ?? 'A',
    cleartextCount : m['cleartextCount'] as int?    ?? 0,
    backgroundCount: m['backgroundCount'] as int?   ?? 0,
  );
}

class ConnectionEntry {
  final String appName;
  final String packageName;
  final String destination;
  final String encryption;
  final bool   isBlocked;
  final bool   wasBackground;
  final int    timestamp;

  const ConnectionEntry({
    required this.appName,
    required this.packageName,
    required this.destination,
    required this.encryption,
    required this.isBlocked,
    required this.wasBackground,
    required this.timestamp,
  });

  factory ConnectionEntry.fromMap(Map<String, dynamic> m) => ConnectionEntry(
    appName       : m['appName']        as String? ?? '',
    packageName   : m['packageName']    as String? ?? '',
    destination   : m['destination']    as String? ?? '',
    encryption    : m['encryption']     as String? ?? 'UNKNOWN',
    isBlocked     : m['isBlocked']      as bool?   ?? false,
    wasBackground : m['wasBackground']  as bool?   ?? false,
    timestamp     : m['timestamp']      as int?    ?? 0,
  );
}

class AlertEntry {
  final String id;
  final String type;
  final String title;
  final String detail;
  final int    severity;
  final int    timestamp;

  const AlertEntry({
    required this.id,
    required this.type,
    required this.title,
    required this.detail,
    required this.severity,
    required this.timestamp,
  });

  factory AlertEntry.fromMap(Map<String, dynamic> m) => AlertEntry(
    id        : m['id']        as String? ?? '',
    type      : m['type']      as String? ?? '',
    title     : m['title']     as String? ?? '',
    detail    : m['detail']    as String? ?? '',
    severity  : m['severity']  as int?    ?? 0,
    timestamp : m['timestamp'] as int?    ?? 0,
  );
}
