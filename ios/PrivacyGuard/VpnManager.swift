import NetworkExtension
import Combine

/// Host-app side VPN lifecycle and status management.
@MainActor
class VpnManager: ObservableObject {

    static let shared = VpnManager()

    @Published var isConnected  = false
    @Published var statusText   = "Disconnected"
    @Published var privacyScore = 50

    private var manager: NETunnelProviderManager?
    private var cancellables = Set<AnyCancellable>()

    private init() {
        Task { await loadManager() }
        NotificationCenter.default.publisher(for: .NEVPNStatusDidChange)
            .sink { [weak self] _ in Task { await self?.updateStatus() } }
            .store(in: &cancellables)
    }

    // ── Manager lifecycle ─────────────────────────────────────────────────────

    func loadManager() async {
        do {
            let managers = try await NETunnelProviderManager.loadAllFromPreferences()
            manager = managers.first ?? NETunnelProviderManager()
            await updateStatus()
        } catch {
            print("VpnManager loadAllFromPreferences error: \(error)")
        }
    }

    func startVpn() async throws {
        let m = manager ?? NETunnelProviderManager()
        m.localizedDescription = "PrivacyGuard"

        let proto = NETunnelProviderProtocol()
        proto.providerBundleIdentifier = "com.privacyguard.app.tunnel"
        proto.serverAddress            = "PrivacyGuard"
        m.protocolConfiguration        = proto
        m.isEnabled                    = true
        m.isOnDemandEnabled            = false

        try await m.saveToPreferences()
        try await m.loadFromPreferences()
        try (m.connection as? NETunnelProviderSession)?.startTunnel(options: nil)
        manager = m
    }

    func stopVpn() {
        manager?.connection.stopVPNTunnel()
    }

    // ── Status ────────────────────────────────────────────────────────────────

    func updateStatus() async {
        let status = manager?.connection.status ?? .disconnected
        isConnected = status == .connected
        statusText  = switch status {
            case .connected:     "Protected"
            case .connecting:    "Connecting…"
            case .disconnecting: "Disconnecting…"
            case .reasserting:   "Reconnecting…"
            default:             "Disconnected"
        }
    }
}
