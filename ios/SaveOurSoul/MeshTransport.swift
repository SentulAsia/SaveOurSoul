import Foundation
import NearbyConnections
import SOSCore

/// Phone-to-phone mesh over Google Nearby Connections. The service id and strategy match the
/// Android app (`MeshTransport.SERVICE_ID`, P2P_CLUSTER), so iPhones and Android phones
/// relay each other's SOS frames.
///
/// iOS limits: discovery and advertising run while the app is in the foreground, or while an
/// active SOS keeps it alive in the background through continuous location updates.
@MainActor
final class MeshTransport: NSObject {
    static let serviceId = "org.saveoursoul.sos.v1"

    private let connectionManager: ConnectionManager
    private let advertiser: Advertiser
    private let discoverer: Discoverer
    private let localName = "sos-" + UUID().uuidString.lowercased().prefix(8)
    private var connected: Set<EndpointID> = []
    private var pending: Set<EndpointID> = []

    /// A raw payload (concatenated frames) arrived from a peer.
    var onPayload: ((EndpointID, Data) -> Void)?
    /// Frames to hand to a newly connected peer.
    var framesToShare: (() -> [Data])?
    var onPeersChanged: ((Int) -> Void)?

    private(set) var running = false

    override init() {
        connectionManager = ConnectionManager(serviceID: Self.serviceId, strategy: .cluster)
        advertiser = Advertiser(connectionManager: connectionManager)
        discoverer = Discoverer(connectionManager: connectionManager)
        super.init()
        connectionManager.delegate = self
        advertiser.delegate = self
        discoverer.delegate = self
    }

    func start() {
        guard !running else { return }
        running = true
        advertiser.startAdvertising(using: Data(localName.utf8))
        discoverer.startDiscovery()
    }

    func stop() {
        guard running else { return }
        running = false
        advertiser.stopAdvertising()
        discoverer.stopDiscovery()
        for id in connected { connectionManager.disconnect(from: id) }
        connected.removeAll()
        pending.removeAll()
        onPeersChanged?(0)
    }

    /// Sends frames to every connected peer except the one we received them from.
    func broadcast(_ frames: [Data], except: EndpointID? = nil) {
        guard !frames.isEmpty else { return }
        let targets = connected.filter { $0 != except }
        guard !targets.isEmpty else { return }
        // Nearby caps BYTES payloads at 32 KiB.
        let perPayload = 32 * 1024 / FrameCodec.frameLength
        stride(from: 0, to: frames.count, by: perPayload).forEach { i in
            let chunk = Array(frames[i..<min(i + perPayload, frames.count)])
            _ = connectionManager.send(FrameCodec.join(chunk), to: Array(targets))
        }
    }
}

extension MeshTransport: AdvertiserDelegate {
    nonisolated func advertiser(_ advertiser: Advertiser, didReceiveConnectionRequestFrom endpointID: EndpointID,
                                with context: Data, connectionRequestHandler: @escaping (Bool) -> Void) {
        // Accept everyone: frames are signed end-to-end, so peers can drop or replay (de-duplicated
        // by the backend) but never forge or alter them.
        connectionRequestHandler(true)
    }
}

extension MeshTransport: DiscovererDelegate {
    nonisolated func discoverer(_ discoverer: Discoverer, didFind endpointID: EndpointID, with context: Data) {
        let remoteName = String(decoding: context, as: UTF8.self)
        Task { @MainActor in
            guard !self.connected.contains(endpointID), !self.pending.contains(endpointID) else { return }
            // Same tie-break as Android: only the lexicographically smaller name dials.
            guard self.localName < remoteName else { return }
            self.pending.insert(endpointID)
            discoverer.requestConnection(to: endpointID, using: Data(self.localName.utf8))
        }
    }

    nonisolated func discoverer(_ discoverer: Discoverer, didLose endpointID: EndpointID) {
        Task { @MainActor in self.pending.remove(endpointID) }
    }
}

extension MeshTransport: ConnectionManagerDelegate {
    nonisolated func connectionManager(_ connectionManager: ConnectionManager, didReceive verificationCode: String,
                                       from endpointID: EndpointID, verificationHandler: @escaping (Bool) -> Void) {
        verificationHandler(true)
    }

    nonisolated func connectionManager(_ connectionManager: ConnectionManager, didReceive data: Data,
                                       withID payloadID: PayloadID, from endpointID: EndpointID) {
        Task { @MainActor in self.onPayload?(endpointID, data) }
    }

    nonisolated func connectionManager(_ connectionManager: ConnectionManager, didReceive stream: InputStream,
                                       withID payloadID: PayloadID, from endpointID: EndpointID,
                                       cancellationToken token: CancellationToken) {}

    nonisolated func connectionManager(_ connectionManager: ConnectionManager, didStartReceivingResourceWithID payloadID: PayloadID,
                                       from endpointID: EndpointID, at localURL: URL, withName name: String,
                                       cancellationToken token: CancellationToken) {}

    nonisolated func connectionManager(_ connectionManager: ConnectionManager, didReceiveTransferUpdate update: TransferUpdate,
                                       from endpointID: EndpointID, forPayload payloadID: PayloadID) {}

    nonisolated func connectionManager(_ connectionManager: ConnectionManager, didChangeTo state: ConnectionState,
                                       for endpointID: EndpointID) {
        Task { @MainActor in
            switch state {
            case .connected:
                self.pending.remove(endpointID)
                self.connected.insert(endpointID)
                if let frames = self.framesToShare?() { self.broadcastTo(endpointID, frames) }
            case .disconnected, .rejected:
                self.pending.remove(endpointID)
                self.connected.remove(endpointID)
            default:
                break
            }
            self.onPeersChanged?(self.connected.count)
        }
    }

    private func broadcastTo(_ id: EndpointID, _ frames: [Data]) {
        guard !frames.isEmpty else { return }
        _ = connectionManager.send(FrameCodec.join(frames), to: [id])
    }
}
