import Flutter
import UIKit

/// iOS side of the Flutter MethodChannel / EventChannel.
///
/// Register in AppDelegate:
///   FlutterVpnPlugin.register(with: flutterEngine.registrar(forPlugin: "VpnPlugin"))
///
/// Mirrors FlutterBridge.kt on Android.

class FlutterVpnPlugin: NSObject, FlutterPlugin {

    static let methodChannel = "com.privacyguard/vpn"
    static let eventChannel  = "com.privacyguard/vpn_events"

    private var eventSink: FlutterEventSink?
    private var statsTimer: Timer?
    private let vpn = VpnManager.shared

    static func register(with registrar: FlutterPluginRegistrar) {
        let plugin = FlutterVpnPlugin()

        FlutterMethodChannel(name: methodChannel, binaryMessenger: registrar.messenger())
            .setMethodCallHandler(plugin.handleMethod)

        FlutterEventChannel(name: eventChannel, binaryMessenger: registrar.messenger())
            .setStreamHandler(plugin)
    }

    // ── Method calls ──────────────────────────────────────────────────────────

    @MainActor
    func handleMethod(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "startVpn":
            Task {
                try? await vpn.startVpn()
                result(nil)
            }
        case "stopVpn":
            vpn.stopVpn()
            result(nil)
        case "isRunning":
            result(vpn.isConnected)
        case "getStats":
            result(buildStatsMap())
        case "getTopRiskApps":
            result([])   // populated from local DB — implement with CoreData/SQLite
        case "getRecentConnections":
            result([])
        case "getAlerts":
            result([])
        case "setProtectionLevel":
            if let args = call.arguments as? [String: Any],
               let level = args["level"] as? String {
                UserDefaults.standard.set(level, forKey: "protection_level")
            }
            result(nil)
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func buildStatsMap() -> [String: Any] {
        [
            "isRunning":             vpn.isConnected,
            "trackersBlocked":       0,
            "cleartextCount":        0,
            "privacyScore":          vpn.privacyScore,
            "activeConnections":     0,
            "throughputBytesPerSec": 0,
            "protectionLevel":       UserDefaults.standard.string(forKey: "protection_level") ?? "STANDARD",
        ]
    }
}

// ── EventChannel (live stats stream) ─────────────────────────────────────────

extension FlutterVpnPlugin: FlutterStreamHandler {

    func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self else { return }
            DispatchQueue.main.async {
                events(self.buildStatsMap())
            }
        }
        return nil
    }

    func onCancel(withArguments arguments: Any?) -> FlutterError? {
        statsTimer?.invalidate()
        statsTimer = nil
        eventSink = nil
        return nil
    }
}
