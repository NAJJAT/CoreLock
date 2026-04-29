import SwiftUI
import UserNotifications

@main
struct PrivacyGuardApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @StateObject private var vpn = VpnManager.shared

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    PrivacyScoreCard(score: vpn.privacyScore, isActive: vpn.isConnected)
                    ProtectionToggle(isConnected: vpn.isConnected) {
                        Task {
                            if vpn.isConnected { vpn.stopVpn() }
                            else { try? await vpn.startVpn() }
                        }
                    }
                    NavigationLink("Apps") { Text("App list — coming soon") }
                        .buttonStyle(.bordered)
                    NavigationLink("Connections") { Text("Live connections — coming soon") }
                        .buttonStyle(.bordered)
                    NavigationLink("Statistics") { Text("Statistics — coming soon") }
                        .buttonStyle(.bordered)
                }
                .padding()
            }
            .navigationTitle("PrivacyGuard")
        }
        .task { await requestNotificationPermission() }
    }

    private func requestNotificationPermission() async {
        _ = try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])
    }
}

struct PrivacyScoreCard: View {
    let score: Int
    let isActive: Bool

    var body: some View {
        RoundedRectangle(cornerRadius: 20)
            .fill(LinearGradient(
                colors: [Color(hex: "#0F1E30"), Color(hex: "#0D2819")],
                startPoint: .topLeading, endPoint: .bottomTrailing))
            .frame(height: 160)
            .overlay {
                VStack(spacing: 8) {
                    Text(isActive ? "Protected" : "Not Protected")
                        .font(.headline)
                        .foregroundColor(isActive ? .green : .red)
                    Text("\(score)")
                        .font(.system(size: 56, weight: .bold))
                        .foregroundColor(.white)
                    Text("Privacy Score")
                        .font(.caption)
                        .foregroundColor(.gray)
                }
            }
    }
}

struct ProtectionToggle: View {
    let isConnected: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            Label(
                isConnected ? "Disable Protection" : "Enable Protection",
                systemImage: isConnected ? "shield.slash" : "shield"
            )
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(isConnected ? .red : .green)
        .controlSize(.large)
    }
}

extension Color {
    init(hex: String) {
        let v = UInt64(hex.hasPrefix("#") ? String(hex.dropFirst()) : hex, radix: 16) ?? 0
        self.init(
            red:   Double((v >> 16) & 0xFF) / 255,
            green: Double((v >> 8)  & 0xFF) / 255,
            blue:  Double( v        & 0xFF) / 255
        )
    }
}
