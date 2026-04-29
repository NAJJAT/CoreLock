import Foundation
import Network
import os.log

/// Bidirectional TCP/UDP relay for iOS Network Extension.
///
/// PacketTunnelProvider calls handleTcp() / handleUdp() after classification.
/// The relay opens a real NWConnection to the destination and pipes data
/// bidirectionally with the virtual packetFlow.

final class TcpRelay {
    private let log = Logger(subsystem: "com.privacyguard", category: "relay")

    private var connections: [String: NWConnection] = [:]
    private let queue = DispatchQueue(label: "pg.relay.tcp")

    func handle(
        dstHost: String,
        dstPort: UInt16,
        srcHost: String,
        srcPort: UInt16,
        data:    Data,
        write:   @escaping (Data) -> Void
    ) {
        let key = "\(srcHost):\(srcPort)→\(dstHost):\(dstPort)"

        if let existing = connections[key] {
            existing.send(content: data, completion: .idempotent)
            return
        }

        let endpoint   = NWEndpoint.hostPort(host: .init(dstHost), port: .init(integerLiteral: dstPort))
        let params     = NWParameters.tcp
        let connection = NWConnection(to: endpoint, using: params)

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                connection.send(content: data, completion: .idempotent)
                self?.receive(connection: connection, write: write)
            case .failed(let error):
                self?.log.error("TCP connection failed: \(error)")
                self?.queue.sync { self?.connections.removeValue(forKey: key) }
            default: break
            }
        }

        queue.sync { connections[key] = connection }
        connection.start(queue: queue)
    }

    private func receive(connection: NWConnection, write: @escaping (Data) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_535) { [weak self] data, _, isComplete, error in
            if let data, !data.isEmpty {
                write(data)
            }
            if !isComplete && error == nil {
                self?.receive(connection: connection, write: write)
            }
        }
    }
}

final class UdpRelay {
    private let log = Logger(subsystem: "com.privacyguard", category: "udp-relay")
    private var connections: [String: NWConnection] = [:]
    private let queue = DispatchQueue(label: "pg.relay.udp")

    func handle(
        dstHost: String,
        dstPort: UInt16,
        srcHost: String,
        srcPort: UInt16,
        data:    Data,
        write:   @escaping (Data) -> Void
    ) {
        let key = "\(srcHost):\(srcPort)→\(dstHost):\(dstPort)"

        if let existing = connections[key] {
            existing.send(content: data, completion: .idempotent)
            return
        }

        let endpoint   = NWEndpoint.hostPort(host: .init(dstHost), port: .init(integerLiteral: dstPort))
        let params     = NWParameters.udp
        let connection = NWConnection(to: endpoint, using: params)

        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                connection.send(content: data, completion: .idempotent)
                self?.receive(connection: connection, write: write)
            case .failed(let error):
                self?.log.error("UDP connection failed: \(error)")
                self?.queue.sync { self?.connections.removeValue(forKey: key) }
            default: break
            }
        }

        queue.sync { connections[key] = connection }
        connection.start(queue: queue)
    }

    private func receive(connection: NWConnection, write: @escaping (Data) -> Void) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_535) { [weak self] data, _, isComplete, error in
            if let data, !data.isEmpty {
                write(data)
            }
            if !isComplete && error == nil {
                self?.receive(connection: connection, write: write)
            }
        }
    }
}
