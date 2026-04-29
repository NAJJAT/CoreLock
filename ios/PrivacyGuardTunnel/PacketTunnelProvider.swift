import NetworkExtension
import os.log

/// NEPacketTunnelProvider implementation for PrivacyGuard iOS/macOS.
///
/// Architecture:
///   PacketTunnelProvider ──▶ reads raw IPv4/IPv6 packets from packetFlow
///                        ──▶ classifies via RustCore.classifyPacket()
///                        ──▶ blocked packets are dropped
///                        ──▶ allowed packets are forwarded via a real socket
///                        ──▶ responses are written back to packetFlow
class PacketTunnelProvider: NEPacketTunnelProvider {

    private let log      = Logger(subsystem: "com.privacyguard", category: "tunnel")
    private let tcpRelay = TcpRelay()
    private let udpRelay = UdpRelay()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        log.info("PrivacyGuard tunnel starting")

        let settings = buildNetworkSettings()
        setTunnelNetworkSettings(settings) { [weak self] error in
            guard let self else { return }
            if let error {
                self.log.error("Network settings error: \(error.localizedDescription)")
                completionHandler(error)
                return
            }
            self.log.info("Network settings applied — starting packet loop")
            completionHandler(nil)
            self.startPacketLoop()
        }
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        log.info("Tunnel stopping: \(reason.rawValue)")
        completionHandler()
    }

    // ── Packet loop ───────────────────────────────────────────────────────────

    private func startPacketLoop() {
        packetFlow.readPacketObjects { [weak self] packets in
            guard let self else { return }
            self.handlePackets(packets)
            // Re-arm: NEPacketTunnelFlow.readPacketObjects is a one-shot callback
            self.startPacketLoop()
        }
    }

    private func handlePackets(_ packets: [NEPacket]) {
        for packet in packets {
            let raw = packet.data
            let len = Int32(raw.count)

            // Fast classification via native Rust
            let flags = raw.withUnsafeBytes { ptr in
                pg_classify_packet(ptr.bindMemory(to: UInt8.self).baseAddress, len)
            }

            if flags & 0x01 != 0 {
                log.debug("Blocked packet (len=\(len))")
                continue
            }

            if flags & 0x02 != 0 { inspectTls(packet) }

            // Route via relay — parse IP header to find protocol and addresses
            routePacket(raw)
        }
    }

    private func routePacket(_ data: Data) {
        guard data.count >= 20 else { return }
        let version = (data[0] >> 4) & 0xF

        if version == 4 {
            let ihl      = Int(data[0] & 0x0F) * 4
            let proto    = data[9]
            let srcIp    = ipv4String(data, offset: 12)
            let dstIp    = ipv4String(data, offset: 16)

            guard data.count >= ihl + 4 else { return }

            if proto == 6 {  // TCP
                let srcPort = UInt16(data[ihl]) << 8 | UInt16(data[ihl + 1])
                let dstPort = UInt16(data[ihl + 2]) << 8 | UInt16(data[ihl + 3])
                let tcpHdr  = Int(data[ihl + 12] >> 4) * 4
                let payload = data.advanced(by: ihl + tcpHdr)
                if !payload.isEmpty {
                    tcpRelay.handle(dstHost: dstIp, dstPort: dstPort,
                                    srcHost: srcIp, srcPort: srcPort,
                                    data: payload) { [weak self] resp in
                        self?.writeIpv4Tcp(src: dstIp, srcPort: dstPort,
                                           dst: srcIp, dstPort: srcPort, payload: resp)
                    }
                }
            } else if proto == 17 {  // UDP
                let srcPort = UInt16(data[ihl]) << 8 | UInt16(data[ihl + 1])
                let dstPort = UInt16(data[ihl + 2]) << 8 | UInt16(data[ihl + 3])
                let payload = data.advanced(by: ihl + 8)
                udpRelay.handle(dstHost: dstIp, dstPort: dstPort,
                                srcHost: srcIp, srcPort: srcPort,
                                data: payload) { [weak self] resp in
                    self?.writeIpv4Udp(src: dstIp, srcPort: dstPort,
                                       dst: srcIp, dstPort: srcPort, payload: resp)
                }
            }
        }
        // IPv6 routing would follow the same pattern using IPv6Packet parsing
    }

    // ── Packet builders ───────────────────────────────────────────────────────

    private func writeIpv4Tcp(src: String, srcPort: UInt16, dst: String, dstPort: UInt16, payload: Data) {
        // Build a minimal IPv4+TCP packet and write back to packetFlow
        var pkt = Data(count: 40 + payload.count)
        pkt[0]  = 0x45; pkt[9] = 6
        let total = UInt16(pkt.count)
        pkt[2] = UInt8(total >> 8); pkt[3] = UInt8(total & 0xFF)
        writeIpv4Addr(into: &pkt, offset: 12, addr: src)
        writeIpv4Addr(into: &pkt, offset: 16, addr: dst)
        pkt[20] = UInt8(srcPort >> 8); pkt[21] = UInt8(srcPort & 0xFF)
        pkt[22] = UInt8(dstPort >> 8); pkt[23] = UInt8(dstPort & 0xFF)
        pkt[32] = 0x50; pkt[33] = 0x18  // data offset=5, PSH+ACK
        pkt[34] = 0xFF; pkt[35] = 0xFF
        pkt.replaceSubrange(40..., with: payload)
        let proto = NSNumber(value: AF_INET)
        packetFlow.writePacketObjects([NEPacket(data: pkt, protocolFamily: proto.uint8Value)])
    }

    private func writeIpv4Udp(src: String, srcPort: UInt16, dst: String, dstPort: UInt16, payload: Data) {
        var pkt = Data(count: 28 + payload.count)
        pkt[0]  = 0x45; pkt[9] = 17
        let total = UInt16(pkt.count)
        pkt[2] = UInt8(total >> 8); pkt[3] = UInt8(total & 0xFF)
        writeIpv4Addr(into: &pkt, offset: 12, addr: src)
        writeIpv4Addr(into: &pkt, offset: 16, addr: dst)
        pkt[20] = UInt8(srcPort >> 8); pkt[21] = UInt8(srcPort & 0xFF)
        pkt[22] = UInt8(dstPort >> 8); pkt[23] = UInt8(dstPort & 0xFF)
        let udpLen = UInt16(8 + payload.count)
        pkt[24] = UInt8(udpLen >> 8); pkt[25] = UInt8(udpLen & 0xFF)
        pkt.replaceSubrange(28..., with: payload)
        let proto = NSNumber(value: AF_INET)
        packetFlow.writePacketObjects([NEPacket(data: pkt, protocolFamily: proto.uint8Value)])
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private func ipv4String(_ data: Data, offset: Int) -> String {
        "\(data[offset]).\(data[offset+1]).\(data[offset+2]).\(data[offset+3])"
    }

    private func writeIpv4Addr(into data: inout Data, offset: Int, addr: String) {
        let parts = addr.split(separator: ".").compactMap { UInt8($0) }
        guard parts.count == 4 else { return }
        for (i, b) in parts.enumerated() { data[offset + i] = b }
    }

    // ── TLS inspection ────────────────────────────────────────────────────────

    private func inspectTls(_ packet: NEPacket) {
        let raw = packet.data
        // Extract TCP payload (skip IP + TCP headers — simplified; adjust offsets for real use)
        let ipHeaderLen = Int((raw[0] & 0x0F)) * 4
        guard raw.count > ipHeaderLen + 20 else { return }
        let tcpHeaderLen = Int((raw[ipHeaderLen + 12] >> 4)) * 4
        let payloadOffset = ipHeaderLen + tcpHeaderLen
        guard raw.count > payloadOffset else { return }

        let tlsData = raw.subdata(in: payloadOffset..<raw.count)
        tlsData.withUnsafeBytes { ptr in
            guard let base = ptr.bindMemory(to: UInt8.self).baseAddress else { return }
            if let hashPtr = pg_compute_ja3(base, Int32(tlsData.count)) {
                defer { pg_free_string(hashPtr) }
                let hash = String(cString: hashPtr)
                checkJa3Threat(hash: hash, packet: packet)
            }
        }
    }

    private func checkJa3Threat(hash: String, packet: NEPacket) {
        // Compare against known-bad hashes (subset — full list in Rust for Android)
        let threats: Set<String> = [
            "6734f37431670b3ab4292b8f60f29984", // Cobalt Strike
            "cda2e0b53bc8d4d9d3cf4945fcad8e4c", // Emotet
            "e7d705a3286e19ea42f587b6be57d9dd", // TrickBot
        ]
        if threats.contains(hash) {
            log.warning("JA3 threat detected: \(hash)")
            sendThreatNotification(hash: hash)
        }
    }

    private func sendThreatNotification(hash: String) {
        let content = UNMutableNotificationContent()
        content.title = "TLS Threat Detected"
        content.body  = "JA3 fingerprint matches known malware: \(hash.prefix(8))…"
        content.sound = .default
        let req = UNNotificationRequest(
            identifier: "ja3.\(hash)",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(req) { _ in }
    }

    // ── Network settings ──────────────────────────────────────────────────────

    private func buildNetworkSettings() -> NEPacketTunnelNetworkSettings {
        // Use a non-routable address for the virtual TUN interface
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "10.0.0.1")

        // IPv4
        let ipv4 = NEIPv4Settings(addresses: ["10.8.0.1"], subnetMasks: ["255.255.255.0"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        settings.ipv4Settings = ipv4

        // IPv6
        let ipv6 = NEIPv6Settings(addresses: ["fd00::1"], networkPrefixLengths: [64])
        ipv6.includedRoutes = [NEIPv6Route.default()]
        settings.ipv6Settings = ipv6

        // DNS — use DoH-capable resolvers
        let dns = NEDNSSettings(servers: ["1.1.1.1", "8.8.8.8", "2606:4700:4700::1111"])
        dns.matchDomains = [""]   // intercept all DNS
        settings.dnsSettings = dns

        settings.mtu = 1500
        return settings
    }
}
